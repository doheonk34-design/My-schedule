package com.shiftnote.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import org.json.JSONArray;
import org.json.JSONObject;

public class CalendarWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new CalendarRemoteViewsFactory(getApplicationContext());
    }
}

class CalendarRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
    private final Context context;
    private JSONArray cells = new JSONArray();

    CalendarRemoteViewsFactory(Context context) {
        this.context = context;
    }

    @Override
    public void onCreate() {
        loadData();
    }

    @Override
    public void onDataSetChanged() {
        loadData();
    }

    private void loadData() {
        SharedPreferences prefs = context.getSharedPreferences(ShiftCalendarWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        try {
            cells = new JSONArray(prefs.getString(ShiftCalendarWidgetProvider.PREF_CELLS, "[]"));
        } catch (Exception e) {
            cells = new JSONArray();
        }
    }

    @Override
    public void onDestroy() {}

    @Override
    public int getCount() {
        return 42;
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.shift_calendar_cell);
        views.setOnClickFillInIntent(R.id.cell_root, new Intent());

        JSONObject cell = position < cells.length() ? cells.optJSONObject(position) : null;
        if (cell == null) {
            views.setTextViewText(R.id.cell_date, "");
            views.setTextViewText(R.id.cell_label, "");
            views.removeAllViews(R.id.cell_dots);
            views.setInt(R.id.cell_root, "setBackgroundColor", Color.TRANSPARENT);
            return views;
        }

        String day = cell.optString("day", "");
        String label = cell.optString("label", "");
        String labelColor = cell.optString("labelColor", "");
        boolean today = cell.optBoolean("today", false);
        JSONArray dots = cell.optJSONArray("dots");

        views.setTextViewText(R.id.cell_date, day);
        views.setInt(R.id.cell_date, "setTextColor", today ? Color.parseColor("#FF5D5D") : Color.parseColor("#E7EAF0"));
        views.setInt(R.id.cell_root, "setBackgroundResource", today ? R.drawable.widget_today_ring : android.R.color.transparent);

        views.setTextViewText(R.id.cell_label, label);
        try {
            views.setInt(R.id.cell_label, "setTextColor", !labelColor.isEmpty() ? Color.parseColor(labelColor) : Color.parseColor("#8992A6"));
        } catch (Exception ignored) {
            views.setInt(R.id.cell_label, "setTextColor", Color.parseColor("#8992A6"));
        }

        views.removeAllViews(R.id.cell_dots);
        if (dots != null) {
            for (int i = 0; i < dots.length() && i < 5; i++) {
                RemoteViews dot = new RemoteViews(context.getPackageName(), R.layout.shift_calendar_dot);
                try {
                    dot.setInt(R.id.dot_view, "setBackgroundColor", Color.parseColor(dots.optString(i)));
                } catch (Exception ignored) {}
                views.addView(R.id.cell_dots, dot);
            }
        }

        return views;
    }

    @Override
    public RemoteViews getLoadingView() {
        return null;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }
}
