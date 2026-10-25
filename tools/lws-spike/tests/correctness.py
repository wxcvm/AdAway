"""Real private-loopback HTTP/TLS/WebSocket evidence. No OS trust/network changes."""

import argparse, base64, hashlib, http.client, json, os, pathlib, socket, ssl, struct, subprocess, tempfile, time


def ports():
    sockets = []
    try:
        for _ in range(2):
            s = socket.socket()
            s.bind(("127.0.0.1", 0))
            sockets.append(s)
        return [s.getsockname()[1] for s in sockets]
    finally:
        for s in sockets:
            s.close()


def response(sock, path="/text", keep=False, headers=""):
    sock.sendall(
        (
            f"GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: "
            + ("keep-alive" if keep else "close")
            + "\r\n"
            + headers
            + "\r\n"
        ).encode()
    )
    reply = http.client.HTTPResponse(sock)
    reply.begin()
    body = reply.read()
    return {
        "status": reply.status,
        "type": reply.getheader("Content-Type"),
        "declared": reply.getheader("Content-Length"),
        "length": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
        "etag": reply.getheader("ETag"),
    }, body


class Server:
    def __init__(
        self,
        exe,
        image,
        root,
        ipv4="127.0.0.1",
        ipv6="::1",
        extra=(),
        http=None,
        https=None,
        fail=False,
    ):
        self.root = pathlib.Path(tempfile.mkdtemp(prefix="service-", dir=root))
        self.http, self.https = ports()
        if http:
            self.http = http
        if https:
            self.https = https
        self.address = "::1" if ipv4 == "-" else "127.0.0.1"
        self.out = (self.root / "stdout.log").open("wb")
        self.err = (self.root / "stderr.log").open("wb")
        cmd = [
            str(exe),
            "--directory",
            str(self.root),
            "--image",
            str(image),
            "--http",
            str(self.http),
            "--https",
            str(self.https),
            "--ipv4",
            ipv4,
            "--ipv6",
            ipv6,
            *extra,
        ]
        self.process = subprocess.Popen(
            cmd,
            stdout=self.out,
            stderr=self.err,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                if fail:
                    return
                raise AssertionError(
                    "Startup failed: "
                    + (self.root / "stderr.log").read_text(errors="replace")
                )
            if fail:
                time.sleep(0.02)
                continue
            if "READY" in (self.root / "stdout.log").read_text(errors="replace"):
                try:
                    self.get("/stats")
                    return
                except OSError:
                    pass
            time.sleep(0.02)
        self.close()
        raise AssertionError("Startup timeout")

    def socket(self, address=None, tls=False, name=None, trusted=True):
        address = address or self.address
        sock = socket.create_connection(
            (address, self.https if tls else self.http), timeout=3
        )
        if tls:
            ctx = (
                ssl.create_default_context(cafile=str(self.root / "ca.pem"))
                if trusted
                else ssl.create_default_context()
            )
            try:
                sock = ctx.wrap_socket(sock, server_hostname=name or address)
            except BaseException:
                sock.close()
                raise
        return sock

    def get(self, path, address=None, tls=False, name=None, headers=""):
        with self.socket(address, tls, name) as sock:
            return response(sock, path, headers=headers)

    def stats(self):
        return json.loads(self.get("/stats")[1])

    def close(self):
        if self.process.poll() is None:
            try:
                self.get("/stop")
            except (OSError, http.client.HTTPException):
                pass
            try:
                self.process.wait(4)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:
                    self.process.wait(2)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait()
        self.out.close()
        self.err.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def exact(sock, n):
    out = b""
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise AssertionError("WebSocket closed early")
        out += chunk
    return out


def websocket(server, address):
    with server.socket(address) as sock:
        key = base64.b64encode(bytes(range(16))).decode()
        sock.sendall(
            (
                f"GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: spike\r\n\r\n"
            ).encode()
        )
        header = b""
        while not header.endswith(b"\r\n\r\n"):
            header += exact(sock, 1)
        assert b" 101 " in header, header
        accept = base64.b64encode(
            hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
            ).digest()
        )
        assert accept in header, header
        mask = b"1234"
        payload = b"echo-evidence"
        masked = bytes(c ^ mask[i % 4] for i, c in enumerate(payload))
        sock.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + masked)
        frame = exact(sock, 2)
        assert frame[0] == 0x81 and frame[1] == len(payload), frame
        assert exact(sock, len(payload)) == payload


