#!/usr/bin/env bash
# Evaluation only: exact upstream commit and recursively pinned submodules.
set -euo pipefail
test "$#" -eq 1
destination=$1
if [ -e "$destination" ]; then
  echo 'Refusing to replace an existing evaluation directory' >&2
  exit 1
fi
git init -q "$destination"
git -C "$destination" remote add origin https://github.com/heiher/hev-socks5-tunnel.git
pin=b1ce1e6c2f572c1b6fecf262c9ea2d7b0916c947
git -C "$destination" fetch --depth 1 origin "$pin"
test "$(git -C "$destination" rev-parse 'FETCH_HEAD^{commit}')" = "$pin"
git -C "$destination" checkout --detach FETCH_HEAD
git -C "$destination" submodule update --init --recursive --depth 1
python3 - "$destination" <<'PY'
import pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1])
pins={
 'src/core':'9f1cf0bfca0c003dd0cf0fbdcf6912a2b46a8d05',
 'third-part/hev-task-system':'b2f2250371c1bd436a42f3c380ee46d4ac3bada8',
 'third-part/lwip':'7f51e51c72d088481af972c3a115cec41c043d49',
 'third-part/yaml':'94441ec8777eb319936735a58b376963d09c73d4',
}
for folder,pin in pins.items():
 actual=subprocess.check_output(['git','-C',str(root/folder),'rev-parse','HEAD'],text=True).strip()
 assert actual==pin, (folder,actual)
 assert (root/folder/'LICENSE').is_file(), folder
print('Candidate commit, recursive dependency pins and license presence verified')
PY
