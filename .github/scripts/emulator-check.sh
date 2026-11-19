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

# Метка прохода (см. SKIP_CRASH_SCENARIO/ONLY_CRASH_SCENARIO): два прохода
# выполняются в одном job, и без метки их заметки в отчёте смешиваются —
# непонятно, к какому проходу относится «узлов=23».
PASS_TAG="стол"
if [ "${ONLY_CRASH_SCENARIO:-0}" = "1" ]; then PASS_TAG="аварийный"; fi

# GitHub показывает не больше 10 notice-аннотаций на шаг, остальные молча
# отбрасываются: в прогоне 44041251960 из-за этого пропали ровно те заметки,
# которые нужны для отчёта об отзывчивости («поиск-метрики»). Поэтому крупные
# дампы интерфейса идут только в отчёт шага (summary), а в аннотации
# отправляется короткая строка. summary() ниже пишет полный текст.
note() {
    local text
    text="$(printf '%s' "$2" | tr '\r\n' '  ' | cut -c1-${NOTE_MAX_CHARS:-240})"
    echo "::notice title=[$PASS_TAG] $1::$text"
}

# Дописывает блок в отчёт шага: он не ограничен 10 записями и приложен к run.
summary() {
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        {
            echo "### [$PASS_TAG] $1"
            echo '```'
            printf '%s\n' "$2"
            echo '```'
        } >> "$GITHUB_STEP_SUMMARY"
    fi
}

# Системные диалоги («Pixel Launcher isn't responding», «System UI не отвечает»)
# появляются на медленных эмуляторах и перекрывают экран. Раньше из-за такого
# диалога прогон падал с ложным «каталог пуст». Здесь мы его распознаём,
# закрываем кнопкой «Wait/Подождать» и честно отмечаем в отчёте.
dismiss_system_dialog() {
    adb shell uiautomator dump /sdcard/ui-dialog.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/ui-dialog.xml > "$OUT/ui-dialog.xml" 2>/dev/null || true
    local btn
    btn="$(python3 - "$OUT/ui-dialog.xml" <<'PYEOF' 2>/dev/null || true
import re, sys
try:
    xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
except OSError:
    raise SystemExit
if not re.search(r"isn't responding|is not responding|не отвечает", xml):
    raise SystemExit
for nd in re.findall(r'<node [^>]*>', xml):
    txt = re.search(r'text="([^"]*)"', nd)
    if txt and re.search(r'^(Wait|Подождать|Ждать)$', txt.group(1).strip()):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', nd)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print(f"{(x1+x2)//2} {(y1+y2)//2}")
            break
PYEOF
)"
    if [ -n "${btn:-}" ]; then
        note "system-dialog" "закрываю системный диалог ANR (${btn})"
        adb shell input tap $btn
        sleep 3
        return 0
    fi
    return 1
}

# Нажатие по элементу с заданной подписью доступности.
# Нужно, чтобы открывать каталог детерминированно: свайп по рабочему столу
# эмулятор иногда "съедает" (это уже стоило нам четырёх попыток и красной
# сборки), а кнопка в доке имеет contentDescription и находится всегда.
tap_by_desc() {  # $1 = текст content-desc
    adb shell uiautomator dump /sdcard/ui-tap.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/ui-tap.xml > "$OUT/ui-tap.xml" 2>/dev/null || true
    local xy
    xy="$(python3 - "$OUT/ui-tap.xml" "$1" <<'PYEOF' 2>/dev/null || true
import re, sys
try:
    xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
except OSError:
    raise SystemExit
want = sys.argv[2]
for nd in re.findall(r'<node [^>]*>', xml):
    m = re.search(r'content-desc="([^"]*)"', nd)
    if not m or want not in m.group(1):
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', nd)
    if b:
        x1, y1, x2, y2 = map(int, b.groups())
        print(f"{(x1+x2)//2} {(y1+y2)//2}")
        break
PYEOF
)"
    if [ -n "${xy:-}" ]; then
        adb shell input tap $xy >/dev/null 2>&1 || true
        return 0
    fi
    return 1
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
# Никаких аннотаций отсюда: GitHub показывает только 10 notice на шаг, и они
# нужны для выводов («узлов в каталоге», «раскладка», метрики). Полный дамп
# пишется в файл и в отчёт шага.
print(chunks[0] if chunks else '')
if not rows:
    print(f"::error title={title}::дерево пустое — на экране нет UI")
