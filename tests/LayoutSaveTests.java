package com.doxaiz.launcher;

/**
 * F-15: сохранение раскладки больше не строит JSON на UI-потоке.
 *
 * <p>Проверяется то, что можно проверить без Android: снимок является копией
 * (дальнейшие правки живых списков его не меняют), содержит все элементы с их
 * координатами и типами, и сборка JSON дважды из одного снимка даёт одинаковый
 * результат. Сам порядок «снимок → фоновый поток → запись» проверяется в CI на
 * эмуляторе и на устройстве.
 */
public final class LayoutSaveTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        snapshotIsIndependentCopy();
        folderAndAppAreDistinct();
        jsonIsStable();

        System.out.println("LAYOUT_SAVE_TESTS=" + n);
    }

    /** Снимок не должен ссылаться на живые списки: иначе фоновый поток читал бы их. */
    private static void snapshotIsIndependentCopy() {
        DesktopModel model = new DesktopModel(null);
        model.pages.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());
        model.dock.clear();
        model.dock.add("app:one");

        model.pages.get(0).add(DesktopItem.appAt("app:one", 0, 0));

        DesktopModel.LayoutSnapshot snap = model.debugSnapshot();

        // Меняем живую модель после снимка.
        model.pages.get(0).clear();
        model.dock.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());

        check(snap.pages.size() == 1, "в снимке осталась одна страница");
        check(snap.pages.get(0).size() == 1, "элемент в снимке сохранился");
        check(snap.dock.size() == 1, "док в снимке сохранился");
        check(snap.pages.get(0).get(0).appKey.equals("app:one"), "ключ элемента верен");
    }

    /** Папка и приложение сериализуются по-разному — иначе раскладка не восстановится. */
    private static void folderAndAppAreDistinct() {
        DesktopModel model = new DesktopModel(null);
        model.pages.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());

        model.pages.get(0).add(DesktopItem.appAt("app:pkg/cls", 0, 1));
        model.pages.get(0).add(DesktopItem.folderAt("7", "Игры",
                java.util.Arrays.asList("app:a", "app:b"), 2, 3));

        String json = model.debugSnapshot().toJson();
        check(json != null && !json.isEmpty(), "JSON собрался");
        check(json.contains("\"type\":\"app\""), "приложение помечено как app");
        check(json.contains("\"type\":\"folder\""), "папка помечена как folder");
        check(json.contains("\"id\":\"7\""), "у папки сохранён строковый id");
        check(json.contains("\"name\":\"Игры\""), "у папки сохранено имя");
        check(json.contains("\"col\":2") || json.contains("\"col\": 2"),
                "координата колонки папки в JSON");
    }

    /** Один снимок — один и тот же JSON: сериализация не зависит от потока. */
    private static void jsonIsStable() {
        DesktopModel model = new DesktopModel(null);
        model.pages.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());
        for (int i = 0; i < 12; i++) {
            model.pages.get(0).add(DesktopItem.appAt("app:" + i, i % 4, i / 4));
        }
        DesktopModel.LayoutSnapshot snap = model.debugSnapshot();
        String first = snap.toJson();
        String second = snap.toJson();
        check(first != null && first.equals(second), "JSON из снимка воспроизводим");
        check(first.contains("\"built\":"), "признак построенной раскладки записан");
        check(first.contains("\"seq\":"), "номер папок записан");
        // 12 элементов должны попасть в JSON: считаем ключи приложений.
        int keys = 0;
        int idx = 0;
        while ((idx = first.indexOf("\"key\":", idx)) >= 0) {
            keys++;
            idx += 6;
        }
        check(keys == 12, "в JSON все 12 элементов, найдено " + keys);
    }
}