def check(exe, image, output, smoke=False):
    events = []

    def record(name, **values):
        events.append({"test": name, "passed": True, **values})

    with Server(exe, image, output) as server:
        golden = {}
        for ip in ("127.0.0.1", "::1"):
            rows = []
            for path, status in [
                ("/empty", 204),
                ("/text", 200),
                ("/image", 200),
                ("/denied", 403),
                ("/missing", 404),
            ]:
                meta, body = server.get(path, ip)
                assert meta["status"] == status, meta
                if path == "/image":
                    assert body == pathlib.Path(image).read_bytes()
                if path == "/text":
                    assert body == b"x" * 4096
                rows.append(meta)
            golden[ip] = rows
            assert [r["sha256"] for r in rows] == [
                r["sha256"] for r in golden["127.0.0.1"]
            ]
            conditional, _ = server.get(
                "/image", ip, headers='If-None-Match: "spike-v1"\r\n'
            )
            assert conditional["status"] == 304 and not conditional["length"]
            with server.socket(ip) as sock:
                for _ in range(5):
                    assert response(sock, keep=True)[0]["status"] == 200
            record("http-family", family=ip, rows=rows)
            websocket(server, ip)
        assert server.stats()["requests"] == 22, server.stats()
        assert server.stats()["ws"] == 2, server.stats()
        record("request-accounting", stats=server.stats())
        for ip in ("127.0.0.1", "::1"):
            assert server.get("/text", ip, True)[0]["status"] == 200
            with server.socket(ip, True, "first.spike.invalid") as sock:
                cert = sock.getpeercert()
                serial = cert["serialNumber"]
                assert ("DNS", "first.spike.invalid") in cert["subjectAltName"]
                response(sock)
            with server.socket(ip, True, "first.spike.invalid") as sock:
                assert sock.getpeercert()["serialNumber"] == serial
                response(sock)
        before = server.stats()
        assert before["cert_hits"] >= 2 and before["cert_misses"] == 1, before
        for trusted, name in [(False, "first.spike.invalid"), (True, "127.0.0.2")]:
            try:
                with server.socket("127.0.0.1", True, name, trusted):
                    pass
                raise AssertionError("Strict TLS rejection missing")
            except ssl.SSLCertVerificationError:
                pass
        record("public-hook-dynamic-sni-and-strict-rejections", stats=server.stats())
        with server.socket(tls=True, name="held.spike.invalid") as held:
            original = held.getpeercert()["serialNumber"]
            response(held, keep=True)
            for i in range(257):
                server.get("/empty", tls=True, name=f"evict{i}.spike.invalid")
                if i % 8 == 0:
                    response(held, keep=True)
            assert response(held, keep=True)[0]["status"] == 200
            assert held.getpeercert()["serialNumber"] == original
            with server.socket(tls=True, name="held.spike.invalid") as fresh:
                assert fresh.getpeercert()["serialNumber"] != original
                response(fresh)
        record("bounded-cache-eviction-preserves-active-ssl", stats=server.stats())
        # A partial request and an early-closed image consumer must not block a healthy peer.
        with server.socket() as slow:
            slow.sendall(b"GET /text HTTP/1.1\r\nHost:")
            for _ in range(5):
                assert server.get("/text")[0]["status"] == 200
        with server.socket() as early:
            early.sendall(b"GET /image HTTP/1.1\r\nHost: localhost\r\n\r\n")
        record("slow-and-early-closed-clients")
    assert server.process.returncode == 0, server.process.returncode
    record("graceful-shutdown")
    with Server(exe, image, output, extra=("--cert-age-ms", "100")) as server:
        with server.socket(tls=True, name="renew.spike.invalid") as sock:
            first = sock.getpeercert()["serialNumber"]
            response(sock)
        time.sleep(0.15)
        with server.socket(tls=True, name="renew.spike.invalid") as sock:
            assert sock.getpeercert()["serialNumber"] != first
            response(sock)
        assert server.stats()["cert_renewals"] >= 1
        record("cache-age-renewal", stats=server.stats())
    for ipv4, ipv6 in [("127.0.0.1", "-"), ("-", "::1")] + (
        [] if smoke else [("0.0.0.0", "::")]
    ):
        with Server(exe, image, output, ipv4, ipv6) as server:
            assert server.get("/text")[0]["status"] == 200
            if ipv4 == "0.0.0.0":
                assert server.get("/text", "::1")[0]["status"] == 200
            record("independent-or-wildcard-bind", ipv4=ipv4, ipv6=ipv6)
    with socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen()
        with Server(
            exe, image, output, http=occupied.getsockname()[1], fail=True
        ) as failed:
            assert failed.process.returncode != 0
            record("port-conflict-rollback")
    with Server(exe, image, output, ipv4="192.0.2.123", ipv6="-", fail=True) as failed:
        assert failed.process.returncode != 0
        record("missing-address-startup-rejected")
    return events


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--binary", required=True)
    p.add_argument("--image", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--local-smoke", action="store_true")
    a = p.parse_args()
    root = pathlib.Path(a.output).resolve()
    root.mkdir(parents=True, exist_ok=True)
    try:
        result = check(
            pathlib.Path(a.binary).resolve(),
            pathlib.Path(a.image).resolve(),
            root,
            a.local_smoke,
        )
        (root / "failure.json").unlink(missing_ok=True)
        (root / "correctness.json").write_text(
            json.dumps(
                {
                    "events": result,
                    "scope": "prototype only",
                    "local_smoke": a.local_smoke,
                },
                indent=2,
            )
            + "\n",
            encoding="utf8",
        )
        print(json.dumps({"passed": len(result), "output": str(root)}))
    except BaseException as failure:
        (root / "failure.json").write_text(
            json.dumps({"error": repr(failure)}, indent=2) + "\n", encoding="utf8"
        )
        raise
