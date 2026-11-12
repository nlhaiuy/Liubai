package com.liubai.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 分段开关：浅色底槽里一块蓝色渐变滑块，点哪段滑块就滑过去。
 * 如果传入 glyphAspects，每段文字前面会画一个对应宽高比的小画幅图标（用于 16:9 / 4:3）。
 */
public class Segmented extends View {
    public interface OnSelect { void onSelect(int index); }

    private final String[] labels;
    private final float[] glyphs;      // 可为 null
    private int sel;
    private float pos;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator anim;
    private OnSelect cb;

    public Segmented(Context c, String[] labels, int sel) {
        this(c, labels, sel, null);
    }

    public Segmented(Context c, String[] labels, int sel, float[] glyphAspects) {
        super(c);
        this.labels = labels;
        this.glyphs = glyphAspects;
        this.sel = sel;
        this.pos = sel;
        paint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 让滑块的阴影在所有机型上都能画出来
    }

    public void setOnSelect(OnSelect cb) { this.cb = cb; }

    public int getSelected() { return sel; }

    public void setSelected(int i, boolean animate) {
        sel = i;
        if (anim != null) anim.cancel();
        if (!animate || !isLaidOut()) {
            pos = i;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, i);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { pos = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 54)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), h = getHeight(), pad = Ui.dp(c, 4);

        // 底槽
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.CHIP);
        float vy = Ui.dp(c, 4);                    // 上下留空，滑块的阴影不被切
        rect.set(0, vy, w, h - vy);
        cv.drawRoundRect(rect, Ui.dp(c, 14), Ui.dp(c, 14), paint);

        // 滑块
        float segW = (w - 2 * pad) / labels.length;
        float l = pad + pos * segW, r = pad + (pos + 1) * segW;
        paint.setShader(new LinearGradient(l, vy + pad, r, h - vy - pad, Ui.PRI2, Ui.PRI, Shader.TileMode.CLAMP));
        paint.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 2), 0x554F7CFF);
        rect.set(l, vy + pad, r, h - vy - pad);
        cv.drawRoundRect(rect, Ui.dp(c, 11), Ui.dp(c, 11), paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        // 文字（和可选的画幅图标）
        paint.setTextSize(Ui.sp(c, 15));
        paint.setTextAlign(Paint.Align.LEFT);
        float ty = h / 2 - (paint.ascent() + paint.descent()) / 2;
        float gap = Ui.dp(c, 8);
        for (int i = 0; i < labels.length; i++) {
            float gh = glyphs != null && glyphs[i] < 1f ? Ui.dp(c, 17) : Ui.dp(c, 12); // 竖向图标画高一点，免得像个“0”
            float near = 1f - Math.min(1f, Math.abs(i - pos));
            int color = Ui.blend(Ui.DIM, 0xFFFFFFFF, near);
            paint.setColor(color);
            paint.setFakeBoldText(near > 0.5f);

            float tw = paint.measureText(labels[i]);
            float gw = glyphs != null ? gh * glyphs[i] : 0f;
            float total = tw + (glyphs != null ? gw + gap : 0f);
            float x = pad + (i + 0.5f) * segW - total / 2f;
            if (glyphs != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(c, 2));
                rect.set(x + Ui.dp(c, 1), h / 2 - gh / 2 + Ui.dp(c, 1),
                        x + gw - Ui.dp(c, 1), h / 2 + gh / 2 - Ui.dp(c, 1));
                cv.drawRoundRect(rect, Ui.dp(c, 3), Ui.dp(c, 3), paint);
                paint.setStyle(Paint.Style.FILL);
                x += gw + gap;
            }
            cv.drawText(labels[i], x, ty, paint);
        }
        paint.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float pad = Ui.dp(getContext(), 4);
            float segW = (getWidth() - 2 * pad) / labels.length;
            int idx = (int) ((e.getX() - pad) / segW);
            idx = Math.max(0, Math.min(labels.length - 1, idx));
            if (idx != sel) {
                setSelected(idx, true);
                if (cb != null) cb.onSelect(idx);
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
