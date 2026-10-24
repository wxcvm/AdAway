#!/usr/bin/env bash
# Build pinned upstream OpenSSL for the explicitly selected Android ABI.
# Must run from a Linux host with Android NDK, Git, Perl, Make and C compiler.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="3.5.9"
TAG="openssl-$VERSION"
COMMIT="45e844fa2a14ec92d146bd8f5778ac130b6625fb"
ABI="${ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
  arm64-v8a) TARGET=android-arm64; TRIPLE=aarch64-linux-android; API=30 ;;
  x86_64) TARGET=android-x86_64; TRIPLE=x86_64-linux-android; API=30 ;;
  riscv64) TARGET=android-riscv64; TRIPLE=riscv64-linux-android; API=35 ;;
  *) echo "ERROR: unsupported Android ABI: $ABI" >&2; exit 1 ;;
esac
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
if [[ ! -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${TRIPLE}${API}-clang" ||
      ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE/$API" ]]; then
  echo "ERROR: this NDK does not provide Android $ABI API $API compiler and sysroot; no architecture fallback" >&2
  exit 1
fi
OUT="$HERE/build/official-openssl/$ABI"
IDENTITY="$COMMIT $ABI $API $(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties") keccak-riscv-o1-v1"
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
if [[ -s "$OUT/lib/libssl.a" && -s "$OUT/lib/libcrypto.a" && -f "$OUT/include/openssl/ssl.h" && -f "$OUT/BUILD-COMMIT" ]] &&
   [[ "$(cat "$OUT/BUILD-COMMIT")" == "$COMMIT" ]] &&
   [[ -f "$OUT/BUILD-IDENTITY" && "$(cat "$OUT/BUILD-IDENTITY")" == "$IDENTITY" ]]; then
  echo "Pinned OpenSSL $VERSION already built."
  exit 0
fi

# Worktree ensures no partially generated files are mistaken for a finished
# library, and does not modify the upstream checkout.
WORK="$HERE/build/official-openssl/work-$ABI"
rm -rf "$WORK"
mkdir -p "$WORK"
git -C "$SOURCE" archive HEAD | tar -xf - -C "$WORK"
pushd "$WORK" >/dev/null
# Static link avoids shipping a second set of SSL .so binaries alongside the
# server executable. No default TLS versions/ciphers are disabled here.
./Configure "$TARGET" -D__ANDROID_API__="$API" \
  no-shared no-tests no-apps no-docs no-module \
  -Wl,-z,max-page-size=16384 \
  --prefix="$OUT" --libdir=lib --openssldir="$OUT/ssl"
if [[ "$ABI" == riscv64 ]]; then
  # NDK 28.1 clang 19 crashes in the optimizer for OpenSSL 3.5.9 keccak1600.c
  # at -O3 (CI 39974845635). Keep the workaround local to that object; do not
  # disable SHA-3, TLS, assembly, or optimization for the rest of the library.
  grep -q '^crypto/sha/libcrypto-lib-keccak1600.o:' Makefile || {
    echo 'ERROR: OpenSSL Keccak target changed; review the compiler workaround' >&2; exit 1;
  }
  printf '\ncrypto/sha/libcrypto-lib-keccak1600.o: LIB_CFLAGS += -O1\n' >> Makefile
  echo 'Android riscv64: compiling only the Keccak C object at -O1 to avoid the observed NDK optimizer crash.'
fi
make -j "${OPENSSL_JOBS:-4}" build_libs
make install_dev
popd >/dev/null

[[ -s "$OUT/lib/libssl.a" && -s "$OUT/lib/libcrypto.a" ]] || {
  echo "ERROR: OpenSSL libraries were not generated" >&2; exit 1;
}
[[ -f "$OUT/include/openssl/opensslv.h" ]] || {
  echo "ERROR: OpenSSL headers were not generated" >&2; exit 1;
}
echo "$COMMIT" > "$OUT/BUILD-COMMIT"
echo "$IDENTITY" > "$OUT/BUILD-IDENTITY"
echo "Official OpenSSL $VERSION from $COMMIT built for Android $ABI API $API."
