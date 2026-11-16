#!/usr/bin/env bash
# Проверка лаунчера на настоящем Android (эмулятор API 34 в GitHub Actions).
#
# Скрипт выполняется внутри android-emulator-runner, то есть adb уже поднят и
# эмулятор загружен. Задача — не «нажать красиво», а получить объективные факты:
#   1. APK устанавливается (значит, подпись и манифест валидны);
#   2. активность реально запускается без падения (нет FATAL EXCEPTION);
#   3. лаунчер выбирается системным HOME;
#   4. кадровые метрики первого экрана и прокрутки снимаются (gfxinfo/jank).
# Всё складывается в emulator-report/ и уходит артефактом.
set -uo pipefail

PKG="com.doxaiz.launcher"
OUT="emulator-report"
mkdir -p "$OUT"

APK="$(find "${APK_PATH:-apk}" -name '*.apk' | head -1)"
log() { echo "::group::$*"; }
endlog() { echo "::endgroup::"; }

report_fail() {
    echo "$*" | tee -a "$OUT/failures.txt"
}

log "Устройство"
adb devices -l
adb shell getprop ro.build.version.release | tee "$OUT/device.txt"
adb shell getprop ro.build.version.sdk | tee -a "$OUT/device.txt"
adb shell getprop ro.product.cpu.abi | tee -a "$OUT/device.txt"
endlog

log "Установка $APK"
adb logcat -c
if adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt" | grep -q "Success"; then
    echo "install=OK" | tee -a "$OUT/install.txt"
else
    report_fail "Установка APK не удалась"
fi
endlog

log "Запуск активности"
adb shell am start -W -n "$PKG/.MainActivity" 2>&1 | tee "$OUT/start.txt"
endlog

log "Проверка, что лаунчер — HOME"
adb shell cmd package set-home-activity "$PKG/.MainActivity" 2>&1 | tail -2 || true
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | tee "$OUT/home.txt"
if grep -q "$PKG" "$OUT/home.txt"; then
    echo "home=OK" | tee -a "$OUT/home.txt"
else
    report_fail "HOME-активность не наш лаунчер"
fi
endlog

log "Скриншот"
adb exec-out screencap -p > "$OUT/screen-1-home.png" 2>/dev/null || report_fail "screencap не сработал"
ls -l "$OUT"
endlog

log "Проверка падений (FATAL / ANR)"
adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>/dev/null || true
adb logcat -d > "$OUT/logcat-full.txt" 2>/dev/null || true
if grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-full.txt"; then
    grep -nE -A 25 "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-full.txt" > "$OUT/crash-summary.txt" || true
    report_fail "В логе есть падение или ANR"
else
    echo "crashes=none" | tee -a "$OUT/crash-summary.txt"
fi
endlog

log "Кадровые метрики: рабочий стол"
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
# Медленное перелистывание страниц туда-обратно.
for _ in 1 2 3; do
    adb shell input swipe 900 1200 200 1200 120
    sleep 1
    adb shell input swipe 200 1200 900 1200 120
    sleep 1
done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-desktop.txt" 2>/dev/null || true
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread" \
    "$OUT/gfxinfo-desktop.txt" | tee "$OUT/gfxinfo-desktop-summary.txt"
endlog

log "Кадровые метрики: каталог приложений"
adb shell input swipe 540 1500 540 400 200
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell dumpsys gfxinfo "$PKG" framestats > "$OUT/gfxinfo-framestats.txt" 2>/dev/null || true
head -40 "$OUT/gfxinfo-framestats.txt"
endlog

log "Проверка светлой темы"
# Просто снимаем второй экран: тема переключается пользователем вручную,
# поэтому здесь фиксируем текущий вид как есть.
adb exec-out screencap -p > "$OUT/screen-2-after-swipe.png" 2>/dev/null || true
endlog

log "Итог"
if [ -s "$OUT/failures.txt" ]; then
    echo "ПРОВЕРКА НЕ ПРОЙДЕНА:"
    cat "$OUT/failures.txt"
    exit 1
fi
echo "Проверки пройдены: установка, запуск, HOME, отсутствие падений."