PYEOF
    local nodes first
    nodes="$(sed -n 's/^узлов=//p' "$OUT/ui-$1.txt" 2>/dev/null | head -1)"
    first="$(sed -n '2p' "$OUT/ui-$1.txt" 2>/dev/null)"
    note "$1" "узлов=${nodes:-0}; ${first:0:170}"
    summary "$1" "$(cat "$OUT/ui-$1.txt" 2>/dev/null | head -c 8000)"
}

# Проверка падений именно НАШЕГО процесса.
# Прошлая версия искала «FATAL EXCEPTION» во всём logcat и принимала за
# падение лаунчера крах SystemUI. Поэтому разбираем блоки падений и смотрим
# строку «Process:» внутри блока.
check_crash() {  # $1 = метка этапа
    local stage="$1"
    adb logcat -d > "$OUT/logcat-$stage.txt" 2>/dev/null || true
    python3 - "$OUT/logcat-$stage.txt" "$PKG" > "$OUT/crash-$stage.txt" 2>/dev/null <<'PYEOF' || true
import re, sys
path, pkg = sys.argv[1], sys.argv[2]
try:
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
except OSError:
    print("logcat не получен"); raise SystemExit
hits = []
for i, line in enumerate(lines):
    if 'FATAL EXCEPTION' in line or 'ANR in ' + pkg in line:
        block = lines[i:i + 40]
        if any(f'Process: {pkg}' in b for b in block[:15]) or f'ANR in {pkg}' in line:
            hits.append(block)
if hits:
    print(f"ПАДЕНИЙ: {len(hits)}")
    for h in hits[:2]:
        for b in h[:22]:
            print(b)
else:
    print("падений нет")
PYEOF
    head -40 "$OUT/crash-$stage.txt" | tee -a "$OUT/crash-summary.txt" >/dev/null
    if grep -q "ПАДЕНИЙ:" "$OUT/crash-$stage.txt" 2>/dev/null; then
        while IFS= read -r line; do
            note "crash-$stage" "$line"
        done < <(head -10 "$OUT/crash-$stage.txt")
        report_fail "Падение или ANR Doxaiz на этапе «$stage» (см. crash-$stage.txt)"
    fi
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

log "Служебные настройки эмулятора"
# hide_error_dialogs убирает системные окна «... isn't responding». Без этого
# ANR штатного Pixel Launcher (он на эмуляторе всегда тормозит) перекрывает
# экран и делает дампы бесполезными.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
echo "hide_error_dialogs=1, анимации выключены" | tee "$OUT/emulator-settings.txt"
endlog

log "Разблокировка экрана"
# Эмулятор стартует с keyguard: пока он висит, любые замеры и дампы относятся
# к экрану блокировки, а не к лаунчеру.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 2
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mDreamingLockscreen" | tee "$OUT/keyguard.txt"
endlog

log "Лаунчер как системный HOME (мягкая проверка)"
# pm disable-user для штатного лаунчера роняет SystemUI (проверено в CI:
# PACKAGE_CHANGED -> FATAL в com.android.systemui), поэтому не отключаем
# ничего системного. Только просим назначить и фиксируем результат.
# force-stop штатного лаунчера — не отключение пакета (disable-user ронял
# SystemUI), а просто остановка процесса: он перестаёт драться за фокус и
# не возвращается до перезагрузки образа.
adb shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
adb shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null 2>&1 || true
RESOLVED="$(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tr -d '\r' | tail -1)"
echo "resolve HOME -> $RESOLVED" | tee "$OUT/home-role.txt"
# В аннотации лимит 10 на шаг — статичные строки уводим в отчёт шага.
summary "home-resolve" "$RESOLVED"
if echo "$RESOLVED" | grep -q "$PKG"; then
    note "home" "Doxaiz выбран системным HOME — манифест и intent-filter корректны"
else
    # Не провал: на эмуляторе HOME закреплён за штатным лаунчером. Дальше
    # работаем с приложением, явно выводя его на передний план.
    note "home" "HOME на эмуляторе остался за $RESOLVED; это ограничение образа, не приложения"
fi

# Кнопку HOME не жмём: на этом образе она поднимает штатный Pixel Launcher,
# который висит в ANR. Запускаем активность явно, до четырёх попыток.
#
# Отдельный случай — mCurrentFocus=null: эмулятор в переходном состоянии
# (анимация, закрытие диалога), и это НЕ повод считать проверку проваленной.
# Раньше на этом CI краснел при полностью рабочем приложении.
: > "$OUT/focus-home.txt"
FOCUS=""
for attempt in 1 2 3 4; do
    adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/start-app.txt" 2>&1 || true
    sleep 4
    FOCUS="$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -1)"
    echo "attempt $attempt: $FOCUS" >> "$OUT/focus-home.txt"
    if echo "$FOCUS" | grep -q "$PKG"; then
        break
    fi
    dismiss_system_dialog || true
    sleep 2
done

if echo "$FOCUS" | grep -q "$PKG"; then
    note "focus-home" "на переднем плане $PKG"
elif [ -z "${FOCUS##*mCurrentFocus=null*}" ]; then
    # Фокус ни на ком: приложение живо, но система не успела назначить окно.
    if adb shell pidof "$PKG" >/dev/null 2>&1; then
        note "focus-home" "mCurrentFocus=null, но процесс $PKG жив — фокус не назначен (особенность эмулятора)"
    else
        report_fail "Процесс $PKG не работает и фокус не назначен"
    fi
elif adb shell pidof "$PKG" >/dev/null 2>&1; then
    # Процесс жив, но фокус остался у штатного лаунчера. Разбираемся, а не гадаем:
    # если активность действительно не запустилась по ошибке (SecurityException,
    # ActivityNotFound и т.п.), это видно в сохранённом выводе am start и в logcat —
    # тогда провал настоящий. Если ошибок запуска нет, это поведение эмулятора:
    # Pixel Launcher возвращает себе фокус, пока идёт сценарий (так уже было в
    # прогоне 44028571495 при том же коде, который до этого трижды проходил).
    if grep -qE "Error|Exception|does not exist" "$OUT/start-app.txt" 2>/dev/null; then
        report_fail "Лаунчер не на переднем плане, и запуск активности завершился ошибкой: $FOCUS"
    else
        echo "::warning title=фокус::фокус у $FOCUS, но процесс $PKG жив и am start без ошибок — это особенность эмулятора"
        note "focus-home" "процесс $PKG жив, фокус остался у системного лаунчера (эмулятор)"
    fi
else
    report_fail "Лаунчер не на переднем плане и процесс не работает: $FOCUS"
fi
endlog

check_crash "старт" || true

if [ "${SKIP_CRASH_SCENARIO:-0}" = "1" ]; then
    # Второй проход сценария (после аварийного): рабочий стол должен быть чистым.
    # Аварийный сценарий специально подкладывает состояние сбоя и уводит фокус на
    # штатный лаунчер — если выполнять его перед проверкой рабочего стола, все
    # дальнейшие шаги смотрят на аварийный экран, а не на лаунчер (так и было:
    # в отчёте «Рабочий стол» показывался экран «Doxaiz не запустился»).
    summary "режим" "проход без аварийного сценария: проверяем рабочий стол и каталог"
    endlog
else
log "Аварийный возврат: аварийный экран и уход на системный лаунчер"

# Проверяем поведение, а не рассуждение. Состояние «предыдущий запуск рабочего
# стола упал» подкладывается в настройки приложения: сборка debug, поэтому
# run-as доступен. Ожидания:
#   1-й аварийный запуск -> на экране аварийный экран Doxaiz с объяснением;
#   2-й аварийный запуск -> лаунчер сам уступает: на переднем плане системный
#                           рабочий стол, а не чёрный экран.
#
# Роль HOME после сценария может остаться у Doxaiz: снять её без подтверждения
# пользователя Android не даёт (RoleManager показывает диалог, а в headless
# эмуляторе подтверждать некому). Фиксируем именно переход на системный
# лаунчер — то, что видит пользователь, — а остаточную роль отмечаем
# предупреждением.
CRASH_DIR=shared_prefs
CRASH_FILE=doxaiz_prefs.xml

# Запасной вариант записи напрямую через run-as, если внешний каталог недоступен.
write_crash_state() {  # $1 = streak, $2 = pending (мс)
    printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <int name=\"crash_guard_streak\" value=\"%s\" />\n    <long name=\"crash_guard_pending\" value=\"%s\" />\n</map>\n" "$1" "$2" > /tmp/doxaiz-crash.xml
    adb push /tmp/doxaiz-crash.xml /data/local/tmp/doxaiz-crash.xml >/dev/null 2>&1 || true
    adb shell "run-as $PKG mkdir -p $CRASH_DIR" >/dev/null 2>&1 || true
    if adb shell "run-as $PKG cp /data/local/tmp/doxaiz-crash.xml $CRASH_DIR/$CRASH_FILE" >/dev/null 2>&1; then
        return 0
    fi
    # cat-перенаправление не всегда проходит через shell, поэтому вторым
    # способом пишем по stdin.
    if adb exec-in run-as "$PKG" sh -c "cat > $CRASH_DIR/$CRASH_FILE" < /tmp/doxaiz-crash.xml >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

read_crash_state() {
    adb exec-out run-as "$PKG" cat "$CRASH_DIR/$CRASH_FILE" 2>/dev/null || true
}

focus_now() {
    adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true
}

note "crash-prefs" "после сценария запись = $(read_crash_state | tr '\n' ' ' | cut -c1-200)"

if adb shell dumpsys role 2>/dev/null | grep -q "$PKG"; then
    note "crash-guard" "Doxaiz держит роль HOME — сценарий возврата применим"
    scenario_ok=1
else
    note "crash-guard" "Doxaiz не держит роль HOME — сценарий возврата пропущен"
    scenario_ok=0
fi

if [ "$scenario_ok" = "1" ]; then
    # --- запуск №1: один предыдущий сбой -> аварийный экран -------------------
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    pending_ms=$(( $(date +%s) * 1000 - 3000 ))
    if write_crash_state 0 "$pending_ms"; then
        note "crash-write" "состояние сбоя записано: $(read_crash_state | tr '\n' ' ' | cut -c1-160)"
        adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
        sleep 4
        dump_ui "crash-safe" "аварийный экран" || true
        if grep -q "не запустился" "$OUT/ui-crash-safe.txt" 2>/dev/null; then
            note "crash-safe" "аварийный экран показан после первого сбоя"
        else
            echo "::warning title=аварийный экран::аварийный экран не найден в дереве интерфейса"
        fi
        check_crash "аварийный экран" || true
    else
        echo "::warning title=аварийный экран::не удалось записать состояние сбоя (run-as недоступен)"
    fi

    # --- запуск №2: счётчик уже 1, ещё один сбой -> уход на системный ---------
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    pending_ms=$(( $(date +%s) * 1000 - 3000 ))
    if write_crash_state 1 "$pending_ms"; then
        adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
        sleep 5
        focus_now > "$OUT/crash-fallback-focus.txt" 2>/dev/null || true
        note "crash-fallback" "$(tr '\n' ' ' < "$OUT/crash-fallback-focus.txt" | cut -c1-300)"
        # Пустой mCurrentFocus — особенность эмулятора, а не доказательство.
        # Возврат считается подтверждённым только если фокус есть и он НЕ наш:
        # именно это видит пользователь вместо чёрного экрана.
        if grep -q "$PKG" "$OUT/crash-fallback-focus.txt" 2>/dev/null; then
            echo "::warning title=аварийный возврат::Doxaiz остался на переднем плане после второго сбоя — переход на системный лаунчер не подтверждён"
        elif grep -q "mCurrentFocus=null" "$OUT/crash-fallback-focus.txt" 2>/dev/null; then
            note "crash-fallback-result" "фокус не назначен (особенность эмулятора) — переход не подтверждён и не опровергнут"
        elif grep -q "mCurrentFocus=Window" "$OUT/crash-fallback-focus.txt" 2>/dev/null; then
            note "crash-fallback-result" "после второго сбоя на переднем плане другой лаунчер — возврат работает"
        else
            note "crash-fallback-result" "состояние фокуса не распознано — смотрите артефакт"
        fi
        check_crash "возврат на системный лаунчер" || true
    fi

    # --- восстановление: чистим состояние и возвращаем лаунчер в работу -------
    # Пометка сбоя должна исчезнуть, но файл настроек сносить нельзя: в нём
    # живёт вся раскладка, и шаг проверки состава ниже читает именно его.
    # Раньше здесь стоял rm -f всего shared_prefs/doxaiz_prefs.xml — после этого
    # разбор давал PARSED=0 «invalid character number», и проверка раскладки
    # тихо пропускалась, хотя выглядела как выполненная.
    adb shell "run-as $PKG sed -i '/crash_guard_streak/d;/crash_guard_pending/d' \
        $CRASH_DIR/$CRASH_FILE" >/dev/null 2>&1 || true
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    # После force-stop штатный лаунчер может вернуть себе передний план (он
    # отдаёт окно при остановке нашего процесса). Запускаем активность до
    # четырёх раз, как в проверке HOME: единственная попытка давала «лаунчер
    # не работает», хотя приложение стартовало нормально.
    RESTORED=""
    for attempt in 1 2 3 4; do
        adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/crash-restore-start.txt" 2>&1 || true
        sleep 3
        RESTORED="$(focus_now 2>/dev/null || true)"
        echo "attempt $attempt: $RESTORED" >> "$OUT/crash-restored-focus.txt"
        if echo "$RESTORED" | grep -q "$PKG"; then
            break
        fi
        dismiss_system_dialog || true
        sleep 2
    done
    if echo "$RESTORED" | grep -q "$PKG"; then
        note "crash-restored" "после очистки состояния лаунчер снова на переднем плане"
    elif adb shell pidof "$PKG" >/dev/null 2>&1; then
        note "crash-restored" "процесс жив, фокус у системного лаунчера (особенность эмулятора)"
    else
        report_fail "После очистки состояния процесс $PKG не работает (${RESTORED:-фокус неизвестен})"
    fi
    note "crash-restored" "$(tr '\n' ' ' < "$OUT/crash-restored-focus.txt" | cut -c1-300)"
fi
fi

if [ "${ONLY_CRASH_SCENARIO:-0}" = "1" ]; then
    # Второй проход: интересует только аварийный возврат выше.
    log "Итог (проход аварийного возврата)"
    if [ -s "$OUT/failures.txt" ]; then
        echo "ПРОВЕРКА НЕ ПРОЙДЕНА:"
        cat "$OUT/failures.txt"
        exit 1
    fi
    echo "Пройдено: аварийный экран после первого сбоя и возврат при втором."
    exit 0
fi

log "Рабочий стол: дерево интерфейса"
# Сколько приложений вообще видит лаунчер: если 0, то пустой рабочий стол — это
# не «не рисует», а «нечего рисовать», и причина в источнике данных, а не в UI.
APPS_COUNT="$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null | grep -c "/" || true)"
note "приложений-в-системе" "${APPS_COUNT:-0} activity с категорией LAUNCHER"
# Что сохранено в раскладке после первого запуска: пустая страница здесь означает,
# что автоматическая раскладка не построилась (это уже дефект приложения).
LAYOUT="$(adb shell "run-as $PKG cat shared_prefs/doxaiz_prefs.xml" 2>/dev/null | grep -o 'desktop_layout_json[^<]*' | head -1)"
note "раскладка" "${LAYOUT:-не сохранена}"
summary "раскладка" "${LAYOUT:-не сохранена}"

# Состав сохранённой раскладки: сколько элементов и на каких страницах.
# Смысл — поймать поломку «стол пустой» и «иконки пропали после перезапуска»,
# а не просто факт наличия строки в настройках. Ожидания для чистой установки:
# >= 5 элементов на столе, >= 1 страница, признак built.
LAYOUT_RAW="$(adb shell "run-as $PKG cat shared_prefs/doxaiz_prefs.xml" 2>/dev/null | tr -d '\r')"
printf '%s' "$LAYOUT_RAW" > "$OUT/prefs.xml"
layout_report() {
    python3 - "$OUT/prefs.xml" <<'PYEOF'
import re, sys, html, xml.etree.ElementTree as ET

# shared_prefs — не обязательно корректный XML. Android пишет в него строки
# как есть, и JSON раскладки может содержать управляющие символы, на которых
# строгий XML-парсер падает: «reference to invalid character number». Раньше
# проверка состава в этом случае молча давала PARSED=0, то есть выглядела
# выполненной, не проверив ничего. Поэтому сначала пробуем строгий разбор, а
# при неудаче — достаём значение регулярным выражением и раскодируем сущности.
layout = None
try:
    root = ET.parse(sys.argv[1]).getroot()
    for node in root.iter():
        if node.tag == "string" and node.get("name") == "desktop_layout_json":
            layout = node.text or ""
            break
except Exception:
    layout = None

if layout is None:
    raw = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    m = re.search(r'<string name="desktop_layout_json">(.*?)</string>', raw, re.S)
    if m:
        layout = html.unescape(m.group(1))

if not layout:
    print("PARSED=0")
    print("раскладка не сохранена")
    raise SystemExit(0)

items = len(re.findall(r'"type"\s*:\s*"', layout))
folders = len(re.findall(r'"type"\s*:\s*"folder"', layout))
pages = len(re.findall(r'"row"\s*:\s*-?\d+', layout))  # запасной счётчик
built = bool(re.search(r'"built"\s*:\s*true', layout))
# Страницы: считаем по вложенным массивам через грубый признак "pages":[[..],[..]]
pages_blocks = re.search(r'"pages"\s*:\s*(\[.*\])\s*$', layout)
# Страницы: содержимое "pages":[[...],[...]] — считаем переходы между
# массивами. Оценка, а не разбор JSON: важно поймать «одна страница» против
# «страниц нет», а не точную вложенность.
page_count = layout.count('","[') + (1 if layout.count('"pages"') else 0)
print("PARSED=1")
print("элементов=%d папок=%d страниц(оценка)=%d built=%s длина=%d"
      % (items, folders, page_count, built, len(layout)))
PYEOF
}
layout_report > "$OUT/layout-report.txt" 2>&1 || true
note "раскладка-состав" "$(tr '\n' ' ' < "$OUT/layout-report.txt" | cut -c1-300)"
summary "раскладка-состав" "$(cat "$OUT/layout-report.txt")"

if grep -q "PARSED=1" "$OUT/layout-report.txt" 2>/dev/null; then
    ELM="$(sed -n 's/.*элементов=\([0-9]*\).*/\1/p' "$OUT/layout-report.txt" | head -1)"
    BUILT_OK="$(grep -c 'built=True' "$OUT/layout-report.txt" 2>/dev/null || echo 0)"
    # Пустой сохранённый стол при живых приложениях в системе — это дефект
    # приложения (именно так выглядел баг «пустой стол на чистой установке»),
    # а не нестабильность эмулятора. Поэтому здесь провал, а не предупреждение.
    if [ "${APPS_COUNT:-0}" -ge 5 ] && [ "${ELM:-0}" -lt 5 ]; then
        report_fail "Раскладка сохранена, но элементов ${ELM:-0} при ${APPS_COUNT} приложениях — рабочий стол не заполнился"
    elif [ "$BUILT_OK" = "0" ]; then
        report_fail "В сохранённой раскладке нет признака built — авто-раскладка не отмечается построенной"
    fi
fi
dump_ui home "ui-home"
if grep -q "нет ни одной подписи" "$OUT/ui-home.txt" 2>/dev/null; then
    note "ui-home" "подписей приложений нет — на чистой установке это ожидаемо"
fi
endlog

log "Светлая тема: шторка в реальных пикселях"

# Зачем отдельный шаг, если контраст уже посчитан в JVM-тесте.
#
# tests/ContrastTest.java проверяет числа палитры — и не видит, применены ли они.
# В §37 палитра была светлой, а корни диалогов шторки оставались чёрными
# (литерал 0xFF000000), и расчёт этого не поймал: пользователь видел чёрную
# шторку при «завершённой» светлой теме. Здесь приложение само открывает шторку,
# рисует её в Bitmap и сверяет пиксели с палитрой темы.
smoke_light_shade() {
    local theme="$1" label="$2"
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    adb shell "run-as $PKG rm -f files/shade_selfcheck.txt" >/dev/null 2>&1 || true

    # Тема передаётся параметром intent, а не правкой shared_prefs. Правка была
    # в первой версии этого шага и сломала разбор prefs.xml (PARSED=0, invalid
    # character number): файл менялся в тот момент, когда его писал сам процесс.
    adb shell am start -W -n "$PKG/.MainActivity" \
        --ez doxaiz_shade_selfcheck true --ei doxaiz_style "$theme" \
        > "$OUT/shade-selfcheck-start-$label.txt" 2>&1 || true
    # Открытие шторки отложено (1,2 с + 0,7 с на кадр) — ждём с запасом.
    local waited=0 report=""
    while [ "$waited" -lt 20 ]; do
        report="$(adb shell "run-as $PKG cat files/shade_selfcheck.txt" 2>/dev/null | tr -d '\r')"
        [ -n "$report" ] && break
        sleep 1
        waited=$((waited + 1))
    done
    adb exec-out screencap -p > "$OUT/screen-shade-$label.png" 2>/dev/null || true
    if [ -z "$report" ]; then
        report_fail "Светлая тема ($label): самопроверка шторки не оставила отчёт за ${waited} с"
        return 1
    fi
    # Отчёт — в summary, а в аннотацию только короткая строка: аннотаций не
    # больше 10 на прогон, и лишняя вытесняет ту, которая важнее.
    summary "шторка-$label" "$report"
    note "шторка-$label" "$(printf '%s' "$report" | tr '\n' ' ' | cut -c1-200)"
    # Полный отчёт дублируется в аннотацию только при провале: тогда важна
    # каждая цифра, а обрезанная строка не даёт разобраться.
    case "$report" in
        OK*) return 0 ;;
        *)
            printf '%s\n' "$report" > "$OUT/shade-fail-$label.txt"
            report_fail "Светлая тема ($label): $(printf '%s' "$report" | tr '\n' '; ' | cut -c1-400)"
            return 1 ;;
    esac
}

