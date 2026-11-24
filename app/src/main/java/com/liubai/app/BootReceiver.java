package com.liubai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 升级后：按总开关和模式恢复遮罩（白名单模式下仍由无障碍服务根据前台应用决定）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        AutoMode.evaluate(c);
        // 升级会清掉定时任务：重新排上自动更新；顺便清掉已经装好的安装包
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) Updater.cleanup(c);
        UpdateJobService.schedule(c, Updater.auto(c));
    }
}
