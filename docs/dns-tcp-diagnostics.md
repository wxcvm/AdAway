# Bounded DNS UDP-to-TCP diagnostics

This phase extends the existing opt-in `example.com IN A/AAAA` diagnostic. It is not
a general DNS resolver, listener, cache or a default VPN route. No package is
installed on the daily device/host, and no system DNS or third-party settings
are changed by development or CI.

`DiagnosticDnsNetwork` selects an OS-provided resolver on a validated non-VPN
network. Within each network, an eligible IPv4 resolver retains priority; an
IPv6-only DNS list is now supported. Any/loopback/multicast endpoints are
excluded. A link-local IPv6 resolver requires an OS-provided positive scope ID;
the original scoped address object is preserved, with no guessed interface.
No custom upstream, family racing or cross-family retry is added.
`DiagnosticDnsTransport` opens an unconnected UDP socket,
registers it for stop cancellation, then requires `VpnService.protect()` and
`Network.bindSocket()` before connecting/sending. Only a matching reply with
TC set triggers one TCP retry to that same address/port/network. TCP also
registers and runs protection/binding before connection. Ordinary valid UDP
responses do not open TCP; timeout or invalid UDP data do not trigger retry.

One monotonic 1200 ms budget covers UDP and TCP connect/read phases; each
partial read uses the remaining budget. TCP uses the two-octet message length
field and handles split prefix/body reads. The original 512-byte message cap
remains: larger TCP frames, short frames, mismatched questions/IDs and a second
truncated response fail explicitly, without a fabricated success. Stop closes
both UDP and TCP sockets, including registration racing with shutdown.

Published rule decisions still precede the transport. Locally blocked/replaced
questions use the existing synthesis path without contacting an upstream.
The diagnostic retains one fixed name and now admits only types A and AAAA.
One-use authorization includes the full question: A/AAAA requests with the same
ID cannot consume each other's permits. Response matching includes the exact
original type, including before accepting TC as a TCP retry. Other types, names,
classes and extensions remain rejected. The scoped /32 is unchanged.

Settings provides separate explicit A and AAAA test actions. Only one DNS test
can run at a time; feedback names the tested type. AAAA checks IPv6 address
records independently of the upstream's IP family. Upstream UDP/TCP may now use
IPv4 or IPv6, while the client diagnostic packet and scoped TUN /32 remain IPv4.
This does not establish an IPv6 TUN or prove IPv6 network interception.

The portable `diagnostic_dns_reply.h` and Java `DiagnosticDnsCodec` consume
`diagnostic_dns_vectors.tsv`. Both distinguish reject, accepted complete reply
and TCP retry. A truncated UDP body may be incomplete: identity and the complete
fixed question are required before retry, and the truncated body is never
returned as an answer. Complete responses check record bounds and compression
loops; this is envelope validation, not DNSSEC/CNAME/cache interpretation.

Validation includes shared vectors and malformed input, real loopback UDP/TCP,
split frames, malformed replies, shared deadline, denied TCP protection, stop
cancellation and rule-before-network integration. Windows uses this codec from
an independently pinned contract in its separate diagnostic executable, without
changing its production portable-core or rule-contract pins.

There are 53 shared reply cases and 16 shared real-import policy cases in
`diagnostic_dns_vectors.tsv` and `diagnostic_dns_policy_vectors.tsv`. The latter
compare action, local source, scope, generation and exact synthesized DNS bytes,
including IPv4/IPv6 replacements, both address-family mismatch directions,
ABP exceptions and unsupported resource conditions. JVM parser/snapshot/relay,
API 30/35 Room import/persistence and the Windows CLI consume the same fixtures.
The authorized AAAA packet relay also runs against real loopback UDP/TCP and
checks returned packet checksums and single-use replay rejection.
All nine real socket transport tests run once on 127.0.0.1 and once on ::1,
including malformed replies, wrong types, total deadline, protection failure,
cancellation and rule-before-network handling. IPv6 availability is required
for these checks; they do not silently rerun on IPv4 or skip the IPv6 cases.
API 30/35 instrumentation also exercises actual Android ::1 UDP, TC-to-TCP,
split frame reads, both query types, scoped-address preservation and stop-time
peer connection closure. These tests do not establish a VPN or invoke system
DNS; actual VpnService protect/bind and external IPv6 reachability remain
dedicated-device acceptance items.

Protocol/API references: [RFC 7766 sections 4 and 8](https://www.rfc-editor.org/rfc/rfc7766.html),
[RFC 3596 sections 1 and 2](https://www.rfc-editor.org/rfc/rfc3596.html),
[RFC 4007 scoped addresses](https://www.rfc-editor.org/rfc/rfc4007.html),
[VpnService.protect(Socket)](https://developer.android.com/reference/android/net/VpnService#protect(java.net.Socket)),
[Network.bindSocket(Socket)](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)).
Real Android network protection/binding, network switches and device stop/revoke
remain device acceptance items; loopback/JVM/CI evidence does not replace them.
