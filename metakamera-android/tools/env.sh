# Shared tool discovery for build.sh / tools/test-core.sh (sourced).
# Uses a real Android SDK when ANDROID_HOME has build-tools (CI), otherwise the Ubuntu packages
# (aapt, dalvik-exchange, zipalign, apksigner, android-sdk-platform-23) plus Robolectric's
# android-all API 36 jar from Maven Central for compiling against modern framework APIs.
ANDROID_ALL_VERSION=16-robolectric-13921718
API23_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar

find_android_all() {
  local c
  for c in "${ANDROID_ALL_JAR:-}" /opt/android-all/android-all-36.jar "$HOME/.cache/metakamera/android-all-$ANDROID_ALL_VERSION.jar"; do
    if [ -n "$c" ] && [ -f "$c" ]; then echo "$c"; return; fi
  done
  mkdir -p "$HOME/.cache/metakamera"
  c="$HOME/.cache/metakamera/android-all-$ANDROID_ALL_VERSION.jar"
  echo "Downloading android-all $ANDROID_ALL_VERSION ..." >&2
  curl -fsSL -o "$c.tmp" "https://repo.maven.apache.org/maven2/org/robolectric/android-all/$ANDROID_ALL_VERSION/android-all-$ANDROID_ALL_VERSION.jar"
  mv "$c.tmp" "$c"
  echo "$c"
}

if [ -n "${ANDROID_HOME:-}" ] && ls "$ANDROID_HOME"/build-tools/*/aapt >/dev/null 2>&1 && ls "$ANDROID_HOME"/platforms/android-*/android.jar >/dev/null 2>&1 && [ "${FORCE_SYSTEM_TOOLS:-0}" != "1" ]; then
  BT_DIR=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
  PLATFORM_JAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
  AAPT="$BT_DIR/aapt"; ZIPALIGN="$BT_DIR/zipalign"; APKSIGNER="$BT_DIR/apksigner"; D8="$BT_DIR/d8"
  AAPT_JAR="$PLATFORM_JAR"
  JAVAC_BOOTCP="$PLATFORM_JAR"
  DEXER=d8
  # keep the strict-dx guarantee if dx is also available
  if command -v dalvik-exchange >/dev/null 2>&1; then :; fi
else
  AAPT=aapt; ZIPALIGN=zipalign; APKSIGNER=apksigner; D8=""
  AAPT_JAR="$API23_JAR"
  JAVAC_BOOTCP="$(find_android_all):$API23_JAR"
  DEXER=dx
fi
export AAPT ZIPALIGN APKSIGNER D8 AAPT_JAR JAVAC_BOOTCP DEXER
