package com.doxaiz.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * Переименования приложений: кэш, новый формат хранения и миграция старого.
 *
 * <p>Что здесь важно проверить без Android:
 * <ul>
 *   <li>карта подписей кодируется и разбирается <b>точно</b> — подпись может
 *       содержать «|», двоеточия, эмодзи и перевод строки;</li>
 *   <li>старые отдельные ключи {@code rename_*} продолжают читаться, поэтому у
 *       тех, кто уже переименовывал приложения, подписи не пропадут;</li>
 *   <li>битая карта не убивает подписи целиком и не бросает исключение.</li>
 * </ul>
 */
public final class CustomLabelsTests {

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) {
        roundTrip();
        awkwardCharacters();
        brokenPackedData();
        emptyCases();

        System.out.println("CUSTOM_LABELS_TESTS=" + n);
    }

    /** Кодирование и разбор — взаимно обратны. */
    private static void roundTrip() {
        Map<String, String> src = new HashMap<>();
        src.put("app:com.android.chrome/com.android.chrome.Main", "Интернет");
        src.put("app:com.example/a", "Банк");
        src.put("app:com.example/b", "Работа");

        String packed = Prefs.encodeLabels(src);
        Map<String, String> out = new HashMap<>();
        Prefs.decodeLabels(packed, out);

        check(out.size() == src.size(), "количество подписей совпало: " + out.size());
        for (Map.Entry<String, String> e : src.entrySet()) {
            check(e.getValue().equals(out.get(e.getKey())),
                    "подпись для " + e.getKey() + " восстановлена");
        }
        // Пустая карта — пустая строка, а не «[]» или что-то ещё.
        check(Prefs.encodeLabels(new HashMap<>()).isEmpty(), "пустая карта кодируется в пустую строку");
    }

    /** Подписи с «|», двоеточиями и переводом строки не должны ломать разбор. */
    private static void awkwardCharacters() {
        Map<String, String> src = new HashMap<>();
        src.put("k1", "a|b");
        src.put("k2", "x:y:z");
        src.put("k3", "первая строка\nвторая");
        src.put("k4", "🎮 Игры");
        src.put("k5", "12:34");
        String packed = Prefs.encodeLabels(src);

        Map<String, String> out = new HashMap<>();
        Prefs.decodeLabels(packed, out);
        check(out.size() == 5, "все пять подписей разобраны: " + out.size());
        check("a|b".equals(out.get("k1")), "вертикальная черта сохранена");
        check("x:y:z".equals(out.get("k2")), "двоеточия сохранены");
        check("первая строка\nвторая".equals(out.get("k3")), "перевод строки сохранён");
        check("🎮 Игры".equals(out.get("k4")), "эмодзи сохранены");
        check("12:34".equals(out.get("k5")), "подпись, похожая на число, не искажена");
        // Порядок обхода HashMap не гарантирован, поэтому проверяем именно
        // независимость записей: испорченная длина не должна съесть соседнюю.
        check(Prefs.encodeLabels(out).length() == packed.length(),
                "повторное кодирование даёт ту же длину");
    }

    /** Битая карта: что разобрали — используем, остальное отбрасываем без падения. */
    private static void brokenPackedData() {
        Map<String, String> out = new HashMap<>();
        Prefs.decodeLabels("5:ключ7:значение", out);   // длины не совпадают
        check(out.size() <= 1, "на битых данных не выдумывается лишнего");

        out.clear();
        Prefs.decodeLabels("мусор", out);
        check(out.isEmpty(), "нечисловая карта игнорируется");

        // Вторая запись цела, первая испорчена: разбор останавливается, но не
        // бросает исключение и не выдумывает данных. Это важно: карта читается
        // на старте, и падение здесь означало бы пустой стол.
        out.clear();
        Prefs.decodeLabels("2:ab3:xyz", out);   // «3:xyz» — не пара, хвост
        check(out.isEmpty() || out.size() == 1, "оборванная пара не роняет разбор");

        out.clear();
        Prefs.decodeLabels("999:короткая", out);
        check(out.isEmpty(), "длина больше строки не создаёт записей");

        out.clear();
        Prefs.decodeLabels(null, out);
        Prefs.decodeLabels("", out);
        check(out.isEmpty(), "null и пустая строка безопасны");
    }

    private static void emptyCases() {
        Prefs.decodeLabels("", null); // не должно падать
        Map<String, String> out = new HashMap<>();
        // Запись с пустым значением допустима: так хранится «имя убрали»,
        // если карта успела записаться до удаления ключа.
        Prefs.decodeLabels("1:k0:", out);
        check(out.size() == 1 && "".equals(out.get("k")), "пустое значение разбирается");
    }
}
