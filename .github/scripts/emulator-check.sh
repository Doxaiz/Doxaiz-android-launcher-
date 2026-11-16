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

dump_ui() {  # $1 = префикс файла, $2 = заголовок аннотации
    adb shell uiautomator dump "/sdcard/ui-$1.xml" >/dev/null 2>&1 || true
    adb shell cat "/sdcard/ui-$1.xml" > "$OUT/ui-$1.xml" 2>/dev/null || true
    python3 - "$OUT/ui-$1.xml" "$2" > "$OUT/ui-$1.txt" 2>/dev/null <<'PYEOF' || true
import re, sys
path, title = sys.argv[1], sys.argv[2]
try:
    xml = open(path, encoding='utf-8', errors='replace').read()
except OSError:
    print(f"::error title={title}::дамп не получен"); raise SystemExit
nodes = re.findall(r'<node [^>]*>', xml)
rows = []
for nd in nodes:
    cls = re.search(r'class="([^"]*)"', nd)
    txt = re.search(r'text="([^"]*)"', nd)
    dsc = re.search(r'content-desc="([^"]*)"', nd)
    bnd = re.search(r'bounds="([^"]*)"', nd)
    cls = (cls.group(1).split('.')[-1] if cls else '?')
    label = ((txt.group(1) if txt else '') or (dsc.group(1) if dsc else '')).strip()
    rows.append(f"{cls}[{label}]{bnd.group(1) if bnd else ''}")
print(f"узлов={len(nodes)}")
chunks, cur = [], ''
for r in rows:
    if len(cur) + len(r) > 700:
        chunks.append(cur); cur = ''
    cur += r + ' ; '
if cur:
    chunks.append(cur)
for i, ch in enumerate(chunks[:6]):
    print(f"::notice title={title} #{i+1}::{ch}")
if len(rows) == 0:
    print(f"::error title={title}::дерево пустое — на экране нет UI")
PYEOF
    cat "$OUT/ui-$1.txt"
}

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

log "Разблокировка экрана"
# Эмулятор стартует с экраном блокировки. Пока он висит, все замеры и дампы
# относятся к keyguard, а не к лаунчеру — в прошлом прогоне из-за этого
# получилось «Total frames rendered: 0» и пустой дамп интерфейса.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 2
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | tee "$OUT/keyguard.txt"
if grep -q "Keyguard" "$OUT/keyguard.txt"; then
    report_fail "Экран блокировки не снят — замеры недостоверны"
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

log "Скриншоты"
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

log "Фокус перед замерами"
FOCUS="$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -1)"
echo "$FOCUS" | tee "$OUT/focus.txt"
if ! echo "$FOCUS" | grep -q "$PKG"; then
    report_fail "В фокусе не лаунчер: $FOCUS"
fi
note "focus" "$FOCUS"
endlog

log "Дерево интерфейса рабочего стола"

# Артефакты из этой среды не скачиваются, поэтому снимаем компактный дамп
# узлов и печатаем частями: видно, что реально на экране.
dump_ui home "ui-home"

# Подписи приложений в узлах: если их нет, рабочий стол действительно пуст,
# а не «не прочитался» дампом.
if ! grep -q "[A-Za-zА-Яа-я].*[A-Za-zА-Яа-я].*[A-Za-zА-Яа-я]" "$OUT/ui-home.txt" 2>/dev/null; then
    note "ui-home-empty" "на рабочем столе нет ни одной подписи приложения"
fi
endlog

log "Кадровые метрики"
# Важно: эмулятор рисует через swiftshader (программный GPU), поэтому абсолютные
# значения FPS/percentile здесь НЕ показывают реальную плавность на телефоне.
# Метрика нужна как относительная: сколько кадров отрисовано и есть ли тяжёлые
# кадры в конкретном сценарии. Реальные числа снимаются на устройстве.
# Базовый замер: перелистывание страниц рабочего стола.
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
for _ in 1 2 3; do
    adb shell input swipe 900 1200 200 1200 120
    sleep 1
    adb shell input swipe 200 1200 900 1200 120
    sleep 1
done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-desktop.txt" 2>/dev/null || true
grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|Number Missed Vsync|Number Slow UI thread" \
    "$OUT/gfxinfo-desktop.txt" | tee "$OUT/gfxinfo-desktop-summary.txt"

# Сценарий 2: каталог приложений. Свайп снизу вверх раскрывает список,
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
dump_ui drawer "ui-drawer"

# Свайп вверх может не открыть каталог (жест зависит от настроек), поэтому
# дополнительно пробуем кнопку в доке. Пустой каталог — это факт, а не сбой
# скрипта, и он должен быть виден в отчёте.
DRAWER_NODES="$(sed -n 's/^узлов=//p' "$OUT/ui-drawer.txt" 2>/dev/null | head -1)"
if [ "${DRAWER_NODES:-0}" -lt 20 ]; then
    note "drawer-retry" "после свайпа узлов=${DRAWER_NODES:-0}, пробую кнопку каталога в доке"
    adb shell input tap 131 2255
    sleep 3
    dump_ui drawer2 "ui-drawer2"
    DRAWER_NODES="$(sed -n 's/^узлов=//p' "$OUT/ui-drawer2.txt" 2>/dev/null | head -1)"
fi
note "drawer" "узлов в каталоге=${DRAWER_NODES:-0}"
if [ "${DRAWER_NODES:-0}" -lt 20 ]; then
    report_fail "Каталог приложений не открылся или пуст (узлов=${DRAWER_NODES:-0})"
fi
adb shell input keyevent KEYCODE_BACK
sleep 2
endlog

log "Итог"
note "install" "$(tail -1 "$OUT/install.txt")"
note "start" "$(grep -E 'Status|Activity|TotalTime|WaitTime' "$OUT/start.txt" | tr '\n' ' ')"
note "crash" "$(head -1 "$OUT/crash-summary.txt")"
note "ui-home" "$(cat "$OUT/ui-home.txt" 2>/dev/null | cut -c1-700)"
note "gfxinfo-desktop" "$(tr '\n' ' ' < "$OUT/gfxinfo-desktop-summary.txt" | cut -c1-700)"
note "gfxinfo-drawer" "$(tr '\n' ' ' < "$OUT/gfxinfo-summary.txt" | cut -c1-800)"
note "gfxinfo-note" "эмулятор рисует программно: числа годятся только как относительные, FPS на телефоне не подтверждают"

if [ -s "$OUT/failures.txt" ]; then
    echo "ПРОВЕРКА НЕ ПРОЙДЕНА:"
    cat "$OUT/failures.txt"
    exit 1
fi
echo "Проверки пройдены: установка, запуск, HOME, отсутствие падений."
