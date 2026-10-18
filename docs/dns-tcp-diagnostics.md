# Bounded DNS UDP-to-TCP diagnostics

This phase extends the existing opt-in `example.com IN A` diagnostic. It is not
a general DNS resolver, listener, cache or a default VPN route. No package is
installed on the daily device/host, and no system DNS or third-party settings
are changed by development or CI.

`DiagnosticDnsNetwork` still selects an OS-provided IPv4 resolver on a validated
non-VPN network. `DiagnosticDnsTransport` opens an unconnected UDP socket,
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
The diagnostic's fixed query, one-use authorization and scoped /32 are unchanged.

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

Protocol/API references: [RFC 7766 sections 4 and 8](https://www.rfc-editor.org/rfc/rfc7766.html),
[VpnService.protect(Socket)](https://developer.android.com/reference/android/net/VpnService#protect(java.net.Socket)),
[Network.bindSocket(Socket)](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)).
Real Android network protection/binding, network switches and device stop/revoke
remain device acceptance items; loopback/JVM/CI evidence does not replace them.
