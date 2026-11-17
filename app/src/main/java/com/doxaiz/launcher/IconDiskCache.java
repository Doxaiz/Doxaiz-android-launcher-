package com.doxaiz.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Постоянный локальный дисковый кэш оригинальных иконок приложений (Doxaiz 3.4).
 *
 * Как работает:
 * 1) Оригинальная иконка приложения (LauncherActivityInfo.getBadgedIcon(0)) 1 раз растеризуется
 *    в Bitmap и сохраняется в getCacheDir()/doxaiz_icons_v1/<hash>_<lastUpdateTime>.img.
 * 2) При последующих запусках оболочки иконки читаются напрямую из дискового кэша в 1 шаг
 *    без повторных обращений к системному PackageManager и перерисовки AdaptiveIconDrawable.
 * 3) Если приложение обновилось (изменился lastUpdateTime), старый файл удаляется автоматически.
 */
public final class IconDiskCache {

    private static final String DIR_NAME = "doxaiz_icons_v2";
    private static final String LEGACY_DIR_NAME = "doxaiz_icons_v1";
    private static final long MAX_ICON_FILE_BYTES = 512L * 1024L; // Защита от OOM на повреждённом файле
    private static final int MAX_CACHE_FILES = 450;               // Верхний предел кэша на флеш-памяти
    /** Уникальный суффикс временных файлов в пределах процесса. */
    private static final AtomicInteger TMP_SEQ = new AtomicInteger(0);
    private final File cacheDir;
    private volatile boolean hadExistingFiles = false;
    // Счётчик изменяется из save() (2–4 потока загрузки иконок) и из evict(), поэтому
    // раньше это было гонкой «++ / --» без атомарности → неверный учёт и лишние обходы каталога.
    private final AtomicInteger knownFileCount = new AtomicInteger(0);

