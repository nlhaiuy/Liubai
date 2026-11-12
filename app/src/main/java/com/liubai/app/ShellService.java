package com.liubai.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 运行在 Shizuku 拉起的 shell 身份进程里（不是留白的主进程，拿不到 Context）。
 * 只做一件事：读 dumpsys audio 里的播放器状态行。
 */
public class ShellService extends IShellService.Stub {

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
}
