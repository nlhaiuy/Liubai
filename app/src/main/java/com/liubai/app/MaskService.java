package com.liubai.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** 横屏：在屏幕左右两侧各盖一块白色悬浮窗，遮住视频两边的黑边；开启“竖屏遮罩”后，竖屏时改盖上下两侧。 */
public class MaskService extends Service {
    public static final String PREFS = "mask_prefs";
    public static final String KEY_RATIO = "ratio";   // 视频宽高比，默认 16/9
    public static final String KEY_OFFSET = "offset"; // 微调(dp)，正数=遮罩变宽
    public static final String KEY_LAYERS = "layers"; // 每侧叠几层：1=标准，3=加强
    public static final String KEY_PORTRAIT = "portrait"; // 视频比例选了“竖屏”：竖屏时盖住视频上下（视频 16:9、撑满屏幕宽度）
    // 竖屏的微调和位置与横屏分开保存
    public static final String KEY_OFFSET_P = "offset_p"; // 竖屏微调(dp)，正数=上下遮罩变厚
    /** 竖屏“视频靠上”预设：视频顶边在屏幕高度约 19% 处（直播间那种视频在上、聊天在下的布局）。 */
    public static final float POS_TOP = 0.252f;
    public static final String KEY_POS_P = "pos_p";       // 视频在竖屏里的上下位置：0=贴顶，0.5=居中，1=贴底
    public static final float DEFAULT_RATIO = 16f / 9f;
    public static final int DEFAULT_LAYERS = 1;
    public static final int MAX_LAYERS = 6;

    private static final String CHANNEL_ID = "mask";
    private static final String CHANNEL_IDLE_ID = "mask_idle";
    public static boolean running = false;
    /** 正在运行的服务：设置页拖动微调时直接调它，不用每一格都重新启动服务。 */
    private static MaskService instance;

    private static final long FADE_IN_MS = 180, FADE_OUT_MS = 160;

