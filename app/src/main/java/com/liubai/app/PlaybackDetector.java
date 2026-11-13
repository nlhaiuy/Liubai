package com.liubai.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 判断“某个软件此刻是不是在播放”。三层，用最准的那层，上一层不可用就退到下一层：
 *  ① Shizuku：以 shell 身份读 dumpsys audio，直接知道哪个应用（uid → 包名）有播放器在 started。
 *  ② 媒体会话：MediaSessionManager（需要通知使用权），知道各个包的 PlaybackState。
 *  ③ 系统音频：AudioPlaybackCallback（无需任何权限），只知道“有没有媒体在响”，不知道是谁，
 *     所以要配合前台包名使用。
 * 三层都看不到无声视频。播放一停就立刻判定为没在播放，不做延迟。
 */
public final class PlaybackDetector {
    private static final String TAG = "LiubaiPlay";

    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final int SHIZUKU_REQ = 1001;
    private static final int SHELL_VERSION = 2;   // 服务端接口变了（新增 grant），升版本让 Shizuku 重启旧的服务进程

    private static final Set<String> EMPTY = Collections.emptySet();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Pattern PLAYER_LINE = Pattern.compile("u/pid:(\\d+)/\\d+\\s+state:(\\w+)");
    private static final Pattern USAGE = Pattern.compile("usage=(\\w+)");

    private static Context app;
    private static boolean started = false;

    /** 界面用：状态变了（Shizuku 连上 / 断开、通知使用权变化等）。 */
    public static volatile Runnable onStateChanged;

