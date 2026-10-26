"""Real private-loopback HTTP/TLS/WebSocket evidence. No OS trust/network changes."""

import argparse, base64, concurrent.futures, hashlib, http.client, json, os, pathlib, socket, ssl, struct, subprocess, tempfile, time


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
        if os.environ.get("SPIKE_DIAGNOSTIC_LOGS") == "1":
            cmd.append("--debug")
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


def check(exe, image, output, smoke=False, events=None):
    events = [] if events is None else events

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
                assert meta["type"] == (
                    "image/webp" if path == "/image" else "text/plain"
                ), meta
                assert int(meta["declared"]) == len(body), meta
                if path == "/image":
                    assert body == pathlib.Path(image).read_bytes()
                if path == "/text":
                    assert body == b"x" * 4096
                if path in ("/denied", "/missing", "/empty"):
                    assert (
                        body
                        == {
                            "/denied": b"forbidden",
                            "/missing": b"missing",
                            "/empty": b"",
                        }[path]
                    )
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
    with Server(
        exe, image, output, extra=("--leaf-seconds", "2", "--timeout-seconds", "10")
    ) as server:
        with server.socket(tls=True, name="expiry.spike.invalid") as held:
            cert = held.getpeercert()
            first = cert["serialNumber"]
            response(held, keep=True)
            with server.socket(tls=True) as default:
                original_default = default.getpeercert()["serialNumber"]
                response(default)
            expires = ssl.cert_time_to_seconds(cert["notAfter"])
            time.sleep(max(0, expires + 0.05 - time.time()))
            # In-flight ownership remains valid even after the cached cert expires.
            assert response(held, keep=True)[0]["status"] == 200
            with server.socket(tls=True, name="expiry.spike.invalid") as fresh:
                assert fresh.getpeercert()["serialNumber"] != first
                response(fresh)
            with server.socket(tls=True) as fresh:
                assert fresh.getpeercert()["serialNumber"] != original_default
                response(fresh)
        assert server.stats()["cert_renewals"] >= 2
        record("certificate-expiry-not-only-cache-age", stats=server.stats())
    with Server(exe, image, output, extra=("--logging",)) as server:
        server.get("/empty")
        server.get("/text")
        server.get("/denied")
        assert server.stats()["requests"] == 3
    lines = (server.root / "requests.log").read_text().splitlines()
    assert len(lines) == 3 and [line.split(",")[0] for line in lines] == [
        "1",
        "2",
        "3",
    ], lines
    record("same-test-log-one-entry-per-http-not-diagnostics", entries=len(lines))
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
    lifecycle_checks(exe, image, output, record)
    forwarding_checks(exe, image, output, record)
    return events


def wait_for(check, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.02)
    raise AssertionError("Bounded condition did not become true")


