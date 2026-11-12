package com.liubai.app;

/** Shizuku 用户服务接口：服务运行在 shell 身份的独立进程里。 */
interface IShellService {
    /** Shizuku 约定的销毁方法，事务号固定。 */
    void destroy() = 16777114;

    /** 读出系统当前所有音频播放器的状态行（每行含 u/pid 和 state），已在服务端过滤，避免超过 Binder 容量。 */
    String playbackDump() = 1;
}
