#!/usr/bin/env bash
# Build the standalone Windows x64 ADBlock web server with mingw-w64.
# Run inside an MSYS2 MINGW64 shell (see .github/workflows/windows-release.yml).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."   # repo/webserver

OUT="$HERE/dist"
rm -rf "$OUT"; mkdir -p "$OUT"

echo "==> Embedding Win11 visual-style manifest ..."
windres "$HERE/app.rc" -O coff -o "$HERE/app_res.o"

echo "==> Compiling webserver.exe ..."
gcc \
  -std=c11 -O2 -Wall \
  -D MG_ENABLE_IPV6 -DMG_TLS=MG_TLS_OPENSSL \
  -I"$HERE" \
  -I"$MINGW_PREFIX/include" \
  -o "$OUT/webserver.exe" \
  jni/webserver.c jni/mongoose/mongoose.c "$HERE/gui_win32.c" "$HERE/app_res.o" \
  -L"$MINGW_PREFIX/lib" \
  -lssl -lcrypto -lws2_32 -lwinmm -lpthread \
  -lgdi32 -luser32 -lshell32 -ladvapi32 -lcrypt32

echo "==> Bundling runtime DLLs ..."
for dll in libssl-3-x64.dll libcrypto-3-x64.dll zlib-1.dll \
           libgcc_s_seh-1.dll libwinpthread-1.dll; do
  if [ -f "$MINGW_PREFIX/bin/$dll" ]; then
    cp "$MINGW_PREFIX/bin/$dll" "$OUT/"
  fi
done

echo "==> Build output:"
ls -la "$OUT"
echo "==> Done."
