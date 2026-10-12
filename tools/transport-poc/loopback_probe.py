#!/usr/bin/env python3
"""Exercise a pinned userspace stack through an AF_UNIX mock TUN, never OS routes.

The SOCKS server below is a bounded test fixture, not a production proxy. It
accepts only the exact loopback destinations created by this process.
"""
import argparse
import ctypes
import json
import resource
import socket
import statistics
import struct
import threading
import time
from contextlib import ExitStack
from pathlib import Path


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    total = sum(struct.unpack('!%dH' % (len(data) // 2), data))
    while total >> 16:
        total = (total & 65535) + (total >> 16)
    return (~total) & 65535


def packet(proto, segment, src=b'\xc6\x12\x00\x02', dst=b'\xc6\x12\x00\x0a'):
    pseudo = src + dst + struct.pack('!BBH', 0, proto, len(segment))
    offset = 6 if proto == 17 else 16
    segment = segment[:offset] + struct.pack('!H', checksum(pseudo + segment) or 65535) + segment[offset + 2:]
    header = struct.pack('!BBHHHBBH4s4s', 69, 0, 20 + len(segment), 1, 0x4000, 64, proto, 0, src, dst)
    return header[:10] + struct.pack('!H', checksum(header)) + header[12:] + segment


def tcp(sport, dport, seq, ack, flags, payload=b''):
    return packet(6, struct.pack('!HHIIBBHHH', sport, dport, seq, ack, 80, flags, 32768, 0, 0) + payload)


def udp(sport, dport, payload):
    return packet(17, struct.pack('!HHHH', sport, dport, len(payload) + 8, 0) + payload)


def udp6(sport, dport, payload):
    src, dst = socket.inet_pton(socket.AF_INET6, 'fd00::2'), socket.inet_pton(socket.AF_INET6, 'fd00::a')
    segment = struct.pack('!HHHH', sport, dport, len(payload) + 8, 0) + payload
    pseudo = src + dst + struct.pack('!I3xB', len(segment), 17)
    segment = segment[:6] + struct.pack('!H', checksum(pseudo + segment) or 65535) + segment[8:]
    return struct.pack('!IHBB', 6 << 28, len(segment), 17, 64) + src + dst + segment


def receive(stream, count):
    result = b''
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise EOFError('SOCKS control channel closed')
        result += chunk
    return result


class Fixtures:
    def __init__(self, resources):
        self.stop = threading.Event()
        self.threads = []
        self.errors = []
        self.sockets = []
        self.lock = threading.Lock()
        self.udp = self.bound(socket.SOCK_DGRAM)
        self.tcp = self.bound(socket.SOCK_STREAM)
        self.socks = self.bound(socket.SOCK_STREAM)
        self.relay = self.bound(socket.SOCK_DGRAM)
        self.tcp.listen(2)
        self.socks.listen(4)
        resources.callback(self.close)
        self.start(self.echo_udp)
        self.start(lambda: self.accept(self.tcp, self.echo_tcp))
        self.start(lambda: self.accept(self.socks, self.proxy))

    def bound(self, kind):
        sock = socket.socket(socket.AF_INET, kind)
        sock.bind(('127.0.0.1', 0))
        sock.settimeout(0.2)
        self.track(sock)
        return sock

    def track(self, sock):
        with self.lock:
            self.sockets.append(sock)
        return sock

    def start(self, action):
        def run():
            try:
                action()
            except EOFError:
                pass  # A tunnel session can close its SOCKS control channel normally.
            except OSError as error:
                if not self.stop.is_set():
                    self.errors.append(str(error))
            except Exception as error:
                self.errors.append(repr(error))
        worker = threading.Thread(target=run, daemon=True)
        self.threads.append(worker)
        worker.start()

    def accept(self, listener, handler):
        accepted = 0
        while not self.stop.is_set():
            try:
                sock, _ = listener.accept()
            except socket.timeout:
                continue
            accepted += 1
            if accepted > 4:
                sock.close()
                raise RuntimeError('fixture connection limit')
            sock.settimeout(3)
            self.track(sock)
            self.start(lambda sock=sock: handler(sock))

    def echo_udp(self):
        while not self.stop.is_set():
            try:
                data, addr = self.udp.recvfrom(1500)
            except socket.timeout:
                continue
            assert addr[0] == '127.0.0.1'
            self.udp.sendto(data, addr)

    def echo_tcp(self, stream):
        with stream:
            while not self.stop.is_set():
                data = stream.recv(4096)
                if not data:
                    return
                stream.sendall(data)

    def proxy(self, control):
        with control:
            ver, count = receive(control, 2)
            assert ver == 5 and 0 in receive(control, count)
            control.sendall(b'\x05\x00')
            ver, cmd, reserved, atyp = receive(control, 4)
            assert (ver, reserved, atyp) == (5, 0, 1)
            target = (socket.inet_ntoa(receive(control, 4)), struct.unpack('!H', receive(control, 2))[0])
            if cmd == 1:
                assert target == ('198.18.0.10', self.tcp.getsockname()[1]), 'fixture refuses non-test targets'
                with self.track(socket.create_connection(self.tcp.getsockname(), timeout=3)) as upstream:
                    control.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                    while not self.stop.is_set():
                        data = control.recv(4096)
                        if not data:
                            return
                        upstream.sendall(data)
                        control.sendall(receive(upstream, len(data)))
            else:
                assert cmd == 3
                control.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01' + struct.pack('!H', self.relay.getsockname()[1]))
                with self.bound(socket.SOCK_DGRAM) as outbound:
                    while not self.stop.is_set():
                        try:
                            data, addr = self.relay.recvfrom(1500)
                        except socket.timeout:
                            continue
                        assert data[:3] == b'\x00\x00\x00'
                        assert data[3] in (1, 4)
                        length, family = (4, socket.AF_INET) if data[3] == 1 else (16, socket.AF_INET6)
                        end = 4 + length
                        destination = (socket.inet_ntop(family, data[4:end]), struct.unpack('!H', data[end:end + 2])[0])
                        expected = '198.18.0.10' if family == socket.AF_INET else 'fd00::a'
                        assert destination == (expected, self.udp.getsockname()[1]), 'fixture refuses non-test targets'
                        outbound.sendto(data[end + 2:], self.udp.getsockname())
                        echoed, sender = outbound.recvfrom(1500)
                        assert sender == self.udp.getsockname()
                        self.relay.sendto(data[:end + 2] + echoed, addr)

    def close(self):
        self.stop.set()
        with self.lock:
            for sock in self.sockets:
                sock.close()
        for worker in self.threads:
            worker.join(4)
        assert not any(worker.is_alive() for worker in self.threads), 'fixture thread leaked'
        assert not self.errors, self.errors


def read_packet(tun, proto, family=4):
    deadline = time.monotonic() + 4
    while time.monotonic() < deadline:
        data = tun.recv(1500)
        actual_family = data[0] >> 4
        assert actual_family in (4, 6)
        if actual_family == 4:
            ihl, actual_proto = (data[0] & 15) * 4, data[9]
            assert len(data) == struct.unpack('!H', data[2:4])[0]
            assert checksum(data[:ihl]) == 0, 'invalid response IP checksum'
            segment = data[ihl:]
            pseudo = data[12:20] + struct.pack('!BBH', 0, actual_proto, len(segment))
        else:
            actual_proto, segment = data[6], data[40:]
            assert len(segment) == struct.unpack('!H', data[4:6])[0]
            pseudo = data[8:40] + struct.pack('!I3xB', len(segment), actual_proto)
        if actual_proto not in (6, 17):
            if actual_family == 6 and actual_proto == 58:
                assert checksum(pseudo + segment) == 0, 'invalid ICMPv6 checksum'
                continue  # lwIP emits IPv6 neighbor/control packets as well.
            raise AssertionError('unexpected non-transport reply: protocol %d' % actual_proto)
        # IPv4 UDP permits an absent checksum; TCP never does.
        absent_udp_checksum = actual_family == 4 and actual_proto == 17 and segment[6:8] == b'\0\0'
        assert absent_udp_checksum or checksum(pseudo + segment) == 0, ('invalid synthetic response transport checksum', hex(checksum(pseudo + segment)), data[:60].hex())
        if actual_proto == proto and actual_family == family:
            if family == 4:
                assert data[12:20] == b'\xc6\x12\x00\x0a\xc6\x12\x00\x02'
            else:
                assert data[8:40] == socket.inet_pton(socket.AF_INET6, 'fd00::a') + socket.inet_pton(socket.AF_INET6, 'fd00::2')
            return segment
    raise TimeoutError('no matching response')


def run(library):
    lib = ctypes.CDLL(str(library.resolve()))
    lib.hev_socks5_tunnel_main_from_str.argtypes = [ctypes.c_char_p, ctypes.c_uint, ctypes.c_int]
    lib.hev_socks5_tunnel_main_from_str.restype = ctypes.c_int
    result = []
    samples = []
    baseline = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    with ExitStack() as resources:
        fixtures = Fixtures(resources)
        engine_fd, tun = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        resources.callback(engine_fd.close)
        resources.callback(tun.close)
        tun.settimeout(4)
        config = f'''tunnel:
  mtu: 1500
  icmp: off
socks5:
  address: 127.0.0.1
  port: {fixtures.socks.getsockname()[1]}
  udp: udp
misc:
  max-session-count: 32
  connect-timeout: 2000
  tcp-read-write-timeout: 3000
  udp-read-write-timeout: 3000
  log-file: null
  log-level: error
'''.encode()
        worker = threading.Thread(target=lambda: result.append(lib.hev_socks5_tunnel_main_from_str(config, len(config), engine_fd.fileno())), daemon=True)
        worker.start()
        # Allow initialization before using the library's process-global quit API.
        time.sleep(0.1)
        def stop():
            lib.hev_socks5_tunnel_quit()
            worker.join(4)
            assert not worker.is_alive(), 'transport failed bounded shutdown'
            assert result == [0], result
        resources.callback(stop)
        destination = fixtures.udp.getsockname()[1]
        for index in range(50):
            payload = b'isolated-udp-' + struct.pack('!I', index) + bytes(range(128))
            started = time.perf_counter()
            tun.send(udp(42000, destination, payload))
            reply = read_packet(tun, 17)
            assert struct.unpack('!HHH', reply[:6]) == (destination, 42000, len(payload) + 8)
            assert reply[8:] == payload
            samples.append((time.perf_counter() - started) * 1000)
        for index in range(10):
            payload = b'isolated-ipv6-udp-' + struct.pack('!I', index)
            tun.send(udp6(42002, destination, payload))
            reply = read_packet(tun, 17, family=6)
            assert struct.unpack('!HHH', reply[:6]) == (destination, 42002, len(payload) + 8)
            assert reply[8:] == payload
        dport, seq = fixtures.tcp.getsockname()[1], 1000
        tun.send(tcp(42001, dport, seq, 0, 2))
        synack = read_packet(tun, 6)
        assert synack[13] & 0x12 == 0x12 and struct.unpack('!I', synack[8:12])[0] == seq + 1
        ack = (struct.unpack('!I', synack[4:8])[0] + 1) & 0xffffffff
        seq += 1
        tun.send(tcp(42001, dport, seq, ack, 16))
        transferred = 0
        started = time.perf_counter()
        for index in range(32):
            payload = struct.pack('!I', index) + bytes(range(256)) * 2
            tun.send(tcp(42001, dport, seq, ack, 24, payload))
            seq += len(payload)
            echoed = b''
            while len(echoed) < len(payload):
                reply = read_packet(tun, 6)
                assert struct.unpack('!HH', reply[:4]) == (dport, 42001)
                data = reply[(reply[12] >> 4) * 4:]
                if data:
                    assert struct.unpack('!I', reply[4:8])[0] == ack
                    echoed += data
                    ack += len(data)
                    tun.send(tcp(42001, dport, seq, ack, 16))
            assert echoed == payload
            transferred += len(payload)
        elapsed = time.perf_counter() - started
        tun.send(tcp(42001, dport, seq, ack, 17))
        fin = False
        while not fin:
            reply = read_packet(tun, 6)
            fin = bool(reply[13] & 1)
        ack += 1
        tun.send(tcp(42001, dport, seq + 1, ack, 16))
    print(json.dumps({
        'environment': 'Linux AF_UNIX mock TUN and loopback SOCKS fixture; not Android/Windows device E2E',
        'ipv4_udp_verified_roundtrips': 50, 'ipv6_udp_verified_roundtrips': 10,
        'ipv4_tcp_verified_bytes': transferred,
        'udp_median_ms': round(statistics.median(samples), 3),
        'udp_p95_ms': round(sorted(samples)[47], 3),
        'tcp_synthetic_echo_MiB_s': round(transferred / elapsed / 1048576, 3),
        'process_peak_rss_before_KiB': baseline,
        'process_peak_rss_after_KiB': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        'bounded_shutdown': True, 'fixture_threads_stopped': True,
    }, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    run(parser.parse_args().library)
