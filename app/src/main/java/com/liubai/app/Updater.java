package com.liubai.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动更新：查 GitHub Release 的最新版本 → 下载 APK → 用系统安装器安装。
 *
 * 版本来源：访问 https://github.com/nlhaiuy/liubai/releases/latest，看它跳转到的标签（v1.0.N，N 就是 versionCode），
 * 不走 API，没有访问次数限制。APK 固定叫 Liubai.apk（见 .github/workflows/build.yml）。
 *
 * 安装方式：PackageInstaller 会话。Android 12+ 上，应用自己装过的更新可以不弹确认直接装好；
 * 第一次（或更低版本的系统）系统会要求确认，前台时直接弹出，后台时用通知提醒。
 * 没有“安装未知应用”权限时，APK 先下载好，等用户允许之后再装。
 */
final class Updater {
    private static final String TAG = "LiubaiUpdate";
    private static final String REPO = "nlhaiuy/liubai";
    private static final String CHANNEL = "update";

    static final String KEY_AUTO = "update_auto";               // 自动更新（默认开）
    private static final String KEY_LAST = "update_last_check";
    static final long CHECK_GAP_MS = 30L * 60 * 1000;           // 打开 App 时，距上次检查不到 30 分钟就不再查

    static final int IDLE = 0, CHECKING = 1, UP_TO_DATE = 2, AVAILABLE = 3, DOWNLOADING = 4,
            INSTALLING = 5, NEED_PERM = 6, ERROR = 7;

    static volatile int state = IDLE;
    static volatile int percent;                // 下载进度，-1 = 不知道总大小
    static volatile String latestName = "";     // 最新版本号，如 1.0.12
    static volatile String message = "";        // 出错 / 取消时的原因
    static volatile Runnable onChanged;         // 状态变化（任意线程调用）
    static volatile boolean appVisible;         // 主页在前台

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static int lastSession = -1;

    private Updater() { }

    // ---------------- 设置 / 版本 ----------------

