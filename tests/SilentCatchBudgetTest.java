package com.doxaiz.launcher;

import java.io.File;

/**
 * Храповик на «немые» catch: их общее число не должно расти.
 *
 * <h3>Зачем</h3>
 * F-07 в аудите — это 268 мест вида {@code catch (Throwable ignored)}, где сбой
 * исчезает без следа. Часть из них законна (чужой ContentProvider не ответил,
 * {@code Dialog.dismiss()} на уже закрытом окне), поэтому «переписать всё» —
 * неправильная цель: шум в журнале хуже тишины. Но и «пусть растёт» — тоже:
 * именно так и накопилось 268.
 *
 * Поэтому здесь фиксируется бюджет: число немых catch на каждый файл. Логика
 * простая:
 * <ul>
 *   <li>стало меньше — тест проходит, и бюджет надо понизить (это и есть
 *       прогресс по F-07);</li>
 *   <li>стало больше — тест падает с именем файла, то есть новый немой catch
 *       не проскочит незамеченным;</li>
 * </ul>
 *
 * Критичные по данным файлы (раскладка, бэкап, кэш иконок, настройки, импорт)
 * отдельно проверяются на ноль «немых» в тех самых местах, где сбой означает
 * потерю данных или неработающую функцию.
 *
 * Тест читает исходники, а не запускает код: это проверка гигиены кода, а не
 * поведения. Относительные пути рассчитаны на запуск из каталога модуля
 * ({@code app/}) — так его запускает Gradle, — с запасным вариантом от корня
 * репозитория.
 */
public final class SilentCatchBudgetTest {

    /**
     * Бюджет по файлам. Значения сняты вручную и обязаны только уменьшаться.
     *
     * Файла нет в таблице — значит в нём немых catch быть не должно вовсе.
     */
    private static final Object[][] BUDGET = {
            {"QuickShadeHelper.java", 75},
            {"MainActivity.java", 27},
            {"DoxaizNotificationService.java", 20},
            {"AppRepository.java", 18},
            {"NetworkQualityHelper.java", 18},
            {"DoxaizLockService.java", 12},
            {"Prefs.java", 10},
            {"Diagnostics.java", 9},
            {"DynamicBarHelper.java", 9},
            {"SoundPanelHelper.java", 9},
            {"HeadsUpBannerHelper.java", 8},
            {"IconHelper.java", 8},
            {"MemoryHelper.java", 7},
            {"BiometricHelper.java", 5},
            {"AccessibilitySetup.java", 4},
            {"CustomSection.java", 4},
            {"IconDiskCache.java", 4},
            {"LayoutImporter.java", 3},
            {"AnimeBackdropView.java", 2},
            {"AnimeParticlesView.java", 2},
            {"BackupHelper.java", 2},
            {"BatteryInfoHelper.java", 2},
            {"CrashGuard.java", 2},
            {"DesktopModel.java", 2},
            {"SafeModeActivity.java", 2},
            {"ShizukuProvider.java", 2},
            {"DesktopPagerView.java", 1},
            {"RestrictedSettingsFix.java", 1},
            {"ShizukuBridge.java", 1},
            {"VectorGlyphs.java", 1},
    };

    /** Общий потолок: сегодня 270, расти не должен. */
    private static final int TOTAL_BUDGET = 270;

    private static int n;

    private static void check(boolean v, String label) {
        if (!v) throw new AssertionError(label);
        n++;
    }

    public static void main(String[] args) throws Exception {
        File dir = sourceDir();
        check(dir != null, "каталог с исходниками не найден");

        File[] files = dir.listFiles();
        check(files != null, "каталог с исходниками пуст");

        int total = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            String src = read(f);
            int count = countSilent(src);
            total += count;
            int budget = budgetFor(f.getName());
            check(count <= budget, "немых catch стало больше в " + f.getName()
                    + ": " + count + " при бюджете " + budget
                    + " — новый сбой не должен исчезать без записи в журнал");
            // Обратная сторона: если стало меньше, бюджет пора понизить, иначе
            // запас со временем снова превратится в разрешение расти.
            if (count < budget) {
                System.out.println("БЮДЖЕТ_МОЖНО_ПОНИЗИТЬ " + f.getName()
                        + ": " + count + " < " + budget);
            }
        }
        check(total <= TOTAL_BUDGET, "всего немых catch " + total
                + " при потолке " + TOTAL_BUDGET);

        // Точки, где сбой означает потерю данных: там немых catch быть не должно.
        String[] mustReport = {
                "DesktopModel.java:DesktopModel.save",
                "BackupHelper.java:BackupHelper.import",
                "IconDiskCache.java:IconDiskCache.load",
        };
        for (String spec : mustReport) {
            String[] parts = spec.split(":");
            String src = read(new File(dir, parts[0]));
            check(src.contains("Diagnostics.record(\"" + parts[1]),
                    "сбой записывается в журнал: " + parts[1]);
        }

        System.out.println("SILENT_CATCH_TOTAL=" + total);
        System.out.println("SILENT_CATCH_BUDGET_TESTS=" + n);
    }

    /** Считает именно `catch (Throwable ignored)` / `catch (Exception ignored)`. */
    private static int countSilent(String src) {
        int count = 0;
        int i = 0;
        int len = src.length();
        while (i < len) {
            int at = src.indexOf("catch", i);
            if (at < 0) break;
            i = at + 5;
            // Границей слова: иначе «catch» внутри строки или имени метода считался бы.
            if (at > 0 && Character.isJavaIdentifierPart(src.charAt(at - 1))) continue;
            int j = at + 5;
            j = skipSpaces(src, j);
            if (j >= len || src.charAt(j) != '(') continue;
            int close = src.indexOf(')', j);
            if (close < 0) break;
            String inside = src.substring(j + 1, close).trim();
            // Тип исключения и имя переменной: "Throwable ignored".
            String[] words = inside.split("\\s+");
            if (words.length == 2
                    && ("Throwable".equals(words[0]) || "Exception".equals(words[0]))
                    && ("ignored".equals(words[1]) || "ignored2".equals(words[1]))) {
                count++;
            }
            i = close;
        }
        return count;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int budgetFor(String name) {
        for (Object[] row : BUDGET) {
            if (row[0].equals(name)) return (Integer) row[1];
        }
        return 0;
    }

    private static File sourceDir() {
        String[] candidates = {
                "src/main/java/com/doxaiz/launcher",          // из app/
                "app/src/main/java/com/doxaiz/launcher",      // из корня
                "../app/src/main/java/com/doxaiz/launcher",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isDirectory()) return f;
        }
        return null;
    }

    private static String read(File f) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
