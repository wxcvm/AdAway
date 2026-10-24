# Content response settings

These controls change responses to HTTP requests already received by the local
Web server. They do not create DNS/Hosts rules, remove an upstream block, decrypt
untrusted HTTPS, or handle QUIC. Resource categories are request-based hints.

The editor exposes all ten existing native categories. Enabled selects that
category response (image, empty script, JSON, or its existing 204 policy).
Disabled means HTTP 204, not allow/forward. Visible placeholders and duplicate,
retry and circuit-breaker controls retain their independent opt-in defaults.
The retired response-cache shortcut remains disabled.

Switches remain drafts until Save. Complete numeric flags and a unique generation
are written to block_config.json through AtomicRuleFile before legacy preference
keys are mirrored. Startup and legacy read/modify/save use one shared lock.
A save failure preserves the published file. A saved file without an exact
running-server generation acknowledgement is described as saved, not applied.
A legacy server returning only the old success body cannot confirm a generation.

Management clients try IPv6 and IPv4 loopback without proxies under one four-second
budget. Control requests reject web Origin headers and requests on a response
port. Native port comparisons convert Mongoose network-byte-order ports.
Malformed or invalid-flag reloads retain the previous running configuration.

Domain responses are a separately atomically saved document, rule_overrides.txt.
The editor accepts up to 64 ASCII domains shorter than 128 characters, each with
204, 403, 404, 410, 503 or 200. Labels are bounded and paths, wildcard/filter syntax
and unsupported conditions are rejected. Domains match themselves and subdomains
at label boundaries; the first matching line wins. Non-204 statuses return a
short plain-text block message. Rule edits do not enable overrides automatically.
The native server refreshes this document on requests at most once per second;
it has no separate runtime acknowledgement. The UI states that limitation.
An unreadable rule file does not prevent disabling overrides or editing unrelated
settings, and cannot be overwritten by an empty editor. Existing legacy syntax
is shown for explicit correction instead of silently rewritten.

Domain overrides use log mode 4 (domain_response), preserving the legacy one-byte
mode and record layout. They carry unknown resource type because the domain
policy did not classify the request. They must not appear as structured resource
or HTTP 204 when the response was e.g. 403. Legacy mode 0..3 meanings are retained.

Validation includes production native classifier/file-response functions on real
private IPv4/IPv6 sockets, all categories on/off, all six response statuses,
first-match and label-boundary cases, exact-generation ACK, old-server ACK and
invalid configuration retention. JVM, API30/API35 storage/transport, Compose and
actual production x86_64 APK runtime checks are separate gates. RISC-V runtime
acceptance is not established by these tests. No personal device is installed or
rooted, no trust store is changed, and no host traffic is taken over.
