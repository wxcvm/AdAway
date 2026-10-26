#!/usr/bin/env bash
# Host-only pinned crypto build for isolated CI. Never installs system-wide.
set -euo pipefail
ROOT="${1:?out-of-tree directory required}"
PIN=45e844fa2a14ec92d146bd8f5778ac130b6625fb
mkdir -p "$ROOT"
if [[ ! -d "$ROOT/source/.git" ]]; then
  git clone --quiet --depth 1 --branch openssl-3.5.9 https://github.com/openssl/openssl.git "$ROOT/source"
fi
[[ $(git -C "$ROOT/source" rev-parse HEAD) == "$PIN" ]]
if [[ -f "$ROOT/install/BUILD-COMMIT" && $(cat "$ROOT/install/BUILD-COMMIT") == "$PIN" ]]; then exit 0; fi
mkdir -p "$ROOT/work"
git -C "$ROOT/source" archive HEAD | tar -xf - -C "$ROOT/work"
cd "$ROOT/work"
perl ./Configure linux-x86_64 no-shared no-tests no-apps no-docs no-module --prefix="$ROOT/install" --libdir=lib
make -j4 build_libs
make install_dev
printf '%s\n' "$PIN" > "$ROOT/install/BUILD-COMMIT"
