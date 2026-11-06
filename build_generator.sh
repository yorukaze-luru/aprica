#!/usr/bin/env bash
# Manual generator APK build pipeline (no Gradle daemon — see REPORT.md §6-2).
#
# Pipeline: aapt2 compile/link -> kotlinc -> d8 (app classes + kotlin-stdlib
# + apksig classes) -> zipalign -> apksigner.
# Output: ~/workspace/web2apk/generator.apk
# The generator APK itself is signed with the keystore in KS. The file name
# dates back to the spike (test-keystore-TEST-ONLY.jks), but per the v1.2
# decision this same key continues as the signing key for GitHub Releases
# builds, so that users can overwrite-install new versions. The APKs the
# generator *produces* are signed on-device with the generator's own
# self-signed key via apksig — never with this key.
set -euo pipefail

ROOT="$HOME/workspace/web2apk"
TOOLS="$ROOT/tools"
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$TOOLS/kotlinc/bin:$PATH"

ANDROID_JAR="$TOOLS/sdk/platforms/android-34/android.jar"
BT="$TOOLS/sdk/build-tools/34.0.0"
APP="$ROOT/generator-app/app"
WORK="$ROOT/build-generator"
KS="$ROOT/test-keystore-TEST-ONLY.jks"
# Signing key password: overridable via env so the script stays publishable
# without embedding the secret (default is the local test value). Despite
# the historical file name, this key is the ongoing signing key for the
# GitHub Releases line (see REPORT-V12.md).
KS_PASS="${WEB2APK_KS_PASS:-change-me}"
APKSIG_JAR="$ROOT/generator-app/libs/apksig-only.jar"

# --- 0. apksig-only jar (extract com/android/apksig/** from SDK apksigner.jar)
if [ ! -f "$APKSIG_JAR" ]; then
  mkdir -p "$ROOT/generator-app/libs" "$WORK/apksig-extract"
  (cd "$WORK/apksig-extract" && unzip -o -q "$BT/lib/apksigner.jar" "com/android/apksig/*" \
    && zip -q -r "$APKSIG_JAR" com)
  echo "extracted apksig-only.jar"
fi

rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/classes" "$WORK/dex"

echo "== 1. aapt2 compile resources =="
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$WORK/compiled/res.zip"

echo "== 2. aapt2 link (manifest + resources + assets incl. template.apk) =="
"$BT/aapt2" link \
  -o "$WORK/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  -A "$APP/src/main/assets" \
  --java "$WORK/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 3 --version-name 1.2 \
  "$WORK/compiled/res.zip"

echo "== 3. kotlinc (no daemon; Kotlin + Java mixed sources + generated R) =="
# NOTE: kotlinc uses the .java sources for symbol resolution but does NOT
# emit bytecode for them; step 3b compiles the Java sources with javac.
kotlinc -J-Dkotlin.daemon.enabled=false \
  -classpath "$ANDROID_JAR:$APKSIG_JAR" \
  -d "$WORK/classes" \
  $(find "$APP/src/main/java" -name '*.kt' -o -name '*.java') \
  $(find "$WORK/gen" -name '*.java')

echo "== 3b. javac (Java sources: core, provider, generated R) =="
javac -encoding UTF-8 \
  -cp "$ANDROID_JAR:$APKSIG_JAR:$WORK/classes" \
  -d "$WORK/classes" \
  $(find "$APP/src/main/java" -name '*.java') \
  $(find "$WORK/gen" -name '*.java')

echo "== 4. d8 -> classes.dex (app + kotlin-stdlib + apksig) =="
"$BT/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') \
  "$TOOLS/kotlinc/lib/kotlin-stdlib.jar" \
  "$APKSIG_JAR"

echo "== 5. add dex, zipalign =="
cp "$WORK/unsigned.apk" "$WORK/with-dex.apk"
(cd "$WORK/dex" && zip -q "$WORK/with-dex.apk" classes.dex)
"$BT/zipalign" -f 4 "$WORK/with-dex.apk" "$WORK/aligned.apk"

echo "== 6. apksigner sign generator.apk (TEST keystore) =="
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/generator.apk" "$WORK/aligned.apk"

echo "== done =="
ls -la "$ROOT/generator.apk"
"$BT/apksigner" verify --verbose "$ROOT/generator.apk" | head -6
"$BT/aapt2" dump badging "$ROOT/generator.apk" | grep -E "^(package:|application-label:|application:)" | head -3
