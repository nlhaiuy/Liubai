package com.liubai.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * 拿到 Shizuku 授权之后，替留白开启其他所需权限（悬浮窗、通知、后台运行、无障碍、通知使用权），
 * 不用再跳系统设置页一项项点。命令在 Shizuku 拉起的 shell 身份进程里执行（见 ShellService.grant）。
 *
 * 播放检测已经连着 Shizuku 服务时直接复用；没连着就临时连一下，做完立刻断开。
 */
final class ShizukuGrant {
    private static final String TAG = "LiubaiGrant";
    private static final long BIND_TIMEOUT_MS = 6000L;

    /** 结果回调，总是在主线程。error 为空表示命令执行成功（是否真的生效，由调用方再检查一遍）。 */
    interface Done {
        void onDone(String error);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newSingleThreadExecutor();

    private ShizukuGrant() { }

    /** what：0 悬浮窗、1 通知、2 后台运行、3 无障碍、4 通知使用权。 */
    static void grant(Context c, int what, Done done) {
        final Context app = c.getApplicationContext();
        final String pkg = app.getPackageName();
        final String comp = what == 3
                ? new ComponentName(app, AutoAccessibilityService.class).flattenToString()
                : what == 4 ? new ComponentName(app, PlaybackListenerService.class).flattenToString() : "";

        IShellService existing = PlaybackDetector.connectedShell();
        if (existing != null) {
            io.execute(() -> finish(done, call(existing, what, pkg, comp)));
            return;
        }

        final AtomicBoolean finished = new AtomicBoolean(false);
        final ServiceConnection[] holder = new ServiceConnection[1];
        final Runnable timeout = () -> {
            if (finished.getAndSet(true)) return;
            unbind(app, holder[0]);
            done.onDone("Shizuku 服务连接超时");
        };
        holder[0] = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName n, IBinder b) {
                main.removeCallbacks(timeout);
                if (finished.getAndSet(true)) return;
                final IShellService s = IShellService.Stub.asInterface(b);
                io.execute(() -> {
                    String err = call(s, what, pkg, comp);
                    main.post(() -> unbind(app, holder[0]));
                    finish(done, err);
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName n) { }
        };
        try {
            Shizuku.bindUserService(PlaybackDetector.shellArgs(app), holder[0]);
            main.postDelayed(timeout, BIND_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
            if (!finished.getAndSet(true)) done.onDone(String.valueOf(t));
        }
    }

    private static String call(IShellService s, int what, String pkg, String comp) {
        try {
            String r = s.grant(what, pkg, comp);
            return r == null ? "" : r.trim();
        } catch (Throwable t) {
            Log.w(TAG, "grant failed", t);
            return String.valueOf(t);
        }
    }

    private static void finish(Done done, String error) {
        main.post(() -> done.onDone(error));
    }

    /** 只断开这一条连接；服务进程要不要保留，由播放检测那条连接决定。 */
    private static void unbind(Context app, ServiceConnection conn) {
        try {
            Shizuku.unbindUserService(PlaybackDetector.shellArgs(app), conn, false);
        } catch (Throwable ignored) { }
    }
}
