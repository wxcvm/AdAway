#!/usr/bin/env bash
# Emulator runner executes separate script lines in separate shells. Keep the
# failing test and evidence collection in ONE shell, before emulator teardown.
set -u
result=0
./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2 \
  -PruleLoaderTestAbi=x86_64 -Pandroid.injected.build.abi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.adaway.ui.compose.ObservationUiTest,org.adaway.ui.compose.ResponseCheckUiTest,org.adaway.ui.compose.StatsObservationDeviceTest || result=$?
adb pull /sdcard/Pictures/AdAwayUiReview observation-ui || true
adb pull /sdcard/Download/AdAwayUiReview observation-manifests || true
exit "$result"
