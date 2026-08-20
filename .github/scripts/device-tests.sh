#!/bin/sh
# Runs the instrumented tests, but only once the emulator is confirmed to have
# a 16 KB page size: a silent fall back to a 4 KB image would pass for the
# wrong reason and put us back where 0.1.0 was.
#
# This cannot be inlined into ci.yml. android-emulator-runner executes its
# `script` input one line per `sh -c`, so a variable does not survive to the
# next line and a multi-line `if` is split mid-statement.
set -e

page_size=$(adb shell getconf PAGE_SIZE | tr -d '\r')
echo "emulator page size: $page_size"
if [ "$page_size" != "16384" ]; then
    echo "::error::expected a 16 KB page size emulator, got '$page_size'"
    exit 1
fi

./gradlew :lua-kmp:connectedAndroidDeviceTest