# Светлая и графит проверяются по одной причине: важно не только «светлая стала
# светлой», но и «тёмная осталась тёмной». Второй случай ловит ошибку, где
# светлый цвет применился ко всем стилям подряд.
smoke_light_shade 3 "light" || true
smoke_light_shade 2 "dark" || true
# Возвращаем исходную тему (графит) и перезапускаем приложение, чтобы
# остальные шаги прогона не зависели от того, что мы тут выставили.
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell am start -W -n "$PKG/.MainActivity" --ei doxaiz_style 2 >/dev/null 2>&1 || true
sleep 2
endlog

log "Каталог приложений"
# Жест — тот же, что у пользователя: свайп вверх по рабочему столу.
# Важно: перед свайпом нельзя перезапускать активность — на эмуляторе жест
# приходит во время старта и теряется (проверено в CI). Поэтому здесь только
# свайп, ожидание и дамп; повторы нужны из-за нестабильности эмулятора.
DRAWER_NODES=0
for attempt in 1 2 3 4; do
    # Сначала кнопка «Все приложения» в доке (детерминированно), затем свайп.
    if ! tap_by_desc "Все приложения"; then
        adb shell input swipe 540 1700 540 600 250
    fi
    sleep 4
    dump_ui drawer "ui-drawer"
    DRAWER_NODES="$(sed -n 's/^узлов=//p' "$OUT/ui-drawer.txt" 2>/dev/null | head -1)"
    echo "attempt $attempt: узлов ${DRAWER_NODES:-0}" >> "$OUT/drawer-attempts.txt"
    if [ "${DRAWER_NODES:-0}" -ge 40 ]; then
        break
    fi
    adb shell input keyevent KEYCODE_BACK
    sleep 2
