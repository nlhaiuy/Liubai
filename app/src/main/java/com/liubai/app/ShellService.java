package com.liubai.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

/**
 * 运行在 Shizuku 拉起的 shell 身份进程里（不是留白的主进程，拿不到 Context）。
 * 做两件事：读 dumpsys audio 里的播放器状态行；以 shell 身份替留白开启几项系统权限。
 * 命令都用参数数组直接执行（不经过 shell 解析），并且只接受包名 / 组件名这类安全字符。
 */
public class ShellService extends IShellService.Stub {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.$/]+");
    private static final String A11Y_KEY = "enabled_accessibility_services";

    public ShellService() { }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String playbackDump() {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "audio").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // 播放器那一行形如：... u/pid:10123/4567 state:started attr:AudioAttributes: usage=USAGE_MEDIA ...
                    if (line.contains("u/pid:") && line.contains("state:")) {
                        sb.append(line.trim()).append('\n');
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
        return sb.toString();
    }

    @Override
    public String grant(int what, String pkg, String component) {
        if (pkg == null || !SAFE.matcher(pkg).matches()) return "bad package";
        boolean needComp = what == 3 || what == 4;
        if (needComp && (component == null || !SAFE.matcher(component).matches())) return "bad component";
        try {
            switch (what) {
                case 0:   // 悬浮窗
                    return run("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow");
                case 1:   // 通知（Android 13+）
                    return run("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS");
                case 2:   // 后台运行：加入电池优化白名单
                    return run("cmd", "deviceidle", "whitelist", "+" + pkg);
                case 3:
                    return enableAccessibility(component);
                case 4:   // 通知使用权
                    return run("cmd", "notification", "allow_listener", component);
                default:
                    return "unsupported";
            }
        } catch (Exception e) {
            return String.valueOf(e);
        }
    }

    /**
     * 开启无障碍服务。已经在列表里（常见于“开着但没连上”）就先摘掉再加回去，让系统重新绑定一次。
     */
    private String enableAccessibility(String comp) throws Exception {
        String cur = out("settings", "get", "secure", A11Y_KEY).trim();
        if (cur.equals("null")) cur = "";
        StringBuilder without = new StringBuilder();
        boolean present = false;
        for (String part : cur.split(":")) {
            if (part.isEmpty()) continue;
            if (part.equals(comp)) { present = true; continue; }
            if (without.length() > 0) without.append(':');
            without.append(part);
        }
        if (present) {
            String e = without.length() == 0
                    ? run("settings", "delete", "secure", A11Y_KEY)
                    : run("settings", "put", "secure", A11Y_KEY, without.toString());
            if (!e.isEmpty()) return e;
            Thread.sleep(400);
        }
        String next = without.length() == 0 ? comp : without + ":" + comp;
        String e = run("settings", "put", "secure", A11Y_KEY, next);
        if (!e.isEmpty()) return e;
        return run("settings", "put", "secure", "accessibility_enabled", "1");
    }

    /** 执行命令，返回标准输出 + 错误输出。 */
    private static String out(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    /** 执行命令：成功返回空字符串，失败返回输出里的原因。 */
    private static String run(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        int code = p.waitFor();
        String o = sb.toString().trim();
        if (code != 0) return o.isEmpty() ? "exit " + code : o;
        // 个别命令失败时退出码仍是 0，只在输出里带 Exception / Error
        if (o.contains("Exception") || o.startsWith("Error")) return o;
        return "";
    }
}
