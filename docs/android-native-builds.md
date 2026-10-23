# Android native architecture builds

The default remains `arm64-v8a`. Select exactly one production ABI per APK:

```
./gradlew :app:assembleRelease -PnativeAbi=arm64-v8a
./gradlew :app:assembleRelease -PnativeAbi=x86_64
./gradlew :app:assembleRelease -PnativeAbi=riscv64
```

`gradle/native-abi.gradle` selects the ABI, minimum API and ELF machine for the
app, webserver and tcpdump. ARM64/x86_64 require API30; RISC-V requires API35.
The existing `ruleLoaderTestAbi=x86_64` option remains a disposable test mode
that omits the production webserver. It cannot be combined with `nativeAbi`.
An unsupported ABI fails configuration rather than packaging another binary.

Pinned NDK 28.1.13356709 and OpenSSL 3.5.9 are unchanged. OpenSSL archives,
work directories and generated executables are separated by ABI. Build identity
includes upstream source, ABI, API, NDK and compiler workaround; changes to the
build script invalidate the Gradle task and CI cache. Compiler and sysroot must
exist for the selected Android target. Linux RISC-V executables are not used.

NDK28.1's Clang optimizer crashed compiling OpenSSL `crypto/sha/keccak1600.c`
at `-O3` in CI run 39974845635. The build currently applies `-O1` only to that
RISC-V object through a checked Makefile target. SHA-3, TLS, assembly paths and
other library optimization remain enabled. This is a narrowly scoped compiler
workaround, not evidence of native runtime or cryptographic performance.

APK verification rejects wrong-machine ELF files, zero/invalid executable
entries, stale variant outputs and load segments without 16KB alignment.
The API35 x86_64 runtime job actually starts the packaged server on private
IPv4/IPv6 loopback, compares repeated complete image bodies, verifies fresh
encrypted TLS receipts using only the fixture server CA in a private SSLContext,
and loads the packaged tcpdump with `--version`. It does not capture traffic,
change a trust store, request root or change routes.

RISC-V's Android ABI remains provisional according to
[Google's Android RISC-V project](https://github.com/google/android-riscv64#status).
Build/ELF/signing checks do not establish operation on arbitrary RISC-V images.
Actual RISC-V Android device/emulator acceptance must be recorded separately.
See [preview package contract](android-abi-preview-contract.md) and
[architecture-aware updates](android-abi-updates.md).