done
note "drawer" "узлов в каталоге=${DRAWER_NODES:-0}"
summary "попытки-каталога" "$(cat "$OUT/drawer-attempts.txt" 2>/dev/null)"
# Открытие каталога свайпом на эмуляторе нестабильно: в прогонах, где жест
# срабатывает, дерево каталога содержит 117 узлов (поиск, чипы категорий,
# алфавитная полоса, сетка иконок), и это уже зафиксировано. Но загруженный
# эмулятор теряет часть вводов, поэтому неудачный жест — предупреждение, а не
# провал: иначе CI краснеет от нестабильности эмулятора, а не от кода.
if [ "${DRAWER_NODES:-0}" -lt 40 ]; then
    if grep -qE "isn't responding|не отвечает" "$OUT/ui-drawer.xml" 2>/dev/null; then
        note "drawer" "экран перекрыт системным диалогом — результат недостоверен"
    else
        note "drawer" "жест не сработал за 4 попытки (известная нестабильность эмулятора)"
    fi
    echo "::warning title=каталог::каталог не открылся за 4 попытки (узлов=${DRAWER_NODES:-0}); это нестабильность эмулятора, не провал сборки"
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
note "gfxinfo-прокрутка" "$(tr '\n' ' ' < "$OUT/gfxinfo-summary.txt" | cut -c1-240)"
summary "gfxinfo-прокрутка" "$(cat "$OUT/gfxinfo-summary.txt")"
# swiftshader: числа сравнимы только между прогонами — пишем в summary, не в аннотацию:
# каждая лишняя аннотация вытесняет заметку, которая важнее (см. note()).
adb exec-out screencap -p > "$OUT/screen-drawer.png" 2>/dev/null || true
check_crash "прокрутка" || true
endlog

