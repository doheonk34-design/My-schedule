package com.shiftnote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/** AlarmScheduler가 등록한 알람이 울릴 때(FIRE)와 매일 재계산할 때(REFRESH) 호출됨 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String FALLBACK_CHANNEL = "alarm_fallback";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (AlarmScheduler.ACTION_REFRESH.equals(action)) {
            AlarmScheduler.scheduleAll(context);
            return;
        }
        if (!AlarmScheduler.ACTION_FIRE.equals(action)) return;

        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String channelId = intent.getStringExtra("channelId");
        int notifId = intent.getIntExtra("notifId", AlarmScheduler.REQ_BASE);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (channelId == null || nm.getNotificationChannel(channelId) == null) {
                // 채널이 아직 없으면(앱을 한 번도 안 열었거나 지워짐) 기본 알람음 채널로 대체
                if (nm.getNotificationChannel(FALLBACK_CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(FALLBACK_CHANNEL, "교대노트 알람 · 기본", NotificationManager.IMPORTANCE_HIGH);
                    ch.enableVibration(true);
                    ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build());
                    nm.createNotificationChannel(ch);
                }
                channelId = FALLBACK_CHANNEL;
            }
        }

        Intent open = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(context, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, channelId)
            : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title != null ? title : "교대노트 알람")
            .setContentText(body != null ? body : "")
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX);

        try {
            nm.notify(notifId, b.build());
        } catch (SecurityException ignored) {
            // 알림 권한(안드로이드 13+)이 꺼져 있으면 표시할 수 없음
        }
    }
}
