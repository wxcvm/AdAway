# Query logs, source management and statistics

This change is under review. Device acceptance, Windows integration, complete
Android CI and release publication are pending; local tests do not replace them.

The response server retains 10,000 requests in memory and persists its latest
4,096 entries. `/internal-stats` contains a bounded summary of up to 400 rows.
The log screen uses `/internal-qlog` pages with a server generation and sequence
anchor. Use `nextOffset`, not the requested limit, for the next request. A full
response may contain fewer rows because the JSON byte limit remains unchanged.
New writes do not shift an existing cursor. Restart, clear, or overwritten
history returns an expired cursor error; clients retain shown rows and offer a
refresh. These are Web response requests, not all DNS activity or all blocks.

Persistence now selects the newest ring entries correctly even before the ring
fills. Loading validates staged contents before publishing them. Invalid files
preserve the current in-memory log. Previously lost rows cannot be recovered.

Source management and rule browsing are separate views. Generated `raw` labels
show repository and file names. Users can edit local labels without downloading
or changing subscription URLs. Addresses shown in source details omit userinfo,
query and fragment. Duplicate inserts are failures rather than false successes.
Rule rows retain their source identity and scope.

Statistics retain the last valid values on an unavailable or invalid response.
Saved snapshots carry their original timestamp; reading one does not make it
live. WebSocket updates refresh the same status as polling. Snapshot reads are
bounded to 2 MiB.

Local validation: the production query-log include with real Mongoose passed
MSVC `/W4 /WX` test compilation, persistence cases 0/1/400/500/4096/7000/10000/
12037, truncated-file rollback, bounded output, stable paging with concurrent
inserts, overwrite and reset invalidation, and invalid numeric parameters.
Source presentation passed two JVM tests. CI adds sanitizer tests, JVM parsing
tests, Room duplicate/rename checks and actual Compose interaction screenshots.
