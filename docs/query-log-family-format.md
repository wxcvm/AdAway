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

QLG2 files use magic `0x514C4732`. They contain the existing
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
the current format. Missing metadata is never reconstructed from host text.

Records and families are staged and validated together, including exact length,
before replacing the live ring. An invalid/truncated QLG2 file preserves the
live rows, family array, sequence and cursor generation. The existing atomic
write adapter continues to publish the complete file together.

The limits remain Android 10,000 in-memory rows, newest 4,096 persisted rows,
400 summary rows and a 49,152-byte summary/page buffer. Windows keeps its
4,096-row ring and persistence caps when using this shared include. The
family metadata adds one byte per retained/persisted row without expanding the legacy
record. JSON entries cost additional bytes, so an existing byte cap can return
a shorter complete page; `returned`, `nextOffset` and `hasMore` describe that
actual page and callers must continue from its cursor.

Native regression tests cover IPv4, IPv6 and mapped peers, Host/family
disagreement, unknown legacy rows, response mode preservation, wrapped-ring
compaction, malformed family/version/length rejection preserving live cursors,
and actual Mongoose HTTP requests over private ephemeral IPv4/IPv6 loopback
sockets. These tests do not replace Android device listener acceptance.

## Local response metadata (QLG3)

New JSON rows additionally include `path`, `status` and `pathTruncated`.
`path` stores only a validated origin-form path, up to 159 UTF-8 bytes. Query
strings and fragments are omitted. Invalid UTF-8 or control characters make
the path unknown (empty). Truncation preserves complete code points and is
explicitly flagged. This is still potentially sensitive browsing history;
export is an explicit user action.

`status` is the HTTP status actually queued locally for that request, or 0
when unknown. It does not establish client receipt or upstream completion.
Asynchronous forwarding therefore remains unknown. File-serving logs are
recorded after headers are queued, so 200, 204, 206, 304 and 404 remain distinct.
The send-buffer offset is captured before each request to avoid borrowing an
earlier queued response's status. Action 3 means access denied, including
internal-endpoint refusals; it is separate from ad blocking and does not itself
increment ad-blocking counters. Access controls remain unchanged.

New saves use `0x514C4733` (QLG3): the QLG2 header, records and family bytes,
followed by `count` metadata records of exactly 164 bytes: a 160-byte terminated
path, native-endian uint16 status, one flags byte (bit 0 = truncated), and a
zero reserved byte. Exact length is `12 + count * 373` bytes. All metadata,
families and rows are staged and validated together. Corruption preserves the
live ring and cursor. QLOG and QLG2 remain readable with unknown path/status;
older binaries cannot read QLG3. Automatic log downgrade is not supported.

Limits remain unchanged. The metadata adds 164 bytes per in-memory row and
persisted row, and the bounded JSON response can contain fewer complete rows.
Pagination always advances by the actual returned count. UI search and CSV
include the recorded path/status, and legacy rows explicitly show unrecorded
values rather than inventing HTTP 200 or a root path.
