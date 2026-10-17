# M1 proposed core contract v1

Depends on Android M0 PR #45 and Windows M0 PR #3. This proposal is a separate review stage, not an activation or production rule-engine replacement. No third shared-core repository is introduced.

`webserver/jni/traffic_core_vectors.txt` is the single 23-case domain-decision fixture. The Java helper and portable C header consume identical inputs; Windows obtains the header, fixture and C test from its exact Android core pin. Existing modifier/normalization vectors remain separate because URL/resource rules cannot safely be reduced to DNS domain rules.

## Decision semantics

1. An invalid question/configuration produces PASS with INVALID_DATA. Non-IN questions remain opaque PASS/UNSUPPORTED_CLASS.
2. User allow/block overrides every matched rule. Otherwise an allow exception takes precedence over replacement and blocking.
3. Valid replacement precedes an ordinary block. Replacement must match A/IPv4 or AAAA/IPv6; it never synthesizes A data for MX/HTTPS. Invalid replacements fail open with a diagnostic reason.
4. Blocking supports NXDOMAIN, REFUSED or zero address. Zero-address policy for non-address questions becomes REFUSED rather than emitting a malformed RR.
5. An unclassified flow is PASS/UNCLASSIFIED; DNS evidence cannot classify encrypted HTTP paths, methods or resource types.

The Java `RuleSnapshotRepository` owns immutable maps and atomically publishes their paired generation. Invalid/older updates leave the previous snapshot intact. It is a domain-only publication primitive, **not yet connected to the real Hosts/ABP loaders**. Native production rule loading has not been replaced by this class. Java concurrent-read testing does not prove native concurrent update safety.

## Interface shapes

The companion JSON Schema defines Packet, FlowKey, DnsQuestion, RuleMatch, Decision, ConnectionStats, ModeState and ChangeJournal. Packet bytes remain unparsed/untrusted until a platform-specific validated decoder runs. C structures are host-memory shapes: never persist/send their raw padded representation; network byte order and versioned field encoding are required at an ABI boundary. IPv4 uses four bytes (remaining C address slots zero); IPv6 uses 16. Flow session generations are nonzero signed-63-bit identifiers and cannot survive restart implicitly.

PASS means a rule permits forwarding, **not** that a socket connected. Counters separate forwarding attempts and confirmations; only verified data-plane completion can increment the latter. STANDALONE_ACTIVE/COEXIST_VERIFIED require actual platform/routing/forwarding evidence, not process installation or packet visibility.

Cache keys must include canonical question/type/class plus rules/network generations and all response-changing scope. Shared cache eligibility excludes DNSSEC, EDNS and personalized input, and never caches SERVFAIL/transient errors. Negative caching needs actual authoritative TTL/negative SOA evidence in M2; this helper only decides eligibility and implements no cache. Raw question case/transaction ID/extensions cannot be blindly reused across clients.

ChangeJournal is a **proposal shape**, not a production recovery authorization. The OS-specific old/new route/DNS schemas, exact typed serialization, ownership/conflict checks, durable ordering and executor are M3 prerequisites. The Windows token-only model is not automatically converted into this shape, and `verifiedOsState=true` is never inferred from successful decryption.

## Validation

```sh
cc -std=c11 -Wall -Wextra -Werror -pedantic webserver/jni/traffic_core_contract_test.c -o /tmp/core-contract-test
/tmp/core-contract-test
python3 tools/core_contract_schema_test.py
./gradlew :app:testDebugUnitTest --tests 'org.adaway.model.traffic.*' --no-daemon --max-workers=2
```

Core tests exercise the same 23 decisions and all 16 DNS response-code cache cases. Java tests additionally cover defensive address ownership, forwarding-counter invariants, malformed/stale update rollback and generation-consistent concurrent reads. Standard JSON Schema validation uses the pinned test-only dependencies in `tools/core-contract-requirements.txt`; data-shape validation is not execution authority or a DNS wire parser. Windows CI repeats the exact C fixture plus the existing modifier vector suite after pin acquisition. Passing these contract tests does not establish TCP/UDP forwarding, device connectivity, native reload atomicity, DNS coverage or OS recovery.

M2 must select and evaluate one maintained TCP/UDP stack, implement a real bounded/protected isolated data path and demonstrate device E2E before any default route. M3 remains blocked on production recovery semantics and verified platform integration. No dependent PR is merged by this stage.
