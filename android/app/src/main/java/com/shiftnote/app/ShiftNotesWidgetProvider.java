package com.shiftnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class ShiftNotesWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "com.shiftnote.app.notesWidget";
    public static final String PREF_TODAY = "today";
    public static final String PREF_UPCOMING = "upcoming";

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
