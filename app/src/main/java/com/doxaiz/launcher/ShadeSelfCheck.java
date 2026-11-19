package com.doxaiz.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Самопроверка светлой шторки: не расчёт контраста, а фактические пиксели.
 *
 * <h3>Зачем это нужно отдельно от теста</h3>
 * {@code tests/ContrastTest.java} проверяет <em>числа палитры</em>. Это ловит
 * ошибку в цвете, но не ловит ошибку в применении: если поверх светлой палитры
 * где-то остался литерал {@code 0xFF000000} или {@code Color.WHITE}, расчёт
 * останется зелёным, а пользователь увидит чёрную шторку. Именно так и вышло в
 * §37: палитра была светлой, а корни диалогов — нет.
 *
 * <p>Поэтому здесь шторка <b>действительно открывается</b>, её вью-дерево
 * рисуется в {@link Bitmap} и полученные пиксели сравниваются с палитрой темы:
 * <ul>
 *   <li>самый частый цвет кадра — это одна из поверхностей темы (а не чёрный);</li>
 *   <li>средняя яркость кадра светлая — шторка не «наполовину тёмная»;</li>
 *   <li>в кадре нет заметной доли чистого белого — иначе подписи остались белыми.</li>
 * </ul>
 *
 * <h3>Как запускается</h3>
 * Только в debug-сборке: {@code adb shell am start -n com.doxaiz.launcher/.MainActivity
 * --ez doxaiz_shade_selfcheck true}. Приложение само открывает шторку, снимает
 * кадр, пишет результат в {@code files/shade_selfcheck.txt} и закрывает шторку.
 * Эмуляторный прогон CI читает этот файл и валит шаг, если проверка не прошла.
 *
 * <p>Зачем писать в файл, а не в logcat: {@code run-as} доступен и работает,
 * а логи в CI читаются хуже; файл же становится артефактом прогона.
 */
public final class ShadeSelfCheck {

    /** Имя extras-флага. Строка одна и та же здесь и в MainActivity. */
    public static final String EXTRA_RUN = "doxaiz_shade_selfcheck";

    private static final String OUT_FILE = "shade_selfcheck.txt";

    /** Доля пикселей, которые обязаны принадлежать одной из поверхностей темы. */
    private static final double MIN_SURFACE_SHARE = 0.55;

    /** Средняя яркость кадра 0..255: светлая шторка не может быть тёмной. */
    private static final int MIN_AVERAGE_LUMA = 150;

    /** Доля чистого белого: подписи/иконки не должны остаться белыми. */
    private static final double MAX_WHITE_SHARE = 0.08;

    private ShadeSelfCheck() {}

    /**
     * Планирует проверку после показа рабочего стола.
     *
     * @param activity активность лаунчера (нужна для открытия шторки)
     * @param shade    помощник шторки
     */
    public static void schedule(final Activity activity, final QuickShadeHelper shade) {
        schedule(activity, shade, -1);
    }

