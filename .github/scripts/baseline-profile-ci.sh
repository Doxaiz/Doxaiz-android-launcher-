#!/usr/bin/env bash
# Called as one command by reactivecircus/android-emulator-runner.
# That action runs script lines through separate /usr/bin/sh processes, so keep
# this multi-line Bash program in a file and invoke it with `bash <file>`.
set -euo pipefail

GRADLE_LOG="baselineprofile/build/baseline-profile-ci.log"
mkdir -p "$(dirname "$GRADLE_LOG")"
: > "$GRADLE_LOG"

run_gradle() {
    local status=0
    echo "::group::Gradle: ./gradlew $*"
    ./gradlew --no-daemon --stacktrace "$@" 2>&1 | tee -a "$GRADLE_LOG" || status=$?
    echo "::endgroup::"
    if (( status != 0 )); then
        echo "::error title=Gradle::команда завершилась с кодом $status: $*"
        tail -n 80 "$GRADLE_LOG" || true
        return "$status"
    fi
}

# mergeIntoMain=true: одна задача, источник — app/src/main/generated/.
run_gradle :app:generateBaselineProfile \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile

PROFILE_DIR="app/src/main/generated/baselineProfiles"
BASELINE="$PROFILE_DIR/baseline-prof.txt"
STARTUP="$PROFILE_DIR/startup-prof.txt"
test -s "$BASELINE" || { echo "::error title=профиль::не создан $BASELINE"; exit 1; }
test -s "$STARTUP" || { echo "::error title=профиль::не создан $STARTUP"; exit 1; }
BASELINE_RULES="$(grep -cvE '^[[:space:]]*(#|$)' "$BASELINE" || true)"
STARTUP_RULES="$(grep -cvE '^[[:space:]]*(#|$)' "$STARTUP" || true)"
[ "$BASELINE_RULES" -gt 0 ] || { echo "::error title=профиль::baseline-prof.txt пуст"; exit 1; }
[ "$STARTUP_RULES" -gt 0 ] || { echo "::error title=профиль::startup-prof.txt пуст"; exit 1; }
echo "::notice title=профили::baseline: $BASELINE_RULES правил; startup: $STARTUP_RULES правил"

# Отдельный вызов Gradle перечитывает только что созданный файл; тогда AGP
# включает dexLayoutOptimization, и R8 проверяет упакованный профиль.
run_gradle :app:assembleRelease
APK="$(find app/build/outputs/apk/release -name '*.apk' -print -quit)"
test -n "$APK" && test -f "$APK" || { echo "::error title=R8::release APK не найден"; exit 1; }
APK_CONTENTS="$(unzip -l "$APK")"
grep -q 'assets/dexopt/baseline.prof' <<< "$APK_CONTENTS" \
    || { echo "::error title=R8::в release APK нет assets/dexopt/baseline.prof"; exit 1; }
echo "::notice title=R8::release APK собран с входным baseline/startup-профилем"

# EMULATOR переводит ограничение в предупреждение. SwiftShader не даёт
# репрезентативных чисел производительности на телефоне.
run_gradle :baselineprofile:connectedBenchmarkReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
