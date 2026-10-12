#!/usr/bin/env bash
# Build pinned upstream OpenSSL for the existing Android arm64 executable.
# Must run from a Linux host with Android NDK, Git, Perl, Make and C compiler.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="3.5.9"
TAG="openssl-$VERSION"
COMMIT="45e844fa2a14ec92d146bd8f5778ac130b6625fb"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK" || ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  echo "ERROR: set ANDROID_NDK_ROOT to the installed Linux Android NDK directory" >&2
  exit 1
fi
for executable in git perl make; do command -v "$executable" >/dev/null || {
  echo "ERROR: missing build tool $executable" >&2; exit 1;
}; done

export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
OUT="$HERE/build/official-openssl/arm64-v8a"
SOURCE="$HERE/build/official-openssl/source"
mkdir -p "$OUT"
if [[ ! -e "$SOURCE/.git" ]]; then
  rm -rf "$SOURCE"
  git clone --quiet --depth 1 --branch "$TAG" https://github.com/openssl/openssl.git "$SOURCE"
fi
GOT="$(git -C "$SOURCE" rev-parse HEAD)"
if [[ "$GOT" != "$COMMIT" ]]; then
  echo "ERROR: OpenSSL source commit mismatch ($GOT != $COMMIT)" >&2
  exit 1
fi
if [[ -f "$OUT/libssl.a" && -f "$OUT/libcrypto.a" && -f "$OUT/include/openssl/ssl.h" && -f "$OUT/BUILD-COMMIT" ]] &&
   [[ "$(cat "$OUT/BUILD-COMMIT")" == "$COMMIT" ]]; then
  echo "Pinned OpenSSL $VERSION already built."
  exit 0
fi

# Worktree ensures no partially generated files are mistaken for a finished
# library, and does not modify the upstream checkout.
WORK="$HERE/build/official-openssl/work"
rm -rf "$WORK"
mkdir -p "$WORK"
git -C "$SOURCE" archive HEAD | tar -xf - -C "$WORK"
pushd "$WORK" >/dev/null
# Static link avoids shipping a second set of SSL .so binaries alongside the
# server executable. No default TLS versions/ciphers are disabled here.
./Configure android-arm64 -D__ANDROID_API__=30 \
  no-shared no-tests no-apps no-docs no-module \
  -Wl,-z,max-page-size=16384 \
  --prefix="$OUT" --openssldir="$OUT/ssl"
make -j "${OPENSSL_JOBS:-4}" build_libs
make install_dev
popd >/dev/null

[[ -s "$OUT/libssl.a" && -s "$OUT/libcrypto.a" ]] || {
  echo "ERROR: OpenSSL libraries were not generated" >&2; exit 1;
}
[[ -f "$OUT/include/openssl/opensslv.h" ]] || {
  echo "ERROR: OpenSSL headers were not generated" >&2; exit 1;
}
echo "$COMMIT" > "$OUT/BUILD-COMMIT"
echo "Official OpenSSL $VERSION from $COMMIT built for Android arm64."
