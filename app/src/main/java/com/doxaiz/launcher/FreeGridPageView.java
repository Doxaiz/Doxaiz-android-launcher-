package com.doxaiz.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * Свободная сетка рабочего стола (cols x rows).
 * Позволяет располагать иконки и папки в любых ячейках с пустыми клетками между ними,
 * а также подсвечивает целевую ячейку при перетаскивании пальцем (без тяжёлых эффектов).
 */
public final class FreeGridPageView extends ViewGroup {

    public static final int HIGHLIGHT_NONE = 0;
    public static final int HIGHLIGHT_MOVE = 1;
    public static final int HIGHLIGHT_FOLDER = 2;

    public static final class LayoutParams extends ViewGroup.LayoutParams {
        public final int col;
        public final int row;

        public LayoutParams(int col, int row) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.col = col;
            this.row = row;
        }
    }

    private int cols;
    private int rows;
    private int hlCol = -1;
    private int hlRow = -1;
    private int hlMode = HIGHLIGHT_NONE;

    private final Paint hlFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hlRect = new RectF();
    private final float cornerPx;
    private final float insetPx;

    public FreeGridPageView(Context context, int cols, int rows) {
        super(context);
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.cornerPx = GlassTheme.dp(context, 16);
        this.insetPx = GlassTheme.dp(context, 4);

        hlFillPaint.setStyle(Paint.Style.FILL);
        hlStrokePaint.setStyle(Paint.Style.STROKE);
        hlStrokePaint.setStrokeWidth(GlassTheme.dp(context, 1.5f));
        setWillNotDraw(false);
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public void setHighlight(int col, int row, int mode, int fillColor, int strokeColor) {
        if (hlCol == col && hlRow == row && hlMode == mode) return;
        hlCol = col;
        hlRow = row;
        hlMode = mode;
        hlFillPaint.setColor(fillColor);
        hlStrokePaint.setColor(strokeColor);
        invalidate();
    }

    public void clearHighlight() {
        if (hlMode == HIGHLIGHT_NONE) return;
        hlCol = -1;
        hlRow = -1;
        hlMode = HIGHLIGHT_NONE;
        invalidate();
    }

    /**
     * Переводит локальные координаты внутри страницы в (col, row).
     */
    public boolean pointToCell(float localX, float localY, int[] outColRow) {
        int contentW = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentW <= 0 || contentH <= 0) return false;

        float x = localX - getPaddingLeft();
        float y = localY - getPaddingTop();
        if (x < 0) x = 0;
        if (x >= contentW) x = contentW - 1;
        if (y < 0) y = 0;
        if (y >= contentH) y = contentH - 1;

        int c = (int) (x * cols / contentW);
        int r = (int) (y * rows / contentH);
        outColRow[0] = Math.max(0, Math.min(cols - 1, c));
        outColRow[1] = Math.max(0, Math.min(rows - 1, r));
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);

        int contentW = Math.max(0, w - getPaddingLeft() - getPaddingRight());
        int contentH = Math.max(0, h - getPaddingTop() - getPaddingBottom());
        int cellW = contentW / cols;
        int cellH = contentH / rows;

        int cwSpec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY);
        int chSpec = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(cwSpec, chSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int padL = getPaddingLeft();
        int padT = getPaddingTop();
        int contentW = Math.max(0, (r - l) - padL - getPaddingRight());
        int contentH = Math.max(0, (b - t) - padT - getPaddingBottom());
        float cellW = contentW / (float) cols;
        float cellH = contentH / (float) rows;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            ViewGroup.LayoutParams rawLp = child.getLayoutParams();
            int c = 0;
            int row = 0;
            if (rawLp instanceof LayoutParams) {
                LayoutParams lp = (LayoutParams) rawLp;
                c = Math.max(0, Math.min(cols - 1, lp.col));
                row = Math.max(0, Math.min(rows - 1, lp.row));
            }
            int left = padL + Math.round(c * cellW);
            int top = padT + Math.round(row * cellH);
            int right = padL + Math.round((c + 1) * cellW);
            int bottom = padT + Math.round((row + 1) * cellH);
            child.layout(left, top, right, bottom);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (hlMode != HIGHLIGHT_NONE && hlCol >= 0 && hlRow >= 0 && hlCol < cols && hlRow < rows) {
            int padL = getPaddingLeft();
            int padT = getPaddingTop();
            int contentW = getWidth() - padL - getPaddingRight();
            int contentH = getHeight() - padT - getPaddingBottom();
            if (contentW > 0 && contentH > 0) {
                float cellW = contentW / (float) cols;
                float cellH = contentH / (float) rows;
                float left = padL + hlCol * cellW + insetPx;
                float top = padT + hlRow * cellH + insetPx;
                float right = padL + (hlCol + 1) * cellW - insetPx;
                float bottom = padT + (hlRow + 1) * cellH - insetPx;
                hlRect.set(left, top, right, bottom);
                canvas.drawRoundRect(hlRect, cornerPx, cornerPx, hlFillPaint);
                canvas.drawRoundRect(hlRect, cornerPx, cornerPx, hlStrokePaint);
            }
        }
    }
}
