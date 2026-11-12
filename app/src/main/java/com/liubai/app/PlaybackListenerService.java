package com.liubai.app;

import android.service.notification.NotificationListenerService;

/**
 * 只为了拿到“通知使用权”，好让 MediaSessionManager 肯告诉我们各个软件的播放状态。
 * 不读取、不处理任何通知内容。
 */
public class PlaybackListenerService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        PlaybackDetector.onListenerConnected(this);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        PlaybackDetector.onListenerDisconnected(this);
    }
}
