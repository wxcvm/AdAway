"""Real native client's positive and deliberately corrupted-body evidence."""

import argparse
import json
import pathlib
import subprocess
from correctness import Server

parser = argparse.ArgumentParser()
parser.add_argument("--binary", required=True)
parser.add_argument("--probe", required=True)
parser.add_argument("--image", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
root = pathlib.Path(args.output).resolve()
root.mkdir(parents=True, exist_ok=True)
exe, image, probe = (
    pathlib.Path(value).resolve() for value in (args.binary, args.image, args.probe)
)
rows = []
corrupt = root / "corrupt-image.webp"
corrupt.write_bytes(b"deliberately not the pinned image")
for content, expected in ((image, 0), (corrupt, 3)):
    with Server(exe, content, root) as server:
        result = subprocess.run(
            [
                str(probe),
                str(server.http),
                str(server.https),
                str(server.root / "ca.pem"),
            ],
            capture_output=True,
            text=True,
            timeout=45,
        )
        assert result.returncode == expected, (
            result.returncode,
            result.stdout,
            result.stderr,
        )
        row = dict(
            corrupt_body=expected != 0, exit=result.returncode, stderr=result.stderr
        )
        if not expected:
            row["checks"] = json.loads(result.stdout)
            assert row["checks"]["native_loopback_checks"] == 18
            assert row["checks"]["body_hashes_verified"]
        rows.append(row)
(root / "native-probe.json").write_text(
    json.dumps(rows, indent=2) + "\n", encoding="utf8"
)
print(json.dumps({"native_positive_and_corrupt_body": True}))
