/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

public class NotificationsService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String channelId = "mmtg_connection";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(channelId) == null) {
                    android.app.NotificationChannel ch = new android.app.NotificationChannel(channelId, "Background connection", android.app.NotificationManager.IMPORTANCE_MIN);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Intent open = new Intent(this, org.telegram.ui.LaunchActivity.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = new androidx.core.app.NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle("mm-tg")
                    .setContentText("Connected to Mattermost")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(0x6d6d, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(0x6d6d, n);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                org.telegram.mattermost.MMBackend.getInstance(a).ensureConnected();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
