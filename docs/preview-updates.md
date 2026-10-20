# Preview updates

Enable **Settings → Include beta releases**, then **Check for updates**. Startup
and daily checks use the same preference. A preview uses the production package
`com.wxcvm.Adaway` and production signer and upgrades in place after Android's
installation confirmation. Opting out checks only stable releases and never
downgrades the installed preview.

Stable discovery remains `android-latest/manifest.json`. Opt-in discovery also
reads `android-preview/manifest.json`. Each preview publishes an immutable
`progress-android-*` manifest with the APK's actual versionCode, package identity
and download URL. The pointer is published only after the signed release exists;
publication is serialized and refuses to overwrite a newer versionCode. Download
URLs always reference the immutable release. Stable pointers are unchanged.

Selection compares versionCode, not date tags, list order or version names. API
fallback accepts only compatible releases with explicit manifests. Drafts,
isolated preview packages and progress releases without version metadata cannot
be offered. Changing the preference invalidates the result cache; checks whose
channel changes in flight are discarded. Pending preview installation is blocked
after opting out. Package, signer, expected versionCode and anti-downgrade checks
are still enforced immediately before invoking Android's installer.

Previously published clients require one manual upgrade to this fix. A published
release cannot retroactively change the updater already installed on a device.
No device installation is performed by CI; API 30/35 tests use isolated emulators.
