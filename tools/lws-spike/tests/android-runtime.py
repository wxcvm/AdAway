"""Only disposable emulators. Execute both real address families inside Android."""

import argparse, json, pathlib, shlex, subprocess, time

p = argparse.ArgumentParser()
p.add_argument("--build", required=True)
p.add_argument("--image", required=True)
p.add_argument("--output", required=True)
a = p.parse_args()
out = pathlib.Path(a.output).resolve()
out.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, encoding="utf8").strip()


serial = adb("get-serialno")
if (
    not serial.startswith("emulator-")
    or adb("shell", "getprop", "ro.kernel.qemu") != "1"
):
    raise RuntimeError("Refusing to push/run on a real device")
root = "/data/local/tmp/adaway-lws-spike"
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
    shell = f"({cmd} > {folder}/stdout.log 2> {folder}/stderr.log; echo $? > {folder}/exit) < /dev/null &"
    adb("shell", shell)
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
            adb("shell", root + "/spike_probe", "18180", "18443", folder + "/ca.pem")
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
        for log in ("stdout.log", "stderr.log"):
            (out / (name + "-" + log)).write_text(
                adb("shell", "cat", folder + "/" + log), encoding="utf8"
            )
(out / "android-runtime.json").write_text(
    json.dumps(results, indent=2) + "\n", encoding="utf8"
)
print(json.dumps(results))
