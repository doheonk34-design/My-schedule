package com.shiftnote.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.util.Map;

/**
 * 끌 때까지 계속 울리는 알람.
 * - 알람음은 "알람" 음량(USAGE_ALARM)으로 반복 재생 → 무음·진동 모드에서도 울림
 * - 잠금 화면 위로 전체 화면 알람(AlarmActivity)을 띄움 (전체 화면 알림)
 * - [끄기]를 누르면 같은 근무의 남은 재알림(3분 간격)도 함께 취소, [5분 뒤]는 5분 후 다시 울림
 * - 10분 동안 아무도 끄지 않으면 자동으로 멈추고 "놓친 알람" 알림을 남김
 */
public class AlarmRingService extends Service {

    static final String ACTION_RING = "com.shiftnote.app.ALARM_RING";
    static final String ACTION_DISMISS = "com.shiftnote.app.ALARM_DISMISS";
    static final String ACTION_SNOOZE = "com.shiftnote.app.ALARM_SNOOZE";
    static final String ACTION_STOPPED = "com.shiftnote.app.ALARM_STOPPED"; // 알람 화면 닫기용 방송

    static final String EXTRA_TITLE = "title";
    static final String EXTRA_BODY = "body";
    static final String EXTRA_SOUND = "sound";   // "classic" 같은 기본 알람음 id 또는 content:// 주소
    static final String EXTRA_GROUP = "group";   // 같은 날·같은 근무의 알람 묶음 (재알림 취소용)

    private static final String CHANNEL = "alarm_ringing_v1";
    private static final int NOTIF_ID = 424242;
    private static final int MISSED_NOTIF_ID = 424243;
    private static final int REQ_SNOOZE = 198000;
    private static final long AUTO_STOP_MS = 10 * 60 * 1000L;
    private static final long SNOOZE_MS = 5 * 60 * 1000L;
    private static final String PREFS = "com.shiftnote.app.alarmRing";

    static volatile boolean ringing = false;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String curTitle = "", curBody = "", curSound = "", curGroup = "";

