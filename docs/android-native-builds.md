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

## RISC-V AndroidX Graphics Path dependency

Compose's pinned ui-graphics 1.8.3 uses graphics-path 1.0.1. Its shared
PathIteratorImpl initializes JNI even on API35; selecting the platform iterator
does not remove the native conic-conversion dependency. The AAR lacks RISC-V.
This is a confirmed unsupported path API, not evidence of a first-screen crash.

RISC-V builds therefore compile libandroidx.graphics.path.so from unmodified
AndroidX 1.0.1 release-endpoint commit
8a05a22af450d589ef911d772a001a49dcb05b71. The source lock records 17 file hashes;
four Kotlin API sources match Google's published 1.0.1 sources jar byte for byte.
The Gradle Java dependency is strictly pinned to that same version for RISC-V.
Other ABIs retain Google's AAR library, so no duplicate library is introduced.

The RISC-V native target retains official C++/visibility/version-script options
and uses static C++ linkage instead of packaging the NDK's 4KB prebuilt shared
C++ runtime. APK verification requires the actual generated shared library,
correct machine, 16KB load alignment and matching stripped load bytes; it rejects
the shared C++ runtime and graphics binaries referring to it. Release assets
include GRAPHICS-PATH-SOURCE.json. API30/API35 tests exercise preserved conics and
native quadratic conversion; production x86 tests repeat that path. These are
not RISC-V runtime acceptance. A RISC-V Android device/image is still required.
