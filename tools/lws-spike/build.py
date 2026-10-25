"""Explicit out-of-tree build; no install step and no production build integration."""

import argparse, json, pathlib, subprocess, hashlib, sys
from fetch import fetch, HERE, LOCK

p = argparse.ArgumentParser()
p.add_argument("--directory", required=True)
p.add_argument("--openssl", required=True)
p.add_argument("--cmake", default="cmake")
p.add_argument("--generator")
p.add_argument("--preflight", action="store_true")
p.add_argument("--define", action="append", default=[])
a = p.parse_args()
directory = pathlib.Path(a.directory).resolve()
source, fixture = fetch(directory / "inputs")
build = directory / "build"
prefix = pathlib.Path(a.openssl).resolve()
if (
    not a.preflight
    and (prefix / "BUILD-COMMIT").read_text().strip() != LOCK["openssl"]["commit"]
):
    raise RuntimeError("Crypto prefix lacks the pinned build provenance")
command = [
    a.cmake,
    "-S",
    str(HERE),
    "-B",
    str(build),
    "-DCMAKE_BUILD_TYPE=Release",
    "-DOPENSSL_ROOT_DIR=" + str(prefix),
    "-DOPENSSL_INCLUDE_DIR=" + str(prefix / "include"),
    "-DOPENSSL_SSL_LIBRARY=" + str(prefix / "lib/libssl.a"),
    "-DOPENSSL_CRYPTO_LIBRARY=" + str(prefix / "lib/libcrypto.a"),
    "-DLWS_SOURCE=" + str(source),
    "-DMONGOOSE_SOURCE=" + str(fixture / "webserver/jni/mongoose"),
]
if a.generator:
    command += ["-G", a.generator]
if a.preflight:
    command += ["-DSPIKE_PREFLIGHT=ON"]
command += ["-D" + value for value in a.define]
subprocess.run(command, check=True)
subprocess.run([a.cmake, "--build", str(build), "--parallel", "4"], check=True)
files = {
    str(x.relative_to(HERE)): hashlib.sha256(x.read_bytes()).hexdigest()
    for x in HERE.rglob("*")
    if x.is_file() and "__pycache__" not in x.parts
}
record = {
    "preflight": a.preflight,
    "command": command,
    "dependencies": LOCK,
    "sources": files,
}
(directory / "build-evidence.json").write_text(
    json.dumps(record, indent=2) + "\n", encoding="utf8"
)