    private final Runnable autoStop = () -> {
        notifyMissed();
        stopRinging();
        stopForegroundCompat();
        stopSelf();
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISMISS.equals(action)) {
            markHandled(intent.getStringExtra(EXTRA_GROUP));
            finishRinging();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            markHandled(intent.getStringExtra(EXTRA_GROUP));
            scheduleSnooze();
            finishRinging();
            return START_NOT_STICKY;
        }
        if (ACTION_RING.equals(action)) {
            String group = intent.getStringExtra(EXTRA_GROUP);
            cleanupOldMarks();
            // 이미 [끄기]/[5분 뒤]로 처리한 근무의 남은 재알림이면 울리지 않음
            if (group != null && isHandled(group)) {
                if (!ringing) stopSelf();
                return START_NOT_STICKY;
            }
            curTitle = nz(intent.getStringExtra(EXTRA_TITLE), "⏰ 교대노트 알람");
            curBody = nz(intent.getStringExtra(EXTRA_BODY), "");
            curSound = nz(intent.getStringExtra(EXTRA_SOUND), "classic");
            curGroup = nz(group, "");
            startRinging();
            return START_NOT_STICKY;
        }
        if (!ringing) stopSelf();
        return START_NOT_STICKY;
    }

    private void startRinging() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        stopSound(); // 이전 알람이 울리던 중이면 교체
        ringing = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shiftnote:alarm");
            wakeLock.acquire(AUTO_STOP_MS + 60000L);
        }
        playSound();
        startVibration();
        handler.removeCallbacks(autoStop);
        handler.postDelayed(autoStop, AUTO_STOP_MS);

        // 앱이 화면에 떠 있을 때 등은 전체 화면 알림 대신 알림 배너만 나올 수 있어서 직접도 한 번 띄워 봄
        try {
            startActivity(activityIntent());
        } catch (Exception ignored) {
            // 백그라운드에서 화면 띄우기가 막힌 경우: 전체 화면 알림이 대신 띄움
        }
    }

    private Intent activityIntent() {
        return new Intent(this, AlarmActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(EXTRA_TITLE, curTitle)
            .putExtra(EXTRA_BODY, curBody)
            .putExtra(EXTRA_GROUP, curGroup);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "교대노트 알람 화면", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);          // 소리는 서비스가 직접 반복 재생
            ch.enableVibration(false);        // 진동도 서비스가 직접
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent full = PendingIntent.getActivity(this, 11, activityIntent(), flags);
        PendingIntent dismiss = PendingIntent.getService(this, 12,
            new Intent(this, AlarmRingService.class).setAction(ACTION_DISMISS).putExtra(EXTRA_GROUP, curGroup), flags);
        PendingIntent snooze = PendingIntent.getService(this, 13,
            new Intent(this, AlarmRingService.class).setAction(ACTION_SNOOZE).putExtra(EXTRA_GROUP, curGroup), flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(curTitle)
            .setContentText(curBody)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(new Notification.Action.Builder(null, "끄기", dismiss).build())
            .addAction(new Notification.Action.Builder(null, "5분 뒤", snooze).build());
        return b.build();
    }

    private void playSound() {
        Uri uri = resolveSound(curSound);
        if (!tryPlay(uri)) {
            tryPlay(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        }
    }

    private Uri resolveSound(String sound) {
        if (sound != null && (sound.startsWith("content://") || sound.startsWith("android.resource://") || sound.startsWith("file://"))) {
            return Uri.parse(sound);
        }
        String name = "alarm_" + (sound == null || sound.isEmpty() ? "classic" : sound);
        int resId = getResources().getIdentifier(name, "raw", getPackageName());
        if (resId == 0) resId = getResources().getIdentifier("alarm_classic", "raw", getPackageName());
        return resId != 0 ? Uri.parse("android.resource://" + getPackageName() + "/" + resId)
            : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    private boolean tryPlay(Uri uri) {
        if (uri == null) return false;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startVibration() {
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 800, 600};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}
    }

    private void stopSound() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
        }
    }

    private void stopRinging() {
        handler.removeCallbacks(autoStop);
        stopSound();
        ringing = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        // 떠 있는 알람 화면 닫기
        sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
    }

    private void finishRinging() {
        stopRinging();
        stopForegroundCompat();
        stopSelf();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    /** 5분 뒤 같은 알람을 다시 울림 (재알림 묶음과 겹치지 않도록 새 묶음 이름 사용) */
    private void scheduleSnooze() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + SNOOZE_MS;
        Intent it = new Intent(this, AlarmReceiver.class)
            .setAction(AlarmScheduler.ACTION_FIRE)
            .putExtra("title", curTitle)
            .putExtra("body", curBody.contains("다시 알림") ? curBody : curBody + " (다시 알림)")
            .putExtra("sound", curSound)
            .putExtra("group", "snooze:" + at)
            .putExtra("notifId", REQ_SNOOZE);
        PendingIntent pi = PendingIntent.getBroadcast(this, REQ_SNOOZE, it,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exact) {
            PendingIntent show = PendingIntent.getActivity(this, 14, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private void notifyMissed() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 15, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("놓친 알람")
            .setContentText(curBody + " — 10분 동안 꺼지지 않아 자동으로 멈췄어요")
            .setContentIntent(open)
            .setAutoCancel(true);
        try { nm.notify(MISSED_NOTIF_ID, b.build()); } catch (SecurityException ignored) {}
    }

    // ---- [끄기]/[5분 뒤]로 처리한 근무 묶음 기록 (남은 재알림 건너뛰기용, 2일 지나면 삭제) ----
    private void markHandled(String group) {
        if (group == null || group.isEmpty()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("h:" + group, System.currentTimeMillis()).apply();
    }

    private boolean isHandled(String group) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).contains("h:" + group);
    }

    private void cleanupOldMarks() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long limit = System.currentTimeMillis() - 2 * 86400000L;
        SharedPreferences.Editor e = p.edit();
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
            if (en.getValue() instanceof Long && (Long) en.getValue() < limit) e.remove(en.getKey());
        }
        e.apply();
    }

    private static String nz(String s, String d) { return s == null ? d : s; }

    @Override
    public void onDestroy() {
        stopRinging();
        super.onDestroy();
    }
}