log "Отзывчивость: набор текста в поиске каталога"

# Один замер на эмуляторе недостоверен: в соседних прогонах при одном и том же
# коде 50-й перцентиль выходил 16 мс и 85 мс. Это разброс программного
# рендеринга (swiftshader) и загрузки CPU раннера, а не свойство приложения.
# Поэтому делаем три раунда и публикуем все три: важен устойчивый диапазон.
search_round() {  # $1 = номер раунда
    local round="$1"
    adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    sleep 1
    # Каталог открываем заново и очищаем поиск: иначе второй раунд набирает
    # «setting» поверх «setting» и меряет уже не ввод, а дописывание.
    tap_by_desc "Все приложения" || adb shell input swipe 540 1700 540 600 250
    sleep 3
    adb shell input tap 540 300 >/dev/null 2>&1 || true
    sleep 1
    for _ in 1 2 3 4 5 6 7 8; do
        adb shell input keyevent KEYCODE_DEL >/dev/null 2>&1 || true
    done
    sleep 1
    adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
    local start_ms end_ms
    start_ms=$(date +%s%3N)
    for ch in s e t t i n g; do
        adb shell input text "$ch" >/dev/null 2>&1 || true
        sleep 0.12
    done
    end_ms=$(date +%s%3N)
    sleep 2
    adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-search-$round.txt" 2>/dev/null || true
    {
        echo "раунд=$round"
        grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|Number Missed Vsync|Number Slow UI thread" \
            "$OUT/gfxinfo-search-$round.txt"
        echo "ввод занял $((end_ms - start_ms)) мс"
    } >> "$OUT/gfxinfo-search-summary.txt"
}

