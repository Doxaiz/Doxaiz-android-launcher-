package com.doxaiz.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.os.SystemClock;

import java.util.List;

/**
 * DesktopPagerView v3.4 — собственный физически плавный перелистыватель страниц рабочего стола.
 *
 * Особенности:
 *  - Прямое следование за пальцем 1:1 и мягкая доводка (собственная, Quintic Ease-Out).
 *  - Синхронизация строго по VSYNC (postInvalidateOnAnimation) — идеально плавно на 90 и 120 Гц.
 *  - Учёт реальной скорости смахивания (VelocityTracker) + мягкий резиновый оттяг на краях.
 *  - Лёгкий GPU-эффект смягчения (scale 1.0 -> 0.965, alpha 1.0 -> 0.80) без тяжёлого 3D.
 *  - Полностью уважает тумблер плавных анимаций (при ВЫКЛ — мгновенное переключение без эффектов),
 *    не мешает вертикальным жестам (шторка/меню), щипку (менеджер страниц) и Drag & Drop.
 */
public final class DesktopPagerView extends ViewGroup {

    public interface OnPageChangedListener {
        void onPageChanged(int pageIndex);
    }

    private final int touchSlop;

    // Собственная доводка вместо OverScroller: OverScroller пропускает первый кадр
    // (после startScroll() позиция меняется только через ~8 мс), из-за чего на 120 Гц
    // ощущалась задержка старта свайпа. Здесь позиция пересчитывается в computeScroll()
    // каждые 16/8 мс по кривой Quintic Ease-Out, без внутреннего «догоняния».
    private boolean snapAnimating = false;
    private long snapStartMs = 0L;
    private int snapFromX = 0;
    private int snapToX = 0;
    private int snapDurationMs = 0;
    private final int minFlingVelocity;
    private VelocityTracker velocityTracker;

    private int currentPage = 0;
    private int pageWidth = 0;

    private float downX = 0f;
    private float downY = 0f;
    private float lastX = 0f;
    private boolean isDragging = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private boolean dragLocked = false;
    private boolean smoothScrollEnabled = true;
    private boolean loopPagesEnabled = true;

    // Кэш применённых трансформаций: setScale/setAlpha на каждом кадре помечают страницу
    // грязной, даже если значение не изменилось. На 8 страницах это лишние инвалидации
    // всех страниц 120 раз в секунду.
    private float[] appliedScale = new float[0];
    private float[] appliedAlpha = new float[0];
    private OnPageChangedListener pageListener;
    private final Runnable pendingSnapRunnable = () -> snapToPage(currentPage, false, false, 0f);

    public DesktopPagerView(Context context) {
        super(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        this.touchSlop = Math.max(6, Math.round(vc.getScaledTouchSlop() * 0.85f));
        this.minFlingVelocity = Math.max(220, vc.getScaledMinimumFlingVelocity() * 2);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);
    }

    /**
     * Doxaiz 4.9: Адаптация длительности доводки под реальную частоту экрана (60/90/120/144 Гц):
     * на 120 Гц скролл ощущается на 12% отзывчивее без потери плавности.
     */
    static float computeRefreshDurationScale(float refreshHz) {
        if (refreshHz >= 115f) return 0.88f;
        if (refreshHz >= 85f) return 0.94f;
        return 1.0f;
    }

    private float readDisplayRefreshScale() {
        try {
            android.view.Display d = getDisplay();
            if (d != null) {
                return computeRefreshDurationScale(d.getRefreshRate());
            }
        } catch (Throwable ignored) {}
        return 1.0f;
    }

    /** Чистая функция кривой замедления (1 - (1 - t)^4) для тестов и доводки. */
    static float quinticOut(float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        float inv = 1f - clamped;
        return 1f - (inv * inv * inv * inv);
    }

