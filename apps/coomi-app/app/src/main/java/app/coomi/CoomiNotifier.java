package app.coomi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.termux.shared.logger.Logger;

import androidx.core.app.NotificationCompat;

public class CoomiNotifier {

    private static final String LOG_TAG = "CoomiNotifier";
    private static final String CHANNEL_ID = "coomi_notifications";
    private static final String CHANNEL_NAME = "Coomi 通知";
    private int notificationId = 2000;

    private final Context mContext;
    private final NotificationManager mNm;

    public CoomiNotifier(Context context) {
        mContext = context.getApplicationContext();
        mNm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            );
            ch.setDescription("Agent 任务完成、提醒等通知");
            mNm.createNotificationChannel(ch);
        }
    }

    public void notify(String title, String body) {
        if (title == null || title.trim().isEmpty()) return;
        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build();
        try {
            mNm.notify(notificationId++, notification);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to post notification: " + e.getMessage());
        }
    }

    public void cancelAll() {
        mNm.cancelAll();
    }
}