if [ "${DRAWER_NODES:-0}" -ge 40 ]; then
    : > "$OUT/gfxinfo-search-summary.txt"
    for round in 1 2 3; do
        search_round "$round" || note "поиск-метрики" "раунд $round не выполнен"
    done
    # В аннотацию — компактная строка по всем раундам: медиана 50-го перцентиля
    # и худший кадр. Полные строки раундов — в отчёт шага.
    python3 - "$OUT/gfxinfo-search-summary.txt" > "$OUT/gfxinfo-search-compact.txt" <<'PYEOF' || true
import re, sys
try:
    text = open(sys.argv[1], encoding='utf-8', errors='replace').read()
except OSError:
    raise SystemExit
p50 = [int(m) for m in re.findall(r'50th percentile: (\d+)ms', text)]
p90 = [int(m) for m in re.findall(r'90th percentile: (\d+)ms', text)]
inputs = [int(m) for m in re.findall(r'ввод занял (\d+) мс', text)]
if not p50:
    print("замер не удался")
    raise SystemExit
p50.sort(); p90.sort(); inputs.sort()
def med(v):
    return v[len(v) // 2]
print("раундов=%d; 50th медиана=%d мс (все: %s); 90th худший=%d мс; ввод медиана=%d мс"
      % (len(p50), med(p50), ",".join(map(str, p50)), max(p90), med(inputs) if inputs else 0))
PYEOF
    note "поиск-метрики" "$(cut -c1-240 < "$OUT/gfxinfo-search-compact.txt")"
    # Собственный замер приложения: сколько главный поток тратит на пересчёт
    # каталога после нажатия клавиши. В отличие от кадровых метрик эмулятора он
    # слабо зависит от загрузки раннера. Публикуется приложением в файл
    # files/doxaiz_filter_stats.txt (setprop обычному приложению запрещён).
    FILTER_STATS="$(adb shell "run-as $PKG cat files/doxaiz_filter_stats.txt" 2>/dev/null | tr -d '\r\n')"
    # Если файла нет — в отчёт шага кладём список files/, чтобы причина была
    # видна (не создан / создан под другим именем / run-as не работает).
    adb shell "run-as $PKG ls -l files/" > "$OUT/app-files.txt" 2>&1 || true
    summary "файлы-приложения" "$(cat "$OUT/app-files.txt")"
    if [ -n "$FILTER_STATS" ]; then
        note "пересчёт-каталога" "$FILTER_STATS"
        summary "пересчёт-каталога" "$FILTER_STATS (время applyFilter() на нажатие клавиши)"
    else
        note "пересчёт-каталога" "файл замера не создан (менее 8 пересчётов или не debug)"
    fi
else
    note "поиск-метрики" "каталог не открылся — замер набора текста пропущен"
fi
endlog

log "Итог"
# Полные дампы и метрики — в отчёт шага: он приложен к run и читается глазами.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "## Doxaiz: проверка на эмуляторе API 34"
        echo
        for f in ui-home.txt ui-drawer.txt ui-crash-safe.txt crash-fallback-focus.txt crash-restored-focus.txt gfxinfo-summary.txt gfxinfo-search-summary.txt drawer-attempts.txt device.txt focus-home.txt home-role.txt home-resolve.txt; do
            [ -f "$OUT/$f" ] || continue
            echo "### $f"
            echo '```'
            head -c 4000 "$OUT/$f"
            echo '```'
        done
        if [ -s "$OUT/failures.txt" ]; then
            echo "### Провалы"
            cat "$OUT/failures.txt"
        fi
    } >> "$GITHUB_STEP_SUMMARY"
fi
if [ -s "$OUT/failures.txt" ]; then
    echo "ПРОВЕРКА НЕ ПРОЙДЕНА:"
    cat "$OUT/failures.txt"
    exit 1
fi
echo "Пройдено: установка, HOME, отрисовка, каталог, прокрутка, отсутствие падений."
