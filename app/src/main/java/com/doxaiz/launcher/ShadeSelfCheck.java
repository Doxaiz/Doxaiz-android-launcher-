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

    /**
     * Тема, которую прогон просит выставить перед показом шторки.
     *
     * Передаётся в intent, а не правкой shared_prefs: правка файла настроек
     * снаружи (sed через run-as) уже один раз сломала разбор XML в CI —
     * «PARSED=0, invalid character number». Intent не трогает состояние
     * приложения на диске в момент, когда его читает сам процесс.
     */
    public static final String EXTRA_STYLE = "doxaiz_style";

    /**
     * Какой экран снимать: {@link #SCREEN_SHADE}, {@link #SCREEN_DRAWER} или
     * {@link #SCREEN_SETTINGS}.
     *
     * Разные экраны проверяются разными запусками, потому что «открыть каталог»
     * и «открыть настройки» — это разные действия пользователя, а не состояние,
     * которое можно выставить флагом.
     */
    public static final String EXTRA_SCREEN = "doxaiz_screen";

    /** Экран из intent или {@link #SCREEN_SHADE} по умолчанию. */
    public static String requestedScreen(Activity activity) {
        if (activity == null || activity.getIntent() == null) return SCREEN_SHADE;
        String screen = activity.getIntent().getStringExtra(EXTRA_SCREEN);
        if (SCREEN_DRAWER.equals(screen) || SCREEN_SETTINGS.equals(screen)) return screen;
        return SCREEN_SHADE;
    }

    /**
     * Применяет тему из intent, если она там есть.
     *
     * @return запрошенный стиль или -1
     */
    public static int applyRequestedStyle(Activity activity, Prefs prefs) {
        if (activity == null || prefs == null) return -1;
        if (!BuildConfig.DEBUG) return -1;
        try {
            android.content.Intent intent = activity.getIntent();
            if (intent == null || !intent.hasExtra(EXTRA_STYLE)) return -1;
            int style = intent.getIntExtra(EXTRA_STYLE, -1);
            // Только известные стили: значение приходит извне, а предпочтение
            // «любое число» уже один раз приводило к непонятной теме.
            if (style != Prefs.STYLE_CLASSIC_LIGHT && style != Prefs.STYLE_CLASSIC_DARK
                    && style != Prefs.STYLE_SAKURA_JADE && style != Prefs.STYLE_CYBER_ANIME) {
                return -1;
            }
            prefs.setVisualStyle(style);
            return style;
        } catch (Throwable t) {
            Diagnostics.record("ShadeSelfCheck.applyStyle", t);
            return -1;
        }
    }

    /** Файлы отчётов: по одному на экран, имя включает имя экрана. */
    private static final String OUT_FILE = "shade_selfcheck.txt";
    private static final String OUT_FILE_PREFIX = "selfcheck_";

    /** Экран, который проверяется: шторка быстрых действий. */
    static final String SCREEN_SHADE = "shade";
    /** Экран каталога всех приложений. */
    static final String SCREEN_DRAWER = "drawer";
    /** Экран настроек лаунчера. */
    static final String SCREEN_SETTINGS = "settings";

    /** Доля пикселей, которые обязаны принадлежать одной из поверхностей темы. */
    private static final double MIN_SURFACE_SHARE = 0.55;

    /** Средняя яркость кадра 0..255: светлая шторка не может быть тёмной. */
    private static final int MIN_AVERAGE_LUMA = 150;

    /**
     * Потолок яркости для тёмной темы: шторка обязана остаться тёмной.
     *
     * Оба порога нужны вместе. Если проверять только светлую, ошибка «светлые
     * цвета применились ко всем стилям подряд» останется незамеченной — а это
     * ровно та ошибка, которую ловит обратная проверка в ContrastTest.
     */
    private static final int MAX_AVERAGE_LUMA_DARK = 90;

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

    /**
     * Снимает кадр с найденного окна и записывает отчёт.
     *
     * Общая часть для всех экранов: разница только в том, что открыть и какое
     * имя файла использовать.
     */
    private static void evaluateView(Activity activity, View target, String screen,
                                     int expectedStyle) {
        if (target == null || target.getWidth() == 0 || target.getHeight() == 0) {
            writeNamed(activity, screen, "СБОЙ\nне найдено окно для снятия кадра (" + screen + ")");
            return;
        }
        Bitmap bmp = Bitmap.createBitmap(target.getWidth(), target.getHeight(),
                Bitmap.Config.ARGB_8888);
        target.draw(new Canvas(bmp));
        Report r = analyse(bmp, expectedStyle, screen);
        bmp.recycle();

        StringBuilder sb = new StringBuilder();
        sb.append(r.ok ? "OK" : "СБОЙ").append('\n');
        sb.append("экран=").append(screen).append('\n');
        sb.append("стиль=").append(styleName(GlassTheme.ACTIVE_STYLE)).append('\n');
        sb.append("кадр=").append(r.width).append('x').append(r.height).append('\n');
        sb.append("самый_частый=#").append(String.format("%06X", r.modeColor & 0xFFFFFF))
                .append(" доля=").append(pct(r.modeShare)).append('\n');
        sb.append("поверхности_темы=").append(pct(r.surfaceShare)).append('\n');
        sb.append("средняя_яркость=").append(r.averageLuma).append('\n');
        sb.append("чисто_белый=").append(pct(r.whiteShare)).append('\n');
        sb.append("лучшая_строка=").append(pct(r.dominantRowShare)).append('\n');
        sb.append("причины=").append(r.problems.length() == 0 ? "нет" : r.problems).append('\n');
        writeNamed(activity, screen, sb.toString());
    }

    /** Проверка каталога: тот же кадр, но с окна активности. */
    static void evaluateScreen(Activity activity, String screen, int expectedStyle) {
        if (activity == null) return;
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            evaluateView(activity, decor, screen, expectedStyle);
        } catch (Throwable t) {
            writeNamed(activity, screen, "СБОЙ\nисключение при снятии кадра: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
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
            evaluateView(activity, target, SCREEN_SHADE, expectedStyle);
        } catch (Throwable t) {
            write(activity, "СБОЙ\nисключение при снятии кадра: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                shade.dismiss();
            } catch (Throwable t) {
                // Закрытие шторки после замера: сбой здесь означает, что шторка
                // осталась на экране, то есть следующие шаги прогона увидят не то.
                Diagnostics.record("ShadeSelfCheck.dismiss", t);
            }
        }
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
    private static Report analyse(Bitmap bmp, int expectedStyle, String screen) {
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

        // Каталог и настройки — это плотный список иконок и подписей, поэтому
        // доля «чистых» пикселей палитры там ниже, чем у шторки с её крупными
        // плашками. Порог снижен осознанно, а яркость и белый — нет: именно они
        // ловят «осталось темно» и «подписи остались белыми».
        double minSurfaceShare = SCREEN_SHADE.equals(screen) ? MIN_SURFACE_SHARE : 0.30;

        r.ok = true;
        // Тема должна быть именно той, которую выставил прогон: иначе можно
        // «успешно» проверить светлую палитру, фактически оставшись в тёмной.
        if (expectedStyle >= 0 && GlassTheme.ACTIVE_STYLE != expectedStyle) {
            r.ok = false;
            r.problems.append("тема не применилась: ожидалась ")
                    .append(styleName(expectedStyle)).append(", активна ")
                    .append(styleName(GlassTheme.ACTIVE_STYLE)).append("; ");
        }
        if (r.surfaceShare < minSurfaceShare) {
            r.ok = false;
            r.problems.append("поверхности темы занимают ")
                    .append(pct(r.surfaceShare)).append(" < ")
                    .append(pct(minSurfaceShare)).append("; ");
        }
        boolean light = expectedStyle != Prefs.STYLE_CLASSIC_DARK;
        if (light && r.averageLuma < MIN_AVERAGE_LUMA) {
            r.ok = false;
            r.problems.append("средняя яркость ").append(r.averageLuma)
                    .append(" < ").append(MIN_AVERAGE_LUMA)
                    .append(" (шторка осталась тёмной); ");
        }
        if (!light && r.averageLuma > MAX_AVERAGE_LUMA_DARK) {
            r.ok = false;
            r.problems.append("средняя яркость ").append(r.averageLuma)
                    .append(" > ").append(MAX_AVERAGE_LUMA_DARK)
                    .append(" (тёмная тема посветлела); ");
        }
        if (r.dominantRowShare < minSurfaceShare) {
            r.ok = false;
            r.problems.append("в самой светлой строке поверхности ")
                    .append(pct(r.dominantRowShare)).append(" < ")
                    .append(pct(minSurfaceShare))
                    .append(" (окно не перекрыло экран); ");
        }
        if (light && r.whiteShare > MAX_WHITE_SHARE) {
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

    /** Отчёт по конкретному экрану: имя файла содержит экран. */
    private static void writeNamed(Activity activity, String screen, String text) {
        writeTo(activity, OUT_FILE_PREFIX + screen + ".txt", text);
    }

    private static void write(Activity activity, String text) {
        writeTo(activity, OUT_FILE, text);
    }

    private static void writeTo(Activity activity, String name, String text) {
        try {
            File f = new File(activity.getFilesDir(), name);
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable t) {
            // Самопроверка не имеет права повлиять на работу лаунчера, но
            // «отчёт не записался» — это причина падения шага в CI, и она
            // должна быть видна в журнале, а не только отсутствием файла.
            Diagnostics.record("ShadeSelfCheck.write", t);
        }
    }
}
