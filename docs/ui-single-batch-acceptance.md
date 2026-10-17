# AdAway UI single-batch handoff (2026-10-17)

This is the unified Android UI branch. It intentionally incorporates the earlier settings-only PR #62 so UI integration can be reviewed in one pass. The Windows half is in `wxcvm/AdAway-Windows` PR #12.

## Android primary navigation

| Destination | Present UI | Future slots / behavioral boundary |
|---|---|---|
| Overview | Protection status, responsive hosts KPI, real server toggle, quick actions | Continue to show Root/hosts/HTTP status only; never claim full capture |
| Statistics | KPI, traffic trends, categories, apps, certificates | Scrollable trend range/style filters, diagnostic coverage metrics as backend arrives |
| Rules | Search with stale-result guard, categorized lists, sources/overrides | Shared rule-engine diagnostics and effective-policy details |
| Network center | Reserved, localized roadmap cards + working links to settings/rules/stats | VPN/Root IPv4+IPv6 capture, AdGuard Home coexistence, DNS, TLS/QUIC, rollback |
| Settings | Searchable seven-category home, existing settings and certificate/tools | Categorized configuration and future advanced-mode settings, not fake toggles |
| Query logs | Secondary route, host-first rows, status chips and incremental history | Stable backend cursor pagination and richer DNS connection diagnostics |

The Network center is a **feature planning and navigation UI**, not an implementation of global network capture. Only actual existing features may have active controls. The planned cards must remain visibly labeled as unavailable until implementation and end-to-end testing.

## Windows navigation

The Windows GUI now has 6 sections: Statistics, Application log, Settings, Block ranking, Subscriptions and Network center. All six have consistent sidebar hit regions, plus correct rank/subscription icons. Network center reserves independent cards for full capture, AdGuard coexistence, DNS rules, HTTPS/QUIC diagnostics and network rollback, alongside existing listener/rule features. The inactive capabilities have no deceptive toggle.

## UX and QA acceptance

- Android builds `compileDebugKotlin`, validates network strings in English and Chinese, and runs existing debug unit tests in the integrated settings CI.
- Windows builds with the sixth navigation destination; smoke tests must still pass.
- Verify 320dp/360dp/large tablet, large font, Android rotation, keyboard in settings search, empty log filter + load older, long hostnames and app names, quick rules tab switching.
- Windows must be visually inspected at 100%, 125%, 150% scaling, resized and maximized, including network center and the sixth nav row.
- Do not merge merely because compilation passed: manual screenshot/keyboard/TalkBack checks are distinct acceptance criteria.
- No source code in this batch installs VPN/TUN/WFP, changes routes or third-party AdGuard settings, or starts TLS interception without consent.
