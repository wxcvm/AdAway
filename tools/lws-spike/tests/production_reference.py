"""Separate Linux production-source observation, never a paired migration gate."""

import argparse
import hashlib
import json
import pathlib
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from correctness import ports, response
from benchmark import measure


class ProductionReference:
    def __init__(self, binary, image, output):
        self.root = pathlib.Path(tempfile.mkdtemp(prefix="production-", dir=output))
        self.http, self.https = ports()
        with socket.socket() as temporary:
            temporary.bind(("127.0.0.1", 0))
            management = temporary.getsockname()[1]
        if management in (self.http, self.https):
            raise RuntimeError(
                "Reference port reservation raced; refuse ambiguous setup"
            )
        shutil.copyfile(image, self.root / "img_01.webp")
        self.out = (self.root / "stdout.log").open("wb")
        self.err = (self.root / "stderr.log").open("wb")
        self.process = subprocess.Popen(
            [
                str(binary),
                "--resources",
                str(self.root),
                "--bind",
                "loop",
                "--http-port",
                str(self.http),
                "--https-port",
                str(self.https),
                "--stats-port",
                str(management),
            ],
            stdout=self.out,
            stderr=self.err,
        )

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()  # only the Popen-owned child, normal SIGTERM path
            try:
                self.process.wait(5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.out.close()
        self.err.close()


def observe(binary, image, output):
    server = ProductionReference(binary, image, output)
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if server.process.poll() is not None:
                raise RuntimeError("Production reference exited before ready")
            if "ADBlock webserver ready" in (server.root / "stdout.log").read_text(
                errors="replace"
            ):
                break
            time.sleep(0.05)
        else:
            raise RuntimeError("Production reference startup timeout")
        ca = server.root / "localhost-2410.crt"
        shutil.copyfile(
            ca, server.root / "ca.pem"
        )  # public test CA for the common load client
        ctx = ssl.create_default_context(cafile=str(ca))
        rows = []
        for family in ("127.0.0.1", "::1"):
            for tls in (False, True):
                row = dict(family=family, tls=tls, path="/")
                try:
                    with socket.create_connection(
                        (family, server.https if tls else server.http), timeout=3
                    ) as raw:
                        if tls:
                            with ctx.wrap_socket(raw, server_hostname=family) as secure:
                                meta, _ = response(secure, "/")
                        else:
                            meta, _ = response(raw, "/")
                    row.update(meta)
                except (OSError, ValueError) as error:
                    row["error"] = type(error).__name__ + ": " + str(error)
                rows.append(row)
        metrics = []
        for tls in (False, True):
            if not any(
                row.get("status") == 200
                and row["family"] == "127.0.0.1"
                and row["tls"] == tls
                for row in rows
            ):
                continue
            metrics.append(dict(tls=tls, **measure(server, "/", tls, False, 1, 2, 5)))
        return dict(
            reference_only=True,
            not_a_paired_comparison=True,
            scope="unchanged complete production server source with host-only Android log shim; not the Android device binary",
            ordinary_responses=rows,
            metrics=metrics,
            binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
            binary_bytes=binary.stat().st_size,
        )
    finally:
        server.close()


if __name__ == "__main__":
    if sys.platform != "linux":
        raise RuntimeError(
            "Production reference is restricted to the disposable Linux host job"
        )
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = pathlib.Path(args.output).resolve()
    root.mkdir(parents=True, exist_ok=True)
    result = observe(
        pathlib.Path(args.binary).resolve(), pathlib.Path(args.image).resolve(), root
    )
    (root / "reference.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf8"
    )
    print(
        json.dumps(
            {"reference_only": True, "responses": len(result["ordinary_responses"])}
        )
    )
