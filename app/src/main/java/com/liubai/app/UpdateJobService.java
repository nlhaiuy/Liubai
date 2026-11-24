package com.liubai.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 后台自动更新：每 6 小时、在不计流量的网络（Wi-Fi）下查一次，有新版本就下载并安装。
 * 打开 App 时另外会查一次（见 MainActivity），所以这里只是兜底。
 */
public class UpdateJobService extends JobService {
    private static final int JOB_ID = 7301;
    private static final long PERIOD_MS = 6L * 60 * 60 * 1000;

    @Override
    public boolean onStartJob(JobParameters p) {
        if (!Updater.auto(this)) return false;
        Updater.run(getApplicationContext(), false, () -> jobFinished(p, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return false;
    }

    /** 自动更新开着就保证有这个定时任务，关了就取消。重复调用没有副作用。 */
    static void schedule(Context c, boolean on) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!on) {
                js.cancel(JOB_ID);
                return;
            }
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) { }
    }
}
