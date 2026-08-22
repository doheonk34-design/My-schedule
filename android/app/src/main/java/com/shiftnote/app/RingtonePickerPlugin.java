package com.shiftnote.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "RingtonePicker")
public class RingtonePickerPlugin extends Plugin {

    /**
     * 안드로이드 시스템 벨소리/알람음 선택 화면을 띄운다.
     * (삼성 기본 벨소리, 알람음, 알림음을 전부 포함해서 보여줌)
     */
    @PluginMethod
    public void pickRingtone(PluginCall call) {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "알람음 선택");

        saveCall(call);
        startActivityForResult(call, intent, "ringtonePickerResult");
    }

    @ActivityCallback
    private void ringtonePickerResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        JSObject ret = new JSObject();
        if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) {
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }

        Uri uri = result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) {
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }

        String name = "선택한 소리";
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(getContext(), uri);
            if (ringtone != null) {
                name = ringtone.getTitle(getContext());
            }
        } catch (Exception e) {
            // 이름을 못 가져와도 URI 자체는 유효하므로 계속 진행
        }

        ret.put("cancelled", false);
        ret.put("uri", uri.toString());
        ret.put("name", name);
        call.resolve(ret);
    }

    /**
     * 고른 벨소리(URI)를 실제로 사용하려면 그 소리를 담은 알림 채널을 만들어야 함
     * (안드로이드 8+ 는 알림 소리가 채널에 고정되기 때문).
     */
    @PluginMethod
    public void createCustomSoundChannel(PluginCall call) {
        String channelId = call.getString("channelId");
        String channelName = call.getString("channelName", "교대노트 알람");
        String uriString = call.getString("uri");

        if (channelId == null || uriString == null) {
            call.reject("channelId와 uri가 필요합니다");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = getContext();
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            // 같은 id로 이미 채널이 있으면 소리를 바꿀 수 없으므로(안드로이드 제약) 지우고 새로 만든다.
            NotificationChannel existing = manager.getNotificationChannel(channelId);
            if (existing != null) {
                manager.deleteNotificationChannel(channelId);
            }

            NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build();

            try {
                Uri soundUri = Uri.parse(uriString);
                channel.setSound(soundUri, audioAttributes);
            } catch (Exception e) {
                call.reject("소리 URI가 올바르지 않습니다: " + e.getMessage());
                return;
            }

            manager.createNotificationChannel(channel);
        }

        call.resolve();
    }

    /**
     * 홈 화면 위젯에 표시할 "오늘의 근무" 정보를 저장하고, 붙어있는 위젯을 즉시 새로고침한다.
     */
    @PluginMethod
    public void updateWidget(PluginCall call) {
        String label = call.getString("label", "-");
        String range = call.getString("range", "");
        String team = call.getString("team", "");
        String color = call.getString("color", "#6C7BFF");

        Context context = getContext();
        SharedPreferences prefs = context.getSharedPreferences(ShiftWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(ShiftWidgetProvider.PREF_LABEL, label)
            .putString(ShiftWidgetProvider.PREF_RANGE, range)
            .putString(ShiftWidgetProvider.PREF_TEAM, team)
            .putString(ShiftWidgetProvider.PREF_COLOR, color)
            .apply();

        ShiftWidgetProvider.updateAll(context);
        call.resolve();
    }

    /**
     * 4x4 달력 위젯 갱신 - JS에서 이번달 42칸(6주 x 7일) 데이터를 JSON 배열로 넘겨받아 저장.
     * 각 칸: {day: "21", color: "#FFB648", today: true/false}
     */
    @PluginMethod
    public void updateCalendarWidget(PluginCall call) {
        String title = call.getString("title", "");
        com.getcapacitor.JSArray cells = call.getArray("cells");

        Context context = getContext();
        SharedPreferences prefs = context.getSharedPreferences(ShiftCalendarWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(ShiftCalendarWidgetProvider.PREF_TITLE, title)
            .putString(ShiftCalendarWidgetProvider.PREF_CELLS, cells != null ? cells.toString() : "[]")
            .apply();

        ShiftCalendarWidgetProvider.updateAll(context);
        call.resolve();
    }

    /**
     * 기본 제공 알람음 5종의 알림 채널을 "진짜 알람"처럼 동작하도록 직접 만든다.
     * (Capacitor의 기본 createChannel()로 만들면 일반 알림 오디오 스트림을 타서
     *  무음모드/알림 음량에 영향을 받아 화면 잠금 중엔 소리가 안 날 수 있었음.
     *  여기서는 시계 알람과 같은 USAGE_ALARM 오디오 스트림을 명시적으로 지정한다.)
     */
    @PluginMethod
    public void setupPresetAlarmChannels(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = getContext();
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            String[] presetIds = {"classic", "chime", "digital", "gentle", "radar"};
            String[] presetNames = {"클래식벨", "차임벨", "디지털", "잔잔한알림", "레이더"};

            for (int i = 0; i < presetIds.length; i++) {
                String channelId = "alarm_" + presetIds[i];
                String channelName = "교대노트 알람 · " + presetNames[i];
                String resName = "alarm_" + presetIds[i];

                NotificationChannel existing = manager.getNotificationChannel(channelId);
                if (existing != null) {
                    manager.deleteNotificationChannel(channelId);
                }

                NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
                channel.enableVibration(true);
                channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);

                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build();

                int resId = context.getResources().getIdentifier(resName, "raw", context.getPackageName());
                if (resId != 0) {
                    Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + resId);
                    channel.setSound(soundUri, audioAttributes);
                }

                manager.createNotificationChannel(channel);
            }
        }
        call.resolve();
    }

    /**
     * 이 앱이 배터리 최적화(절전) 제외 목록에 있는지 확인.
     * 최적화 대상에 포함돼있으면(=제외 안 돼있으면) 시스템이 백그라운드에서 앱을 재워서
     * 예약해둔 알람이 늦게 울리거나 아예 안 울릴 수 있음.
     */
    @PluginMethod
    public void checkBatteryOptimization(PluginCall call) {
        Context context = getContext();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        JSObject ret = new JSObject();
        ret.put("ignoring", ignoring);
        call.resolve(ret);
    }

    /**
     * 배터리 최적화 제외 요청 화면을 띄운다 (시스템 팝업으로 바로 뜨는 기기도 있고,
     * 설정 화면으로 넘어가는 기기도 있음 - 제조사마다 다름).
     */
    @PluginMethod
    public void requestIgnoreBatteryOptimization(PluginCall call) {
        Context context = getContext();
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // 일부 기기/OS 버전은 이 인텐트를 지원하지 않을 수 있음 - 조용히 무시
        }
        call.resolve();
    }
}
