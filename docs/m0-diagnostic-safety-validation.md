# M0: bounded, authorized Android diagnostics

Depends on draft PR #43 (`feat/traffic-mode-foundation`). This stage does not merge, release, add default routes or enable production packet forwarding.

## Implemented safety boundaries

- A nonblocking TUN descriptor and 100 ms poll keep reads independent of DNS/UDP waits. Two transport workers have an eight-packet queue; a separate eight-response queue feeds one writer. Direct Echo does not queue behind network timeouts. Full queues drop deterministically. UDP replies can complete out of arrival order and retain their originating endpoints.
- Only the current app package is admitted by `Builder.addAllowedApplication`. TUN bytes alone cannot establish sender UID. The UI probe allocates its source socket first and obtains an in-process, single-use DNS lease bound to that app UID, source port and exact question. Only one lease is outstanding; its 2500 ms TTL, explicit close, consumption before network I/O and service shutdown prevent retries/replay from authorizing further upstream queries. No Intent or exported IPC API can issue a lease.
- Shutdown revokes leases, closes registered sockets to cancel receives, interrupts the writer and discards pending work. Session counters belong to the service instance; stale work cannot reset another session's statistics.
- IPv4 checksums are always verified, including zero values. UDP lengths/checksums, protocol, fragmentation, diagnostic IPs and ports remain constrained. Fixed-question DNS responses validate section boundaries and compression pointers in addition to ID/question/source matching.
- OS-reported VPN owner UID, live local service evidence and current VPN consent are combined; incomplete observations are UNKNOWN, never invented ownership. Package installation or process presence is not ownership evidence.
- Root cleanup preserves shell exit status, removes repeated hooks with a limit, and cannot label inspection/deletion failures CLEAN. Installation uses a fail-fast subshell and attempts own-chain cleanup on failure. Only the product's named chain is touched.

## Executed local checks

```sh
source /workspace/setup-tools/activate.sh
cd /workspace/AdAway
bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  --no-daemon --max-workers=2 -Pandroid.injected.build.abi=arm64-v8a
```

80 JVM tests passed, zero failures/errors/skips, including real loopback UDP transport and receive cancellation, authorization expiry/replay, queue saturation, late responses, shell failure/duplicate-hook scenarios, 20,000 deterministic malformed-IP/DNS inputs and 1,000 valid payload controls. All native modules and the Debug app/test APKs built successfully. With the IDE ABI selector these APKs are in `app/build/intermediates/apk/debug/` and `app/build/intermediates/apk/androidTest/debug/`; the CI artifact paths match the verified directories. Current CI links and exact results are recorded in the PR description.

This is not an Android TUN end-to-end acceptance result. JVM loopback I/O is not the same as Android `protect()`, Network binding, VPN permission or device routing.

## Device instrumentation: BLOCKED

No usable Android device is attached/verified in this cloud workspace; the local adb binary also attempts to initialize a read-only home directory. The permission-boundary instrumented tests are compiled but **not executed**. No device throughput, energy or latency values are claimed.

On a fresh, dedicated ARM64 Android 11+ test device (preserve existing user data and production installation):

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.adaway.model.traffic.DiagnosticConsentBoundaryTest
```

The Debug signing certificate/version differs from official releases; do not treat the test APK as a release upgrade. Use a clean test device/profile, not an automated uninstall of the user's production app.

Manual consent-gated acceptance:

1. Capture routes/DNS before starting. Confirm no other VPN/root hijack is active. Explicitly consent through the diagnostic UI.
2. Confirm only this app's traffic to `198.18.0.1/32` enters TUN. Test the three diagnostic ports through the provided UI; normal third-party traffic must retain its original route.
3. A separate test app sends matching fixed DNS packets: no upstream query may occur. Repeating or spoofing an old request, mismatched port/query and expired/canceled permits must fail.
4. Block UDP 53 or switch Wi-Fi/mobile networks during the DNS probe. Echo must remain responsive; no queue growth or service-thread leak is allowed. Any claimed Android API behavior must be measured here.
5. Revoke VPN consent, stop/kill the app, lock/suspend the device, and repeat start/stop. Verify no diagnostic route remains and baseline networking is unchanged. UNKNOWN ownership must not be displayed as verified takeover/coexistence.
6. On an explicitly authorized rooted test image, exercise iptables inspection/deletion failures and duplicate hooks. Confirm actual own-chain state; a failed cleanup must stay reported as failure. Never execute the fake shell test against host/system iptables.

## Remaining capabilities

No generic TCP/UDP forwarding, IPv6 path, production DNS policy, global route, app-wide protection, HTTPS decryption, QUIC content filtering or verified AdGuard coexistence is added. M1 can freeze shared domain/flow contracts independently; M2 activation requires a real forwarding backend and the device acceptance above. No downstream activation/merge is authorized by passing these unit tests.
