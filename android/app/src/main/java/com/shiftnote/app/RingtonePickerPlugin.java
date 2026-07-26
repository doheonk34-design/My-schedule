package com.shiftnote.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
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
}
