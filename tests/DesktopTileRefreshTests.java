package com.doxaiz.launcher;

/**
 * F-05: точечное обновление плитки вместо пересборки всех страниц.
 *
 * <p>Проверяется чистая часть — сравнение подписей, по которому решается, нужно
 * ли вообще трогать View. Само обновление View проверяется на эмуляторе; здесь
 * важно зафиксировать, что «одинаковый текст» распознаётся как одинаковый (иначе
 * на каждом возврате домой шла бы лишняя работа), а «разный» — как разный
 * (иначе метка «новое» не гасла бы).
 */
public final class DesktopTileRefreshTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        identicalText();
        newAppMarker();
        folderContentDescription();
        nulls();

        System.out.println("DESKTOP_TILE_TESTS=" + n);
    }

    private static void identicalText() {
        check(TileLabels.sameText("Банк", "Банк"), "одинаковый текст — равны");
        check(!TileLabels.sameText("Банк", "Банк "), "пробел в конце — уже разный");
        check(!TileLabels.sameText("Банк", "банк"), "регистр учитывается");
        // StringBuilder/Spannable с тем же текстом считаются равными: подпись
        // с меткой «новое» — это SpannableStringBuilder, и сравнение по toString
        // обязательно, иначе текст менялся бы каждый раз.
        StringBuilder sb = new StringBuilder("Погода");
        check(TileLabels.sameText(sb, "Погода"), "StringBuilder сравнивается по тексту");
    }

    /** Метка «новое» — это префикс «• » и другой цвет; текст уже не совпадает. */
    private static void newAppMarker() {
        // В JVM android.text недоступен (заглушка), поэтому своя реализация
        // CharSequence с тем же текстом — проверяется именно сравнение по тексту.
        CharSequence marked = new CharSequence() {
            private final String text = "• Банк";
            @Override public int length() { return text.length(); }
            @Override public char charAt(int i) { return text.charAt(i); }
            @Override public CharSequence subSequence(int a, int b) { return text.subSequence(a, b); }
            @Override public String toString() { return text; }
        };
        check(!TileLabels.sameText(marked, "Банк"),
                "подпись с меткой «новое» отличается от обычной");
        check(TileLabels.sameText(marked, "• Банк"),
                "та же метка распознаётся как та же");
        check(marked.toString().startsWith("• "), "метка — префикс «• »");
    }

    private static void folderContentDescription() {
        check("Инструменты".equals(FolderTileLabels.contentDescription("Инструменты", 0)),
                "пустая папка описывается только именем");
        check("Игры, приложений: 3".equals(FolderTileLabels.contentDescription("Игры", 3)),
                "описание папки включает число приложений");
        check("Папка, приложений: 1".equals(FolderTileLabels.contentDescription(null, 1)),
                "null-имя использует безопасное название");
    }

    private static void nulls() {
        check(TileLabels.sameText(null, null), "два null равны");
        check(!TileLabels.sameText(null, "текст"), "null и текст не равны");
        check(!TileLabels.sameText("текст", null), "текст и null не равны");
        check(TileLabels.sameText("", ""), "две пустые строки равны");
    }
}
