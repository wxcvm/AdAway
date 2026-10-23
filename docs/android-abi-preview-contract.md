# Production-identity ABI previews

The preview workflow produces separate Release APKs for `arm64-v8a`, `x86_64`
and `riscv64`, using one version code/name and the production package
`com.wxcvm.Adaway`. Each APK is signed with the existing production keystore;
each signer is independently compared with the current published stable APK.
An ABI build, native verification, signature or identity failure stops the whole
publication. No APK is substituted for another ABI and stable update pointers
are not advanced by this workflow.

| Native ABI | APK attachment | Required Android API | Acceptance boundary |
|---|---|---|---|
| `arm64-v8a` | `AdAway-preview-arm64.apk` | 30 (Android 11) | Production-identity ARM64 build; device acceptance remains separate |
| `x86_64` | `AdAway-preview-x86_64.apk` | 30 (Android 11) | Native x86_64 build; disposable test fixtures do not establish every device path |
| `riscv64` | `AdAway-preview-riscv64.apk` | 35 | Provisional Google Android ABI; no RISC-V device or emulator runtime acceptance claimed |

RISC-V is provisional toolchain/platform support. A successful build and ELF
check is not a claim that this APK runs on an arbitrary RISC-V Android image.
The pinned Google NDK must actually provide the requested compiler/sysroot;
there is no fallback to an unknown third-party toolchain or another ABI.

The build checks the packaged webserver and tcpdump against the generated
executables for that exact ABI, including ELF machine, executable entry point,
load-segment contents and 16 KB segment alignment. It repeats these checks on
the signed APK. These checks reject shared-library placeholders and stale or
wrong-ABI outputs. Signed identity reports must agree on package, version name
and version code; each ABI's minimum SDK and native-code declaration is checked.

`manifest.json` keeps the ARM64 `downloadUrl`, `sha256`, package and version fields
at the top level for existing clients. Its optional `artifacts` map contains
ABI-specific URLs, digests, `packageName`, `minSdk`, `nativeAbi` and source commit.
Every URL uses this preview's immutable release tag. ARM64 keeps the existing
`APK-IDENTITY.txt` and `APK-SIGNATURE.txt` attachment names; additional reports
use `-x86_64` and `-riscv64` suffixes. All packages upgrade the same installed app
in place; no parallel preview package identity is introduced.

Publication occurs only through an explicit dated preview tag or controlled
preview release branch. The workflow never installs a user device package.
