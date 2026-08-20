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

public class ShiftWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "com.shiftnote.app.widget";
    public static final String PREF_LABEL = "label";
    public static final String PREF_RANGE = "range";
    public static final String PREF_TEAM = "team";
    public static final String PREF_COLOR = "color";

    /** JS 쪽에서 데이터가 바뀔 때마다 이 메서드를 호출해서 화면에 붙어있는 모든 위젯을 즉시 갱신한다. */
    public static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, ShiftWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            ShiftWidgetProvider provider = new ShiftWidgetProvider();
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
        String label = prefs.getString(PREF_LABEL, "-");
        String range = prefs.getString(PREF_RANGE, "");
        String team = prefs.getString(PREF_TEAM, "");
        String colorStr = prefs.getString(PREF_COLOR, "#6C7BFF");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.shift_widget);
        views.setTextViewText(R.id.widget_label, (label == null || label.isEmpty()) ? "근무 정보 없음" : label);
        views.setTextViewText(R.id.widget_range, range == null ? "" : range);
        views.setTextViewText(R.id.widget_team, team == null ? "" : team);
        views.setTextViewText(R.id.widget_badge, (label != null && !label.isEmpty()) ? label.substring(0, 1) : "-");

        try {
            views.setInt(R.id.widget_badge, "setBackgroundColor", Color.parseColor(colorStr));
        } catch (Exception e) {
            views.setInt(R.id.widget_badge, "setBackgroundColor", Color.parseColor("#6C7BFF"));
        }

        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}
