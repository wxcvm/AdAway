# Bounded Web request logs and statistics

Web request logs are a bounded history, not a record of every system DNS query.
The existing limits remain 10,000 live rows, 4,096 persisted rows, a 400-row stats
summary and a 49,152-byte per-page JSON limit. History clients follow actual
returned counts and generation/anchor cursors, retaining displayed rows on errors.

Long metadata exposed a separate statistics failure: the top-host renderer left
part of a truncated row in its fixed buffer, making the entire JSON document
invalid. App rows could also advance offsets by snprintf's would-be length.
App/host/TLS arrays now publish only complete rows within the same fixed byte
limits, roll back a partial app as a unit, and sanitize app-host display metadata
through the existing JSON-safe display helper. Domain matching is unchanged.
The Windows overlay applies equivalent guards to its frozen production core,
including the Windows-only listener array.

Zero timestamps are the legacy empty-slot sentinel. If the clock is unset or a
clock read fails, the log skips that observation before changing a ring slot,
count, sequence or cursor. It resumes on a valid clock and preserves the previous
readable/persistable rows. This conditional issue was found by DeepSeek review
and reproduced with an injected clock; it was not observed on the user's device.

The stats fixture compiles verbatim production rendering blocks with controlled
process/global inputs. Real Mongoose checks 130 combinations of population,
long hostnames, maximum counters and escaped/control metadata; strict builds and
ASan/UBSan are separate from whole NDK/APK and Windows HTTP/GUI validation.
The clock test fills the ring, injects zero and failed time reads, verifies the
old slot/counters/sequence survive, saves/reloads and resumes valid observations.
No system clock, device installation or host network takeover is changed by tests.
