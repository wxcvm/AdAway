# OpenSSL 3.5.9 LTS source migration

The official upstream source is https://github.com/openssl/openssl at
tag `openssl-3.5.9`, resolved and checked against commit
`45e844fa2a14ec92d146bd8f5778ac130b6625fb`.

Do **not** change this pin without checking the official tag, security notes,
license and target compiler compatibility.

## Android

- `webserver/scripts/build-official-openssl.sh` builds arm64-v8a static libraries
  from the official source with Android API 30 and the project's pinned NDK.
- `webserver/jni/Android.mk` links them statically into the local server
  executable. Old `com.android.ndk.thirdparty:openssl:1.1.1q-beta-1`
  Prefab import was removed.
- `./gradlew :app:assembleDebug` is the build and packaging smoke test;
  `.github/workflows/openssl-lts-ci.yml` validates the actual executable
  version and absence of OpenSSL shared-library dependencies.
- Android TLS handshake, certificate chain and runtime compatibility on
  physical devices remain separate release gates.

## Windows

- Separate `wxcvm/AdAway-Windows` PR builds the identical official release
  from source with MINGW64 and statically links the archives.
- Do not assume the MSYS2 OpenSSL DLL is the same version as the built
  application; inspect imports and embedded version information.
- Verify existing HTTPS listener, CA generation/validation, self-update and
  installer in Windows CI and on a real Windows 11 machine.

## Release gates

1. Both native builds and final artifact version checks pass.
2. HTTPS handshake and CA/certificate management regression tests pass.
3. Platform security/16 KiB Android page compatibility is checked.
4. No new dynamic SSL dependency or unsupported SSL 1.1.x binary is shipped.
5. Source attribution and THIRD_PARTY_LICENSES.md are updated if needed.
6. Do not merge if CI fails; do not automatically publish from these branches.

Rollback: revert the PR commit(s) without altering users' system certificates,
routes, DNS or AdGuard settings. This migration only modifies build/link inputs.
