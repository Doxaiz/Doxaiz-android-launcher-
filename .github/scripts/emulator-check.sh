#!/usr/bin/env bash
# Проверка лаунчера на настоящем Android (эмулятор API 34 в GitHub Actions).
#
# Скрипт выполняется внутри android-emulator-runner: adb поднят, эмулятор
# загружен. Задача — получить объективные факты, а не «нажать красиво»:
#   1. APK устанавливается (валидны подпись и манифест);
#   2. лаунчер становится системным HOME;
#   3. активность рисует интерфейс (дерево узлов, а не пустой экран);
#   4. каталог приложений открывается и прокручивается;
#   5. по ходу сценария нет FATAL EXCEPTION / ANR (проверяется несколько раз,
#      потому что падение после взаимодействия — самый частый случай);
#   6. снимаются кадровые метрики. Эмулятор рисует программно (swiftshader),
#      поэтому абсолютные миллисекунды НЕ равны плавности на телефоне; это
#      относительный показатель и он так и подписан в отчёте.
#
# Все артефакты складываются в emulator-report/. Ключевые числа дополнительно
# печатаются аннотациями: в среде, откуда запускается проверка, артефакты
# GitHub не скачиваются, а аннотации читаются через REST API.
set -uo pipefail

PKG="com.doxaiz.launcher"
OUT="emulator-report"
mkdir -p "$OUT"

APK="$(find "${APK_PATH:-apk}" -name '*.apk' | head -1)"
log() { echo "::group::$*"; }
endlog() { echo "::endgroup::"; }

note() {
    echo "::notice title=$1::$(printf '%s' "$2" | tr '\r\n' '  ' | cut -c1-900)"
}
report_fail() {
    echo "::error title=проверка::$*"
    echo "$*" | tee -a "$OUT/failures.txt"
}

# Компактный дамп дерева интерфейса: класс, подпись, границы.
dump_ui() {  # $1 = префикс файла, $2 = заголовок
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
for i, ch in enumerate(chunks[:8]):
    print(f"::notice title={title} #{i+1}::{ch}")
if not rows:
    print(f"::error title={title}::дерево пустое — на экране нет UI")
PYEOF
    cat "$OUT/ui-$1.txt"
}

# Проверка падений. Вызывается НЕСКОЛЬКО раз за прогон: падение после
# взаимодействия не видно в проверке, сделанной до него.
check_crash() {  # $1 = метка этапа
    local stage="$1"
    adb logcat -d -b crash > "$OUT/logcat-crash-$stage.txt" 2>/dev/null || true
    adb logcat -d > "$OUT/logcat-$stage.txt" 2>/dev/null || true
    if grep -qE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-$stage.txt" 2>/dev/null; then
        grep -nE -A 30 "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-$stage.txt" \
            > "$OUT/crash-$stage.txt" 2>/dev/null || true
        head -12 "$OUT/crash-$stage.txt" | while IFS= read -r line; do
            note "crash-$stage" "$line"
        done
        report_fail "Падение или ANR на этапе «$stage» (подробности: crash-$stage.txt)"
    else
        echo "$stage: падений нет" | tee -a "$OUT/crash-summary.txt"
    fi
    # Процесс на месте? Если его убили, дальше мерить нечего.
    if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
        report_fail "Процесс $PKG не работает на этапе «$stage»"
        return 1
    fi
    return 0
}

log "Устройство"
adb devices -l
adb shell getprop ro.build.version.release | tee "$OUT/device.txt"
adb shell getprop ro.build.version.sdk | tee -a "$OUT/device.txt"
adb shell getprop ro.product.cpu.abi | tee -a "$OUT/device.txt"
endlog

log "Установка $APK"
adb logcat -c 2>/dev/null || true
if adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt" | grep -q "Success"; then
    echo "install=OK" | tee -a "$OUT/install.txt"
else
    report_fail "Установка APK не удалась"
fi
endlog

log "Разблокировка экрана"
# Эмулятор стартует с keyguard: пока он висит, любые замеры и дампы относятся
# к экрану блокировки, а не к лаунчеру.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 2
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mDreamingLockscreen" | tee "$OUT/keyguard.txt"
endlog

