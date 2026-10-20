#!/usr/bin/env bash
# Disposable CI emulator only. Never invoked against the user's attached phone.
set -euo pipefail
alias=fd6b:2a91:84f0::73
adb root
adb wait-for-device
if adb shell "ip -6 address show to $alias/128" | grep -q 'inet6 '; then
  echo 'Fixture address already exists; refusing to claim it'; exit 1
fi
cleanup() { adb shell "ip -6 address del $alias/128 dev lo"; }
adb shell "ip -6 address add $alias/128 dev lo nodad"
trap cleanup EXIT
adb shell "ip -6 address show to $alias/128 dev lo" | grep -q 'inet6 '
# Same add used by the app and generated boot script; existing aliases must be left intact.
if adb shell "ip -6 address add $alias/128 dev lo nodad"; then
  echo 'Duplicate address unexpectedly added'; exit 1
fi
./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2 \
  -PruleLoaderTestAbi=x86_64 -Pandroid.injected.build.abi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.adaway.model.source.SourceLoaderRoomTest,org.adaway.model.source.RuleBrowserRoomTest,org.adaway.model.traffic.DiagnosticIpv6TransportDeviceTest,org.adaway.model.traffic.ListenerSettingsDeviceTest,org.adaway.util.ListenerTlsDeviceTest
cleanup
trap - EXIT
if adb shell "ip -6 address show to $alias/128 dev lo" | grep -q 'inet6 '; then
  echo 'Alias cleanup failed'; exit 1
fi
echo 'Disposable emulator: root alias create, existing duplicate, app address discovery/native bind and cleanup passed'
