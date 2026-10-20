#!/usr/bin/env bash
# Runs exclusively against the disposable GitHub Actions emulator.
# Installs two distinct packages without replacing or uninstalling either app.
set -euo pipefail

production_apk=${1:?Pass the production Debug APK}
premium_apk=${2:?Pass the Premium Test APK}
production_package=ro.mdp.remix
premium_package=ro.mdp.remix.premiumtest
build_tools="${ANDROID_HOME:?Android SDK is required}/build-tools/35.0.0"
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT

# A separate certificate reproduces the user's existing-app signing situation.
# Only this disposable emulator fixture is re-signed; the uploaded APK is untouched.
keytool -genkeypair -noprompt -keystore "$fixture_dir/production-fixture.jks" \
  -storepass android -keypass android -alias coexistence -keyalg RSA -keysize 2048 \
  -validity 2 -dname 'CN=Disposable CI Coexistence Fixture'
"$build_tools/apksigner" sign --ks "$fixture_dir/production-fixture.jks" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$fixture_dir/production.apk" "$production_apk"
production_certificate=$("$build_tools/apksigner" verify --print-certs "$fixture_dir/production.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
premium_certificate=$("$build_tools/apksigner" verify --print-certs "$premium_apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test -n "$production_certificate"
test -n "$premium_certificate"
test "$production_certificate" != "$premium_certificate"

adb wait-for-device
adb install "$fixture_dir/production.apk"
production_path=$(adb shell pm path "$production_package" | tr -d '\r')
test -n "$production_path"
production_uid=$(adb shell pm list packages -U "$production_package" | tr -d '\r' | awk '$1 == "package:ro.mdp.remix" {print $2}')
test -n "$production_uid"
adb shell run-as "$production_package" mkdir -p files
printf '%s' 'production-data-preserved' | adb shell run-as "$production_package" tee files/premiumtest-install-marker.txt >/dev/null
production_launch=$(adb shell am start -W -n "$production_package/ro.mdp.remix.MainActivity")
printf '%s\n' "$production_launch"
grep -q 'Status: ok' <<< "$production_launch"
adb shell am force-stop "$production_package"

adb install "$premium_apk"
premium_uid=$(adb shell pm list packages -U "$premium_package" | tr -d '\r' | awk '$1 == "package:ro.mdp.remix.premiumtest" {print $2}')
test -n "$premium_uid"
test "$premium_uid" != "$production_uid"
test "$(adb shell pm path "$production_package" | tr -d '\r')" = "$production_path"
test "$(adb shell run-as "$production_package" cat files/premiumtest-install-marker.txt | tr -d '\r')" = 'production-data-preserved'
if adb shell run-as "$premium_package" cat files/premiumtest-install-marker.txt >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: Test app unexpectedly inherited production private data.' >&2
  exit 1
fi

premium_launch=$(adb shell am start -W -n "$premium_package/ro.mdp.remix.MainActivity")
printf '%s\n' "$premium_launch"
grep -q 'Status: ok' <<< "$premium_launch"
adb shell pidof "$premium_package"
adb shell run-as "$premium_package" mkdir -p files
printf '%s' 'premium-test-data' | adb shell run-as "$premium_package" tee files/premiumtest-install-marker.txt >/dev/null
test "$(adb shell run-as "$production_package" cat files/premiumtest-install-marker.txt | tr -d '\r')" = 'production-data-preserved'

production_relaunch=$(adb shell am start -W -n "$production_package/ro.mdp.remix.MainActivity")
printf '%s\n' "$production_relaunch"
grep -q 'Status: ok' <<< "$production_relaunch"
adb shell pidof "$production_package"
printf '%s\n' 'PASS: Both apps install and launch with different signing certificates, distinct UIDs, and isolated private data. Production package and data are preserved.'