    /** Лёгкий масштаб страницы при смещении offset ∈ [-1..1] (0 = по центру). */
    static float computePageScale(float offsetAbs) {
        float c = Math.max(0f, Math.min(1f, offsetAbs));
        return 1f - 0.035f * c;
    }

    /** Лёгкая прозрачность страницы при смещении offset ∈ [-1..1]. */
    static float computePageAlpha(float offsetAbs) {
        float c = Math.max(0f, Math.min(1f, offsetAbs));
        return 1f - 0.20f * c;
    }

    /** Расчёт целевой страницы с учётом скорости, смещения и зацикливания. */
    static int computeTargetPage(int current, int count, int scrollX, int width,
                                 float velocityX, int minFlingVel, float totalDx,
                                 boolean loopEnabled) {
        if (count <= 1 || width <= 0) return 0;

        // Быстрый свайп пальцем
        boolean isFling = Math.abs(velocityX) >= minFlingVel;
        if (isFling) {
            if (velocityX < 0) {
                // Свайп влево -> следующая страница
                if (current == count - 1) return loopEnabled ? 0 : current;
                return current + 1;
            }
            // Свайп вправо -> предыдущая страница
            if (current == 0) return loopEnabled ? (count - 1) : 0;
            return current - 1;
        }

        // Зацикливание при уверенном протягивании за край
        if (loopEnabled && Math.abs(totalDx) > width * 0.24f) {
            if (current == count - 1 && totalDx < 0) return 0;
            if (current == 0 && totalDx > 0) return count - 1;
        }

        int nearest = Math.round(scrollX / (float) width);
        return Math.max(0, Math.min(count - 1, nearest));
    }

    /**
     * Определяет, является ли переход зацикленным прыжком через край (0 <-> last).
     * При быстром смахивании учитывает направление velocityX, а не только totalDx.
     */
    static boolean isLoopWrapTransition(int current, int target, int count,
                                        float velocityX, int minFlingVel, float totalDx,
                                        boolean loopEnabled) {
        if (!loopEnabled || count < 2 || current == target) return false;
        boolean isFling = Math.abs(velocityX) >= minFlingVel;
        if (current == count - 1 && target == 0) {
            return isFling ? (velocityX < 0f) : (totalDx < 0f);
        }
        if (current == 0 && target == count - 1) {
            return isFling ? (velocityX > 0f) : (totalDx > 0f);
        }
        return false;
    }

    public void setOnPageChangedListener(OnPageChangedListener listener) {
        this.pageListener = listener;
    }

    public void setDragLocked(boolean locked) {
        this.dragLocked = locked;
        if (locked) {
            isDragging = false;
            recycleVelocityTracker();
        }
    }

    public void setSmoothScrollEnabled(boolean enabled) {
        this.smoothScrollEnabled = enabled;
        if (!enabled) {
            resetChildTransforms();
        } else {
            applyPageTransitions();
        }
    }