    /**
     * То же, но с ожидаемой темой: проверка провалится, если приложение
     * оказалось не в той теме, которую выставил прогон.
     *
     * @param expectedStyle {@link Prefs#STYLE_*} или -1, если тема не важна
     */
    public static void schedule(final Activity activity, final QuickShadeHelper shade,
                                final int expectedStyle) {
        if (activity == null || shade == null) return;
        if (!BuildConfig.DEBUG) return;
        Handler handler = new Handler(Looper.getMainLooper());
        // Пауза нужна, чтобы рабочий стол успел показаться и шторка открылась
        // поверх готового окна: иначе кадр снимается с пустого root.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    shade.show(QuickShadeHelper.TAB_CONTROL);
                } catch (Throwable t) {
                    write(activity, "СБОЙ\nне удалось открыть шторку: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                    return;
                }
                // Один кадр на построение содержимого, второй — на отрисовку.
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        evaluate(activity, shade, expectedStyle);
                    }
                }, 700L);
            }
        }, 1200L);
    }

    private static void evaluate(Activity activity, QuickShadeHelper shade, int expectedStyle) {
        try {
            View root = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (root == null || !shade.isShowing()) {
                write(activity, "СБОЙ\nшторка не открылась: root=" + (root != null)
                        + " isShowing=" + shade.isShowing());
                return;
            }
            // Снимаем не рабочий стол, а именно окно шторки: у Dialog своё окно,
            // и decorView активности его не содержит.
            View target = findShadeRoot(activity);
            if (target == null || target.getWidth() == 0 || target.getHeight() == 0) {
                write(activity, "СБОЙ\nне найден корень шторки для снятия кадра");
                return;
            }

            Bitmap bmp = Bitmap.createBitmap(target.getWidth(), target.getHeight(),
                    Bitmap.Config.ARGB_8888);
            target.draw(new Canvas(bmp));

            Report r = analyse(bmp, expectedStyle);
            bmp.recycle();

            StringBuilder sb = new StringBuilder();
            sb.append(r.ok ? "OK" : "СБОЙ").append('\n');
            sb.append("стиль=").append(styleName(GlassTheme.ACTIVE_STYLE)).append('\n');
            sb.append("кадр=").append(r.width).append('x').append(r.height).append('\n');
            sb.append("самый_частый=#").append(String.format("%06X", r.modeColor & 0xFFFFFF))
                    .append(" доля=").append(pct(r.modeShare)).append('\n');
            sb.append("поверхности_темы=").append(pct(r.surfaceShare)).append('\n');
            sb.append("средняя_яркость=").append(r.averageLuma).append('\n');
            sb.append("чисто_белый=").append(pct(r.whiteShare)).append('\n');
            sb.append("лучшая_строка=").append(pct(r.dominantRowShare)).append('\n');
            sb.append("причины=").append(r.problems.isEmpty() ? "нет" : r.problems).append('\n');
            if (!r.ok) {
                // Подсказка для того, кто читает отчёт в CI: чаще всего причина
                // не в палитре, а в том, что снят не тот кадр.
                sb.append("подсказка=если кадр снят не с окна шторки, включите в эмуляторе ")
                        .append("отладочные макеты окон (debug layout) — иначе decorView ")
                        .append("отдаёт содержимое рабочего стола\n");
            }
            write(activity, sb.toString());
        } catch (Throwable t) {
            write(activity, "СБОЙ\nисключение при снятии кадра: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                shade.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Ищет корень шторки среди окон процесса.
     *
     * Диалог шторки живёт в отдельном окне, поэтому {@code decorView} активности
     * для кадра не подходит: на нём рабочего стола уже нет — сверху окно шторки.
     * {@code WindowManagerGlobal} не входит в публичный SDK, но список окон там
     * единственный способ получить без рефлексии по иерархии. Поэтому обход
     * идёт по нему, а при недоступности — запасной путь через decorView
     * активности (проверка тогда честно провалится, а не соврёт «OK»).
     */
    private static View findShadeRoot(Activity activity) {
        View best = null;
        int bestArea = 0;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object instance = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object views = f.get(instance);
            if (views instanceof java.util.List) {
                for (Object o : (java.util.List<?>) views) {
                    if (!(o instanceof View)) continue;
                    View v = (View) o;
                    if (!v.isShown() || v.getWidth() == 0 || v.getHeight() == 0) continue;
                    // Окно шторки — полноэкранное и не является окном активности,
                    // то есть его корень не совпадает с decorView активности.
                    View activityDecor = activity.getWindow() != null
                            ? activity.getWindow().getDecorView() : null;
                    if (v == activityDecor) continue;
                    int area = v.getWidth() * v.getHeight();
                    if (area > bestArea) {
                        bestArea = area;
                        best = v;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Ниже — запасной путь.
        }
        if (best != null) return best;
        return activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
    }

    private static final class Report {
        int width, height;
        int modeColor;
        double modeShare;
        double surfaceShare;
        int averageLuma;
        double whiteShare;
        double dominantRowShare;
        boolean ok;
        final StringBuilder problems = new StringBuilder();
    }

    /**
     * Считает статистику кадра и сверяет её с палитрой активной темы.
     *
     * Пиксель считается «поверхностью темы», если он близок к одной из них:
     * фону шторки, карточке, полю, плитке или акценту. Допуск 12 на канал —
     * это сглаживание при отрисовке текста и скруглений.
     */
    private static Report analyse(Bitmap bmp, int expectedStyle) {
        Report r = new Report();
        r.width = bmp.getWidth();
        r.height = bmp.getHeight();

        int[] surfaces = {
                GlassTheme.SHADE_BG, GlassTheme.POPUP_BG, GlassTheme.SURFACE_ELEVATED,
                GlassTheme.SHADE_CARD, GlassTheme.SHADE_FIELD, GlassTheme.SHADE_TILE_OFF,
                GlassTheme.TILE_INACTIVE, GlassTheme.TILE_SUB_BG,
                GlassTheme.TILE_ACTIVE_BLUE, GlassTheme.TILE_ACTIVE_GREEN,
        };

        // Самая «поверхностная» строка: если окно шторки не перекрыло экран,
        // в кадре будет полоса чужого контента, и общая доля поверхностей
        // просядет — но по одной строке это видно точнее.
        double bestRowShare = 0;

        // Гистограмма по квантованному цвету: нужно только «самый частый».
        java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<>();
        long lumaSum = 0;
        int total = 0;
        int onSurface = 0;
        int white = 0;

        for (int y = 0; y < r.height; y += 3) {
            int rowTotal = 0;
            int rowSurface = 0;
            for (int x = 0; x < r.width; x += 3) {
                int c = bmp.getPixel(x, y);
                int rgb = c & 0xFFFFFF;
                total++;
                rowTotal++;
                lumaSum += luma(rgb);
                if (isNear(rgb, Color.WHITE, 10)) white++;
                if (matchesAny(rgb, surfaces, 12)) {
                    onSurface++;
                    rowSurface++;
                }
                Integer key = rgb;
                Integer prev = hist.get(key);
                hist.put(key, prev == null ? 1 : prev + 1);
            }
            if (rowTotal > 0) {
                double share = rowSurface / (double) rowTotal;
                if (share > bestRowShare) bestRowShare = share;
            }
        }
        r.dominantRowShare = bestRowShare;
        if (total == 0) {
            r.ok = false;
            r.problems.append("пустой кадр; ");
            return r;
        }

        int bestColor = 0;
        int bestCount = -1;
        for (java.util.Map.Entry<Integer, Integer> e : hist.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestColor = e.getKey();
            }
        }
        r.modeColor = bestColor;
        r.modeShare = bestCount / (double) total;
        r.surfaceShare = onSurface / (double) total;
        r.averageLuma = (int) (lumaSum / total);
        r.whiteShare = white / (double) total;

        r.ok = true;
        // Тема должна быть именно той, которую выставил прогон: иначе можно
        // «успешно» проверить светлую палитру, фактически оставшись в тёмной.
        if (expectedStyle >= 0 && GlassTheme.ACTIVE_STYLE != expectedStyle) {
            r.ok = false;
            r.problems.append("тема не применилась: ожидалась ")
                    .append(styleName(expectedStyle)).append(", активна ")
                    .append(styleName(GlassTheme.ACTIVE_STYLE)).append("; ");
        }
        if (r.surfaceShare < MIN_SURFACE_SHARE) {
            r.ok = false;
            r.problems.append("поверхности темы занимают ")
                    .append(pct(r.surfaceShare)).append(" < ")
                    .append(pct(MIN_SURFACE_SHARE)).append("; ");
        }
        if (r.averageLuma < MIN_AVERAGE_LUMA) {
            r.ok = false;
            r.problems.append("средняя яркость ").append(r.averageLuma)
                    .append(" < ").append(MIN_AVERAGE_LUMA)
                    .append(" (шторка осталась тёмной); ");
        }
        if (r.dominantRowShare < MIN_SURFACE_SHARE) {
            r.ok = false;
            r.problems.append("в самой светлой строке поверхности ")
                    .append(pct(r.dominantRowShare)).append(" < ")
                    .append(pct(MIN_SURFACE_SHARE))
                    .append(" (окно не перекрыло экран); ");
        }
        if (r.whiteShare > MAX_WHITE_SHARE) {
            r.ok = false;
            r.problems.append("чистого белого ").append(pct(r.whiteShare))
                    .append(" > ").append(pct(MAX_WHITE_SHARE))
                    .append(" (подписи остались белыми); ");
        }
        return r;
    }

    private static boolean matchesAny(int rgb, int[] colors, int tolerance) {
        for (int c : colors) {
            if (isNear(rgb, c & 0xFFFFFF, tolerance)) return true;
        }
        return false;
    }

    private static boolean isNear(int a, int b, int tolerance) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tolerance
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tolerance
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= tolerance;
    }

    /** Яркость по WCAG (0..255), чтобы порог был сопоставим с тестом контраста. */
    private static int luma(int rgb) {
        double r = channel((rgb >> 16) & 0xFF);
        double g = channel((rgb >> 8) & 0xFF);
        double b = channel(rgb & 0xFF);
        return (int) Math.round(255 * (0.2126 * r + 0.7152 * g + 0.0722 * b));
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Имя стиля строкой: число в отчёте ничего не говорит тому, кто его читает. */
    static String styleName(int style) {
        switch (style) {
            case Prefs.STYLE_CLASSIC_LIGHT: return "светлая";
            case Prefs.STYLE_CLASSIC_DARK:  return "графит";
            case Prefs.STYLE_SAKURA_JADE:   return "сакура";
            case Prefs.STYLE_CYBER_ANIME:   return "кибер";
            default: return "стиль-" + style;
        }
    }

    private static String pct(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static void write(Activity activity, String text) {
        try {
            File f = new File(activity.getFilesDir(), OUT_FILE);
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
            // Самопроверка не имеет права повлиять на работу лаунчера.
        }
    }
}
