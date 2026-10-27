# Settings modules and response summaries

SettingsScreen now owns navigation/search, fixed category IDs 0..6, independent
saveable scroll states and the root-lived network diagnostic actions only.
Category content is split into Network/WebServer/Certificate/Statistics/App/Tools
modules; ContentResponseSettingsCard remains the existing content category.
Preferences live in SettingsPreferences with unchanged files, keys and defaults.

Pending diagnostic consent and its coroutine lifetime remain owned outside the
conditional category. Certificate observations/actions and content cache remain
in StatsViewModel. Only the visible app/system category collects server app
snapshots, so unrelated categories no longer recompose on each server refresh.
This is a bounded recomposition change, not a measured performance percentage.
No change to listener configuration, trust, rule priority, database or resource limits.

Ordinary response results render separately by configured address family and
protocol, with localized outcome/status/type/length/byte summaries. Unknown status
is not zero; HTTP403 does not imply advertising-rule hits or rendering success.
The full original technical report remains expandable, selectable and copyable.
Browser actions retain target/configuration ownership checks. No WebView, extra
poller or additional diagnostic request was added.

Required evidence: full JVM and existing settings/rule UI; API30/35 observation
navigation/scroll/first frames plus real loopback response summaries; three ABI
builds and existing native/runtime checks. Android17 physical-device behavior
and RISC-V execution remain separate. Do not claim these from source checks.
