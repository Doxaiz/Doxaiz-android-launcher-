#!/usr/bin/env python3
"""Разбор отчёта Android Lint в компактную сводку.

Зачем: отчёт Lint — это HTML/XML на тысячи строк, а логи job и артефакты в
некоторых окружениях недоступны. Скрипт печатает счётчики по важности и
списку проблем по категориям аннотациями GitHub Actions, чтобы по ним можно
было работать прямо из PR.

Отчёт берётся из app/build/reports/lint-results-debug.xml (генерируется
вместе с HTML). Если его нет — скрипт не падает, а сообщает об этом.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

REPORT_CANDIDATES = [
    "app/build/reports/lint-results-debug.xml",
    "app/build/reports/lint-results.xml",
]

# Категории, которые важнее всего для лаунчера.
FOCUS_CATEGORIES = {
    "Correctness": "корректность",
    "Security": "безопасность",
    "Performance": "производительность",
    "Battery": "батарея",
    "Usability": "удобство",
    "Accessibility": "доступность",
}

MAX_ISSUES_PER_ANNOTATION = 25


def find_report():
    for path in REPORT_CANDIDATES:
        if os.path.isfile(path):
            return path
    return None


def annotate(title, message):
    text = re.sub(r"[\r\n]+", " ", str(message))[:900]
    print(f"::notice title={title}::{text}")


def main():
    path = find_report()
    if not path:
        print("::warning title=lint::отчёт lint-results-debug.xml не найден")
        return 0

    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"::warning title=lint::не удалось разобрать {path}: {exc}")
        return 0

    issues = []
    for issue in root.findall("issue"):
        severity = issue.get("severity", "?")
        category = issue.get("category", "?")
        issues.append({
            "id": issue.get("id", "?"),
            "severity": severity,
            "category": category,
            "message": (issue.get("message") or "").strip(),
            "file": (issue.findtext("location/file") or "").strip(),
            "line": (issue.findtext("location/line") or "").strip(),
        })

    by_severity = {}
    by_category = {}
    for it in issues:
        by_severity[it["severity"]] = by_severity.get(it["severity"], 0) + 1
        by_category[it["category"]] = by_category.get(it["category"], 0) + 1

    errors = by_severity.get("Error", 0) + by_severity.get("Fatal", 0)
    warnings = by_severity.get("Warning", 0)
    infos = by_severity.get("Information", 0) + by_severity.get("Informational", 0)

    summary_lines = [
        "Сводка Android Lint",
        f"Ошибки: {errors}",
        f"Предупреждения: {warnings}",
        f"Информационные: {infos}",
        "",
        "По категориям:",
    ]
    for cat, count in sorted(by_category.items(), key=lambda kv: -kv[1]):
        human = FOCUS_CATEGORIES.get(cat, cat)
        summary_lines.append(f"  {human} ({cat}): {count}")

    summary = "\n".join(summary_lines)
    print(summary)

    with open("lint-summary.txt", "w", encoding="utf-8") as fh:
        fh.write(summary + "\n\n")
        for it in sorted(issues, key=lambda x: (x["severity"] != "Error", x["category"])):
            where = f"{it['file']}:{it['line']}" if it["file"] else "-"
            fh.write(f"[{it['severity']}] {it['id']} ({it['category']}) {where} — {it['message']}\n")

    annotate(
        "lint-итог",
        f"ошибок={errors}, предупреждений={warnings}, информационных={infos}; "
        + ", ".join(f"{c}={n}" for c, n in sorted(by_category.items(), key=lambda kv: -kv[1])),
    )

    # Печатаем самые значимые: ошибки и предупреждения в важных категориях.
    important = [
        it for it in issues
        if it["severity"] in ("Error", "Fatal")
        or (it["severity"] == "Warning" and it["category"] in FOCUS_CATEGORIES)
    ]
    seen = set()
    shown = 0
    for it in important:
        key = (it["id"], it["file"], it["line"])
        if key in seen:
            continue
        seen.add(key)
        where = os.path.basename(it["file"]) if it["file"] else "-"
        annotate(
            f"lint {it['severity']} {it['id']}",
            f"{where}:{it['line']} — {it['message']}",
        )
        shown += 1
        if shown >= MAX_ISSUES_PER_ANNOTATION:
            break

    if errors:
        # Ошибки Lint должны ронять сборку — это и есть смысл порога.
        print(f"::error title=lint::найдено ошибок: {errors} (см. lint-summary.txt)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
