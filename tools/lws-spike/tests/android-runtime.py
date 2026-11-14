"""Only disposable emulators. Execute both real address families inside Android."""

import argparse, json, pathlib, shlex, subprocess, time

p = argparse.ArgumentParser()
p.add_argument("--build", required=True)
p.add_argument("--image", required=True)
p.add_argument("--output", required=True)
a = p.parse_args()
out = pathlib.Path(a.output).resolve()
out.mkdir(parents=True, exist_ok=True)


serial = None


def adb(*args, timeout=20):
    selected = ["-s", serial] if serial else []
    return subprocess.check_output(
        ["adb", *selected, *args], text=True, encoding="utf8", timeout=timeout
    ).strip()


serial = adb("get-serialno")
if (
    not serial.startswith("emulator-")
    or adb("shell", "getprop", "ro.kernel.qemu") != "1"
):
    raise RuntimeError("Refusing to push/run on a real device")
root = "/data/local/tmp/adaway-lws-spike-" + str(time.monotonic_ns())
adb("shell", "mkdir", "-p", root)
for name in ("spike_lws", "spike_mongoose", "spike_probe"):
    adb("push", str(pathlib.Path(a.build) / name), root + "/" + name)
    adb("shell", "chmod", "700", root + "/" + name)
adb("push", a.image, root + "/image.webp")
results = []
for name in ("lws", "mongoose"):
    folder = root + "/" + name
    adb("shell", "mkdir", "-p", folder)
    cmd = f"{root}/spike_{name} --directory {folder} --image {root}/image.webp --http 18180 --https 18443 --lifetime 90"
    # Redirect the WHOLE wrapper, not just the server. Otherwise the waiting
    # wrapper retains ADB stdout and the host cannot launch its client until
    # the server's lifetime has already expired.
    child = f"{cmd} & server=$!; echo $server > {folder}/pid; wait $server; echo $? > {folder}/exit"
    shell = f"nohup sh -c {shlex.quote(child)} > {folder}/stdout.log 2> {folder}/stderr.log < /dev/null &"
    adb("shell", shell, timeout=5)
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if "READY" in adb("shell", "cat", folder + "/stdout.log"):
                break
            if adb("shell", f"test -f {folder}/exit && cat {folder}/exit || true"):
                raise RuntimeError("Server startup failed")
            time.sleep(0.1)
        else:
            raise RuntimeError("Android startup timed out")
        result = json.loads(
            adb(
                "shell",
                root + "/spike_probe",
                "18180",
                "18443",
                folder + "/ca.pem",
                timeout=45,
            )
        )
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            code = adb("shell", f"test -f {folder}/exit && cat {folder}/exit || true")
            if code:
                break
            time.sleep(0.1)
        if code != "0":
            raise RuntimeError("Android service did not close normally: " + code)
        result.update(
            backend=name,
            api=adb("shell", "getprop", "ro.build.version.sdk"),
            abi=adb("shell", "getprop", "ro.product.cpu.abi"),
        )
        results.append(result)
    finally:
        pid = adb("shell", f"cat {folder}/pid 2>/dev/null || true")
        if pid.isdecimal():
            actual = adb("shell", f"cat /proc/{pid}/cmdline 2>/dev/null || true")
            if actual.startswith(root + "/spike_" + name + "\x00"):
                adb("shell", "kill", "-TERM", pid)
        for log in ("stdout.log", "stderr.log"):
            (out / (name + "-" + log)).write_text(
                adb("shell", "cat", folder + "/" + log), encoding="utf8"
            )
(out / "android-runtime.json").write_text(
    json.dumps(results, indent=2) + "\n", encoding="utf8"
)
print(json.dumps(results))
