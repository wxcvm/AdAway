# Activity, statistics and automatic update behavior

The query-log screen is driven by a ViewModel-owned browser. It retains history,
search, action and application filters across navigation, pins cursors while
browsing, and keeps previous records on read/expiry errors. Search filters only
loaded records. Browsing, scrolling and inspection pause live updates. Live
updates reuse existing statistics refreshes with a ten-second throttle instead
of adding another polling timer. Poll completion wakes the log reader even when
statistics fail or only an unchanged disk snapshot is available. Only actual
dragging pins the log; layout/programmatic scrolls do not. Resume remains visible
while browsing history and returns to the latest page. Late or cancelled responses cannot replace a
newer or paused page.

The screen has domain/path/status search, action filters, pause/resume, request details and
CSV export. Details distinguish local exemptions (a no-content reply) and
forwarding started from successful origin access. Missing app/rule/source
identity and unknown legacy codes are not guessed. The server keeps up to
10,000 log entries in memory and restores up to 4,096 after restart.

Statistics use the native `total_blocked` counter, including fast-fail and rule
override mechanisms. Older servers without this field retain the category-sum
fallback. The category chart includes the unclassified mechanism difference.
A reported zero current request rate remains zero; historical averages are used
only when a server does not supply a measured rate. Duplicate lifetime cards and
the misleading permanent time range are removed. Existing range 3 maps to the
actually retained 30-day series. Empty ranges retain their switch controls.

All these counters describe this HTTP response server. DNS/Hosts blocks that
never reach it and third-party AdGuard counts are not observable here. Some
runtime mechanism counters reset on restart; this is not an all-device lifetime
blocking total. The repeated-host fast-204 option is documented as changing the
response, not replaying a complete cached decision. Root Hosts IPv6 records are
distinct from enabling an IPv6 listener or VPN capture.

Statistics/log reads preserve IPv6-first ordering and fall back to IPv4 and
direct HTTP when an nc binary or address fails. The entire read has a four-second
budget and a bounded response, including child-process pipe reads. Live HTTP
reads validate the complete declared byte length: nc exit code zero is not
proof of completeness. A truncated/malformed nc response immediately switches
to the direct HTTP fallback instead of repeating other nc variants. Live HTTP
observations are ordered against concurrent pushes by publication revision,
not wall-clock time, so clock correction does not freeze statistics. Disk
fallback remains stale data. The bar chart includes its connection series.

DNS arrival diagnostics show the exact HTTP port tested. DNS records do not
specify ports: a receipt from a configured nondefault port does not prove the
ordinary 80/443 domain path. Unmatched/nonlocal DNS answers are never contacted;
domain HTTPS is explicitly marked untested when there is no eligible address.
System resolution observes this application's view, not every app's private DNS.

Each new HTTP log records the actual connection family (`4`, `6`, or unknown
`0`); IPv4-mapped IPv6 sockets are classified as IPv4. Host text and DNS answer
types are never used to guess it. Both paged and summary JSON carry the field.
The bounded QLG3 persistence format restores family and local-response metadata; legacy QLOG rows
remain readable with unknown family. Filtering combines family, response type,
action, app and domain over loaded rows, and CSV exports family too. Response
type is the native LT_* policy category, not subscription source attribution.
Diagnostics include optional legacy IPv6 as well as mandatory configured binds;
an IPv4-only server must not announce IPv6 as listening.

Automatic application downloads are enabled by default and can be disabled.
The startup and daily checks use the selected channel. The model persists the
DownloadManager ID and expected version, deduplicates active/ready downloads,
leaves failed downloads available for explicit retry, and replaces an older
pending download only after the newer one is persisted. A manifest receiver
and unique WorkManager verification recover completions after process death.
The verified local APK must match package, newer expected version and signer.
Only the verified copy is passed to the system installer. Background completion
notifies the user to continue; foreground recovery verifies again.

Android can require system installation confirmation. This implementation does
not promise silent privileged installation or force-opening the application
after replacement. See the official
[PackageInstaller user-action contract](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams).
After installation, existing startup/restoration settings still govern the
response service. Device installation and reboot acceptance are separate tests.

At lifecycle reconciliation, a reachable port or surviving process alone cannot
certify the installed version. The app compares the APK's bundled image hash
with the owned process's actual `/proc/PID/exe`, including deleted old images.
Staging uses a verified temporary copy and atomic rename before stopping the
old process, retaining its actual image as `.previous`. Failed preparation
leaves the existing process alive; failed new startup reports failure and
retains the backup/configuration without claiming an automatic rollback.
Hashing is outside statistics polling. See `listener-lifecycle.md` for limits.

Compose update switches now reschedule/cancel the real WorkManager jobs, including
the source-update network constraint, instead of merely writing preferences.
Preview releases use the production package/signature without changing stable
update feeds. CI uses controlled downloads and emulators, not the user's phone.
