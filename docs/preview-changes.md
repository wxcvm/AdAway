# Preview changes

## Statistics observation and recovery
- Overview and Statistics now use the same immutable observation: counters, ranking, observation time, source, failure, refreshing and revision.
- Foreground HTTP polling remains every 10 seconds, including when realtime updates are connected but quiet.
- Disconnected realtime updates reconnect with 1/2/4/8/16/30-second capped backoff. Background/disabled observers cancel connections and retries; stale callbacks cannot publish.
- Failed reads keep the last known values marked stale; missing data is not zero. Valid empty rankings clear previous rows. Request-log refresh remains independent of statistics availability.
- Settings-only CI no longer repeats the full JVM/debug build. Other required builds and platform tests remain.

## Installation and acceptance boundaries
This opt-in preview upgrades the production package **com.wxcvm.Adaway** in place with the same signing identity; there is no parallel preview app. Stable update feeds are unchanged.
ARM64/x86_64 require API 30+, RISC-V API 35+. RISC-V is build-only evidence, not runtime acceptance.
Emulator, JVM and native evidence do not establish Android 17 / Root / AdGuard field acceptance. No device installation or host network/certificate change is performed by this release.
This remains Web-request/local-policy statistics, not whole-device DNS logging. No LWS migration, Room schema change or network takeover is included.
