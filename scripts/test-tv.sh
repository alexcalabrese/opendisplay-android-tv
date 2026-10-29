#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME JAVA_HOME
adb_bin="$ANDROID_HOME/platform-tools/adb"
tv_address="${1:?Usage: scripts/test-tv.sh TV_IP:PORT}"
adb_port="${TV_ADB_SERVER_PORT:-5038}"
"$adb_bin" -P "$adb_port" connect "$tv_address"
"$adb_bin" -P "$adb_port" -s "$tv_address" get-state
./gradlew -PdevBuild=true assembleDebug
"$adb_bin" -P "$adb_port" -s "$tv_address" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -P "$adb_port" -s "$tv_address" shell am force-stop io.github.mohithdas.opendisplay.tv
"$adb_bin" -P "$adb_port" -s "$tv_address" shell am force-stop io.github.mohithdas.opendisplay.tv.dev
"$adb_bin" -P "$adb_port" -s "$tv_address" shell am start -n io.github.mohithdas.opendisplay.tv.dev/io.github.mohithdas.opendisplay.tv.MainActivity
mkdir -p diagnostics
log_path="diagnostics/tv-$(date +%Y%m%d-%H%M%S).log"
printf 'Saving logs to %s. Press Ctrl-C to stop logging.\n' "$log_path"
"$adb_bin" -P "$adb_port" -s "$tv_address" logcat -v threadtime -s OpenDisplay:V MediaCodec:V ACodec:V CCodec:V AndroidRuntime:E | tee "$log_path"
