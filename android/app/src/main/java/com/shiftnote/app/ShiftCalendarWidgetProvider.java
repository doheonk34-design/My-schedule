package com.shiftnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

public class ShiftCalendarWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "com.shiftnote.app.calendarWidget";
    public static final String PREF_TITLE = "title";
    public static final String PREF_CELLS = "cells"; // JSON 배열, [{day, color, today}, ...] 최대 42개

    public static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ShiftCalendarWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            ShiftCalendarWidgetProvider provider = new ShiftCalendarWidgetProvider();
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
        String title = prefs.getString(PREF_TITLE, "");
        String cellsJson = prefs.getString(PREF_CELLS, "[]");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.shift_calendar_widget);
        views.setTextViewText(R.id.cal_widget_title, title);

        try {
            JSONArray cells = new JSONArray(cellsJson);
            for (int i = 0; i < 42; i++) {
                int cellId = context.getResources().getIdentifier("cal_cell_" + i, "id", context.getPackageName());
                if (cellId == 0) continue;

                if (i < cells.length()) {
                    JSONObject cell = cells.getJSONObject(i);
                    String day = cell.optString("day", "");
                    String color = cell.optString("color", "");
                    boolean isToday = cell.optBoolean("today", false);

                    views.setTextViewText(cellId, day);
                    if (!color.isEmpty()) {
                        try {
                            views.setInt(cellId, "setBackgroundColor", Color.parseColor(color));
                        } catch (Exception ignored) {}
                    } else {
                        views.setInt(cellId, "setBackgroundColor", Color.TRANSPARENT);
                    }
                    views.setInt(cellId, "setTextColor", isToday ? Color.parseColor("#FF5D5D") : Color.parseColor("#E7EAF0"));
                } else {
                    views.setTextViewText(cellId, "");
                    views.setInt(cellId, "setBackgroundColor", Color.TRANSPARENT);
                }
            }
        } catch (Exception e) {
            // 데이터가 아직 없거나 파싱 실패하면 빈 달력으로 둠
        }

        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.cal_widget_root, pending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}
