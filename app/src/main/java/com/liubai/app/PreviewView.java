package com.liubai.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 演示屏：开启后，视频两边（横屏）或上下（竖屏）的黑边变成白色。
 * 选了“竖屏”（KEY_PORTRAIT）就画竖屏；比例、微调与真实遮罩算法一致。
 * 竖屏有“居中”和“视频靠上”两种位置。
 * 屏幕外有一圈很细的浅蓝灰描边，白边才看得出边界。外面的白色卡片由使用它的布局提供。
 */
public class PreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Path tri = new Path();
    private final SharedPreferences sp;
    private float amount; // 0 = 黑边，1 = 白边
    private ValueAnimator anim;
    private float shownRatio;          // 正在显示的画幅（切换 16:9 / 4:3 时平滑过渡）
    private ValueAnimator ratioAnim;

    /** 竖屏演示屏的默认高度（dp）；主页会按横屏那张卡的大小传入合适的高度，见 setPortraitHeightPx。 */
    private static final int PORTRAIT_H = 210;
    private float portraitHPx = 0f;

    /** 竖屏演示屏的高度（px）。小手机跟微调并排放在一行，所以高度由主页按横屏那张卡的大小算好传进来。 */
    public void setPortraitHeightPx(float px) {
        portraitHPx = px;
        requestLayout();
        invalidate();
    }

    private float portraitH() {
        return portraitHPx > 0f ? portraitHPx : Ui.dp(getContext(), PORTRAIT_H);
    }

    public PreviewView(Context c) {
        super(c);
        sp = c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        amount = AutoMode.isMaster(c) ? 1f : 0f;
        shownRatio = curRatio();
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 让屏幕下面的阴影在所有机型上都能画出来
    }

    private boolean portrait() { return sp.getBoolean(MaskService.KEY_PORTRAIT, false); }

    private float curRatio() {
        return portrait() ? MaskService.DEFAULT_RATIO
                : sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
    }

    private int curOffset() {
        return sp.getInt(portrait() ? MaskService.KEY_OFFSET_P : MaskService.KEY_OFFSET, 0);
    }

    public void setOn(boolean on) {
        float to = on ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (!isLaidOut()) { amount = to; invalidate(); return; }
        anim = ValueAnimator.ofFloat(amount, to);
        anim.setDuration(380);
        anim.addUpdateListener(a -> { amount = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    /** 为阴影留的空间（dp）：布局里要用同样大小的负边距把它抵消掉。 */
    public static final int PAD_X = 12, PAD_T = 8, PAD_B = 22;

    /** 画幅改了：视频画面平滑地变宽 / 变窄，不再瞬间跳。 */
    public void ratioChanged() {
        float to = curRatio();
        if (ratioAnim != null) ratioAnim.cancel();
        if (!isLaidOut() || Math.abs(to - shownRatio) < 0.001f) { shownRatio = to; invalidate(); return; }
        ratioAnim = ValueAnimator.ofFloat(shownRatio, to);
        ratioAnim.setDuration(260);
        ratioAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        ratioAnim.addUpdateListener(a -> { shownRatio = (Float) a.getAnimatedValue(); invalidate(); });
        ratioAnim.start();
    }

    /** 切换了横屏 / 竖屏：演示屏高度不同，直接换。 */
    public void tabChanged() {
        if (ratioAnim != null) ratioAnim.cancel();
        shownRatio = curRatio();
        requestLayout();
        invalidate();
    }

    /** 用真实屏幕的比例，小窗 / 分屏时也和真正的遮罩一致。 */
    static float aspect(Context c) {
        float[] s = Ui.realScreen(c);
        return Math.max(16f / 9f, Math.min(2.4f, s[0] / s[1]));
    }

    private float aspect() {
        return aspect(getContext());
    }

    /** 演示屏的尺寸：{宽, 高}（px，不含阴影留白）。 */
    private float[] screenSize() {
        Context c = getContext();
        float[] scr = Ui.realScreen(c);
        float areaW = getWidth() - 2 * Ui.dp(c, PAD_X);
        if (portrait()) {
            float sh = portraitH();
            return new float[]{sh * scr[1] / scr[0], sh};
        }
        return new float[]{areaW, areaW / aspect()};
    }

    @Override
    protected void onMeasure(int w, int h) {
        Context c = getContext();
        if (portrait()) {
            // 竖屏：只有一只小手机，宽度跟着手机走（旁边是微调），不撑满整行
            float[] scr = Ui.realScreen(c);
            float sh = portraitH();
            float sw = sh * scr[1] / scr[0];
            setMeasuredDimension(Math.round(sw + 2 * Ui.dp(c, PAD_X)),
                    Math.round(sh + Ui.dp(c, PAD_T + PAD_B)));
            return;
        }
        int width = MeasureSpec.getSize(w);
        float inner = width - 2 * Ui.dp(c, PAD_X);
        setMeasuredDimension(width, Math.round(inner / aspect() + Ui.dp(c, PAD_T + PAD_B)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float[] scr = Ui.realScreen(c);
        float density = scr[2];
        boolean port = portrait();
        float areaW = getWidth() - 2 * Ui.dp(c, PAD_X);
        float[] sz = screenSize();
        float sw = sz[0], sh = sz[1];
        float scale = port ? sw / scr[1] : sw / scr[0];   // 演示屏上 1 个真实 px 对应多少（dp 要先乘 density）
        cv.save();
        cv.translate(Ui.dp(c, PAD_X) + (areaW - sw) / 2f, Ui.dp(c, PAD_T));
        float rad = Ui.dp(c, 18);

        // 屏幕本体 + 两层阴影：宽而淡的环境影 + 紧贴屏幕的接触影
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.EDGE);
        paint.setShadowLayer(Ui.dp(c, 14), 0, Ui.dp(c, 7), 0x302B3A78);
        rect.set(0, 0, sw, sh);
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1.5f), 0x262B3042);
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.clearShadowLayer();

        clip.reset();
        clip.addRoundRect(new RectF(0, 0, sw, sh), rad, rad, Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        paint.setColor(Ui.EDGE);
        cv.drawRect(0, 0, sw, sh, paint);

        // 视频画面 + 遮罩厚度（a = 左 / 上，b = 右 / 下）
        float ratio = shownRatio;
        float off = curOffset() * density * scale;
        float vl, vt, vw, vh, a, b;
        if (port) {
            float pos = Math.max(0f, Math.min(1f, sp.getFloat(MaskService.KEY_POS_P, 0.5f)));
            vw = sw;
            vh = sw / ratio;
            vl = 0f;
            float free = Math.max(0f, sh - vh);
            vt = free * pos;
            a = Math.max(0f, Math.min(vt + off, sh));
            b = Math.max(0f, Math.min(free - vt + off, sh - a));
        } else {
            vw = Math.min(sw, sh * ratio);
            vh = sh;
            vl = (sw - vw) / 2f;
            vt = 0f;
            a = Math.max(0f, Math.min(vl + off, sw / 2f));
            b = a;
        }
        paint.setShader(new LinearGradient(vl, vt, vl + vw, vt + vh,
                new int[]{0xFF7F93D8, 0xFF4A5BA8, 0xFF323F7A}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        cv.drawRect(vl, vt, vl + vw, vt + vh, paint);
        paint.setShader(null);

        // 播放键
        paint.setColor(0xE0FFFFFF);
        float cx = vl + vw / 2f, cy = vt + vh / 2f, ts = Math.min(vw, vh) * 0.14f;
        tri.reset();
        tri.moveTo(cx - ts * 0.6f, cy - ts);
        tri.lineTo(cx - ts * 0.6f, cy + ts);
        tri.lineTo(cx + ts, cy);
        tri.close();
        cv.drawPath(tri, paint);

        // 遮罩：黑边 → 白边（横屏盖左右，竖屏盖上下）
        paint.setColor(Ui.blend(Ui.EDGE, 0xFFFFFFFF, amount));
        float edge = Ui.dp(c, 10);
        int alpha = Math.round(0x2E * amount);
        int dark = (alpha << 24) | 0x1F2638, none = 0x001F2638;
        boolean shade = amount > 0.01f;
        if (port) {
            if (a > 0f) cv.drawRect(0, 0, sw, a, paint);
            if (b > 0f) cv.drawRect(0, sh - b, sw, sh, paint);
            // 白边靠视频一侧的一点点阴影，让白边和画面分得开
            if (shade && a > 0f) {
                paint.setShader(new LinearGradient(0, a, 0, a + edge, dark, none, Shader.TileMode.CLAMP));
                cv.drawRect(0, a, sw, a + edge, paint);
            }
            if (shade && b > 0f) {
                paint.setShader(new LinearGradient(0, sh - b, 0, sh - b - edge, dark, none, Shader.TileMode.CLAMP));
                cv.drawRect(0, sh - b - edge, sw, sh - b, paint);
            }
        } else {
            cv.drawRect(0, 0, a, sh, paint);
            cv.drawRect(sw - b, 0, sw, sh, paint);
            if (shade && a > 0f) {
                paint.setShader(new LinearGradient(a, 0, a + edge, 0, dark, none, Shader.TileMode.CLAMP));
                cv.drawRect(a, 0, a + edge, sh, paint);
                paint.setShader(new LinearGradient(sw - b, 0, sw - b - edge, 0, dark, none, Shader.TileMode.CLAMP));
                cv.drawRect(sw - b - edge, 0, sw - b, sh, paint);
            }
        }
        paint.setShader(null);

        // 玻璃感：左上一抹高光，底部一点暗
        paint.setShader(new LinearGradient(0, 0, sw * 0.45f, sh * 0.75f, 0x2AFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, sw, sh, paint);
        if (!port) {   // 底部压暗只盖在两条白边之间，白边保持纯白
            paint.setShader(new LinearGradient(0, sh * 0.7f, 0, sh, 0x00141A32, 0x1A141A32,
                    Shader.TileMode.CLAMP));
            cv.drawRect(a, sh * 0.7f, sw - b, sh, paint);
        }
        paint.setShader(null);
        cv.restore();

        // 屏幕外的细描边
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(c, 1));
        paint.setColor(Ui.OUTLINE);
        rect.set(Ui.dp(c, 0.5f), Ui.dp(c, 0.5f), sw - Ui.dp(c, 0.5f), sh - Ui.dp(c, 0.5f));
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        cv.restore();
    }
}
