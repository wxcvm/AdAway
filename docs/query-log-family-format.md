# Request log transport family and disk migration

Every entry in `/internal-stats.query_log` and `/internal-qlog.entries` carries
`family`: `0` means unavailable, `4` means observed IPv4, and `6` means observed
IPv6. This describes the client connection received by the response server.
It does not describe a DNS question type, resolved origin address, rule source,
or the spelling of the HTTP Host. An IPv4-mapped IPv6 peer (`::ffff:0:0/96`)
counts as IPv4. A call with no observed connection remains unknown.

The existing `type` is a response classification hint, and `mode` is the
response policy byte. Neither is reused to encode the transport family.
The legacy record stays exactly 208 bytes; a compile-time assertion protects
its layout. A parallel one-byte array follows each ring slot through writes,
clearing, compaction and loading.

The new `query_log.dat` uses magic `0x514C4732` (`QLG2`). It contains the existing
three native-endian uint32 values `(magic, 0, count)`, followed by `count`
legacy 208-byte records and then `count` family bytes in the same record order.
Only compact files are written: `count <= QLOG_PERSIST_MAX`, and `pos` must be
zero. The exact file length is `12 + count * 209` bytes. Only family values
0, 4 and 6 are accepted. This remains a local platform file, not a portable
network serialization format.

The loader continues to accept the original `QLOG` magic `0x514C4F47`, including
compact records and full legacy rings with an arbitrary valid ring position.
Legacy rows receive family 0; the missing observation is never reconstructed
from an IPv6 literal or domain. Their original timestamps, UID, action,
classification and mode are retained. The next successful atomic save writes
QLG2. Old binaries do not understand QLG2; automatic downgrade is not supported.

Records and families are staged and validated together, including exact length,
before replacing the live ring. An invalid/truncated QLG2 file preserves the
live rows, family array, sequence and cursor generation. The existing atomic
write adapter continues to publish the complete file together.

The limits remain Android 10,000 in-memory rows, newest 4,096 persisted rows,
400 summary rows and a 49,152-byte summary/page buffer. Windows keeps its
4,096-row ring and persistence caps when using this shared include. The family
metadata adds one byte per retained/persisted row without expanding the legacy
record. JSON entries cost additional bytes, so an existing byte cap can return
a shorter complete page; `returned`, `nextOffset` and `hasMore` describe that
actual page and callers must continue from its cursor.

Native regression tests cover IPv4, IPv6 and mapped peers, Host/family
disagreement, unknown legacy rows, response mode preservation, wrapped-ring
compaction, malformed family/version/length rejection preserving live cursors,
and actual Mongoose HTTP requests over private ephemeral IPv4/IPv6 loopback
sockets. These tests do not replace Android device listener acceptance.