    private WindowManager wm;
    private final java.util.List<View> bars = new java.util.ArrayList<>();
    private int builtA, builtB, builtH, builtSW, builtLayers; // 当前这批遮罩是按什么尺寸建的（A/B=两侧遮罩厚度，SW/H=屏幕宽高）
    private boolean builtPortrait;
    private boolean notifActive = false;        // 通知当前是不是“遮罩已开启”

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "遮罩状态", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_IDLE_ID, "遮罩待命", NotificationManager.IMPORTANCE_MIN));
        // 系统要求 startForegroundService 后几秒内必须 startForeground，所以这里先挂一条
        // 最低优先级的“待命”通知（无状态栏图标、不提示）；真正盖上遮罩后 update() 再换成
        // “遮罩已开启”。
        Notification n = buildNotification(false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        running = true;
        instance = this;
        refreshTile();
    }

    /** 设置变了：能原地改就原地改，不拆也不重建，拖动微调时就不会闪。 */
    public static boolean refresh() {
        MaskService s = instance;
        if (s == null) return false;
        s.update();
        return true;
    }

    private Notification buildNotification(boolean active) {
        return new Notification.Builder(this, active ? CHANNEL_ID : CHANNEL_IDLE_ID)
                .setContentTitle(active ? "遮罩已开启" : "遮罩待命中")
                .setContentText(active ? "点按进入设置" : "切到横屏后自动显示")
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** 遮罩真的盖上屏幕才显示“已开启”；竖屏等没有遮罩的时候退回待命通知。 */
    private void setNotificationActive(boolean active) {
        if (notifActive == active) return;
        notifActive = active;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(1, buildNotification(active));
        } catch (Exception ignored) { }
    }

    /**
     * 没有遮罩可显示：该停服务（白名单模式竖屏等）就停，并且不再碰通知——
     * stopService 一调用系统就会撤掉前台通知，这时再 notify 会把它重新发成一条没人管的残留通知；
     * 还要留着服务（全局模式）才退回待命通知。
     */
    private void goIdle() {
        if (!AutoMode.shouldRun(this)) {
            AutoMode.evaluate(this);
            return;
        }
        setNotificationActive(false);
    }

    private void refreshTile() {
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, MaskTileService.class));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        update();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        update(); // 旋转屏幕后重新计算
    }

    @SuppressWarnings("deprecation")
    private void update() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        boolean portrait = w <= h;

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int layers = Math.max(1, Math.min(MAX_LAYERS, sp.getInt(KEY_LAYERS, DEFAULT_LAYERS)));

        // a = 左 / 上遮罩的厚度，b = 右 / 下遮罩的厚度
        int a, b;
        if (portrait) {
            if (!sp.getBoolean(KEY_PORTRAIT, false)) {   // 竖屏遮罩没开：不需要遮罩
                removeViews(false);
                goIdle();
                return;
            }
            // 视频撑满宽度，上下位置由 pos 决定：上边黑条 = 剩余高度 × pos
            float ratio = DEFAULT_RATIO;
            int offsetPx = Math.round(sp.getInt(KEY_OFFSET_P, 0) * m.density);
            float free = Math.max(0f, h - w / ratio);
            float pos = Math.max(0f, Math.min(1f, sp.getFloat(KEY_POS_P, 0.5f)));
            a = Math.round(free * pos) + offsetPx;
            b = Math.round(free * (1f - pos)) + offsetPx;
            a = Math.max(0, Math.min(a, h));
            b = Math.max(0, Math.min(b, h - a));
        } else {
            // 横屏：左右各一条，宽 = (屏幕宽 − 屏幕高×比例) / 2
            float ratio = sp.getFloat(KEY_RATIO, DEFAULT_RATIO);
            int offsetPx = Math.round(sp.getInt(KEY_OFFSET, 0) * m.density);
            a = Math.round((w - h * ratio) / 2f) + offsetPx;
            a = Math.max(0, Math.min(a, w / 2));
            b = a;
        }
        if (a == 0 && b == 0) {
            removeViews(false);
            goIdle();
            return;
        }

        // 屏幕尺寸、朝向都没变：厚度原地改；层数变了只加 / 减多出来的那几层，原来的不拆
        // （标准 ⇄ 加强 切换时才不会闪一下黑边）。某一侧从有到无（贴顶 / 贴底）才重建。
        boolean same = !bars.isEmpty() && builtH == h && builtSW == w && builtPortrait == portrait
                && (builtA > 0) == (a > 0) && (builtB > 0) == (b > 0);
        if (same) {
            if (builtA != a || builtB != b) {
                for (View v : bars) {
                    try {
                        int t = Boolean.TRUE.equals(v.getTag()) ? a : b;
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                        if (portrait) lp.height = t; else lp.width = t;
                        wm.updateViewLayout(v, lp);
                    } catch (Exception ignored) { }
                }
                builtA = a;
                builtB = b;
            }
            int per = (a > 0 ? 1 : 0) + (b > 0 ? 1 : 0);
            int have = bars.size() / per;
            if (have < layers) {
                java.util.List<View> added = new java.util.ArrayList<>();
                for (int i = have; i < layers; i++) addLayer(portrait, a, b, w, h, added);
                builtLayers = layers;
                fade(added, 0f, 1f, FADE_IN_MS, false);
            } else if (have > layers) {
                java.util.List<View> gone = new java.util.ArrayList<>(
                        bars.subList(layers * per, bars.size()));
                for (int i = bars.size() - 1; i >= layers * per; i--) bars.remove(i);
                builtLayers = layers;
                fade(gone, 1f, 0f, FADE_OUT_MS, true);
            }
            setNotificationActive(true);
            return;
        }

        removeViews(false);
        for (int i = 0; i < layers; i++) addLayer(portrait, a, b, w, h, bars);
        builtA = a;
        builtB = b;
        builtH = h;
        builtSW = w;
        builtPortrait = portrait;
        builtLayers = layers;
        fade(new java.util.ArrayList<>(bars), 0f, 1f, FADE_IN_MS, false);
        setNotificationActive(true);   // 遮罩已经加到屏幕上了，才显示“已开启”
    }

    /** 加一层：厚度为 0 的那一侧不加（视频贴顶 / 贴底时）。 */
    private void addLayer(boolean portrait, int a, int b, int sw, int sh, java.util.List<View> out) {
        if (a > 0) {
            View v = addBar(portrait, true, a, sw, sh);
            if (out != bars) bars.add(v);
            out.add(v);
        }
        if (b > 0) {
            View v = addBar(portrait, false, b, sw, sh);
            if (out != bars) bars.add(v);
            out.add(v);
        }
    }

    /**
     * 整体淡入 / 淡出：用窗口自身的透明度，不改变悬浮窗的像素格式（所以遮得还是一样实）。
     * 每个窗口一帧一帧地更新，总共约 0.2 秒。
     */
    private void fade(final java.util.List<View> views, float from, float to, long ms,
                      final boolean removeAtEnd) {
        final WindowManager manager = wm;
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.addUpdateListener(an -> {
            float alpha = (Float) an.getAnimatedValue();
            for (View v : views) {
                try {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                    if (lp == null) continue;
                    lp.alpha = alpha;
                    manager.updateViewLayout(v, lp);
                } catch (Exception ignored) { }
            }
        });
        if (removeAtEnd) {
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    for (View v : views) {
                        try { manager.removeView(v); } catch (Exception ignored) { }
                    }
                }
            });
        }
        a.start();
    }

    /** first=true：横屏的左边 / 竖屏的上边；false：右边 / 下边。bar=遮罩厚度，sw/sh=屏幕宽高。 */
    private View addBar(boolean portrait, boolean first, int bar, int sw, int sh) {
        int width = portrait ? sw : bar;
        int height = portrait ? bar : sh;
        int gravity = portrait
                ? (first ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START
                : Gravity.TOP | (first ? Gravity.START : Gravity.END);
        View v = new View(this);
        v.setTag(first);   // 原地改厚度时要区分是哪一侧
        v.setBackgroundColor(Color.WHITE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = gravity;
        lp.alpha = 0f;
        lp.x = 0;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(v, lp);
        return v;
    }

    /** 去掉全部遮罩；animate 为 true 时先淡出再去掉（只用在整个服务关闭时）。 */
    private void removeViews(boolean animate) {
        if (bars.isEmpty()) return;
        if (animate) {
            fade(new java.util.ArrayList<>(bars), 1f, 0f, FADE_OUT_MS, true);
        } else {
            for (View v : bars) {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }
        }
        bars.clear();
        builtA = builtB = builtH = builtSW = builtLayers = 0;
    }

    @Override
    public void onDestroy() {
        removeViews(true);
        running = false;
        AutoMode.onMaskStopped();
        try {   // 兜底：停服务之后若还有迟到的 notify，在这里一并清掉，避免残留
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(1);
        } catch (Exception ignored) { }
        if (instance == this) instance = null;
        refreshTile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
