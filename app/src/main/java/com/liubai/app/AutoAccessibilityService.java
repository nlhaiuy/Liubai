package com.liubai.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/** 只用来知道“现在前台是哪个 App”，不读取屏幕内容。 */
public class AutoAccessibilityService extends AccessibilityService {
    private static final String TAG = "LiubaiA11y";

    /**
     * 引导用户开启无障碍时记下时间；开启成功且在有效期内，就自动跳回留白，省得手动返回好几层。
     * 用时间戳而不是布尔值：用户中途放弃时标志不会一直残留，更不会在日后重启 / 更新时突然把界面拉到前台。
     */
    public static final String KEY_RETURN = "return_after_a11y_at";
    private static final String KEY_RETURN_LEGACY = "return_after_a11y";
    private static final long RETURN_TTL_MS = 5 * 60 * 1000L;

    public static void markReturn(SharedPreferences sp) {
        sp.edit().remove(KEY_RETURN_LEGACY).putLong(KEY_RETURN, System.currentTimeMillis()).apply();
    }

    public static void clearReturn(SharedPreferences sp) {
        sp.edit().remove(KEY_RETURN_LEGACY).remove(KEY_RETURN).apply();
    }

    /** 亮屏 / 息屏 / 解锁时重新判断：息屏、锁屏后台播放时不显示留白，解锁回到视频软件后自动恢复。 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            AutoMode.evaluate(c);
        }
    };
    private boolean receiverRegistered = false;

    /** 是否还在接收“界面内容变化”事件（认出前台软件之后就关掉，见 trimEventTypes）。 */
    private boolean contentEventsOn = true;

    /** 服务是否真的连上了（设置里开关是开的，不代表系统已经把服务连起来）。 */
    public static volatile boolean connected = false;
    /** 连上 / 断开时通知界面刷新。 */
    public static volatile Runnable onStateChanged;

    private static void notifyState() {
        Runnable r = onStateChanged;
        if (r != null) r.run();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        contentEventsOn = true;   // 系统重新绑定服务时事件类型会恢复成配置文件里的值
        notifyState();

        // onServiceConnected 可能被重复调用：只注册一次，避免重复接收广播
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            try {
                registerReceiver(screenReceiver, f);
                receiverRegistered = true;
            } catch (Exception e) {
                Log.w(TAG, "registerReceiver failed", e);
            }
        }
        AutoMode.evaluate(this);

        SharedPreferences sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        long markedAt = sp.getLong(KEY_RETURN, 0L);
        if (markedAt != 0L) {
            clearReturn(sp);
            if (System.currentTimeMillis() - markedAt < RETURN_TTL_MS) {
                // 后台拉起界面可能被系统拦截且不报错，所以先给一句提示
                Toast.makeText(this, "无障碍已开启，返回留白即可", Toast.LENGTH_SHORT).show();
                try {
                    startActivity(new Intent(this, MainActivity.class).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } catch (Exception e) {
                    Log.w(TAG, "return to app failed", e);
                }
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        int t = e.getEventType();
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AutoMode.onForeground(this, pkg.toString(), e.getClassName());
            trimEventTypes();
        } else if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !AutoMode.hasForeground()) {
            // 重启 / 更新后服务刚连上，还没等到窗口切换：用界面内容变化的事件先认出当前前台软件
            AutoMode.onForeground(this, pkg.toString());
            trimEventTypes();
        }
    }

    /**
     * “界面内容变化”事件只在刚连上、还不知道前台是谁时有用；视频软件的弹幕、进度条每秒会产生大量这类事件，
     * 认出前台软件后就不再订阅，只留窗口切换事件，省电。
     */
    private void trimEventTypes() {
        if (!contentEventsOn || !AutoMode.hasForeground()) return;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
            contentEventsOn = false;
        } catch (Exception ex) {
            Log.w(TAG, "trim event types failed", ex);
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        serviceGone();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        serviceGone();
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    /** 服务断开：忘掉旧的前台记录并重新判断（白名单模式下没人识别前台了，遮罩不能一直留着）。 */
    private void serviceGone() {
        connected = false;
        AutoMode.onServiceGone(this);
        notifyState();
    }
}
