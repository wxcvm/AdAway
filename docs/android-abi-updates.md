# Android ABI-aware application updates

The production package and signing identity remain `com.wxcvm.Adaway`. Native
packages are Android APKs for `arm64-v8a`, `x86_64`, or `riscv64`; a Linux RISC-V
archive is never an alternative Android update. The current native build floors
are Android API 30 for ARM64/x86_64 and API 35 for RISC-V.

The release manifest may include an `artifacts` object keyed by those exact ABI
names. Each entry requires:

| Field | Contract |
| --- | --- |
| `downloadUrl` | Exact HTTPS URL in `wxcvm/AdAway`, under the same immutable release tag, with the ABI-specific APK filename below. |
| `sha256` | Exactly 64 hexadecimal digits for that APK; normalized to lowercase by selection. |
| `packageName` | Exactly `com.wxcvm.Adaway`. |
| `minSdk` | Integer Android API level, at least the current ABI build floor. |
| `nativeAbi` | Optional metadata; when present, must equal the containing ABI key. |

Other entry metadata such as `sourceCommit` is permitted. Unknown ABI keys,
malformed entries, cross-tag URLs, missing checksums, and an explicitly present
but empty/invalid `artifacts` object reject the manifest. They do not enable a
fallback to its legacy top-level ARM64 URL.

Preview filenames are `AdAway-preview-arm64.apk`, `AdAway-preview-x86_64.apk`,
and `AdAway-preview-riscv64.apk`. Future stable artifact maps use
`ADBlock-latest.apk`, `ADBlock-x86_64.apk`, and `ADBlock-riscv64.apk`. Adding
selection support does not publish a stable release or modify stable pointers.

Selection uses the running device's `Build.SUPPORTED_ABIS` preference order and
`Build.VERSION.SDK_INT`. It does not confuse `BuildConfig.NATIVE_ABI` (the
currently installed APK's architecture) with the device's preferred native ABI.
An ARM APK running under platform translation can therefore discover a genuine
native RISC-V APK when Android reports RISC-V support and the API requirement is
met. No ABI or translation capability is invented by this selector.

Legacy manifests without `artifacts` retain their ARM64-only meaning. A device
that does not report ARM64 support cannot receive that legacy download. The
top-level ARM64 package/URL/version fields remain available to older clients;
new artifact metadata does not retroactively change their installed updater.

The selected ABI, checksum, and API floor are persisted atomically with the
DownloadManager ID and expected version. They survive process death. Duplicate
download suppression compares this complete selection, so an older pending ARM
package cannot suppress the correct native package at the same version code.

Before installation the private verified APK copy must have the selected
SHA-256 (when supplied by artifact metadata), declared Android minimum API,
production package, newer expected version, and the installed signing identity.
Its two packaged native executables must be ELF64 little-endian PIE files for
the selected ABI, with an entry in an executable load segment and Android's
`/system/bin/linker64` interpreter. Native library paths for another ABI and
shared-library placeholders are rejected. ZIP inspection reads bounded headers;
it neither extracts nor executes these binaries.

Startup, daily and foreground checks use this same selector. Preview opt-out,
anti-downgrade checks, bounded downloads, and Android installation confirmation
remain in effect. A compatible preview can still be selected when the stable
pointer only describes an incompatible legacy ARM package and the optional API
fallback is unavailable.

JVM tests cover all ABI/SDK selection and malformed metadata cases, including
legacy and immutable URL behavior. Archive tests cover ELF machine/layout,
Linux interpreter rejection, missing executables, malformed paths and digest
mismatch. API 30/35 model tests use controlled compatible manifests and fake
downloads; they do not install an update. A host archive check or an x86 emulator
does not establish installation, native startup, or network behavior on a real
RISC-V Android device.
