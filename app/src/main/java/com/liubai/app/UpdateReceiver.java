package com.liubai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** 接收系统安装器对更新安装的结果：要确认就弹出 / 提醒，成功或失败就更新状态。 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = android.os.Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) Updater.onNeedConfirm(c.getApplicationContext(), confirm);
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Updater.onInstalled(c.getApplicationContext());
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:      // 用户在确认页点了取消
                Updater.onFailed(c.getApplicationContext(), true, "");
                break;
            default:
                Updater.onFailed(c.getApplicationContext(), false,
                        i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                break;
        }
    }
}