def lifecycle_checks(exe, image, output, record):
    with Server(exe, image, output) as server:
        saved_ports = server.http, server.https
        with server.socket() as slow:
            slow.sendall(b"GET /text HTTP/1.1\r\nHost:")
            assert server.get("/text")[0]["status"] == 200
            slow.settimeout(5)
            try:
                assert slow.recv(1) == b""
            except ConnectionError:
                pass
        record("partial-header-timeout-and-peer-isolation")
        with server.socket() as oversized:
            oversized.sendall(
                b"GET /text HTTP/1.1\r\nHost: localhost\r\nX-Large: "
                + b"x" * 18000
                + b"\r\n\r\n"
            )
            oversized.settimeout(5)
            data = b""
            try:
                while len(data) < 32768:
                    chunk = oversized.recv(4096)
                    if not chunk:
                        break
                    data += chunk
            except ConnectionError:
                pass
            assert b" 200 " not in data, data[:100]
        assert server.get("/text")[0]["status"] == 200
        record("oversized-header-rejected-without-breaking-peer")
    with Server(exe, image, output, extra=("--timeout-seconds", "10")) as server:
        saved_ports = server.http, server.https
        sockets = []
        try:
            with server.socket() as control:
                response(control, "/stats", keep=True)

                def partial(_):
                    sock = server.socket()
                    try:
                        sock.sendall(b"GET /text HTTP/1.1\r\nHost:")
                    except ConnectionError:
                        pass  # an admission rejection is an expected outcome
                    return sock

                with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
                    sockets.extend(pool.map(partial, range(140)))

                def saturated():
                    stats = json.loads(response(control, "/stats", keep=True)[1])
                    return stats if stats["rejected"] else None

                wait_for(saturated, timeout=1.5)
        finally:
            for sock in sockets:
                sock.close()
        stats = wait_for(lambda: (s if (s := server.stats())["active"] <= 2 else None))
        assert stats["peak"] <= 128 and stats["rejected"] > 0, stats
        record("connection-cap-and-resource-recovery", stats=stats)
        # A bounded receiver keeps an image response pending while peers work.
        with server.socket() as reader:
            reader.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            reader.sendall(
                b"GET /image HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            )
            assert reader.recv(1) == b"H"
            assert server.get("/text")[0]["status"] == 200
        record("slow-reader-and-early-close-peer-isolation")
    assert server.process.returncode == 0
    with Server(
        exe, image, output, http=saved_ports[0], https=saved_ports[1]
    ) as restarted:
        assert restarted.get("/text")[0]["status"] == 200
        record("restart-reuses-released-listeners")


def forwarding_checks(exe, image, output, record):
    with Server(exe, image, output) as upstream:
        options = (
            "--upstream-http",
            str(upstream.http),
            "--upstream-https",
            str(upstream.https),
            "--upstream-ca",
            str(upstream.root / "ca.pem"),
        )
        with Server(exe, image, output, extra=options) as front:
            rows = []
            for ip in ("127.0.0.1", "::1"):
                for path in ("/forward/text", "/forward-tls/text"):
                    print(
                        json.dumps({"forward_attempt": path, "family": ip}), flush=True
                    )
                    meta, body = front.get(path, ip)
                    assert (
                        meta["status"] == 200
                        and meta["type"] == "text/plain"
                        and body == b"x" * 4096
                    ), meta
                    rows.append(dict(family=ip, path=path, **meta))
            stats = wait_for(
                lambda: (s if (s := front.stats())["forward_active"] == 0 else None)
            )
            assert stats["requests"] == 4 and upstream.stats()["requests"] == 4, stats
            record("controlled-http-and-strict-tls-forward", rows=rows, stats=stats)
        with Server(exe, image, output) as wrong_ca:
            bad = (
                "--upstream-https",
                str(upstream.https),
                "--upstream-ca",
                str(wrong_ca.root / "ca.pem"),
            )
            with Server(exe, image, output, extra=bad) as front:
                try:
                    result, _ = front.get("/forward-tls/text")
                    assert result["status"] != 200, result
                    outcome = str(result["status"])
                except (ConnectionError, http.client.RemoteDisconnected) as error:
                    outcome = type(error).__name__
                assert front.get("/text")[0]["status"] == 200
                wait_for(lambda: front.stats()["forward_active"] == 0)
                record(
                    "untrusted-upstream-refused-not-placeholder-success",
                    outcome=outcome,
                )
    with socket.socket() as controlled:
        controlled.bind(("127.0.0.1", 0))
        controlled.listen()
        controlled.settimeout(5)
        with Server(
            exe,
            image,
            output,
            extra=("--upstream-http", str(controlled.getsockname()[1])),
        ) as front:
            client = front.socket()
            try:
                client.sendall(b"GET /forward/text HTTP/1.1\r\nHost: localhost\r\n\r\n")
                peer, _ = controlled.accept()
                with peer:
                    peer.settimeout(3)
                    headers = b""
                    while not headers.endswith(b"\r\n\r\n") and len(headers) < 16384:
                        chunk = peer.recv(1)
                        assert chunk, "Upstream closed before request"
                        headers += chunk
                    assert headers.endswith(b"\r\n\r\n")
                    started = time.monotonic()
                    client.close()  # response is deterministically held by this test
                    peer.settimeout(1.5)  # less than the fixture's 2-second timeout
                    try:
                        assert peer.recv(1) == b""
                    except ConnectionError:
                        pass
                    elapsed = time.monotonic() - started
            finally:
                client.close()
            assert front.get("/text")[0]["status"] == 200
            wait_for(lambda: front.stats()["forward_active"] == 0)
            record("cancel-held-forward-closes-child-before-timeout", seconds=elapsed)
    forward_capacity_checks(exe, image, output, record)


def forward_capacity_checks(exe, image, output, record):
    clients, children = [], []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(128)
        listener.settimeout(5)
        options = (
            "--upstream-http",
            str(listener.getsockname()[1]),
            "--timeout-seconds",
            "10",
        )
        with Server(exe, image, output, extra=options) as front:
            try:
                for _ in range(64):
                    client = front.socket()
                    clients.append(client)
                    client.sendall(
                        b"GET /forward/text HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    )
                    peer, _ = listener.accept()
                    children.append(peer)
                stats = wait_for(
                    lambda: (
                        s if (s := front.stats())["forward_active"] == 64 else None
                    )
                )
                rejected, _ = front.get("/forward/text")
                assert rejected["status"] == 503, rejected
                assert front.get("/text")[0]["status"] == 200
                assert stats["forward_peak"] == 64, stats
            finally:
                for sock in clients + children:
                    sock.close()
            stats = wait_for(
                lambda: (s if (s := front.stats())["forward_active"] == 0 else None)
            )
            assert stats["requests"] == 66, stats  # 65 attempts and one healthy peer
            record("forward-cap-and-cleanup", stats=stats)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--binary", required=True)
    p.add_argument("--image", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--local-smoke", action="store_true")
    a = p.parse_args()
    root = pathlib.Path(a.output).resolve()
    root.mkdir(parents=True, exist_ok=True)
    events = []
    try:
        result = check(
            pathlib.Path(a.binary).resolve(),
            pathlib.Path(a.image).resolve(),
            root,
            a.local_smoke,
            events,
        )
        (root / "failure.json").unlink(missing_ok=True)
        (root / "correctness.json").write_text(
            json.dumps(
                {
                    "events": result,
                    "scope": "prototype only",
                    "local_smoke": a.local_smoke,
                    "complete": True,
                },
                indent=2,
            )
            + "\n",
            encoding="utf8",
        )
        print(json.dumps({"passed": len(result), "output": str(root)}))
    except BaseException as failure:
        detail = {"error": repr(failure)}
        if isinstance(failure, http.client.IncompleteRead):
            detail.update(
                received_bytes=len(failure.partial),
                expected_remaining=failure.expected,
                partial_sha256=hashlib.sha256(failure.partial).hexdigest(),
                tail_hex=failure.partial[-16:].hex(),
            )
        (root / "partial-correctness.json").write_text(
            json.dumps(
                {
                    "events": events,
                    "complete": False,
                    "error": repr(failure),
                    "scope": "partial prototype evidence, not acceptance",
                },
                indent=2,
            )
            + "\n",
            encoding="utf8",
        )
        (root / "failure.json").write_text(
            json.dumps(detail, indent=2) + "\n", encoding="utf8"
        )
        raise
