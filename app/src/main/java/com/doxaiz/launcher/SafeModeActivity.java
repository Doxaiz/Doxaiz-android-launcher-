package com.doxaiz.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Аварийный экран: показывается вместо рабочего стола, если лаунчер дважды
 * подряд не смог стартовать (см. {@link CrashGuard}).
 *
 * <h3>Почему отдельная Activity, а не диалог</h3>
 * Диалог и рабочий стол живут в одном процессе. Если падение происходит при
 * построении интерфейса, диалог показать уже некому. Этот экран собран из
 * обычных TextView/LinearLayout без GlassTheme, без анимаций и без обращения
 * к раскладке: единственный источник данных — настройки (Prefs).
 *
 * <h3>Что видит пользователь</h3>
 * Понятное объяснение («лаунчер не смог запуститься»), причина из журнала
 * диагностики, если она есть, и четыре выхода: вернуться на штатный лаунчер,
 * попробовать снова, сбросить счётчик падений и открыть настройки приложения
 * (там можно снять роль HOME или очистить данные).
 *
 * Экран сознательно остаётся работоспособным при крупном системном шрифте:
 * всё внутри {@link ScrollView}, текст переносится, кнопки — на всю ширину.
 */
public class SafeModeActivity extends Activity {

    /** Передаётся из MainActivity: причина последнего сбоя, если она известна. */
    public static final String EXTRA_REASON = "reason";

    private Prefs prefs;

