#!/usr/bin/env bash
# Builds a signed release APK without Gradle/AGP:
#   aapt (resources+manifest) -> javac (Java 8 bytecode) -> dx/d8 (dex) -> zipalign -> apksigner
#
#   ./build.sh                       -> build/MetaKamera-<version>.apk
#   VERSION_NAME=1.2.0 VERSION_CODE=3 ./build.sh
#
# Works with the Ubuntu packages (aapt zipalign apksigner dalvik-exchange android-sdk-platform-23)
# or with a regular Android SDK (ANDROID_HOME with build-tools + platforms), see tools/env.sh.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"
source "$ROOT/tools/env.sh"

VERSION_NAME=${VERSION_NAME:-1.0.0}
VERSION_CODE=${VERSION_CODE:-1}
MIN_SDK=26
TARGET_SDK=36
# Signing key (never committed). Resolution order:
#   1. $KEYSTORE (path) with $KS_PASS / $KS_ALIAS
#   2. $KEYSTORE_BASE64 (e.g. a CI secret) decoded to a temp file
#   3. ~/.config/metakamera/metakamera-release.jks (created on first build if missing)
# Keep the same key for every build, otherwise Android refuses to install an update over the old app.
KS_PASS=${KS_PASS:-metakamera}
KS_ALIAS=${KS_ALIAS:-metakamera}
if [ -n "${KEYSTORE:-}" ]; then
  :
elif [ -n "${KEYSTORE_BASE64:-}" ]; then
  KEYSTORE="$ROOT/build/ci-keystore.jks"
  mkdir -p "$ROOT/build"
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KEYSTORE"
else
  KEYSTORE="$HOME/.config/metakamera/metakamera-release.jks"
  if [ ! -f "$KEYSTORE" ]; then
    echo "No signing key found -> creating $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias "$KS_ALIAS" -keyalg RSA -keysize 3072 -validity 18250 \
      -dname "CN=MetaKamera, O=MetaKamera, C=TR" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
    chmod 600 "$KEYSTORE"
  fi
fi

B="$ROOT/build/apk"
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "[1/6] aapt package (resources, manifest, assets) using $AAPT_JAR"
"$AAPT" package -f -m \
  -J "$B/gen" \
  -M AndroidManifest.xml \
  -S res \
  -A assets \
  -I "$AAPT_JAR" \
  -F "$B/app.unsigned.apk" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --no-version-vectors

echo "[2/6] javac"
find src -name '*.java' > "$B/sources.txt"
find "$B/gen" -name '*.java' >> "$B/sources.txt"
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$JAVAC_BOOTCP" \
     -d "$B/classes" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$B/javac.log"; echo "javac FAILED"; exit 1
fi
grep -v '^Picked up JAVA_TOOL_OPTIONS' "$B/javac.log" || true

echo "[3/6] dex ($DEXER)"
if command -v dalvik-exchange >/dev/null 2>&1; then
  # strict check (rejects lambdas/invokedynamic which would crash at runtime without desugaring)
  if ! dalvik-exchange --dex --output="$B/dex/classes.dex" "$B/classes" > "$B/dx.log" 2>&1; then
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "$B/dx.log"; echo "dx FAILED"; exit 1
  fi
fi
if [ "$DEXER" = "d8" ]; then
  rm -f "$B/dex/classes.dex"
  (cd "$B/classes" && find . -name '*.class' > ../classlist.txt)
  (cd "$B/classes" && "$D8" --release --min-api $MIN_SDK --lib "$PLATFORM_JAR" --output "$B/dex" @../classlist.txt)
fi

echo "[4/6] add classes.dex"
(cd "$B/dex" && "$AAPT" add "$B/app.unsigned.apk" classes.dex > /dev/null)

echo "[5/6] zipalign"
"$ZIPALIGN" -f -p 4 "$B/app.unsigned.apk" "$B/app.aligned.apk"

echo "[6/6] apksigner"
OUT="$ROOT/build/MetaKamera-$VERSION_NAME.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --key-pass "pass:$KS_PASS" --out "$OUT" "$B/app.aligned.apk" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
if ! "$APKSIGNER" verify --min-sdk-version $MIN_SDK "$OUT" > "$B/verify.log" 2>&1; then
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$B/verify.log"; echo "apksigner verify FAILED"; exit 1
fi
rm -f "$OUT.idsig"
echo "OK -> $OUT ($(du -h "$OUT" | cut -f1))"
