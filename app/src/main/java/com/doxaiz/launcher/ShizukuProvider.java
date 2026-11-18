package com.doxaiz.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Принимает binder от сервера Shizuku.
 *
 * <p>Shizuku присылает каждому приложению, объявившему провайдера с authority
 * {@code <package>.shizuku}, Binder своего сервера — метод {@code sendBinder}.
 * Дальше через него можно выполнять shell-команды (uid 2000). Провайдер
 * обязателен: без него Shizuku не знает, куда доставлять binder.
 *
 * <p>Разрешение {@code android.permission.INTERACT_ACROSS_USERS_FULL} в манифесте
 * выглядит страшно, но именно так устроен Shizuku: это разрешение есть у shell
 * (uid 2000) и нет у обычных приложений, поэтому провайдер доступен только
 * самому Shizuku и нашему процессу. Данные через него не ходят: только binder.
 */
public class ShizukuProvider extends ContentProvider {

    static final String METHOD_SEND_BINDER = "sendBinder";
    static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_SEND_BINDER.equals(method) && extras != null) {
            IBinder binder = readBinder(extras);
            if (binder != null && getContext() != null) {
                ShizukuBridge.onBinderReceived(binder, getContext().getPackageName());
            }
        }
        return new Bundle();
    }

    /**
     * Binder приходит либо как Parcelable-обёртка Shizuku (у неё есть поле
     * {@code binder}), либо напрямую. Библиотеки у нас нет, поэтому вытаскиваем
     * его через Parcel: у обёртки и у самого Binder достаточно одного и того же
     * strong binder. Это устойчиво к смене имени класса обёртки в новых версиях.
     */
    private static IBinder readBinder(Bundle extras) {
        extras.setClassLoader(ShizukuProvider.class.getClassLoader());
        try {
            Object raw = extras.getParcelable(EXTRA_BINDER);
            if (raw instanceof IBinder) return (IBinder) raw;
            if (raw != null) {
                Parcel p = Parcel.obtain();
                try {
                    ((android.os.Parcelable) raw).writeToParcel(p, 0);
                    p.setDataPosition(0);
                    IBinder inner = p.readStrongBinder();
                    if (inner != null) return inner;
                } finally {
                    p.recycle();
                }
            }
        } catch (Throwable ignored) {
            // Ниже — прямая попытка по ключу.
        }
        try {
            Object direct = extras.get(EXTRA_BINDER);
            if (direct instanceof IBinder) return (IBinder) direct;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
