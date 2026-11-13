package com.liubai.app;

/** Shizuku 用户服务接口：服务运行在 shell 身份的独立进程里。 */
interface IShellService {
    /** Shizuku 约定的销毁方法，事务号固定。 */
    void destroy() = 16777114;

    /** 读出系统当前所有音频播放器的状态行（每行含 u/pid 和 state），已在服务端过滤，避免超过 Binder 容量。 */
    String playbackDump() = 1;

    /**
     * 以 shell 身份替留白开启一项权限。what 与主页的权限编号一致：
     * 0 悬浮窗、1 通知、2 后台运行（电池白名单）、3 无障碍、4 通知使用权。
     * component 是 包名/类名（无障碍、通知使用权用）。成功返回空字符串，失败返回原因。
     */
    String grant(int what, String pkg, String component) = 2;
}