    /**
     * Открывает аварийный экран вместо рабочего стола.
     *
     * @param from  активность, которая его вызывает (нужна для контекста)
     * @param reason причина последнего сбоя или null, если она неизвестна
     */
    public static void show(Activity from, String reason) {
        if (from == null) return;
        try {
            Intent intent = new Intent(from, SafeModeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (reason != null && !reason.isEmpty()) intent.putExtra(EXTRA_REASON, reason);
            from.startActivity(intent);
        } catch (Throwable t) {
            Diagnostics.record("SafeMode.show", t);
            // Худший случай: экран не открылся. Тогда возвращаем пользователя
            // на штатный лаунчер — без рабочего стола он всё равно остался бы
            // с чёрным экраном.
            try {
                CrashGuard.switchToSystemLauncher(from, new Prefs(from));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        // Аварийный экран — тоже HOME-активность: если пользователь нажмёт
        // «Домой» и останется этот экран, кнопка не должна открывать падающий
        // рабочий стол. Поэтому main-фильтр здесь, а выбор лаунчера не
        // выполняется: пользователю и так предложены выходы.
        String reason = null;
        try {
            Intent intent = getIntent();
            if (intent != null) reason = intent.getStringExtra(EXTRA_REASON);
        } catch (Throwable t) {
            Diagnostics.record("SafeMode.extra", t);
        }

        setContentView(buildUi(reason));
    }

    private View buildUi(String reason) {
        int bg = Color.parseColor("#0E1116");
        int card = Color.parseColor("#171C24");
        int textPrimary = Color.parseColor("#F1F5F9");
        int textSecondary = Color.parseColor("#9AA6B2");
        int accent = Color.parseColor("#3B9EFF");
        int danger = Color.parseColor("#F87171");

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(bg);
        scroller.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Doxaiz не запустился");
        title.setTextColor(textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Рабочий стол дважды подряд не смог открыться, поэтому показан "
                + "этот экран вместо него. Данные не потеряны — раскладка и настройки на месте.");
        subtitle.setTextColor(textSecondary);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        subtitle.setLineSpacing(dp(3), 1.0f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.topMargin = dp(10);
        subLp.bottomMargin = dp(18);
        root.addView(subtitle, subLp);

        if (reason != null && !reason.isEmpty()) {
            TextView reasonView = new TextView(this);
            reasonView.setText("Последняя ошибка:\n" + reason);
            reasonView.setTextColor(danger);
            reasonView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            reasonView.setTypeface(Typeface.MONOSPACE);
            reasonView.setPadding(dp(12), dp(10), dp(12), dp(10));
            reasonView.setBackgroundColor(card);
            LinearLayout.LayoutParams rLp = matchWrap();
            rLp.bottomMargin = dp(18);
            root.addView(reasonView, rLp);
        }

        TextView actionsTitle = new TextView(this);
        actionsTitle.setText("Что можно сделать");
        actionsTitle.setTextColor(textSecondary);
        actionsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams atLp = matchWrap();
        atLp.bottomMargin = dp(8);
        root.addView(actionsTitle, atLp);

        boolean hasAlternative = CrashGuard.hasAlternativeLauncher(this, prefs);
        addButton(root, hasAlternative ? "Вернуться на штатный лаунчер" : "Открыть выбор лаунчера",
                "Системный рабочий стол — самый быстрый выход", accent, true,
                () -> {
                    boolean ok = CrashGuard.switchToSystemLauncher(this, prefs);
                    if (!ok) {
                        Toast.makeText(this, "Не удалось открыть другой лаунчер",
                                Toast.LENGTH_LONG).show();
                    }
                });

        addButton(root, "Попробовать снова", "Запустить Doxaiz ещё раз",
                textPrimary, false, this::tryAgain);

        addButton(root, "Сбросить счётчик сбоев",
                "Лаунчер снова попробует стать основным при следующем запуске",
                textPrimary, false, () -> {
                    CrashGuard.resetStreak(prefs);
                    Toast.makeText(this, "Счётчик сбоев сброшен", Toast.LENGTH_SHORT).show();
                });

        addButton(root, "Настройки приложения",
                "Снять роль «Главный экран» или очистить данные",
                textPrimary, false, () -> {
                    if (!CrashGuard.openAppSettings(this)) {
                        Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show();
                    }
                });

        TextView footer = new TextView(this);
        footer.setText("Если этот экран появляется снова, вернитесь на системный лаунчер "
                + "и сообщите об ошибке: журнал диагностики доступен в настройках Doxaiz.");
        footer.setTextColor(textSecondary);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        footer.setLineSpacing(dp(2), 1.0f);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = matchWrap();
        fLp.topMargin = dp(18);
        root.addView(footer, fLp);

        scroller.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    /** Кнопка на всю ширину: попадать по ней должно быть легко и с крупным шрифтом. */
    private void addButton(LinearLayout parent, String title, String subtitle,
                           int titleColor, boolean filled, Runnable onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(13), dp(16), dp(13));
        box.setBackgroundColor(filled ? Color.parseColor("#12304F") : Color.parseColor("#171C24"));
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> onClick.run());

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(titleColor);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(Color.parseColor("#9AA6B2"));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        s.setLineSpacing(dp(2), 1.0f);
        box.addView(s);

        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(9);
        parent.addView(box, lp);
    }

    /**
     * Перезапуск лаунчера. Именно новый запуск MainActivity, а не finish():
     * пользователь хочет увидеть рабочий стол, если сбой был разовым.
     * Счётчик не сбрасывается — если падение повторится, вернётся этот экран.
     */
    private void tryAgain() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Throwable t) {
            Diagnostics.record("SafeMode.tryAgain", t);
            Toast.makeText(this, "Не удалось запустить лаунчер", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * На Android 13+ системная кнопка «Назад» на HOME-экране обычно ничего не
     * делает; здесь она тоже ничего не ломает — экран остаётся на месте.
     */
    @Override
    public void onBackPressed() {
        // Намеренно ничего: выходить из аварийного экрана «в никуда» нельзя.
    }

    /** Аварийный экран не должен зависеть от темы приложения и её анимаций. */
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                getWindow().setStatusBarColor(Color.parseColor("#0E1116"));
                getWindow().setNavigationBarColor(Color.parseColor("#0E1116"));
            } catch (Throwable ignored) {
            }
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
