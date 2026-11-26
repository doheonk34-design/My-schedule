package com.shiftnote.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 근무표 알람을 네이티브에서 직접 예약한다.
 *
 * JS가 "근무 순환 규칙"(기준일, 순서, 날짜별 수정, 근무유형별 알람 설정)을 넘겨주면,
 * 여기서 앞으로 DAYS_AHEAD일치 알람을 계산해 AlarmManager에 등록한다.
 * 매일 새벽 스스로 다시 계산(REFRESH)하고, 재부팅·시간 변경 때도 다시 계산하므로
 * 앱을 오랫동안 열지 않아도 알람이 계속 이어진다.
 */
public class AlarmScheduler {

    static final String PREFS = "com.shiftnote.app.alarms";
    static final String KEY_MODEL = "model";
    static final String KEY_COUNT = "scheduledCount";

    static final String ACTION_FIRE = "com.shiftnote.app.ALARM_FIRE";
    static final String ACTION_REFRESH = "com.shiftnote.app.ALARM_REFRESH";

    static final int DAYS_AHEAD = 14;      // 14일치를 미리 등록 (매일 갱신하므로 며칠 갱신이 밀려도 여유가 있음)
    static final int REQ_BASE = 200000;    // 알람별 PendingIntent requestCode 시작값
    static final int REQ_REFRESH = 199999; // 매일 재계산용
    static final long DAY_MS = 86400000L;

    /** JS에서 받은 규칙 저장 (null이면 알람 전부 해제) */
    static void saveModel(Context ctx, String modelJson) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (modelJson == null) e.remove(KEY_MODEL); else e.putString(KEY_MODEL, modelJson);
        e.commit();
    }

    /** 저장된 규칙으로 알람을 전부 다시 등록. 결과: {count, next(ms, 없으면 0)} */
    static synchronized JSONObject scheduleAll(Context ctx) {
        JSONObject result = new JSONObject();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        try {
            result.put("count", 0);
            result.put("next", 0L);
        } catch (Exception ignored) {}
        if (am == null) return result;

        cancelPrevious(ctx, am, prefs.getInt(KEY_COUNT, 0));

        String json = prefs.getString(KEY_MODEL, null);
        if (json == null) {
            prefs.edit().putInt(KEY_COUNT, 0).commit();
            cancelRefresh(ctx, am);
            return result;
        }

        int idx = 0;
        long next = 0L;
        try {
            JSONObject model = new JSONObject(json);
            JSONArray seq = model.optJSONArray("sequence");
            JSONObject overrides = model.optJSONObject("overrides");
            JSONObject types = model.optJSONObject("types");
            Calendar ref = parseIso(model.optString("refDate", ""));
            int seqLen = seq == null ? 0 : seq.length();

            Calendar today = Calendar.getInstance();
            setMidnight(today);
            long now = System.currentTimeMillis();
            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

            for (int d = 0; d < DAYS_AHEAD; d++) {
                Calendar day = (Calendar) today.clone();
                day.add(Calendar.DAY_OF_MONTH, d);
                String iso = isoFmt.format(day.getTime());

                String typeId = null;
                if (overrides != null && overrides.has(iso)) {
                    typeId = overrides.optString(iso, "");
                } else if (seqLen > 0 && ref != null) {
                    long diff = Math.round((day.getTimeInMillis() - ref.getTimeInMillis()) / (double) DAY_MS);
                    int i = (int) (((diff % seqLen) + seqLen) % seqLen);
                    typeId = seq.optString(i, "");
                }
                if (typeId == null || typeId.isEmpty() || types == null) continue;

                JSONObject t = types.optJSONObject(typeId);
                if (t == null || !t.optBoolean("usable", true)) continue;
                JSONObject a = t.optJSONObject("alarm");
                if (a == null || !a.optBoolean("enabled", false)) continue;
                String time = a.optString("time", "");
                if (!time.matches("\\d{1,2}:\\d{2}")) continue;

                String[] hm = time.split(":");
                int repeat = Math.max(1, Math.min(10, a.optInt("repeat", 1)));
                String channelId = a.optString("channelId", "alarm_classic");
                String sound = a.optString("sound", "classic"); // 기본 알람음 id 또는 사용자가 고른 소리 주소
                String name = t.optString("name", "");
                String group = iso + ":" + typeId; // 같은 날·같은 근무의 재알림 묶음

                for (int r = 0; r < repeat; r++) {
                    Calendar fire = (Calendar) day.clone();
                    fire.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                    fire.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                    fire.add(Calendar.MINUTE, r * 3); // 3분 간격 재알림
                    long at = fire.getTimeInMillis();
                    if (at <= now) continue;

                    int req = REQ_BASE + idx;
                    Intent it = new Intent(ctx, AlarmReceiver.class)
                        .setAction(ACTION_FIRE)
                        .putExtra("title", "⏰ 교대노트 알람")
                        .putExtra("body", name + " 근무 시간이에요" + (r > 0 ? " (재알림 " + (r + 1) + "/" + repeat + ")" : ""))
                        .putExtra("channelId", channelId)
                        .putExtra("sound", sound)
                        .putExtra("group", group)
                        .putExtra("notifId", req);
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, req, it,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    setAlarm(ctx, am, at, pi);
                    if (next == 0L || at < next) next = at;
                    idx++;
                }
            }
        } catch (Exception ignored) {
            // 규칙이 깨졌으면 지금까지 등록한 것만 유지
        }

        prefs.edit().putInt(KEY_COUNT, idx).commit();
        scheduleRefresh(ctx, am);
        try {
            result.put("count", idx);
            result.put("next", next);
        } catch (Exception ignored) {}
        return result;
    }

    /** 정확한 시각 알람. 권한이 없으면(안드로이드 12+에서 사용자가 끈 경우) 근사 알람으로 대체 */
    private static void setAlarm(Context ctx, AlarmManager am, long at, PendingIntent pi) {
        boolean exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exactAllowed) {
            // 시계 앱 알람과 같은 방식: 절전(Doze) 중에도 정시에 울리고, 상태바에 알람 아이콘이 표시됨
            Intent show = new Intent(ctx, MainActivity.class);
            PendingIntent showPi = PendingIntent.getActivity(ctx, 3, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancelPrevious(Context ctx, AlarmManager am, int count) {
        for (int i = 0; i < count; i++) {
            Intent it = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_FIRE);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_BASE + i, it,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
    }

    private static PendingIntent refreshIntent(Context ctx, int flags) {
        Intent it = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(ctx, REQ_REFRESH, it, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 다음 날 00:10쯤 다시 계산 (근사 알람이어도 충분) */
    private static void scheduleRefresh(Context ctx, AlarmManager am) {
        Calendar c = Calendar.getInstance();
        setMidnight(c);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MINUTE, 10);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(),
            refreshIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private static void cancelRefresh(Context ctx, AlarmManager am) {
        PendingIntent pi = refreshIntent(ctx, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    private static void setMidnight(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static Calendar parseIso(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}.*")) return null;
        Calendar c = Calendar.getInstance();
        setMidnight(c);
        c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10)));
        return c;
    }
}
