package com.shiftnote.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 재부팅·앱 업데이트·시간/시간대 변경·정확한 알람 권한 변경 시 알람을 다시 등록 (AlarmManager 알람은 재부팅하면 사라짐) */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmScheduler.scheduleAll(context);
    }
}
