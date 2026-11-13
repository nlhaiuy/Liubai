package com.liubai.app;

import android.animation.ArgbEvaluator;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

/** 留白的视觉常量：浅蓝白底、白色卡片、一抹蓝色，没有大面积的深色。 */
final class Ui {
    static final int BG = 0xFFF3F6FC;        // 页面底色
    static final int CARD = 0xFFFFFFFF;      // 卡片
    static final int INK = 0xFF1F2638;       // 主文字
    static final int TITLE = 0xFF2A3A72;     // 页面大标题：深靛蓝，和演示画面、主色同一色系
    static final int DIM = 0xFF5F6A83;       // 次要文字（白底约 5.4:1，够清楚）
    static final int LINE = 0xFFE9EEF7;      // 细分隔线
    static final int CHIP = 0xFFEEF2FA;      // 控件底槽
    static final int TRACK = 0xFFDDE5F3;     // 滑条未走过的部分
    static final int PRI = 0xFF4F7CFF;       // 主蓝
    static final int PRI2 = 0xFF7C9BFF;      // 浅一点的蓝（渐变起点）
    static final int PRI_SOFT = 0xFFE6EDFF;  // 蓝色浅底
    static final int GREEN = 0xFF2EB67D;     // “已开启”的绿点
    static final int EDGE = 0xFF2B3042;      // 演示屏里的黑边
    static final int OUTLINE = 0xFFC3D0EC;   // 演示屏外的细描边
    static final int WARN_BG = 0xFFFFF8E6;   // 授权提示卡
    static final int WARN_TX = 0xFF5C4410;
    static final int WARN_SUB = 0xFF86703F;
    static final int WARN_LINE = 0x1A7A5A12;

    private static final ArgbEvaluator EV = new ArgbEvaluator();

    private Ui() { }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return (Integer) EV.evaluate(t, a, b);
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** 真实屏幕（不受小窗 / 分屏影响）：{长边px, 短边px, density}。 */
    @SuppressWarnings("deprecation")
    static float[] realScreen(Context c) {
        try {
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                    c.getSystemService(Context.DISPLAY_SERVICE);
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(m);
            return new float[]{Math.max(m.widthPixels, m.heightPixels),
                    Math.min(m.widthPixels, m.heightPixels), m.density};
        } catch (Exception e) {
            android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
            return new float[]{Math.max(m.widthPixels, m.heightPixels),
                    Math.min(m.widthPixels, m.heightPixels), m.density};
        }
    }

    /** 这个画幅下，横屏时每边黑边的宽度（dp）。微调往负方向最多减到它，再减就是 0 了。 */
    static int baseBarDp(Context c, float ratio) {
        float[] s = realScreen(c);
        return Math.max(0, (int) Math.floor((s[0] - s[1] * ratio) / 2f / s[2]));
    }

    /** 竖屏时，视频撑满宽度、上下居中，每边黑边的高度（dp）。 */
    static int basePortraitBarDp(Context c, float ratio) {
        float[] s = realScreen(c);
        return Math.max(0, (int) Math.floor((s[0] - s[1] / ratio) / 2f / s[2]));
    }

    /**
     * 竖屏：视频位置在当前预设的基础上，最多能往上 / 往下移多远（dp）。返回 {往上, 往下}。
     * 往上最多移到贴顶，往下最多移到贴底。
     */
    static int[] portraitShiftRangeDp(Context c, float ratio, float storedPos) {
        float[] s = realScreen(c);
        float free = Math.max(0f, s[0] - s[1] / ratio);
        float pos = portraitPos(c, storedPos, s[0], s[1], ratio);
        return new int[]{(int) Math.floor(free * pos / s[2]), (int) Math.floor(free * (1f - pos) / s[2])};
    }

    /** 系统状态栏的高度（px）。 */
    static int statusBarPx(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : Math.round(dp(c, 24));
    }

    /**
     * 竖屏视频的上下位置，占“屏幕高度 − 视频高度”的比例（0=贴顶，0.5=居中，1=贴底）。
     * stored 小于 0（MaskService.POS_STATUS）表示“紧贴状态栏下面”：按这台手机的状态栏高度换算，
     * 例如 B 站、抖音竖屏视频那种视频顶边正好接在状态栏下沿的布局。
     * screenH / screenW 是真实屏幕的长边 / 短边（px），ratio 是视频宽高比。
     */
    static float portraitPos(Context c, float stored, float screenH, float screenW, float ratio) {
        if (stored >= 0f) return Math.min(1f, stored);
        float free = Math.max(1f, screenH - screenW / ratio);
        return Math.min(1f, statusBarPx(c) / free);
    }

    /**
     * 让阴影不被父布局切掉：Android 默认会把子 View 的绘制裁在父布局（和它的内边距）里，
     * 阴影一出界就变成一条直边。滚动容器 / 列表要保留裁剪，其余全部放开。
     */
    static void noClip(View v) {
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        boolean holdsScroller = false;      // 直接装着滚动区的容器要继续裁剪，否则滚动内容会盖到外面
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            if (ch instanceof android.widget.ScrollView || ch instanceof android.widget.AbsListView) {
                holdsScroller = true;
            }
        }
        if (!holdsScroller && !(g instanceof android.widget.ScrollView)
                && !(g instanceof android.widget.AbsListView)) {
            g.setClipChildren(false);
            g.setClipToPadding(false);
        }
        for (int i = 0; i < g.getChildCount(); i++) noClip(g.getChildAt(i));
    }

    /** 按下时轻轻缩一下、松手弹回：只做动画，不吃掉点击。 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static void press(View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(scale).scaleY(scale).setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    static float sp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().scaledDensity;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 蓝色渐变（左上浅、右下深）的圆角底。 */
    static GradientDrawable gradient(float radiusPx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{PRI2, PRI});
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 给 View 一个带淡蓝阴影的悬浮感（Android 9+ 阴影带颜色，更柔和）。 */
    static void lift(View v, float elevDp, int ambient, int spot) {
        v.setElevation(dp(v.getContext(), elevDp));
        if (Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(ambient);
            v.setOutlineSpotShadowColor(spot);
        }
    }

    /** 白色圆角卡片 + 淡蓝阴影。 */
    static void card(View v, float radiusDp) {
        v.setBackground(round(CARD, dp(v.getContext(), radiusDp)));
        lift(v, 6, 0x294F7CFF, 0x544F7CFF);
    }

    /** 浅色页面：状态栏、导航栏跟随底色，图标用深色。 */
    @SuppressWarnings("deprecation")
    static void lightWindow(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