    // ---------------- ③ 系统音频 ----------------
    private static AudioManager am;
    private static volatile boolean audioActive = false;
    private static final AudioManager.AudioPlaybackCallback audioCb =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    onAudioChanged(configs);
                }
            };

    // ---------------- ② 媒体会话 ----------------
    private static MediaSessionManager msm;
    private static ComponentName listener;
    private static boolean sessListenerAdded = false;
    private static volatile boolean sessionsOk = false;
    private static volatile Set<String> sessionPkgs = EMPTY;
    private static volatile Set<String> sessionPlaying = EMPTY;
    private static final List<MediaController> controllers = new ArrayList<>();
    private static final MediaController.Callback ctrlCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            recomputeSessions();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };
    private static final MediaSessionManager.OnActiveSessionsChangedListener sessCb =
            PlaybackDetector::setControllers;

    // ---------------- ① Shizuku ----------------
    private static volatile IShellService shell;
    private static boolean binding = false;
    private static boolean shizukuListeners = false;
    private static boolean permListenerAdded = false;
    private static volatile boolean shizukuBroken = false;   // 读到的内容认不出格式：退回下一层
    private static volatile Set<String> shizukuPkgs = EMPTY;
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean queryPending = new AtomicBoolean(false);

    private static final Shizuku.OnBinderReceivedListener binderRecv = () -> {
        tryBindShell();
        notifyChanged();
    };
    private static final Shizuku.OnBinderDeadListener binderDead = () -> {
        shell = null;
        binding = false;
        shizukuPkgs = EMPTY;
        notifyChanged();
    };
    private static final Shizuku.OnRequestPermissionResultListener permResult = (code, result) -> {
        tryBindShell();
        notifyChanged();
        // 没开“仅播放时”的话检测没在运行，notifyChanged 不会通知界面；授权结果仍要让设置页刷新
        main.post(() -> {
            Runnable r = onStateChanged;
            if (r != null) r.run();
        });
    };
    private static final android.content.ServiceConnection shellConn =
            new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    binding = false;
                    shell = IShellService.Stub.asInterface(b);
                    refreshShizuku();
                    notifyChanged();
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    shell = null;
                    binding = false;
                    shizukuPkgs = EMPTY;
                    notifyChanged();
                }
            };

    private PlaybackDetector() { }

    // ---------------- 对外接口 ----------------

    /** 按需要开启 / 关闭检测（总开关开着、且设置为“仅播放时显示”才需要）。 */
    static synchronized void sync(Context c, boolean wanted) {
        if (wanted) start(c);
        else stop();
    }

    /** 这个软件此刻是不是在播放。pkg 为空表示“不管是谁”，只问有没有东西在播。 */
    static boolean isPlaying(Context c, String pkg) {
        synchronized (PlaybackDetector.class) {
            start(c);
        }
        return computeNow(pkg == null ? "" : pkg);
    }

    /** 当前生效的是哪一层：3 = Shizuku，2 = 媒体会话，1 = 系统音频。 */
    public static int level() {
        if (shell != null && !shizukuBroken) return 3;
        if (sessionsOk) return 2;
        return 1;
    }

    /** 通知使用权是不是已经给了留白。 */
    public static boolean listenerEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            if (s == null || s.isEmpty()) return false;
            ComponentName me = new ComponentName(c, PlaybackListenerService.class);
            for (String part : s.split(":")) {
                if (me.equals(ComponentName.unflattenFromString(part.trim()))) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "listenerEnabled failed", e);
        }
        return false;
    }

    /** 播放检测已经连上的 Shizuku 服务（没连上是 null）。一键授权优先复用它。 */
    static IShellService connectedShell() {
        return shell;
    }

    /** Shizuku 状态：0 没装，1 装了但没在运行，2 在运行但留白还没授权，3 就绪。 */
    public static int shizukuState(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        } catch (Exception ignored) { }
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return 1;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return 2;
            return 3;
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 弹出 Shizuku 的授权窗口。 */
    public static void requestShizukuPermission(Context c) {
        app = c.getApplicationContext();
        try {
            if (!permListenerAdded) {
                Shizuku.addRequestPermissionResultListener(permResult);
                permListenerAdded = true;
            }
            Shizuku.requestPermission(SHIZUKU_REQ);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    static void onListenerConnected(Context c) {
        main.post(() -> {
            if (!started) return;
            refreshSessions();
            notifyChanged();
        });
    }

    static void onListenerDisconnected(Context c) {
        main.post(() -> {
            if (!started) return;
            refreshSessions();
            notifyChanged();
        });
    }

    // ---------------- 启动 / 停止 ----------------

    private static synchronized void start(Context c) {
        if (started) return;
        app = c.getApplicationContext();
        started = true;
        listener = new ComponentName(app, PlaybackListenerService.class);

        // ③ 系统音频：立刻读一次现状，再订阅变化
        try {
            am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.registerAudioPlaybackCallback(audioCb, main);
            audioActive = anyMedia(am.getActivePlaybackConfigurations());
        } catch (Exception e) {
            Log.w(TAG, "audio callback failed", e);
            audioActive = false;
        }

        // ② 媒体会话
        msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        refreshSessions();

        // ① Shizuku
        try {
            if (!shizukuListeners) {
                Shizuku.addBinderReceivedListenerSticky(binderRecv);
                Shizuku.addBinderDeadListener(binderDead);
                shizukuListeners = true;
            }
            tryBindShell();
        } catch (Throwable t) {
            Log.w(TAG, "shizuku init failed", t);
        }
    }

    private static synchronized void stop() {
        if (!started) return;
        started = false;
        try {
            if (am != null) am.unregisterAudioPlaybackCallback(audioCb);
        } catch (Exception ignored) { }
        audioActive = false;

        try {
            if (msm != null && sessListenerAdded) msm.removeOnActiveSessionsChangedListener(sessCb);
        } catch (Exception ignored) { }
        sessListenerAdded = false;
        for (MediaController mc : controllers) {
            try { mc.unregisterCallback(ctrlCb); } catch (Exception ignored) { }
        }
        controllers.clear();
        sessionsOk = false;
        sessionPkgs = EMPTY;
        sessionPlaying = EMPTY;

        try {
            if (shizukuListeners) {
                Shizuku.removeBinderReceivedListener(binderRecv);
                Shizuku.removeBinderDeadListener(binderDead);
                shizukuListeners = false;
            }
            if (shell != null || binding) {
                Shizuku.unbindUserService(shellArgs(), shellConn, true);
            }
        } catch (Throwable ignored) { }
        shell = null;
        binding = false;
        shizukuBroken = false;
        shizukuPkgs = EMPTY;
    }

    // ---------------- 判断 ----------------

    private static boolean computeNow(String pkg) {
        boolean any = pkg.isEmpty();
        if (shell != null && !shizukuBroken) {                       // ① 最准
            Set<String> s = shizukuPkgs;
            return any ? !s.isEmpty() : s.contains(pkg);
        }
        if (sessionsOk) {                                            // ② 这个软件有媒体会话就听它的
            Set<String> playing = sessionPlaying;
            if (any) {
                if (!playing.isEmpty()) return true;
            } else if (sessionPkgs.contains(pkg)) {
                return playing.contains(pkg);
            }
        }
        return audioActive;                                          // ③ 保底（没有会话的软件走这里）
    }

    // ---------------- ③ 系统音频 ----------------

    private static void onAudioChanged(List<AudioPlaybackConfiguration> configs) {
        audioActive = anyMedia(configs);
        refreshShizuku();
        notifyChanged();
    }

    private static boolean anyMedia(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) return false;
        for (AudioPlaybackConfiguration cfg : configs) {
            AudioAttributes a = cfg.getAudioAttributes();
            if (a == null) continue;
            int u = a.getUsage();
            // 只算“媒体”：提示音、闹钟、导航语音、通话不算
            if (u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME
                    || u == AudioAttributes.USAGE_UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    // ---------------- ② 媒体会话 ----------------

    private static void refreshSessions() {
        if (msm == null) return;
        try {
            if (!sessListenerAdded) {
                msm.addOnActiveSessionsChangedListener(sessCb, listener, main);
                sessListenerAdded = true;
            }
            setControllers(msm.getActiveSessions(listener));
            sessionsOk = true;
        } catch (SecurityException e) {
            // 没有通知使用权：这一层不可用
            sessionsOk = false;
            sessionPkgs = EMPTY;
            sessionPlaying = EMPTY;
        } catch (Exception e) {
            Log.w(TAG, "refreshSessions failed", e);
            sessionsOk = false;
        }
    }

    private static void setControllers(List<MediaController> list) {
        for (MediaController mc : controllers) {
            try { mc.unregisterCallback(ctrlCb); } catch (Exception ignored) { }
        }
        controllers.clear();
        if (list != null) {
            for (MediaController mc : list) {
                try {
                    mc.registerCallback(ctrlCb, main);
                    controllers.add(mc);
                } catch (Exception ignored) { }
            }
        }
        recomputeSessions();
    }

    private static void recomputeSessions() {
        Set<String> pkgs = new HashSet<>();
        Set<String> playing = new HashSet<>();
        for (MediaController mc : controllers) {
            String p = mc.getPackageName();
            if (p == null) continue;
            pkgs.add(p);
            PlaybackState st = mc.getPlaybackState();
            if (st != null && isPlayingState(st.getState())) playing.add(p);
        }
        sessionPkgs = pkgs;
        sessionPlaying = playing;
        notifyChanged();
    }

    private static boolean isPlayingState(int s) {
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING;
    }

    // ---------------- ① Shizuku ----------------

    private static Shizuku.UserServiceArgs shellArgs() {
        return shellArgs(app);
    }

    /** Shizuku 用户服务的参数。播放检测和一键授权必须用同一份，才会连到同一个服务进程。 */
    static Shizuku.UserServiceArgs shellArgs(Context c) {
        return new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), ShellService.class.getName()))
                .processNameSuffix("shell")
                .daemon(false)
                .debuggable(false)
                .version(SHELL_VERSION);
    }

    private static void tryBindShell() {
        if (!started || app == null || shell != null || binding) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            binding = true;
            Shizuku.bindUserService(shellArgs(), shellConn);
        } catch (Throwable t) {
            binding = false;
            Log.w(TAG, "bind shell failed", t);
        }
    }

    /** 重新读一次 dumpsys（在后台线程；系统音频没有媒体在响时不用读，直接清空）。 */
    private static void refreshShizuku() {
        final IShellService s = shell;
        if (s == null) return;
        if (!audioActive) {
            shizukuPkgs = EMPTY;
            return;
        }
        if (queryPending.getAndSet(true)) return;
        io.execute(() -> {
            queryPending.set(false);
            try {
                String out = s.playbackDump();
                final Set<String> pkgs = new HashSet<>();
                int total = 0;
                if (out != null) {
                    for (String line : out.split("\n")) {
                        Matcher m = PLAYER_LINE.matcher(line);
                        if (!m.find()) continue;
                        total++;
                        if (!"started".equals(m.group(2)) || !isMediaLine(line)) continue;
                        String[] names = app.getPackageManager().getPackagesForUid(Integer.parseInt(m.group(1)));
                        if (names == null) continue;
                        for (String n : names) {
                            if (!n.equals(app.getPackageName())) pkgs.add(n);
                        }
                    }
                }
                // 系统说有媒体在响，却一行播放器记录都没读到：多半是这个系统的输出格式不一样，退回下一层
                final boolean broken = total == 0;
                main.post(() -> {
                    shizukuBroken = broken;
                    shizukuPkgs = pkgs;
                    if (broken) Log.w(TAG, "dumpsys audio: no player lines recognised, falling back");
                    notifyChanged();
                });
            } catch (Throwable t) {
                Log.w(TAG, "playbackDump failed", t);
            }
        });
    }

    private static boolean isMediaLine(String line) {
        Matcher m = USAGE.matcher(line);
        if (!m.find()) return true;            // 认不出用途就不排除
        String u = m.group(1);
        return u.equals("USAGE_MEDIA") || u.equals("USAGE_GAME") || u.equals("USAGE_UNKNOWN")
                || u.equals("0") || u.equals("1") || u.equals("14");
    }

    // ---------------- 通知 ----------------

    /** 状态变了：让开关逻辑重新判断一次，并通知界面刷新。 */
    private static void notifyChanged() {
        main.post(() -> {
            if (!started || app == null) return;
            AutoMode.evaluate(app);
            Runnable r = onStateChanged;
            if (r != null) r.run();
        });
    }
}
