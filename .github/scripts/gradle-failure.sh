#!/usr/bin/env bash
# Печатает причину падения Gradle-задачи аннотациями GitHub Actions.
#
# Зачем отдельный скрипт: в части окружений логи job недоступны (блокируются
# *.blob.core.windows.net), а аннотации читаются через REST API. Поэтому
# хвост лога режем на короткие ::error-строки — по 900 символов, которые
# гарантированно проходят.
#
# Использование: gradle-failure.sh <файл-лога>
set -uo pipefail

LOG="${1:-gradle.log}"
MAX=${MAX_LINES:-45}

emit() {
    # Обрезаем до лимита GitHub (1024 символа на аннотацию) и убираем переводы строк.
    local text
    text="$(printf '%s' "$1" | tr '\r\n' '  ' | sed 's/%/%25/g; s/::/%3A%3A/g' | cut -c1-900)"
    echo "::error title=gradle::${text}"
}

if [ ! -f "$LOG" ]; then
    emit "Лог $LOG не создан — падение до запуска Gradle"
    exit 0
fi

# Сначала блок «What went wrong» — там самая содержательная часть.
if grep -q "What went wrong" "$LOG"; then
    awk '/What went wrong/{flag=1} flag{print} flag&&/^\* Try/{exit}' "$LOG" \
        | head -20 | while IFS= read -r line; do
            [ -n "${line//[[:space:]]/}" ] && emit "$line"
        done
fi

# Затем точечные признаки: ошибки компиляции, проваленные тесты, причины.
grep -nE "FAILED|error:|Caused by:|tests? completed, .*failed|Execution failed for task|A problem occurred" "$LOG" \
    | head -"$MAX" | while IFS= read -r line; do
        emit "$line"
    done

# И последние строки — там вывод Gradle об итоге.
tail -5 "$LOG" | while IFS= read -r line; do
    [ -n "${line//[[:space:]]/}" ] && emit "tail: $line"
done

exit 0
