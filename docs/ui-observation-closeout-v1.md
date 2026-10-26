# Current-stack UI and response observations

This branch retains the production HTTP/TLS core, package identity, update feeds,
rule semantics, database schema and resource limits. The LWS spike is independent.

## State ownership

- `StatsViewModel` owns immutable ranking/certificate observations. Missing or
  malformed ranking evidence preserves the last valid result as stale; a valid
  empty array replaces it with a current empty result. The enabled card remains
  mounted throughout. Counts must fit an exact nonnegative signed long.
- Each settings detail category owns its saveable scroll state. No post-layout
  scroll-to-top is used. Lifecycle resume starts one certificate observation;
  initial status is unknown/checking, never a false untrusted/stopped result.
- Certificate reads publish service/trust/expiry together, off the UI thread.
  A bounded wait and a single unfinished root worker prevent queued root probes.
  Revision checks reject late results. User-initiated trust actions have a
  ViewModel-owned single-flight gate that survives category navigation and is
  released on failure/cancellation; no automatic certificate mutation is added.
- Response and arrival diagnostics share one admission gate. An ordinary manual
  GET / probe is separate from authenticated arrival receipts. It can increment
  Web request statistics. It is not DNS interception or browser-rendering proof.

## Actual-response check boundaries

Saved literal local IPv4 and IPv6 targets are checked separately; wildcard binds
map only to the same family's loopback address. No address is created. HTTPS uses
normal chain and actual-IP name validation without hostname substitution.
Headers and body share a 4096-byte read allowance; samples are at most 512 bytes.
Redirects are not followed. Chunked/incomplete responses are explicitly incomplete,
image prefix recognition does not certify a full image, and TLS rejection is not
attributed to certificate pinning. Opening the external browser is manual and
revalidates the saved configuration and local ownership.

## Evidence requirements

Local strict javac/JUnit checks cover the pure-Java diagnostic path only. Required
CI also runs full JVM tests, API 30/35 first-frame/state/response instrumentation,
three ABI APK builds and existing native/sanitizer checks. Screenshot sequences
and semantic manifests are artifacts, not merely final screenshots. Restoration
simulation is not a physical rotation or Android 17 device acceptance.
No phone installation, reboot, network/certificate/AdGuard change is authorized by
these tests. Android 17 whole-page flashing remains device-pending until observed.