    public void setLoopPagesEnabled(boolean enabled) {
        this.loopPagesEnabled = enabled;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getPageCount() {
        return getChildCount();
    }

    public View getPageView(int index) {
        if (index < 0 || index >= getChildCount()) return null;
        return getChildAt(index);
    }

    public void setPages(List<View> views, int targetPage) {
        abortSnapAnimation();
        removeAllViews();
        if (views != null) {
            for (View v : views) {
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
        }
        int count = getChildCount();
        currentPage = (count == 0) ? 0 : Math.max(0, Math.min(targetPage, count - 1));
        appliedScale = new float[0];
        appliedAlpha = new float[0];
        requestLayout();
        removeCallbacks(pendingSnapRunnable);
        post(pendingSnapRunnable);
    }

    public void goToPage(int index) {
        snapToPage(index, true, smoothScrollEnabled, 0f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);

        int childWSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int childHSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(childWSpec, childHSpec);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        boolean widthChanged = (w > 0 && w != pageWidth);
        if (w > 0) pageWidth = w;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            int left = i * w;
            child.layout(left, 0, left + w, h);
        }

        if (widthChanged && count > 0) {
            currentPage = Math.max(0, Math.min(currentPage, count - 1));
            scrollTo(currentPage * pageWidth, 0);
            applyPageTransitions();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        // Единственная точка применения трансформаций за кадр: scrollTo() внутри
        // computeScroll() сам вызывает onScrollChanged(), поэтому вторая (прежняя)
        // копия вызова там удваивала работу на каждом кадре анимации доводки.
        if (smoothScrollEnabled) {
            applyPageTransitions();
        }
    }

    @Override
    public void computeScroll() {
        if (snapAnimating) {
            final long elapsed = SystemClock.uptimeMillis() - snapStartMs;
            final float rawT = (snapDurationMs > 0) ? (elapsed / (float) snapDurationMs) : 1f;
            if (rawT >= 1f) {
                scrollTo(snapToX, 0);
                snapAnimating = false;
                if (smoothScrollEnabled) applyPageTransitions();
                return;
            }
            float t = quinticOut(rawT);
            int x = snapFromX + Math.round((snapToX - snapFromX) * t);
            scrollTo(x, 0);
            if (smoothScrollEnabled) {
                // Кривая демпфирует старт, поэтому первые кадры дают смещение меньше пикселя
                // и без явного onScrollChanged() трансформации страниц отставали бы от
                // фактической позиции. Шагаем по кадру явно.
                applyPageTransitions();
            }
            postInvalidateOnAnimation();
        } else if (smoothScrollEnabled && !isDragging) {
            applyPageTransitions();
        }
    }

    private void abortSnapAnimation() {
        snapAnimating = false;
    }

    /**
     * Страница участвует в переходе, только если она в пределах кадра (или рядом с ним).
     * Дальние страницы всё равно не видны: их трансформация не меняет картинку, а стоит
     * инвалидации всего поддерева.
     */
    static boolean isPageInTransitionRange(float pageOffset) {
        return Math.abs(pageOffset) < 1.001f;
    }

    private void applyPageTransitions() {
        // Единственный поддерживаемый стиль — плавный. Если анимации выключены пользователем,
        // страницы должны оставаться без масштаба/прозрачности, иначе «мгновенное» перелистывание
        // всё равно бледнеет и уменьшается во время скролла.
        if (!smoothScrollEnabled) {
            resetChildTransforms();
            return;
        }
        int w = (pageWidth > 0) ? pageWidth : getWidth();
        int count = getChildCount();
        if (w <= 0 || count == 0) return;

        ensureTransformCache(count);
        final int childH = (count > 0) ? getChildAt(0).getHeight() : 0;
        final float pivotX = w * 0.5f;
        final float pivotY = childH * 0.5f;
        final float sx = getScrollX();

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            float pageOffset = (i * w - sx) / (float) w;

            // Дальние страницы: приводим к «покойному» состоянию один раз и больше не трогаем.
            if (!isPageInTransitionRange(pageOffset)) {
                if (appliedScale[i] != 1f) {
                    appliedScale[i] = 1f;
                    child.setScaleX(1f);
                    child.setScaleY(1f);
                }
                if (appliedAlpha[i] != 1f) {
                    appliedAlpha[i] = 1f;
                    child.setAlpha(1f);
                }
                applyPageVisibility(child, false);
                continue;
            }
            applyPageVisibility(child, true);

            float abs = Math.min(1f, Math.abs(pageOffset));
            float scale = (abs < 0.002f) ? 1f : computePageScale(abs);
            float alpha = (abs < 0.002f) ? 1f : computePageAlpha(abs);

            if (appliedScale[i] != scale) {
                appliedScale[i] = scale;
                if (child.getPivotX() != pivotX) child.setPivotX(pivotX);
                if (child.getPivotY() != pivotY) child.setPivotY(pivotY);
                child.setScaleX(scale);
                child.setScaleY(scale);
            }
            if (appliedAlpha[i] != alpha) {
                appliedAlpha[i] = alpha;
                child.setAlpha(alpha);
            }
        }
    }

    /**
     * Скрывает отрисовку невидимых страниц.
     *
     * Все страницы лежат в одном ViewGroup и формально находятся «в окне», поэтому
     * их onDraw вызывается на каждом кадре, даже если видна только одна. Для восьми
     * страниц по 20–25 плиток это сотни лишних вызовов draw и заметный расход
     * GPU-времени при прокрутке. Отключаем отрисовку у страниц вне кадра: layout
     * и позиции сохраняются, поэтому возврат на страницу не требует пересборки.
     */
    private void applyPageVisibility(View child, boolean visible) {
        int desired = visible ? View.VISIBLE : View.INVISIBLE;
        if (child.getVisibility() != desired) {
            child.setVisibility(desired);
        }
    }

    private void ensureTransformCache(int count) {
        if (appliedScale.length == count) return;
        appliedScale = new float[count];
        appliedAlpha = new float[count];
        java.util.Arrays.fill(appliedScale, 1f);
        java.util.Arrays.fill(appliedAlpha, 1f);
    }

    private void resetChildTransforms() {
        int count = getChildCount();
        ensureTransformCache(count);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (appliedScale[i] != 1f) {
                appliedScale[i] = 1f;
                child.setScaleX(1f);
                child.setScaleY(1f);
            }
            if (appliedAlpha[i] != 1f) {
                appliedAlpha[i] = 1f;
                child.setAlpha(1f);
            }
            if (child.getTranslationX() != 0f) child.setTranslationX(0f);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (dragLocked || getChildCount() <= 1) return false;
        // Щипок (2 пальца) не перехватываем — отдаём менеджеру страниц
        if (ev.getPointerCount() >= 2) {
            isDragging = false;
            return false;
        }

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                lastX = downX;
                activePointerId = ev.getPointerId(0);
                isDragging = false;
                initOrResetVelocityTracker();
                velocityTracker.addMovement(ev);
                abortSnapAnimation();
                return false;

            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(activePointerId);
                if (idx < 0) return false;
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                float dx = Math.abs(x - downX);
                float dy = Math.abs(y - downY);
                if (dx > touchSlop && dx > dy * 1.15f) {
                    isDragging = true;
                    lastX = x;
                    if (velocityTracker != null) velocityTracker.addMovement(ev);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                recycleVelocityTracker();
                return false;

            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (dragLocked || getChildCount() == 0) return false;
        if (ev.getPointerCount() >= 2) {
            finishDrag(0f);
            return false;
        }

        initVelocityTrackerIfNotExists();
        velocityTracker.addMovement(ev);

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                abortSnapAnimation();
                downX = ev.getX();
                downY = ev.getY();
                lastX = downX;
                activePointerId = ev.getPointerId(0);
                return true;

            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(activePointerId);
                if (idx < 0) idx = 0;
                float x = ev.getX(idx);
                float deltaX = lastX - x;
                lastX = x;

                if (!isDragging && Math.abs(x - downX) > touchSlop) {
                    isDragging = true;
                }
                if (isDragging) {
                    int w = (pageWidth > 0) ? pageWidth : getWidth();
                    int maxScroll = Math.max(0, (getChildCount() - 1) * w);
                    float nextX = getScrollX() + deltaX;
                    // Мягкий физический резиновый оттяг на краях (0.32x)
                    if (nextX < 0f || nextX > maxScroll) {
                        nextX = getScrollX() + deltaX * 0.32f;
                        int limit = Math.round(w * 0.28f);
                        nextX = Math.max(-limit, Math.min(maxScroll + limit, nextX));
                    }
                    scrollTo(Math.round(nextX), 0);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                float velX = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000, 12000f);
                    velX = (activePointerId != MotionEvent.INVALID_POINTER_ID)
                            ? velocityTracker.getXVelocity(activePointerId)
                            : velocityTracker.getXVelocity();
                }
                int upIdx = (activePointerId != MotionEvent.INVALID_POINTER_ID)
                        ? ev.findPointerIndex(activePointerId) : -1;
                float upX = (upIdx >= 0 && upIdx < ev.getPointerCount()) ? ev.getX(upIdx) : ev.getX();
                float totalDx = (upX - downX);
                finishDragWithGesture(velX, totalDx);
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                finishDrag(0f);
                return true;

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIdx = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIdx);
                if (pointerId == activePointerId) {
                    int newIdx = (pointerIdx == 0) ? 1 : 0;
                    if (newIdx < ev.getPointerCount()) {
                        float newX = ev.getX(newIdx);
                        downX += (newX - lastX);
                        lastX = newX;
                        activePointerId = ev.getPointerId(newIdx);
                    }
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void finishDrag(float velX) {
        finishDragWithGesture(velX, 0f);
    }

    private void finishDragWithGesture(float velocityX, float totalDx) {
        isDragging = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        recycleVelocityTracker();

        int count = getChildCount();
        int w = (pageWidth > 0) ? pageWidth : getWidth();
        if (count == 0 || w <= 0) return;

        int target = computeTargetPage(
                currentPage, count, getScrollX(), w,
                velocityX, minFlingVelocity, totalDx, loopPagesEnabled);

        // Определяем, произошёл ли зацикленный прыжок через край (0 <-> last)
        boolean wrapped = isLoopWrapTransition(
                currentPage, target, count, velocityX, minFlingVelocity, totalDx, loopPagesEnabled);

        if (wrapped && smoothScrollEnabled) {
            // Плавно въезжаем на противоположную страницу со стороны свайпа без длинной перемотки
            int entryOffset = (target == 0) ? -Math.round(w * 0.22f) : Math.round(w * 0.22f);
            scrollTo(target * w + entryOffset, 0);
        }

        snapToPage(target, true, smoothScrollEnabled, velocityX);
    }

    private void snapToPage(int index, boolean notify, boolean animate, float velocityX) {
        int count = getChildCount();
        if (count == 0) return;
        int clamped = Math.max(0, Math.min(index, count - 1));
        boolean changed = (clamped != currentPage);
        currentPage = clamped;

        int w = (pageWidth > 0) ? pageWidth : getWidth();
        if (w > 0) {
            int targetX = currentPage * w;
            int startX = getScrollX();
            int dx = targetX - startX;
            abortSnapAnimation();

            if (animate && dx != 0) {
                float distRatio = Math.min(1f, Math.abs(dx) / (float) w);
                int baseDur = Math.round(165f + 105f * distRatio);
                // Быстрый смах + реальная частота экрана (60/90/120 Гц) -> упругая и мгновенная доводка
                float speedBoost = Math.min(0.32f, Math.abs(velocityX) / 7500f);
                float hzScale = readDisplayRefreshScale();
                snapDurationMs = Math.max(130, Math.min(275,
                        Math.round(baseDur * (1f - speedBoost) * hzScale)));
                snapFromX = startX;
                snapToX = targetX;
                snapStartMs = SystemClock.uptimeMillis();
                snapAnimating = true;
                // Первый кадр рисуем сразу, не ожидая следующего тика скроллера.
                postInvalidateOnAnimation();
            } else {
                scrollTo(targetX, 0);
                if (smoothScrollEnabled) {
                    applyPageTransitions();
                } else {
                    resetChildTransforms();
                }
            }
        }

        if (notify && changed && pageListener != null) {
            pageListener.onPageChanged(currentPage);
        }
    }

    private void initOrResetVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
    }

    private void initVelocityTrackerIfNotExists() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(pendingSnapRunnable);
        recycleVelocityTracker();
        abortSnapAnimation();
    }
}
