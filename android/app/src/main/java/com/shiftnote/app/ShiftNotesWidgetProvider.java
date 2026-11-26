package com.shiftnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

public class ShiftNotesWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "com.shiftnote.app.notesWidget";
    public static final String PREF_TODAY = "today";
    public static final String PREF_UPCOMING = "upcoming";
    /** 오늘 이후 메모 목록 JSON 배열: [{date:"YYYY-MM-DD", time:"HH:MM"|"", text}] (날짜·시간순) */
    public static final String PREF_ITEMS = "items";
    private static final int MAX_ITEMS = 6;

    public static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ShiftNotesWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            ShiftNotesWidgetProvider provider = new ShiftNotesWidgetProvider();
            for (int id : ids) {
                provider.updateOne(context, mgr, id);
            }
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateOne(context, appWidgetManager, id);
        }
    }

    private void updateOne(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String today = prefs.getString(PREF_TODAY, "일정 없음");
        String upcoming = prefs.getString(PREF_UPCOMING, "일정 없음");

        // 날짜별 목록이 있으면 "지금 날짜" 기준으로 다시 계산 (앱을 안 열고 날짜가 바뀌어도 맞게 표시)
        String itemsJson = prefs.getString(PREF_ITEMS, null);
        if (itemsJson != null) {
            try {
                JSONArray items = new JSONArray(itemsJson);
                String todayIso = ShiftWidgetProvider.todayIso();
                StringBuilder t = new StringBuilder();
                StringBuilder u = new StringBuilder();
                int tc = 0, uc = 0;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String date = it.optString("date", "");
                    String time = it.optString("time", "");
                    String text = it.optString("text", "");
                    int cmp = date.compareTo(todayIso);
                    if (cmp == 0 && tc < MAX_ITEMS) {
                        if (tc > 0) t.append('\n');
                        t.append(time.isEmpty() ? "" : time + " ").append(text);
                        tc++;
                    } else if (cmp > 0 && uc < MAX_ITEMS && date.length() >= 10) {
                        if (uc > 0) u.append('\n');
                        int month = Integer.parseInt(date.substring(5, 7));
                        int day = Integer.parseInt(date.substring(8, 10));
                        u.append(month).append('/').append(day).append(' ').append(text);
                        uc++;
                    }
                }
                today = tc > 0 ? t.toString() : "일정 없음";
                upcoming = uc > 0 ? u.toString() : "일정 없음";
            } catch (Exception ignored) {
                // 깨진 데이터면 마지막으로 받은 문자열 그대로 사용
            }
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.shift_notes_widget);
        views.setTextViewText(R.id.notes_widget_today, today);
        views.setTextViewText(R.id.notes_widget_upcoming, upcoming);

        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.notes_widget_root, pending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}
