package com.doxaiz.launcher.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Генератор профилей для Doxaiz (AUDIT §54).
 *
 * <p>Что делает: запускает холодный старт и несколько основных действий,
 * записывает, какие методы реально исполнялись, и складывает результат в
 * {@code baseline-prof.txt} и {@code startup-prof.txt}. ART компилирует эти
 * методы заранее, поэтому первый запуск у пользователя не тратит время на
 * интерпретацию.
 *
 * <p><b>Почему пути именно такие.</b> Сценарий повторяет то, что делает
 * пользователь при первом запуске: рабочий стол, затем открытие каталога. Если
 * профилировать только старт, каталог останется некомпилированным, и первое
 * открытие будет медленным — а это второй по частоте экран.
 *
 * <p><b>Чего профиль не делает.</b> Он не ускоряет то, чего нет в сценарии: если
 * добавить новый тяжёлый экран и не добавить его сюда, он останется без
 * оптимизации. Поэтому {@code docs/maintenance/AUDIT.md} §54 требует обновлять
 * этот файл вместе с новыми экранами.
 *
 * <p><b>Как запускать.</b> {@code ./gradlew :app:generateReleaseBaselineProfile}
 * на устройстве или эмуляторе API 34. В обычном CI не запускается: это минуты
 * прогона, а не секунды.
 */
@RunWith(AndroidJUnit4.class)
public class BaselineProfileGenerator {

    @Rule
    public final BaselineProfileRule rule = new BaselineProfileRule();

    /** Имя пакета приложения: то же, что applicationId в :app. */
    private static final String PACKAGE_NAME = "com.doxaiz.launcher";

    /**
     * Холодный старт + открытие каталога.
     *
     * <p>{@code includeInStartupProfile = true} — это и есть «startup»-часть:
     * её build-система использует для оптимизации раскладки DEX, что по
     * документации даёт 15–30 % сверх обычного профиля.
     */
    @Test
    public void startupAndDrawer() {
        rule.collect(
                PACKAGE_NAME,
                10,     // до 10 итераций: профиль получается устойчивее
                5,      // стабильные итерации — когда список методов перестал расти
                true,   // includeInStartupProfile
                profileBlock -> {
                    UiDevice device = androidx.test.platform.app.InstrumentationRegistry
                            .getInstrumentation().getUiAutomation() != null
                            ? androidx.test.uiautomator.UiDevice.getInstance(
                                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
                            : null;
                    // Сначала рабочий стол: это и есть холодный старт.
                    pressHome();
                    startActivityAndWait();

                    if (device != null) {
                        device.waitForIdle(2_000);
                        // Каталог: второй по частоте экран. Жест тот же, что у
                        // пользователя — свайп вверх по рабочему столу.
                        try {
                            device.swipe(device.getDisplayWidth() / 2,
                                    (int) (device.getDisplayHeight() * 0.7),
                                    device.getDisplayWidth() / 2,
                                    (int) (device.getDisplayHeight() * 0.25),
                                    30);
                        } catch (Throwable ignored) {
                            // Свайп — не цель профиля: если жест не прошёл,
                            // профиль всё равно корректен, просто без каталога.
                        }
                        device.waitForIdle(2_000);
                    }
                });
    }
}
