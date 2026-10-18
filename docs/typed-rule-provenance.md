# Typed rules and provenance v1

This stage extends the current master, including its integrated UI, updater,
indexed wildcard matcher and opt-in fixed-question DNS probe. It creates no
routes, sockets, cache, release or production update-feed changes.

## Data and decisions

Imported rules retain domain, action, EXACT / SUFFIX / WILDCARD scope, address,
local source ID and emitted-entry order. Source 1 is explicit user input.
No subscription URL appears in match diagnostics. Hosts aliases receive
separate increasing entry orders; comments/skipped conditions do not consume
an order. `||domain^` and `@@||domain^` include the apex and label-boundary
subdomains. Bare/Hosts domains stay exact. Unsupported conditions stay skipped.

Precedence is user before non-user; non-user allow > replacement > block.
Within that tier, EXACT > SUFFIX > WILDCARD, then longer canonical pattern,
lower numeric source ID, then earlier source entry. Explicit user ties use
the latest entry instead. Lexical domain, action and binary address provide
deterministic defensive ties. Source order is explicit, independent of map
iteration and SQL query plans. A replacement family mismatch returns PASS /
INVALID_DATA without falling through to another rule. The matched provenance
is still available. Action and address resolve in one immutable snapshot.

Android IDN-to-ASCII behavior is retained; native input remains ASCII-only.
Unicode source imports are therefore a documented platform difference, not
claimed equivalent. Canonical ASCII/punycode fixtures are the shared subset.

## Persistence and compatibility

Room v8 -> v9 adds `match_scope` (default -1) and `source_order` (default 0).
Migrated rows retain their old exact/explicit-glob behavior and are marked
legacy; lost ABP syntax is never guessed. Refreshing a source replaces its
rows with fully typed records inside the existing bounded transaction.
User rows use their database ID for latest-write ordering. Disabling/deleting
a source excludes it from snapshot preparation. Cold-start probe access
rebuilds a validated snapshot from committed rows before handling a query.

The effective Hosts table is now an export of the same typed decisions for
known concrete names only. It cannot express suffix/glob coverage for unknown
subdomains. Source synchronization prepares all indexes before replacing
the effective table; publication follows successful transaction commit.
Failed/stale publication retains the previous in-memory generation.

Native production block snapshots retain allow/replacement/block records,
provenance and scope. Exact/suffix lookups use a sorted index and parent-label
searches. Wildcard records use rare required literal anchor buckets, built
before publication. The 500,000-entry and 32 MiB name-pool limits remain.
Legacy native semantic files remain readable with a legacy marker. Java typed
snapshots also enforce the existing native 32 MiB name budget before commit;
ordinary imported blocks use NXDOMAIN, while other explicit block policies
remain available through the compatibility map API. Windows
new subscription updates serialize versioned records in one staged file:

`@adaway-rule-v1<TAB>source<TAB>order<TAB>kind<TAB>scope<TAB>domain<TAB>address-or--`

Kinds are block=0, allow=1, replacement=2; scopes exact=0, suffix=1, wildcard=2.
Windows subscription local IDs are configured source slots (2 + zero-based
position); reordering the source list changes these IDs and their tie order.
There is no claim of source identity continuity across list reordering.
Remote source text cannot inject these internal records through the importer.

## Integration and evidence

`SourceLoaderRoomTest` exercises real v8 migration, persisted scope, refresh,
restart, source disable/delete, rollback, exact Hosts export and DNS answers
on disposable API 30/35 emulators. `TypedRuleSnapshotTest` and native
`typed_rule_snapshot_test.c` consume the same 26 query vectors; C also passes
through the subscription serialization and production index. Existing
concurrent readers, 300k rules, and Windows file-transaction checks remain.
CI runs the full app JVM suite, strict C, ASan/UBSan and APK builds.

Android's existing opt-in fixed-question DNS probe uses the typed Java
snapshot. Android native HTTP continues to read its explicit block file;
it is not a new DNS listener or an automatic Room-to-HTTP publication channel.
Windows DNS synthesis gains a production-snapshot adapter, with import-to-wire
integration tests; it still opens no DNS listener. Standalone native domain
exemption files remain a separate HTTP capability, not a unified DNS override.
Those platform boundaries must not be reported as whole-device protection or
full M1 acceptance. Physical-device/Root/network-recovery tests remain pending.
