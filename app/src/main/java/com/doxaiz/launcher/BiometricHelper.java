package com.doxaiz.launcher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

/**
 * Системная проверка отпечатка пальца / лица / PIN-кода экрана Android
 * для защиты раздела «Скрытые приложения» без сторонних библиотек.
 */
public final class BiometricHelper {

    public static final int REQ_DEVICE_CREDENTIAL = 5109;

    /**
     * Отложенное действие текущей проверки: из {@link #tryConfirmCredentialFallback}
     * нельзя вернуть результат вызывающему, поэтому успешное подтверждение
     * системным PIN-кодом приходит в onActivityResult, а действие, ради которого
     * проверка запускалась, живёт здесь.
     *
     * <h3>Почему поле, а не только колбэк активности</h3>
     * На Android 8–10 (и на прошивках без рабочей биометрии) запрос уходит в
     * {@code KeyguardManager}, ответ приходит отдельным result-кодом. Раньше
     * действие хранила вызывающая активность в одном общем поле, и оно же
     * обнулялось при каждом новом запросе защиты: отмена проверки оставляла
     * «висящее» действие, а следующая проверка могла выполнить ЧУЖОЕ действие
     * (например, подтвердить PIN для скрытых приложений и получить экспорт
     * защищённой копии). Здесь действие привязано к самой проверке и снимается
     * при её запуске и при её завершении.
     */
    private static final PendingAuthAction sPendingCredential = new PendingAuthAction();

    private BiometricHelper() {}

    public static boolean isDeviceSecure(Context context) {
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceSecure();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Doxaiz 4.9: Определяет реальный аппаратный способ защиты данного устройства
     * (отпечаток/лицо + PIN, только PIN/графический ключ или отсутствие блокировки).
     */
    public static String describeAuthMethod(Context context) {
        if (context == null || !isDeviceSecure(context)) {
            return "Задайте PIN или отпечаток в настройках Android для включения защиты";
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                BiometricManager bm = context.getSystemService(BiometricManager.class);
                if (bm != null) {
                    int bio = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    if (bio == BiometricManager.BIOMETRIC_SUCCESS) {
                        return "Активна системная защита: отпечаток / лицо или код экрана";
                    }
                    return "Активна системная защита: PIN-код / пароль / графический ключ";
                }
            } catch (Throwable ignored) {}
        }
        return "Запрашивает системную биометрию или код экрана при входе";
    }

    private static boolean tryConfirmCredentialFallback(Activity activity, String title, String subtitle) {
        try {
            KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                @SuppressWarnings("deprecation")
                Intent intent = km.createConfirmDeviceCredentialIntent(title, subtitle);
                if (intent != null) {
                    activity.startActivityForResult(intent, REQ_DEVICE_CREDENTIAL);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Запрашивает системную биометрию или PIN-код устройства.
     * Если блокировка экрана отсутствует, безопасно отказывает через onCancelOrError.
     */
    public static void authenticate(
            final Activity activity,
            final String title,
            final String subtitle,
            final Runnable onSuccess,
            final Runnable onCancelOrError
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            finishWithError(onCancelOrError);
            return;
        }
        if (!isDeviceSecure(activity)) {
            finishWithError(onCancelOrError);
            return;
        }

        // Действие и отмена этой проверки фиксируются ДО любого диалога: если
        // система уйдёт в KeyguardManager, ответ придёт в onActivityResult и
        // возьмёт ровно то, что просили здесь.
        sPendingCredential.set(onSuccess, onCancelOrError);

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_WEAK
                                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        .build();
                CancellationSignal signal = new CancellationSignal();
                prompt.authenticate(signal, activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                // Диалог ответил сам: отложенное действие больше не нужно.
                                clearPendingCredentialAction();
                                if (!activity.isFinishing() && !activity.isDestroyed() && onSuccess != null) {
                                    onSuccess.run();
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                // На части OEM-прошивок без биометрии BiometricPrompt возвращает HW/NO_BIOMETRICS —
                                // автоматически переключаемся на системный PIN/графический ключ вместо отказа.
                                if ((errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                                        || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                        || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE)
                                        && !activity.isFinishing() && !activity.isDestroyed()) {
                                    if (tryConfirmCredentialFallback(activity, title, subtitle)) {
                                        return;
                                    }
                                }
                                finishWithError(onCancelOrError);
                            }
                        });
                return;
            } catch (Throwable ignored) {
                // Фоллбек на KeyguardManager ниже
            }
        } else if (Build.VERSION.SDK_INT == 29) {
            try {
                @SuppressWarnings("deprecation")
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setDeviceCredentialAllowed(true)
                        .build();
                CancellationSignal signal = new CancellationSignal();
                prompt.authenticate(signal, activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                clearPendingCredentialAction();
                                if (!activity.isFinishing() && !activity.isDestroyed() && onSuccess != null) {
                                    onSuccess.run();
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                finishWithError(onCancelOrError);
                            }
                        });
                return;
            } catch (Throwable ignored) {
            }
        }

        // Фоллбек для Android 8–8.1 или при сбое BiometricPrompt
        if (tryConfirmCredentialFallback(activity, title, subtitle)) {
            return;
        }

        // Fail-closed: устройство защищено паролем/отпечатком, но системная проверка недоступна — не обходим защиту!
        finishWithError(onCancelOrError);
    }

    /**
     * Единая точка «проверка не удалась»: снимает отложенное действие и зовёт
     * отмену. Через неё проходят ВСЕ пути отказа, иначе ожидание осталось бы
     * висеть, а следующий системный ответ выполнил бы чужое действие.
     */
    private static void finishWithError(Runnable onCancelOrError) {
        clearPendingCredentialAction();
        if (onCancelOrError != null) {
            try {
                onCancelOrError.run();
            } catch (Throwable t) {
                Diagnostics.record("BiometricHelper.onCancel", t);
            }
        }
    }

    /**
     * Итог системного подтверждения PIN-кодом (Android 8–10 и прошивки без
     * биометрии). Вызывается активностью из onActivityResult с кодом
     * {@link #REQ_DEVICE_CREDENTIAL}.
     *
     * @return true, если было отложенное действие — то есть этот результат
     *         относится к нашей проверке, а не к постороннему диалогу
     */
    public static boolean deliverCredentialResult(boolean success) {
        return sPendingCredential.deliver(success);
    }

    /** Снимает отложенное действие: новый запрос заменяет предыдущий. */
    public static void clearPendingCredentialAction() {
        sPendingCredential.clear();
    }
}