log "Лаунчер как системный HOME"
# cmd package set-home-activity на части образов не срабатывает, поэтому
# назначаем роль HOME штатным механизмом и проверяем результат.
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
HOME_HOLDERS="$(adb shell cmd role get-role-holders android.app.role.HOME 2>/dev/null | tr -d '\r')"
echo "HOME holders: $HOME_HOLDERS" | tee "$OUT/home-role.txt"
if ! echo "$HOME_HOLDERS" | grep -q "$PKG"; then
    note "home-role" "роль HOME не назначилась, отключаю системный лаунчер"
    adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
    adb shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
    HOME_HOLDERS="$(adb shell cmd role get-role-holders android.app.role.HOME 2>/dev/null | tr -d '\r')"
    echo "HOME holders (retry): $HOME_HOLDERS" >> "$OUT/home-role.txt"
fi
if ! echo "$HOME_HOLDERS" | grep -q "$PKG"; then
    report_fail "Лаунчер не стал системным HOME ($HOME_HOLDERS) — проверка жестов недостоверна"
fi
adb shell input keyevent KEYCODE_HOME
sleep 4
FOCUS="$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -1)"
echo "$FOCUS" | tee "$OUT/focus-home.txt"
if ! echo "$FOCUS" | grep -q "$PKG"; then
    report_fail "По кнопке HOME открылся не лаунчер: $FOCUS"
fi
note "focus-home" "$FOCUS"
endlog

check_crash "старт" || true

log "Рабочий стол: дерево интерфейса"
dump_ui home "ui-home"
if grep -q "нет ни одной подписи" "$OUT/ui-home.txt" 2>/dev/null; then
    note "ui-home" "подписей приложений нет — на чистой установке это ожидаемо"
fi
endlog

log "Каталог приложений"
# Открываем каталог тем же жестом, что и пользователь: свайп вверх по
# рабочему столу. Если не открылось — пробуем кнопку в доке по координатам
# из дампа, а не наугад.
adb shell input swipe 540 1700 540 600 250
sleep 4
dump_ui drawer "ui-drawer"
DRAWER_NODES="$(sed -n 's/^узлов=//p' "$OUT/ui-drawer.txt" 2>/dev/null | head -1)"
if [ "${DRAWER_NODES:-0}" -lt 20 ]; then
    note "drawer-retry" "после свайпа узлов=${DRAWER_NODES:-0}, ищу кнопку каталога в дампе"
    BTN="$(python3 - "$OUT/ui-home.xml" <<'PYEOF' 2>/dev/null || true
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for nd in re.findall(r'<node [^>]*>', xml):
    dsc = re.search(r'content-desc="([^"]*)"', nd)
    if dsc and ('Все приложения' in dsc.group(1) or 'каталог' in dsc.group(1).lower()):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', nd)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print(f"{(x1+x2)//2} {(y1+y2)//2}")
            break
PYEOF
)"
    if [ -n "${BTN:-}" ]; then
        note "drawer-retry" "нажимаю кнопку каталога в $BTN"
        adb shell input tap $BTN
        sleep 3
        dump_ui drawer "ui-drawer"
        DRAWER_NODES="$(sed -n 's/^узлов=//p' "$OUT/ui-drawer.txt" 2>/dev/null | head -1)"
    else
        note "drawer-retry" "кнопка каталога в дереве не найдена"
    fi
fi
note "drawer" "узлов в каталоге=${DRAWER_NODES:-0}"
if [ "${DRAWER_NODES:-0}" -lt 20 ]; then
    report_fail "Каталог приложений не открылся или пуст (узлов=${DRAWER_NODES:-0})"
fi
check_crash "каталог" || true
endlog

log "Прокрутка каталога и метрики кадров"
# Эмулятор рисует программно: числа показывают, что кадры вообще идут и
# насколько тяжёлые шаги, но не FPS на телефоне.
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
for _ in 1 2 3; do
    adb shell input swipe 540 1700 540 700 150
    sleep 1
    adb shell input swipe 540 700 540 1700 150
    sleep 1
done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-drawer.txt" 2>/dev/null || true
grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap uploads" \
    "$OUT/gfxinfo-drawer.txt" | tee "$OUT/gfxinfo-summary.txt"
note "gfxinfo-drawer" "$(tr '\n' ' ' < "$OUT/gfxinfo-summary.txt" | cut -c1-700)"
note "gfxinfo-note" "эмулятор рисует через swiftshader: числа относительные, FPS на телефоне не подтверждают"
adb exec-out screencap -p > "$OUT/screen-drawer.png" 2>/dev/null || true
check_crash "прокрутка" || true
endlog

log "Итог"
if [ -s "$OUT/failures.txt" ]; then
    echo "ПРОВЕРКА НЕ ПРОЙДЕНА:"
    cat "$OUT/failures.txt"
    exit 1
fi
echo "Пройдено: установка, HOME, отрисовка, каталог, прокрутка, отсутствие падений."
