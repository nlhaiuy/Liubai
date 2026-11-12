package com.liubai.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

/** 大开关按钮：关着时是蓝色渐变的“开启…”，开着时翻成白底蓝边的“关闭…”（文字可改）。 */
public class PowerButton extends TextView {
    private float t;            // 0 = 关，1 = 开
    private String offText = "开启遮罩", onText = "关闭遮罩";
    private ValueAnimator anim;
    private final GradientDrawable bg = new GradientDrawable();

    public PowerButton(Context c) {
        super(c);
        setGravity(Gravity.CENTER);
        setTextSize(17);
        setTypeface(Typeface.DEFAULT_BOLD);
        setLetterSpacing(0.04f);
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setCornerRadius(Ui.dp(c, 31));
        setBackground(bg);
        Ui.lift(this, 8, 0x404F7CFF, 0x884F7CFF);
        setClickable(true);
        apply();
    }

    public void setLabels(String off, String on) {
        offText = off;
        onText = on;
    }

    public void setOn(boolean on, boolean animate) {
        float to = on ? 1f : 0f;
        if (anim != null) anim.cancel();
        setText(on ? onText : offText);
        if (!animate || !isLaidOut()) { t = to; apply(); return; }
        anim = ValueAnimator.ofFloat(t, to);
        anim.setDuration(300);
        anim.addUpdateListener(a -> { t = (Float) a.getAnimatedValue(); apply(); });
        anim.start();
    }

    private void apply() {
        int c1 = Ui.blend(Ui.PRI2, 0xFFFFFFFF, t);
        int c2 = Ui.blend(Ui.PRI, 0xFFFFFFFF, t);
        bg.setColors(new int[]{c1, c2});
        bg.setStroke(Math.round(Ui.dp(getContext(), 1.5f)), Ui.blend(0x004F7CFF, Ui.PRI, t));
        setTextColor(Ui.blend(0xFFFFFFFF, Ui.PRI, t));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }
}
