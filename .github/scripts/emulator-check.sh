#!/usr/bin/env bash
# Проверка лаунчера на настоящем Android (эмулятор API 34 в GitHub Actions).
#
# Скрипт выполняется внутри android-emulator-runner, то есть adb уже поднят и
# эмулятор загружен. Задача — не «нажать красиво», а получить объективные факты:
#   1. APK устанавливается (значит, подпись и манифест валидны);
#   2. активность реально запускается без падения (нет FATAL EXCEPTION);
#   3. лаунчер выбирается системным HOME;
#   4. кадровые метрики каталога приложений снимаются (gfxinfo/jank) как
#      относительный показатель: эмулятор рисует программно, FPS на телефоне
#      по ним не определяется.
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

# Ключевые факты дублируем аннотацией: артефакты из этой среды не скачиваются
# (блокируется blob-хранилище), а аннотации читаются через REST API.
note() {
    echo "::notice title=$1::$(printf '%s' "$2" | tr '\r\n' '  ' | cut -c1-900)"
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
    note "home" "HOME-активность = $PKG"
else
    report_fail "HOME-активность не наш лаунчер"
fi
endlog

log "Скриншот рабочего стола"
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

log "Кадровые метрики"
# Важно: эмулятор рисует через swiftshader (программный GPU), поэтому абсолютные
# значения FPS/percentile здесь НЕ показывают реальную плавность на телефоне.
# Метрика нужна как относительная: сколько кадров отрисовано и есть ли тяжёлые
# кадры в конкретном сценарии. Реальные числа снимаются на устройстве.
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true

# Сценарий 1: каталог приложений. Свайп снизу вверх раскрывает список,
# прокрутка списка — самая частая операция в лаунчере.
adb shell input swipe 540 1900 540 500 200
sleep 4
for _ in 1 2 3; do
    adb shell input swipe 540 1600 540 600 150
    sleep 1
    adb shell input swipe 540 600 540 1600 150
    sleep 1
done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-drawer.txt" 2>/dev/null || true
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands" \
    "$OUT/gfxinfo-drawer.txt" | tee "$OUT/gfxinfo-summary.txt"

adb exec-out screencap -p > "$OUT/screen-2-drawer.png" 2>/dev/null || true
adb shell input keyevent KEYCODE_BACK
sleep 2
endlog

log "Итог"
note "install" "$(tail -1 "$OUT/install.txt")"
note "start" "$(grep -E 'Status|Activity|TotalTime|WaitTime' "$OUT/start.txt" | tr '\n' ' ')"
note "crash" "$(head -1 "$OUT/crash-summary.txt")"
note "gfxinfo-drawer" "$(tr '\n' ' ' < "$OUT/gfxinfo-summary.txt" | cut -c1-800)"
note "gfxinfo-note" "эмулятор рисует программно: числа годятся только как относительные, FPS на телефоне не подтверждают"

if [ -s "$OUT/failures.txt" ]; then
    echo "ПРОВЕРКА НЕ ПРОЙДЕНА:"
    cat "$OUT/failures.txt"
    exit 1
fi
echo "Проверки пройдены: установка, запуск, HOME, отсутствие падений."
