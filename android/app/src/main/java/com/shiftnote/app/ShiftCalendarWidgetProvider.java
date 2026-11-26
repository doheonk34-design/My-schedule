package com.shiftnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class ShiftCalendarWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "com.shiftnote.app.calendarWidget";
    public static final String PREF_TITLE = "title";
    public static final String PREF_CELLS = "cells"; // JSON 배열, [{day, label, labelColor, today, dots:[...]}]
    /** 월별 데이터 JSON: {"YYYY-MM": {title, cells}} - 앱을 안 열어도 달이 바뀌면 새 달을 보여주기 위함 */
    public static final String PREF_MONTHS = "months";

    /** 지금 날짜에 맞는 월 데이터. 없으면 null */
    static org.json.JSONObject currentMonth(SharedPreferences prefs) {
        try {
            org.json.JSONObject months = new org.json.JSONObject(prefs.getString(PREF_MONTHS, "{}"));
            return months.optJSONObject(ShiftWidgetProvider.todayIso().substring(0, 7));
        } catch (Exception e) {
            return null;
        }
    }

    /** JS 쪽에서 데이터가 바뀔 때마다 호출: 저장 + 화면에 붙은 모든 달력 위젯을 즉시 갱신 */
    public static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ShiftCalendarWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            ShiftCalendarWidgetProvider provider = new ShiftCalendarWidgetProvider();
            for (int id : ids) {
                provider.updateOne(context, mgr, id);
            }
            // 그리드 안의 셀 데이터가 바뀌었으니 각 셀을 새로 그리게 함
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.cal_grid);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateOne(context, appWidgetManager, id);
        }
        // 30분마다 호출될 때 칸도 다시 그려서, 자정이 지나면 "오늘" 표시가 옮겨가고 달이 바뀌면 새 달로 바뀜
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.cal_grid);
        // 동기화 중이면 다른 기기에서 바꾼 근무표도 반영
        WidgetSync.refreshAsync(context, goAsync(), null);
    }

    private void updateOne(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String title = prefs.getString(PREF_TITLE, "");
        org.json.JSONObject month = currentMonth(prefs);
        if (month != null) title = month.optString("title", title);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.shift_calendar_widget);
        views.setTextViewText(R.id.cal_widget_title, title);

        // GridView는 RemoteViewsService를 통해 42칸을 각각 필요할 때 그림 (한번에 다 안 넣음 -> 위젯 복잡도 제한 회피)
        Intent svcIntent = new Intent(context, CalendarWidgetService.class);
        svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svcIntent.setData(android.net.Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.cal_grid, svcIntent);

        // 그리드 안 개별 칸을 눌러도 앱이 열리도록 (모든 칸이 같은 동작이라 템플릿 방식 사용)
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setPendingIntentTemplate(R.id.cal_grid, pending);
        views.setOnClickPendingIntent(R.id.cal_widget_root, pending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}
