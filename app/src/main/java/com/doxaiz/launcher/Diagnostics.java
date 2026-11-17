package com.doxaiz.launcher;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Диагностический журнал сбоев, которые код сознательно проглатывает.
 *
 * Зачем это нужно (находка F-07 аудита). В проекте больше сотни `catch (Throwable)`
 * без какой-либо реакции: иконка не записалась, бэкап не прочитался, кэш не
 * очистился — и снаружи это выглядит как «функция просто не работает». Найти
 * причину на телефоне пользователя было невозможно: ни ошибки, ни следа.
 *
 * Требования, которые определили устройство класса:
 *  - не может сам уронить приложение: любая проблема внутри методы глушится;
 *  - не создаёт заметных задержек: размер ограничен, запись редкая (не чаще
 *    раза в 300 мс), всё остальное время — только память;
 *  - не мешает работе: это не печать в logcat (её нет ни в одном месте проекта),
 *    а собственный кольцевой буфер, который видно на экране диагностики;
 *  - не хранит чувствительных данных: только имя компонента, класс исключения
 *    и короткое сообщение, без путей пользовательских файлов и без имён
 *    скрытых приложений (ключи приложений обрезаются до типа компонента).
 *
 * Хранение: SharedPreferences (последние {@link #MAX_ENTRIES} записей), чтобы
 * журнал пережил перезапуск лаунчера, — сбой мог случиться и до него.
 */
public final class Diagnostics {

    /** Сколько записей хранится. Кольцевой буфер: старые вытесняются. */
    private static final int MAX_ENTRIES = 40;

    /** Не чаще одного сброса на диск в столько миллисекунд (защита от шторма). */
    private static final long FLUSH_INTERVAL_MS = 300L;

    /** Максимальная длина сообщения в записи — лишнее обрезается. */
    private static final int MAX_MESSAGE_LEN = 120;

    private static final String PREF_KEY = "diagnostics_log_v1";
    private static final String SEP = "\u0001";
    private static final String FIELD = "\u0002";

    /** Счётчики по компонентам: сколько раз сбой повторился. */
    private static final Object LOCK = new Object();
    private static final List<Entry> BUFFER = new ArrayList<>();
    private static SharedPreferences storage;
    private static long lastFlushMs = 0L;
    private static boolean loaded = false;

    private Diagnostics() {}

    /** Одна запись журнала. Неизменяемая. */
    public static final class Entry {
        public final long timeMs;
        public final String component;
        public final String errorType;
        public final String message;

        Entry(long timeMs, String component, String errorType, String message) {
            this.timeMs = timeMs;
            this.component = component;
            this.errorType = errorType;
            this.message = message;
        }

        /** Строка для экрана диагностики: «12:34:05 · IconDiskCache · IOException: ...». */
        public String format() {
            String time;
            try {
                time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(timeMs));
            } catch (Throwable t) {
                time = "--:--:--";
            }
            StringBuilder sb = new StringBuilder(96);
            sb.append(time).append("  ").append(component).append("  ").append(errorType);
            if (!message.isEmpty()) sb.append(": ").append(message);
            return sb.toString();
        }
    }

    /**
     * Регистрирует сбой. Метод намеренно «всеядный»: он вызывается из мест, где
     * приложение уже находится в нештатной ситуации, поэтому любая ошибка здесь
     * должна быть проигнорирована, а не выброшена наружу.
     *
     * @param component короткое имя места сбоя, например "IconDiskCache.save"
     * @param error     исключение (может быть null)
     */
    public static void record(String component, Throwable error) {
        try {
            if (component == null || component.isEmpty()) component = "unknown";
            String errorType = (error == null) ? "Error" : error.getClass().getSimpleName();
            String message = (error == null) ? "" : safeMessage(error);
            Entry entry = new Entry(System.currentTimeMillis(), component, errorType, message);
            boolean needFlush;
            synchronized (LOCK) {
                BUFFER.add(entry);
                while (BUFFER.size() > MAX_ENTRIES) {
                    BUFFER.remove(0);
                }
                long now = System.currentTimeMillis();
                needFlush = (now - lastFlushMs) >= FLUSH_INTERVAL_MS;
                if (needFlush) lastFlushMs = now;
            }
            if (needFlush) persistAsync();
        } catch (Throwable ignored) {
            // Диагностика не имеет права влиять на работу приложения.
        }
    }

    /** Привязывает хранилище и поднимает журнал с диска (вызывается один раз при старте). */
    public static void init(SharedPreferences prefs) {
        if (prefs == null) return;
        synchronized (LOCK) {
            storage = prefs;
            if (loaded) return;
            loaded = true;
        }
        try {
            String raw = prefs.getString(PREF_KEY, "");
            if (raw == null || raw.isEmpty()) return;
            List<Entry> parsed = parse(raw);
            synchronized (LOCK) {
                // То, что успело записаться в память до загрузки, должно остаться.
                if (!parsed.isEmpty()) {
                    parsed.addAll(BUFFER);
                    BUFFER.clear();
                    BUFFER.addAll(parsed);
                    while (BUFFER.size() > MAX_ENTRIES) {
                        BUFFER.remove(0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Снимок журнала: новые записи сверху. */
    public static List<Entry> getAll() {
        synchronized (LOCK) {
            List<Entry> copy = new ArrayList<>(BUFFER);
            java.util.Collections.reverse(copy);
            return copy;
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return BUFFER.size();
        }
    }

    /** Очистка журнала (кнопка на экране диагностики). */
    public static void clear() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
        SharedPreferences sp;
        synchronized (LOCK) {
            sp = storage;
        }
        try {
            if (sp != null) sp.edit().remove(PREF_KEY).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Короткое сообщение исключения без чувствительных деталей.
     *
     * Обрезаем путь к файлу: в сообщениях FileNotFoundException приходят полные
     * пути, а в них бывают имена скрытых приложений и расположение личных файлов.
     */
    private static String safeMessage(Throwable error) {
        String msg;
        try {
            msg = error.getMessage();
        } catch (Throwable t) {
            return "";
        }
        if (msg == null) return "";
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        // Убираем абсолютные пути: оставляем только имя файла.
        msg = msg.replaceAll("(/[^\\s:]+/)+([^\\s/:]+)", "$2");
        if (msg.length() > MAX_MESSAGE_LEN) msg = msg.substring(0, MAX_MESSAGE_LEN) + "…";
        return msg;
    }

    private static void persistAsync() {
        SharedPreferences sp;
        String payload;
        synchronized (LOCK) {
            sp = storage;
            if (sp == null) return;
            payload = serialize();
        }
        try {
            sp.edit().putString(PREF_KEY, payload).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String serialize() {
        StringBuilder sb = new StringBuilder(BUFFER.size() * 64);
        for (Entry e : BUFFER) {
            sb.append(e.timeMs).append(FIELD)
              .append(e.component).append(FIELD)
              .append(e.errorType).append(FIELD)
              .append(e.message).append(SEP);
        }
        return sb.toString();
    }

    private static List<Entry> parse(String raw) {
        List<Entry> result = new ArrayList<>();
        for (String chunk : raw.split(SEP)) {
            if (chunk.isEmpty()) continue;
            String[] parts = chunk.split(FIELD, -1);
            if (parts.length < 4) continue;
            try {
                long time = Long.parseLong(parts[0]);
                result.add(new Entry(time, parts[1], parts[2], parts[3]));
            } catch (Throwable ignored) {
                // Битая запись — просто пропускаем, журнал не должен из-за неё падать.
            }
        }
        return result;
    }
}