    public IconDiskCache(Context context) {
        File rootCache = context.getApplicationContext().getCacheDir();
        this.cacheDir = new File(rootCache, DIR_NAME);
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        } else {
            // Однократная подчистка незавершённых .tmp-файлов и обрезка переросшего кэша
            try {
                File[] cur = cacheDir.listFiles();
                if (cur != null) {
                    int imgCount = 0;
                    for (File f : cur) {
                        if (f == null) continue;
                        if (f.getName().endsWith(".tmp")) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        } else if (f.isFile() && f.getName().endsWith(".img")) {
                            imgCount++;
                        }
                    }
                    hadExistingFiles = (imgCount > 0);
                    knownFileCount.set(imgCount);
                    if (imgCount > MAX_CACHE_FILES) {
                        pruneExcessFiles(cur, imgCount - MAX_CACHE_FILES);
                        recountFiles();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Doxaiz 4.2: автоматически чистим старый кэш v1, чтобы все иконки сразу обновились на ровный Squircle
        File legacyDir = new File(rootCache, LEGACY_DIR_NAME);
        if (legacyDir.isDirectory()) {
            try {
                File[] oldFiles = legacyDir.listFiles();
                if (oldFiles != null) {
                    for (File f : oldFiles) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                legacyDir.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    public Drawable load(Context ctx, String appKey, long versionStamp) {
        if (appKey == null || appKey.isEmpty()) return null;
        try {
            File f = fileFor(appKey, versionStamp);
            if (!f.isFile()) return null;
            long len = f.length();
            if (len <= 16L || len > MAX_ICON_FILE_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                removeCacheFile(f);
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (!isSafeIconDimensions(bounds.outWidth, bounds.outHeight)) {
                removeCacheFile(f);
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp != null && !bmp.isRecycled()) {
                return new BitmapDrawable(ctx.getResources(), bmp);
            }
            // Удаляем повреждённый файл
            removeCacheFile(f);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Сохранение иконки.
     *
     * Раньше метод был synchronized целиком: пока один поток кодировал Bitmap в WebP/PNG
     * (десятки миллисекунд), остальные 2–4 рабочих потока AppRepository ждали на входе,
     * то есть кодирование фактически сериализовалось. Блокировка нужна только вокруг
     * учёта файлов, а уникальность .tmp обеспечивается именем целевого файла:
     * (hash, versionStamp) уже уникальны сами по себе.
     */
    public void save(String appKey, long versionStamp, Bitmap bmp) {
        if (appKey == null || appKey.isEmpty() || bmp == null || bmp.isRecycled()) return;
        try {
            if (!cacheDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }
            String prefix = hashKey(appKey) + "_";
            File target = new File(cacheDir, prefix + Math.max(0L, versionStamp) + ".img");
            if (target.isFile() && target.length() > 16L) return;

            // Имя .tmp делаем уникальным счётчиком, а не Thread.getId(): тот помечен
            // deprecated (Java 19 / API 33), а Thread.threadId() на minSdk 26 ещё
            // отсутствует. Счётчик уникален в пределах процесса — этого достаточно.
            File tmp = new File(cacheDir, prefix + Math.max(0L, versionStamp)
                    + "_" + TMP_SEQ.incrementAndGet() + ".tmp");
            boolean ok;
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                if (Build.VERSION.SDK_INT >= 30) {
                    ok = bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, fos);
                } else {
                    ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                fos.flush();
            }
            boolean existed = target.isFile();
            if (!ok || tmp.length() <= 16L || tmp.length() > MAX_ICON_FILE_BYTES || !tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } else {
                if (!existed) knownFileCount.incrementAndGet();
                if (hadExistingFiles) cleanupStaleVersions(prefix, target.getName());
                // Держим лимит и во время долгой сессии, а не только при старте.
                int over = knownFileCount.get() - MAX_CACHE_FILES;
                if (over > 0) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        recountFiles();
                        over = knownFileCount.get() - MAX_CACHE_FILES;
                        if (over > 0) {
                            pruneExcessFiles(files, over);
                            recountFiles();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Раньше здесь был пустой catch: иконка молча не кэшировалась, и
            // пользователь видел «иконки долго грузятся» без объяснения (F-07).
            Diagnostics.record("IconDiskCache.save", t);
        }
    }

    private void pruneExcessFiles(File[] files, int removeCount) {
        if (files == null || removeCount <= 0) return;
        java.util.List<File> imgs = new java.util.ArrayList<>();
        for (File f : files) {
            if (f != null && f.isFile() && f.getName().endsWith(".img")) {
                imgs.add(f);
            }
        }
        imgs.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int lim = Math.min(removeCount, imgs.size());
        for (int i = 0; i < lim; i++) {
            //noinspection ResultOfMethodCallIgnored
            imgs.get(i).delete();
        }
    }

    private void cleanupStaleVersions(String prefix, String currentFileName) {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f != null && isStaleVersionFile(f.getName(), prefix, currentFileName)) {
                    //noinspection ResultOfMethodCallIgnored
                    if (f.delete()) decrementCount();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean isStaleVersionFile(String fileName, String prefix, String currentFileName) {
        if (fileName == null || prefix == null || currentFileName == null) return false;
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".img")
                || !currentFileName.startsWith(prefix) || !currentFileName.endsWith(".img")) return false;
        try {
            long fileVersion = Long.parseLong(fileName.substring(prefix.length(), fileName.length() - 4));
            long currentVersion = Long.parseLong(currentFileName.substring(prefix.length(), currentFileName.length() - 4));
            return fileVersion >= 0L && fileVersion < currentVersion;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public synchronized void evictByKey(String appKey) {
        if (appKey == null || appKey.isEmpty()) return;
        try {
            String prefix = hashKey(appKey) + "_";
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().startsWith(prefix)) {
                    removeCacheFile(f);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public synchronized void clearAll() {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            for (File f : files) removeCacheFile(f);
        } catch (Throwable ignored) {
        }
    }

    static boolean isSafeIconDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= 1024 && height <= 1024
                && (long) width * height <= 1024L * 1024L;
    }

    private synchronized void removeCacheFile(File f) {
        if (f != null && f.delete() && f.getName().endsWith(".img"))
            decrementCount();
    }

    /** Атомарное уменьшение счётчика без ухода в минус. */
    private void decrementCount() {
        knownFileCount.updateAndGet(v -> Math.max(0, v - 1));
    }

    private void recountFiles() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        int count = 0;
        for (File f : files) if (f.isFile() && f.getName().endsWith(".img")) count++;
        knownFileCount.set(count);
    }

    /**
     * Возвращает массив {fileCount, totalBytes}.
     */
    public long[] getStats() {
        long count = 0L;
        long bytes = 0L;
        try {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".img")) {
                        count++;
                        bytes += f.length();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new long[]{count, bytes};
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " Б";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f КБ", bytes / 1024.0);
        return String.format(Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0));
    }

    private File fileFor(String appKey, long versionStamp) {
        return new File(cacheDir, hashKey(appKey) + "_" + Math.max(0L, versionStamp) + ".img");
    }

    private static String hashKey(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(20);
            for (int i = 0; i < 10; i++) {
                sb.append(String.format(Locale.US, "%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
