#!/usr/bin/env bash
# Manual template APK build pipeline (no Gradle daemon).
#
# Why: in this VM, Java-process TCP is blocked, so the Gradle daemon
# (which talks to the client over a loopback socket) cannot run.
# This script builds the same template with aapt2 + kotlinc + d8 directly.
#
# Output: ~/workspace/web2apk/template.apk  (signed with TEST keystore)
set -euo pipefail

ROOT="$HOME/workspace/web2apk"
TOOLS="$ROOT/tools"
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$TOOLS/kotlinc/bin:$PATH"

ANDROID_JAR="$TOOLS/sdk/platforms/android-34/android.jar"
BT="$TOOLS/sdk/build-tools/34.0.0"
APP="$ROOT/template-app/app"
WORK="$ROOT/build-manual"
KS="$ROOT/test-keystore-TEST-ONLY.jks"
# Signing key password: overridable via env so the script stays publishable
# without embedding the secret (default is the local test value).
KS_PASS="${WEB2APK_KS_PASS:-change-me}"

rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/classes" "$WORK/dex"

echo "== 1. aapt2 compile resources =="
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$WORK/compiled/res.zip"

echo "== 2. aapt2 link (manifest + resources + assets) =="
"$BT/aapt2" link \
  -o "$WORK/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  -A "$APP/src/main/assets" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 6 --version-name 1.6 \
  "$WORK/compiled/res.zip"

echo "== 3. kotlinc (no daemon) =="
kotlinc -J-Dkotlin.daemon.enabled=false \
  -classpath "$ANDROID_JAR" \
  -d "$WORK/classes" \
  "$APP/src/main/java/app/webgen/template/MainActivity.kt"

echo "== 4. d8 -> classes.dex (app classes + kotlin-stdlib) =="
"$BT/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') \
  "$TOOLS/kotlinc/lib/kotlin-stdlib.jar"

echo "== 5. add dex, zipalign =="
cp "$WORK/unsigned.apk" "$WORK/with-dex.apk"
(cd "$WORK/dex" && zip -q "$WORK/with-dex.apk" classes.dex)
"$BT/zipalign" -f 4 "$WORK/with-dex.apk" "$WORK/aligned.apk"

echo "== 6. test keystore (TEST ONLY) =="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias testkey -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=WebGen TEST ONLY, O=web2apk spike" 2>/dev/null
fi

echo "== 7. apksigner sign (v1+v2) =="
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/template.apk" "$WORK/aligned.apk"

echo "== done =="
ls -la "$ROOT/template.apk"
"$BT/apksigner" verify --verbose "$ROOT/template.apk" | head -6
"$BT/aapt2" dump badging "$ROOT/template.apk" | head -4
