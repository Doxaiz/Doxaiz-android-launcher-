package com.doxaiz.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Современная панель звука с быстрым переходом в дополнительное меню настройки звука:
 * - Компактная вертикальная капсула: режим звонка + ползунок мультимедиа + кнопка «•••»
 * - Доп. меню настройки звука: 4 независимых вертикальных ползунка реальных потоков Android
 *   (Мультимедиа, Звонок, Уведомления, Будильник), быстрые режимы (Звонок / Вибро / Без звука)
 *   и переход в системные параметры звука и эквалайзера.
 */
public final class SoundPanelHelper {

    public interface Callbacks {
        void onDismiss();
        void onOpenExpanded();
        void onRefreshExpanded();
        void onRefreshCompact();
        default void onUserInteraction() {}
    }

    private SoundPanelHelper() {}

    /**
     * Компактная вертикальная панель громкости с кнопкой «•••» для открытия доп. меню звука.
     */
    public static View buildCompactPopup(
            final Context ctx,
            final GlassTheme theme,
            final Callbacks cb
    ) {
        return buildCompactPopup(ctx, theme, true, cb);
    }

    public static View buildCompactPopup(
            final Context ctx,
            final GlassTheme theme,
            final boolean hapticEnabled,
            final Callbacks cb
    ) {
        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int pct = getStreamPercent(am, AudioManager.STREAM_MUSIC);
        int ringer = getRingerModeSafe(am);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10));
        card.setBackground(GlassTheme.makeStyledCard(
                ctx, theme.dialogBgColor, theme.dialogBorderColor, 26f, 1f, true
        ));

        // Верхняя кнопка переключения режима (Звонок / Вибро / Без звука) — векторная иконка
        FrameLayout modeBtn = new FrameLayout(ctx);
        modeBtn.setBackground(GlassTheme.makeGlassRect(
                ctx,
                ringer == AudioManager.RINGER_MODE_NORMAL ? theme.chipActiveBg : theme.cardBgColor,
                theme.cardBorderColor, 18f, 1f
        ));
        ImageView mIv = VectorGlyphs.makeIconView(ctx, ringerGlyphName(ringer), 18,
                ringer == AudioManager.RINGER_MODE_NORMAL ? theme.chipActiveText : theme.textPrimary);
        FrameLayout.LayoutParams modeIconLp = new FrameLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18));
        modeIconLp.gravity = Gravity.CENTER;
        modeBtn.addView(mIv, modeIconLp);
        modeBtn.setOnClickListener(v -> {
            cb.onUserInteraction();
            if (hapticEnabled) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            cycleRingerMode(ctx, am);
            cb.onRefreshCompact();
        });
        card.addView(modeBtn, new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)));

        // Процент (создаём заранее, чтобы живой % обновлялся прямо во время движения пальца!)
        final TextView pctTv = new TextView(ctx);
        pctTv.setText(pct + "%");
        pctTv.setTextColor(theme.textSecondary);
        pctTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);

        // Вертикальный ползунок мультимедиа с мгновенным обновлением процента
        View slider = buildVerticalBar(ctx, theme, am, AudioManager.STREAM_MUSIC, "music",
                dp(ctx, 38), dp(ctx, 148), pctTv, hapticEnabled, cb::onUserInteraction, cb::onRefreshCompact);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 148));
        sLp.topMargin = dp(ctx, 8);
        sLp.bottomMargin = dp(ctx, 8);
        card.addView(slider, sLp);

        card.addView(pctTv);

        // Кнопка «•••» — открывает доп. меню с настройкой всех потоков звука
        TextView moreBtn = new TextView(ctx);
        moreBtn.setText("•••");
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        moreBtn.setTextColor(theme.textPrimary);
        moreBtn.setBackground(GlassTheme.makeGlassRect(
                ctx, theme.cardBgColor, theme.cardBorderColor, 18f, 1f
        ));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 34));
        mLp.topMargin = dp(ctx, 8);
        moreBtn.setOnClickListener(v -> cb.onOpenExpanded());
        card.addView(moreBtn, mLp);

        return card;
    }

    /**
     * Расширенное дополнительное меню настройки звука (4 вертикальных канала + режимы + системные параметры).
     */
    public static View buildExpandedPanel(
            final Context ctx,
            final GlassTheme theme,
            final Callbacks cb
    ) {
        return buildExpandedPanel(ctx, theme, true, cb);
    }

    public static View buildExpandedPanel(
            final Context ctx,
            final GlassTheme theme,
            final boolean hapticEnabled,
            final Callbacks cb
    ) {
        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int ringer = getRingerModeSafe(am);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        box.setPadding(pad, dp(ctx, 16), pad, dp(ctx, 14));
        box.setBackground(GlassTheme.makeStyledCard(
                ctx, theme.dialogBgColor, theme.dialogBorderColor, 24f, 1f, true
        ));

        // Шапка
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("Настройка звука");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView modeLbl = new TextView(ctx);
        boolean fixedVol = isVolumeFixedSafe(am);
        modeLbl.setText(fixedVol ? "Фикс. громкость" : ringerLabel(ringer));
        modeLbl.setTextColor(fixedVol ? GlassTheme.ACCENT_AMBER : theme.textSecondary);
        modeLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hdr.addView(modeLbl);
        box.addView(hdr);

        // 4 вертикальных столбика: Мультимедиа, Звонок, Уведомления, Будильник (компактнее в landscape)
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        int barH = dp(ctx, landscape ? 96 : 138);

        LinearLayout barsRow = new LinearLayout(ctx);
        barsRow.setOrientation(LinearLayout.HORIZONTAL);
        barsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        brLp.topMargin = dp(ctx, landscape ? 8 : 14);
        brLp.bottomMargin = dp(ctx, landscape ? 8 : 14);
        barsRow.setLayoutParams(brLp);

        int[] streams = new int[]{
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_ALARM
        };
        String[] glyphs = new String[]{"music", "phone", "bell", "sun"};
        String[] names = new String[]{"Медиа", "Звонок", "Уведомл.", "Будильник"};

        for (int i = 0; i < streams.length; i++) {
            final int st = streams[i];
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pctTv = new TextView(ctx);
            pctTv.setText(getStreamPercent(am, st) + "%");
            pctTv.setTextColor(theme.textSecondary);
            pctTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            pLp.bottomMargin = dp(ctx, 6);
            col.addView(pctTv, pLp);

            View bar = buildVerticalBar(ctx, theme, am, st, glyphs[i], dp(ctx, 48), barH,
                    pctTv, hapticEnabled, cb::onUserInteraction, cb::onRefreshExpanded);
            col.addView(bar, new LinearLayout.LayoutParams(dp(ctx, 48), barH));

            TextView lbl = new TextView(ctx);
            lbl.setText(names[i]);
            lbl.setTextColor(theme.textPrimary);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            lbl.setSingleLine(true);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lLp.topMargin = dp(ctx, 6);
            col.addView(lbl, lLp);

            barsRow.addView(col);
        }
        box.addView(barsRow);

        // Кнопки режима звука (Вибро показывается только при наличии физического вибромотора)
        boolean hasVib = hasHardwareVibrator(ctx);
        LinearLayout modesRow = new LinearLayout(ctx);
        modesRow.setOrientation(LinearLayout.HORIZONTAL);
        modesRow.addView(makeModeChip(ctx, theme, "sound", "Звонок",
                ringer == AudioManager.RINGER_MODE_NORMAL, false, () -> {
                    setRingerModeSafe(ctx, am, AudioManager.RINGER_MODE_NORMAL);
                    cb.onRefreshExpanded();
                }));
        if (hasVib) {
            modesRow.addView(makeModeChip(ctx, theme, "vibrate", "Вибро",
                    ringer == AudioManager.RINGER_MODE_VIBRATE, true, () -> {
                        setRingerModeSafe(ctx, am, AudioManager.RINGER_MODE_VIBRATE);
                        cb.onRefreshExpanded();
                    }));
        }
        modesRow.addView(makeModeChip(ctx, theme, "mute", "Тихо",
                ringer == AudioManager.RINGER_MODE_SILENT, true, () -> {
                    setRingerModeSafe(ctx, am, AudioManager.RINGER_MODE_SILENT);
                    cb.onRefreshExpanded();
                }));
        box.addView(modesRow);

        // Нижние кнопки: Системные настройки звука + Готово
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fLp.topMargin = dp(ctx, 12);
        foot.setLayoutParams(fLp);

        TextView sysBtn = new TextView(ctx);
        sysBtn.setText("Все настройки звука и эквалайзера >");
        sysBtn.setTextColor(theme.textSecondary);
        sysBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sysBtn.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        sysBtn.setOnClickListener(v -> {
            cb.onDismiss();
            try {
                Intent i = new Intent(Settings.ACTION_SOUND_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
        });
        foot.addView(sysBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView doneBtn = new TextView(ctx);
        doneBtn.setText("Готово");
        doneBtn.setTextColor(theme.accentColor);
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        doneBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        doneBtn.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8));
        doneBtn.setOnClickListener(v -> cb.onDismiss());
        foot.addView(doneBtn);
        box.addView(foot);

        return box;
    }

    private static View buildVerticalBar(
            final Context ctx,
            final GlassTheme theme,
            final AudioManager am,
            final int streamType,
            final String glyph,
            final int widthPx,
            final int heightPx,
            final TextView livePctView,
            final boolean hapticEnabled,
            final Runnable onInteraction,
            final Runnable onChanged
    ) {
        final FrameLayout wrap = new FrameLayout(ctx);
        wrap.setBackground(GlassTheme.makeGlassRect(
                ctx, theme.cardBgColor, theme.cardBorderColor, 18f, 1f
        ));
        wrap.setClipToOutline(true);

        int pct = getStreamPercent(am, streamType);
        final View fill = new View(ctx);
        fill.setBackground(GlassTheme.makeGlassRect(
                ctx, theme.chipActiveBg, Color.TRANSPARENT, 14f, 0f
        ));
        int fillH = Math.max(dp(ctx, 10), (heightPx * pct) / 100);
        FrameLayout.LayoutParams fLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fillH);
        fLp.gravity = Gravity.BOTTOM;
        wrap.addView(fill, fLp);

        final ImageView ico = VectorGlyphs.makeIconView(ctx, glyph, 18, Color.WHITE);
        // Иконка стоит на границе заливки: у неё своя подложка, иначе при громкости
        // около 50% половина глифа оказывается на заливке, половина — на треке.
        // Цвет подложки из палитры: 0xFF303642 на светлом треке был тёмным пятном.
        ico.setBackground(GlassTheme.makeCircle(GlassTheme.SHADE_TILE_OFF));
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18));
        iLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        iLp.bottomMargin = dp(ctx, 10);
        wrap.addView(ico, iLp);

        final int[] lastEdge = new int[]{-1};
        final int[] lastTarget = new int[]{-1};
        wrap.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_CANCEL) {
                onChanged.run();
                return true;
            }
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE
                    || act == MotionEvent.ACTION_UP) {
                if (onInteraction != null) onInteraction.run();
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                float ratio = 1f - Math.max(0f, Math.min(1f, ev.getY() / Math.max(1f, v.getHeight())));
                int appliedPct = setStreamRatioIfChanged(am, streamType, ratio, lastTarget);
                if (appliedPct < 0) {
                    if (act == MotionEvent.ACTION_UP) onChanged.run();
                    return true;
                }
                if ((appliedPct == 0 || appliedPct == 100) && lastEdge[0] != appliedPct) {
                    lastEdge[0] = appliedPct;
                    if (hapticEnabled) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                } else if (appliedPct > 2 && appliedPct < 98) {
                    lastEdge[0] = -1;
                }
                int newH = Math.max(dp(ctx, 8), Math.round(v.getHeight() * appliedPct / 100f));
                ViewGroup.LayoutParams curLp = fill.getLayoutParams();
                curLp.height = newH;
                fill.setLayoutParams(curLp);
                if (livePctView != null) {
                    livePctView.setText(appliedPct + "%");
                }
                if (act == MotionEvent.ACTION_UP) {
                    onChanged.run();
                }
                return true;
            }
            return false;
        });

        return wrap;
    }

    private static View makeModeChip(Context ctx, GlassTheme theme, String glyph, String label, boolean active, boolean leftGap, Runnable onClick) {
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(ctx, 6), dp(ctx, 9), dp(ctx, 6), dp(ctx, 9));
        chip.setBackground(GlassTheme.makeGlassRect(
                ctx,
                active ? theme.chipActiveBg : theme.cardBgColor,
                active ? theme.chipActiveBorder : theme.cardBorderColor,
                14f, 1f
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (leftGap) lp.leftMargin = dp(ctx, 6);
        chip.setLayoutParams(lp);

        ImageView iv = VectorGlyphs.makeIconView(ctx, glyph, 15, active ? Color.WHITE : theme.textSecondary);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(ctx, 15), dp(ctx, 15));
        iLp.rightMargin = dp(ctx, 5);
        chip.addView(iv, iLp);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        tv.setTextColor(active ? theme.chipActiveText : theme.textSecondary);
        chip.addView(tv);

        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private static int getStreamMinVolumeSafe(AudioManager am, int stream) {
        if (am == null) return 0;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return Math.max(0, am.getStreamMinVolume(stream));
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static boolean isVolumeFixedSafe(AudioManager am) {
        if (am == null) return false;
        try {
            return am.isVolumeFixed();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean hasHardwareVibrator(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm != null && vm.getDefaultVibrator().hasVibrator();
            }
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            return v != null && v.hasVibrator();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Чистая функция преобразования [min..max] в 0..100% (защита от потоков с minVolume > 0). */
    static int volumeToPercent(int cur, int min, int max) {
        int span = max - min;
        if (span <= 0) return (cur > 0) ? 100 : 0;
        int clamped = Math.max(min, Math.min(max, cur));
        return Math.max(0, Math.min(100, Math.round(((clamped - min) * 100f) / span)));
    }

    /** Чистая функция преобразования доли ползунка [0..1] в дискретный уровень [min..max]. */
    static int ratioToVolume(float ratio, int min, int max) {
        if (max <= min) return min;
        float r = Math.max(0f, Math.min(1f, ratio));
        int target = min + Math.round((max - min) * r);
        return Math.max(min, Math.min(max, target));
    }

    private static int getStreamPercent(AudioManager am, int stream) {
        if (am == null) return 50;
        try {
            int min = getStreamMinVolumeSafe(am, stream);
            int max = am.getStreamMaxVolume(stream);
            if (max <= 0) return 0;
            int cur = am.getStreamVolume(stream);
            return volumeToPercent(cur, min, max);
        } catch (Throwable ignored) {
            return 50;
        }
    }

    /**
     * Обновляет громкость потока только при реальной смене дискретного шага (убирает лишний Binder IPC
     * на 60–120 Гц при движении пальца), учитывает minVolume (API 28+) и isVolumeFixed().
     */
    private static int setStreamRatioIfChanged(AudioManager am, int stream, float ratio, int[] lastTarget) {
        if (am == null) return -1;
        try {
            if (isVolumeFixedSafe(am)) {
                return getStreamPercent(am, stream);
            }
            int min = getStreamMinVolumeSafe(am, stream);
            int max = am.getStreamMaxVolume(stream);
            if (max <= 0) return 0;
            int target = ratioToVolume(ratio, min, max);
            if (lastTarget == null || lastTarget[0] != target) {
                am.setStreamVolume(stream, target, 0);
                if (lastTarget != null) lastTarget[0] = target;
            }
            return volumeToPercent(am.getStreamVolume(stream), min, max);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int getRingerModeSafe(AudioManager am) {
        if (am == null) return AudioManager.RINGER_MODE_NORMAL;
        try {
            return am.getRingerMode();
        } catch (Throwable ignored) {
            return AudioManager.RINGER_MODE_NORMAL;
        }
    }

    private static void cycleRingerMode(Context ctx, AudioManager am) {
        int cur = getRingerModeSafe(am);
        boolean hasVib = hasHardwareVibrator(ctx);
        int next;
        if (cur == AudioManager.RINGER_MODE_NORMAL) {
            next = hasVib ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_SILENT;
        } else if (cur == AudioManager.RINGER_MODE_VIBRATE) {
            next = AudioManager.RINGER_MODE_SILENT;
        } else {
            next = AudioManager.RINGER_MODE_NORMAL;
        }
        setRingerModeSafe(ctx, am, next);
    }

    private static void setRingerModeSafe(Context ctx, AudioManager am, int mode) {
        if (am == null) return;
        try {
            am.setRingerMode(mode);
        } catch (SecurityException se) {
            Toast.makeText(ctx, "Разрешите Doxaiz доступ к режиму «Не беспокоить»", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static String ringerGlyphName(int mode) {
        if (mode == AudioManager.RINGER_MODE_VIBRATE) return "vibrate";
        if (mode == AudioManager.RINGER_MODE_SILENT) return "mute";
        return "sound";
    }

    private static String ringerLabel(int mode) {
        if (mode == AudioManager.RINGER_MODE_VIBRATE) return "Вибрация";
        if (mode == AudioManager.RINGER_MODE_SILENT) return "Без звука";
        return "Звонок включён";
    }

    private static int dp(Context ctx, int v) {
        return GlassTheme.dp(ctx, v);
    }
}