    static boolean auto(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private static long codeOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    static int currentCode(Context c) {
        try {
            return (int) codeOf(c.getPackageManager().getPackageInfo(c.getPackageName(), 0));
        } catch (Exception e) {
            return 0;
        }
    }

    static String currentName(Context c) {
        try {
            String n = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return n == null ? "" : n;
        } catch (Exception e) {
            return "";
        }
    }

    /** 打开 App 时的自动检查：距上次检查够久才查。 */
    static void maybeCheck(Context c) {
        if (System.currentTimeMillis() - prefs(c).getLong(KEY_LAST, 0L) < CHECK_GAP_MS) return;
        run(c, false, null);
    }

    // ---------------- 主流程 ----------------

    /**
     * 检查 → （自动更新开着，或者用户手动点了）下载 → 安装。在后台线程做，同一时间只跑一个。
     * manual = 用户主动点的：出错要告诉他，并且不管“自动更新”开没开都会更新。
     */
    static void run(Context ctx, boolean manual, Runnable done) {
        final Context c = ctx.getApplicationContext();
        if (!busy.compareAndSet(false, true)) {
            if (done != null) done.run();
            return;
        }
        io.execute(() -> {
            try {
                doRun(c, manual);
            } catch (Throwable t) {
                Log.w(TAG, "update failed", t);
                set(ERROR, 0, "更新失败：" + why(t));   // 后台查的也显示，不然看不出是网络还是别的问题
            } finally {
                busy.set(false);
                if (done != null) done.run();
            }
        });
    }

    /** 把异常翻成人能看懂的一句话。 */
    private static String why(Throwable t) {
        if (t instanceof java.net.UnknownHostException) return "连不上 GitHub（检查网络 / 需要能访问 github.com）";
        if (t instanceof java.net.SocketTimeoutException) return "连接 GitHub 超时";
        if (t instanceof javax.net.ssl.SSLException) return "与 GitHub 的安全连接失败";
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static void doRun(Context c, boolean manual) throws Exception {
        set(CHECKING, 0, "");
        String tag = latestTag();
        prefs(c).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
        int code = parseCode(tag);
        int cur = currentCode(c);
        if (tag == null || code <= 0 || code <= cur) {   // 没有发布过 / 已是最新
            cleanup(c);
            set(UP_TO_DATE, 0, "");
            return;
        }
        latestName = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!manual && !auto(c)) {
            set(AVAILABLE, 0, "");
            return;
        }

        File f = new File(dir(c), "Liubai-" + code + ".apk");
        if (!valid(c, f, code)) {
            download(c, "https://github.com/" + REPO + "/releases/download/" + tag + "/Liubai.apk", f);
            if (!valid(c, f, code)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                throw new IOException("下载的安装包不完整");
            }
        }

        // Shizuku 就绪：以 shell 身份 pm install -r，不弹确认，也不需要“安装未知应用”
        if (PlaybackDetector.shizukuState(c) == 3) {
            set(INSTALLING, 0, "");
            String err = installViaShizuku(c, f);
            if (err.isEmpty() || currentCode(c) >= code) return;   // 装好后本进程会被系统结束，走不到这里很正常
            Log.w(TAG, "shizuku install failed: " + err);          // 失败：退回下面的系统安装器
        }

        if (!c.getPackageManager().canRequestPackageInstalls()) {
            set(NEED_PERM, 0, "");
            if (!appVisible) {
                notify(c, "留白 " + latestName + " 已下载", "点这里，允许“安装未知应用”后就能自动更新",
                        PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            return;
        }
        set(INSTALLING, 0, "");
        install(c, f);
    }

    /** 最新发布的标签（如 v1.0.12）；还没有发布过时返回 null。 */
    private static String latestTag() throws IOException {
        HttpURLConnection h = (HttpURLConnection)
                new URL("https://github.com/" + REPO + "/releases/latest").openConnection();
        try {
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(10000);
            h.setReadTimeout(15000);
            h.setRequestProperty("User-Agent", "Liubai-Updater");
            h.getResponseCode();
            String loc = h.getHeaderField("Location");
            if (loc == null) return null;
            int i = loc.lastIndexOf("/tag/");
            if (i < 0) return null;
            String tag = loc.substring(i + 5);
            int q = tag.indexOf('?');
            return q >= 0 ? tag.substring(0, q) : tag;
        } finally {
            h.disconnect();
        }
    }

    /** v1.0.12 → 12（发布标签的最后一段就是构建号，也就是 versionCode）。 */
    private static int parseCode(String tag) {
        if (tag == null) return 0;
        try {
            return Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 安装包存放处。优先放在外部文件目录（/sdcard/Android/data/<包名>/files/update）：
     * Shizuku 的 shell 进程读不了应用的私有目录，却读得了这里。没有外部存储就退回私有缓存。
     */
    private static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        File d = new File(base != null ? base : c.getCacheDir(), "update");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 清掉已经下载的安装包（装好了 / 已是最新）。 */
    static void cleanup(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 是留白自己的安装包，并且版本不低于 code。 */
    private static boolean valid(Context c, File f, int code) {
        if (!f.isFile() || f.length() == 0) return false;
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            return pi != null && c.getPackageName().equals(pi.packageName) && codeOf(pi) >= code;
        } catch (Exception e) {
            return false;
        }
    }

    private static void download(Context c, String url, File dest) throws IOException {
        set(DOWNLOADING, 0, "");
        File part = new File(dest.getPath() + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "Liubai-Updater");
            int rc = h.getResponseCode();
            if (rc != 200) throw new IOException("下载失败（HTTP " + rc + "）");
            long total = h.getContentLengthLong();
            long got = 0;
            int lastPct = -2;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int pct = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pct != lastPct) {
                        lastPct = pct;
                        set(DOWNLOADING, pct, "");
                    }
                }
            }
            if (total > 0 && got != total) throw new IOException("下载不完整");
        } finally {
            h.disconnect();
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!part.renameTo(dest)) throw new IOException("保存安装包失败");
    }

    // ---------------- 安装 ----------------

    /** 让 Shizuku 的 shell 服务静默安装；返回空字符串表示命令成功，否则是失败原因。最多等 2 分钟。 */
    private static String installViaShizuku(Context c, File apk) {
        final String[] result = {"Shizuku 没有返回结果"};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ShizukuGrant.install(c, apk.getAbsolutePath(), err -> {
            result[0] = err == null ? "" : err;
            latch.countDown();
        });
        try {
            latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        if (lastSession >= 0) {            // 上一次没走完的会话先丢掉
            try {
                pi.abandonSession(lastSession);
            } catch (Exception ignored) { }
            lastSession = -1;
        }
        PackageInstaller.SessionParams p =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        lastSession = id;
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("liubai.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            // 安装器会往这个 PendingIntent 里塞结果，所以 Android 12+ 必须是 mutable（意图本身指定了接收者）
            Intent result = new Intent(c, UpdateReceiver.class).setAction("com.liubai.app.UPDATE_RESULT");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, result, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) { }
            lastSession = -1;
            throw e;
        }
    }

    // ---------------- 安装结果（UpdateReceiver 调用） ----------------

    static void onInstalled(Context c) {
        lastSession = -1;
        cleanup(c);
        set(UP_TO_DATE, 0, "");
    }

    static void onFailed(Context c, boolean cancelled, String why) {
        lastSession = -1;
        if (cancelled) {
            set(AVAILABLE, 0, "");
        } else {
            cleanup(c);
            set(ERROR, 0, "安装失败" + (why == null || why.isEmpty() ? "" : "：" + why));
        }
    }

    /** 系统要用户点一下确认：前台直接弹出，后台发通知。 */
    static void onNeedConfirm(Context c, Intent confirm) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (appVisible) {
            try {
                c.startActivity(confirm);
                return;
            } catch (Exception e) {
                Log.w(TAG, "start confirm failed", e);
            }
        }
        notify(c, "留白有新版本 " + latestName, "点这里完成更新",
                PendingIntent.getActivity(c, 1, confirm,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    // ---------------- 通知 / 状态 ----------------

    private static void notify(Context c, String title, String text, PendingIntent pi) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "软件更新",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, CHANNEL)
                    : new android.app.Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(7301, b.build());
        } catch (Exception e) {
            Log.w(TAG, "notify failed", e);
        }
    }

    private static void set(int s, int pct, String msg) {
        state = s;
        percent = pct;
        message = msg;
        Runnable r = onChanged;
        if (r != null) r.run();
    }
}
