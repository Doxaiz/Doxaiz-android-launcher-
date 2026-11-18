package com.doxaiz.launcher;

/**
 * F-15: сохранение раскладки больше не строит JSON на UI-потоке.
 *
 * <h3>Что здесь можно проверить, а что нельзя</h3>
 * Проверяется снимок: он независим от живых списков, сохраняет координаты, типы
 * и содержимое папок, и создаётся без обращения к {@code org.json}. Содержимое
 * самого JSON здесь проверить нельзя: в unit-тестах {@code org.json} — заглушка
 * {@code android.jar}, любой вызов бросает {@code RuntimeException: Stub!}
 * (по этой же причине из тестов исключён {@code Audit54Tests}). Поэтому в тесте
 * зафиксировано, что при недоступном JSON сериализация <b>не падает</b>, а
 * возвращает {@code null} — сохранением занимается фон, и падение уборщика
 * раскладки было бы хуже отсутствия записи. Настоящий JSON проверяется на
 * эмуляторе (CI) и на устройстве: там же видно, что раскладка выживает
 * перезапуск.
 */
public final class LayoutSaveTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        snapshotIsIndependentCopy();
        snapshotKeepsPlacementAndType();
        serializationFailureIsNotFatal();

        System.out.println("LAYOUT_SAVE_TESTS=" + n);
    }

    /** Снимок не должен ссылаться на живые списки: иначе фон читал бы их на ходу. */
    private static void snapshotIsIndependentCopy() {
        DesktopModel model = new DesktopModel(null);
        model.pages.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());
        model.dock.clear();
        model.dock.add("app:one");
        model.pages.get(0).add(DesktopItem.appAt("app:one", 0, 0));

        DesktopModel.LayoutSnapshot snap = model.debugSnapshot();

        // Меняем живую модель уже после снимка — снимок меняться не должен.
        model.pages.get(0).clear();
        model.dock.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());

        check(snap.pages.size() == 1, "в снимке осталась одна страница");
        check(snap.pages.get(0).size() == 1, "элемент в снимке сохранился");
        check(snap.dock.size() == 1, "док в снимке сохранился");
        check("app:one".equals(snap.pages.get(0).get(0).appKey), "ключ элемента верен");
        check("app:one".equals(snap.dock.get(0)), "ключ дока верен");
        // Признак «раскладка построена» тоже фиксируется в снимке: по нему решается,
        // перезаполнять ли пустой стол после перезапуска.
        check(snap.built, "снимок помнит, что раскладка построена");
    }

    /** Папка и приложение должны дойти до сериализации с разными данными. */
    private static void snapshotKeepsPlacementAndType() {
        DesktopModel model = new DesktopModel(null);
        model.pages.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());
        model.pages.get(0).add(DesktopItem.appAt("app:pkg/cls", 0, 1));
        model.pages.get(0).add(DesktopItem.folderAt("7", "Игры",
                java.util.Arrays.asList("app:a", "app:b"), 2, 3));

        DesktopModel.LayoutSnapshot snap = model.debugSnapshot();
        java.util.List<DesktopItem> page = snap.pages.get(0);
        check(page.size() == 2, "на странице два элемента");

        DesktopItem app = page.get(0);
        check(app.type == DesktopItem.TYPE_APP, "первый элемент — приложение");
        check(app.col == 0 && app.row == 1, "координаты приложения сохранены");
        check("app:pkg/cls".equals(app.appKey), "ключ приложения сохранён");

        DesktopItem folder = page.get(1);
        check(folder.type == DesktopItem.TYPE_FOLDER, "второй элемент — папка");
        check(folder.col == 2 && folder.row == 3, "координаты папки сохранены");
        check("7".equals(folder.folderId), "id папки сохранён");
        check("Игры".equals(folder.folderName), "имя папки сохранено");
        check(folder.folderApps.size() == 2, "приложения папки сохранены");

        // Пустая страница в снимке остаётся пустой страницей, а не исчезает:
        // иначе после сохранения страницы бы «схлопывались».
        DesktopModel twoPages = new DesktopModel(null);
        twoPages.pages.clear();
        twoPages.pages.add(new java.util.ArrayList<DesktopItem>());
        twoPages.pages.add(new java.util.ArrayList<DesktopItem>());
        check(twoPages.debugSnapshot().pages.size() == 2, "пустые страницы сохраняются");
    }

    /**
     * Недоступный {@code org.json} (unit-тесты) не должен превращаться в падение
     * сохранения: {@code toJson()} обязан вернуть null и записать причину, а не
     * бросить исключение в фоновый поток.
     */
    private static void serializationFailureIsNotFatal() {
        DesktopModel model = new DesktopModel(null);
        model.pages.clear();
        model.pages.add(new java.util.ArrayList<DesktopItem>());
        model.pages.get(0).add(DesktopItem.appAt("app:one", 0, 0));

        String json;
        try {
            json = model.debugSnapshot().toJson();
        } catch (Throwable t) {
            throw new AssertionError("toJson не имеет права бросать исключение: " + t, t);
        }
        // В JVM-тестах JSON недоступен — ожидаем null. Если когда-нибудь в тестовый
        // classpath добавят настоящий org.json, тест это просто примет.
        check(json == null || !json.isEmpty(), "результат сериализации: null или строка");
    }
}
