#!/usr/bin/env bash
# Compiles the pure-Java core exactly like the APK build does (Android API-23 java.* stubs +
# android-all API 36, Java 8 bytecode), dexes it with dx in strict mode (fails on lambdas /
# invokedynamic / default methods), then compiles and runs the JVM tests.
#
#   tools/test-core.sh                 # all tests
#   tools/test-core.sh JpegEditorTest  # only classes whose name contains the filter
#
# Each run uses its own build dir, so several runs can happen concurrently.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source "$ROOT/tools/env.sh"

B=${TEST_BUILD_DIR:-$ROOT/build/test-run-$$}
rm -rf "$B"
mkdir -p "$B/core" "$B/test" "$B/work"

find src/com/metakamera/core -name '*.java' > "$B/core-sources.txt"
echo "[1/4] javac core (Android bootclasspath, Java 8 bytecode)"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
      -bootclasspath "$JAVAC_BOOTCP" -d "$B/core" @"$B/core-sources.txt" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
if [ ! -f "$B/core/com/metakamera/core/MetadataEditor.class" ]; then echo "core compile FAILED"; exit 1; fi
if grep -rl --include='*.java' -E '^import android\.' src/com/metakamera/core >/dev/null; then
  echo "core must not import android.*:"; grep -rln --include='*.java' -E '^import android\.' src/com/metakamera/core; exit 1
fi

echo "[2/4] dx strict check (no lambdas / invokedynamic)"
if ! dalvik-exchange --dex --output="$B/core.dex" "$B/core" > "$B/dx.log" 2>&1; then
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$B/dx.log"; echo "dx FAILED"; exit 1
fi

echo "[3/4] javac tests (host JDK)"
find test -name '*.java' > "$B/test-sources.txt"
javac -nowarn -encoding UTF-8 -cp "$B/core" -d "$B/test" @"$B/test-sources.txt" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
if [ ! -f "$B/test/com/metakamera/test/TestRunner.class" ]; then echo "test compile FAILED"; exit 1; fi

echo "[4/4] run tests"
set +e
java -Dmetakamera.work="$B/work" -cp "$B/core:$B/test" com.metakamera.test.TestRunner test "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
rc=${PIPESTATUS[0]}
set -e
if [ "${KEEP_TEST_BUILD:-0}" != "1" ] && [ $rc -eq 0 ]; then rm -rf "$B"; else echo "build/work dir kept: $B"; fi
exit $rc
