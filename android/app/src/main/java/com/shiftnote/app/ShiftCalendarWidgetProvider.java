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
    }

    private void updateOne(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String title = prefs.getString(PREF_TITLE, "");

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
