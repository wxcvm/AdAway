# M1-R1: transactional domain imports and native reload integration

Update (2026-10-18): this document records the original loader stage. Typed
Room persistence, suffix/provenance decisions and current integration limits
are described in [typed-rule-provenance.md](typed-rule-provenance.md).

Depends on Android draft #47 (and its #46/#45 parents). This change keeps the
selected mode and diagnostic capture gates. It does not create routes, launch
a new forwarding backend, install a CA, merge or publish a release.

## Implemented production paths

- `SourceLoader` calls the bounded Hosts/ABP parser inside one actual Room
  transaction. Clearing the previous source and all 100-item insert batches
  roll back together on download interruption, malformed recognized rules,
  database failure or cancellation. There are no parser threads or queues
  left waiting after a failed inserter. Complete empty files intentionally
  clear the source. Unsupported conditional/cosmetic rules remain separate.
- Plain domains, multiple Hosts aliases, A/AAAA replacements, exceptions,
  internationalized Android hostnames (normalized to ASCII) and existing
  explicit wildcard allow/replacement forms are retained. The whole ABP
  suffix is inspected: `||example.test^/path` and `$script` are never silently
  turned into unconditional domain rules. Unsupported rules are counted.
- Effective Room precedence is user rules, then allow exceptions, then
  replacement, then ordinary blocking. Duplicate replacement rows use stable
  source/id order; latest matching user row wins. LIKE wildcards are explicit:
  a literal underscore is escaped. After a successful effective-table sync,
  `SourceModel` publishes an immutable paired action/replacement snapshot.
  Validation occurs before commit; a failed/stale publication keeps the old
  snapshot. Addresses are literals, never resolved during rule import.
- The actual native `block_set_load`/`block_set_contains` functions now use
  owned, sorted full-domain names rather than truncated/hash-only matches.
  Rule/index/pool/generation switch together under a lock. Queries return
  copied values; readers cannot retain a freed allocation. The initial legacy
  Android hosts fallback remains available only before the first publication.
  A failed reload preserves the last valid dataset; a valid empty dataset
  still obeys the existing empty-list proxy gate.
- The actual native domain-exemption reader now validates a complete file,
  publishes one snapshot, preserves old data on failure and logs rejection.
  Unchanged exemption content does not advance its generation. Resource-path
  identity prevents an old directory's exemptions leaking into another.
- App-written block, exemption and HTTP override files use a same-directory
  staged write, flush and atomic replacement. There is no unsafe truncate or
  append window. CSS publication uses the same file writer.

## Bounds and generations

Android source streams allow at most 64 Mi characters, 4,096 characters per
line and 500,000 emitted entries per source. Cosmetic staging is capped at
20,000 lines/4 Mi characters. The effective action map is capped at 500,000
unique entries. UTF-8 file publication refuses more than 64 MiB.

Native streams allow 64 MiB, 4,096 bytes per line and 500,000 block-table
entries; the block name pool is capped at 32 MiB. The separate exemption/
general decision evaluator is capped at 100,000 entries. Allocation failure
or exceeding a bound rejects the update. The old and staged datasets can
coexist while parsing; these are limits, not device memory measurements.

Generations are process-local, monotonic publication identifiers. No DNS
response cache is added. Future caches must include rule and network
generation plus response-changing scope. Network switching/invalidation is
not implemented by a rule-loader helper. Native reload requests receive
ordered tickets so a slower old request cannot overwrite a newer request.

## Reproduce and evidence

```sh
./gradlew :app:testDebugUnitTest --tests 'org.adaway.model.source.*' \
  --tests 'org.adaway.model.traffic.*' --no-daemon --max-workers=2
cc -std=c11 -O2 -Wall -Wextra -Werror -pedantic -pthread \
  webserver/jni/domain_rule_snapshot_test.c -o /tmp/domain-loader-test
/tmp/domain-loader-test webserver/jni/source_loader_vectors.txt "$(mktemp -d)"
cc -std=c11 -O2 -Wall -Wextra -Werror -pedantic -pthread \
  webserver/jni/block_rule_snapshot_test.c -o /tmp/block-loader-test
/tmp/block-loader-test
```

Local Windows checks use MSVC `/utf-8 /std:c11 /W4 /WX` and JBR javac
`--release 17`, JUnit 4.13.2 and the app's pinned Guava 33.6.0-android.
The 20 selected JVM tests (including the migrated legacy importer tests) pass. 37 shared loader vectors, invalid/stale rollback,
the production exemption reader, two four-reader/1,000-update/200,000-read
tests and a 300,000-entry actual block source have passed locally. Final
HEAD-specific CI evidence is recorded in the draft PR and workspace report.
CI additionally runs Linux ASan/UBSan, builds app/test APKs, and executes four
actual Room transaction tests on a disposable API 35 x86_64 emulator. Emulator
results must be reported separately from physical-device acceptance.

## Remaining M1-R1 acceptance and product limits

The typed Java snapshot now retains persisted source/suffix metadata; the
effective Hosts export still expresses only known concrete names. Shared
ASCII domain decisions and the existing limited DNS encoders are covered by
the typed-stage tests. Native HTTP exemption files, Unicode source parsing
and full device/platform coverage remain distinct capabilities, so full M1
acceptance is not implied by matching shared vectors.

Manual exemption files intentionally treat a pasted full URL as an explicit
whole-domain user exemption; conditional ABP imports are always skipped.
HTTP response-code overrides retain their existing semantics and are not DNS
user-override fields. Cosmetic collection/injection is a separate legacy
pipeline, not one atomic DNS-rule/cache generation. No production DNS wire
answer, CNAME/TTL cache, TCP/UDP protected forwarding, default VPN route,
HTTPS path visibility or verified coexistence is established by these tests.

Physical-device/Root-image/network-switch/OS recovery acceptance is pending a
confirmed dedicated environment. An ADB-online PGKM10 was discovered; it has
not been treated as a dedicated test device, installed to or modified.
