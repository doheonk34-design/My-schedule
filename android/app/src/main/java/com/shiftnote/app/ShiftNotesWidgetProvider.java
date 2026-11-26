package com.shiftnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class ShiftNotesWidgetProvider extends AppWidgetProvider {

    public static final String PREFS_NAME = "com.shiftnote.app.notesWidget";
    public static final String PREF_TODAY = "today";
    public static final String PREF_UPCOMING = "upcoming";
    /** 오늘 이후 메모 목록 JSON 배열: [{date:"YYYY-MM-DD", time:"HH:MM"|"", text}] (날짜·시간순) */
    public static final String PREF_ITEMS = "items";
    /** 기기 간 동기화 문서 주소 (Firestore REST). 있으면 30분마다 서버에서 최신 메모를 직접 가져옴 */
    public static final String PREF_SYNC_URL = "syncUrl";
    /** 마지막으로 반영한 동기화 버전(clientUpdatedAt) - 이보다 새 데이터만 반영 */
    public static final String PREF_KNOWN_VERSION = "knownVersion";

    private static final int MAX_ITEMS = 6;
    private static final int MAX_LINE_CHARS = 22; // 한 항목은 한 줄로 (길면 …), 긴 메모 하나가 위젯을 다 차지하지 않게

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
        // 30분마다: 우선 저장된 데이터로 날짜 기준 다시 그리고,
        for (int id : appWidgetIds) {
            updateOne(context, appWidgetManager, id);
        }
        // 동기화를 쓰는 중이면 서버에서 최신 메모를 가져와 다시 그림 (다른 기기에서 쓴 메모도 앱을 안 열고 반영)
        final Context appCtx = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (refreshFromServer(appCtx)) updateAll(appCtx);
            } catch (Exception ignored) {
            } finally {
                pending.finish();
            }
        }).start();
    }

    /** 동기화 문서에서 메모를 읽어 PREF_ITEMS 갱신. 새 데이터가 반영되면 true */
    static boolean refreshFromServer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(PREF_SYNC_URL, null);
        if (url == null || url.isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return false;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject fields = new JSONObject(sb.toString()).optJSONObject("fields");
            if (fields == null) return false;

            long version = numberValue(fields.optJSONObject("clientUpdatedAt"));
            long known = prefs.getLong(PREF_KNOWN_VERSION, 0L);
            if (version <= known) return false; // 이 기기가 이미 아는 것과 같거나 오래된 데이터

            JSONObject notes = path(fields, "payload", "notes");
            String todayIso = ShiftWidgetProvider.todayIso();
            List<String[]> list = new ArrayList<>();
            if (notes != null) {
                Iterator<String> it = notes.keys();
                while (it.hasNext()) {
                    String iso = it.next();
                    if (iso.compareTo(todayIso) < 0) continue;
                    JSONObject arr = notes.optJSONObject(iso).optJSONObject("arrayValue");
                    JSONArray values = arr == null ? null : arr.optJSONArray("values");
                    if (values == null) continue;
                    for (int i = 0; i < values.length(); i++) {
                        JSONObject mv = values.optJSONObject(i).optJSONObject("mapValue");
                        JSONObject nf = mv == null ? null : mv.optJSONObject("fields");
                        if (nf == null) continue;
                        String time = stringValue(nf.optJSONObject("time"));
                        String text = stringValue(nf.optJSONObject("text"));
                        list.add(new String[]{iso, time, text});
                    }
                }
            }
            // 날짜순, 같은 날은 시간 있는 것 먼저(시간순) → 시간 없는 것 (앱과 같은 순서)
            Collections.sort(list, (a, b) -> {
                int c = a[0].compareTo(b[0]);
                if (c != 0) return c;
                if (!a[1].isEmpty() && !b[1].isEmpty()) return a[1].compareTo(b[1]);
                if (!a[1].isEmpty()) return -1;
                if (!b[1].isEmpty()) return 1;
                return 0;
            });
            JSONArray items = new JSONArray();
            for (int i = 0; i < list.size() && i < 200; i++) {
                JSONObject o = new JSONObject();
                o.put("date", list.get(i)[0]);
                o.put("time", list.get(i)[1]);
                o.put("text", list.get(i)[2]);
                items.put(o);
            }
            prefs.edit().putString(PREF_ITEMS, items.toString()).putLong(PREF_KNOWN_VERSION, version).commit();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- Firestore REST 응답(타입이 붙은 JSON) 읽기 도우미 ----
    private static JSONObject path(JSONObject fields, String... keys) {
        JSONObject cur = fields;
        for (String k : keys) {
            JSONObject v = cur == null ? null : cur.optJSONObject(k);
            JSONObject mv = v == null ? null : v.optJSONObject("mapValue");
            cur = mv == null ? null : mv.optJSONObject("fields");
        }
        return cur;
    }

    private static String stringValue(JSONObject v) {
        return v == null ? "" : v.optString("stringValue", "");
    }

    private static long numberValue(JSONObject v) {
        if (v == null) return 0L;
        if (v.has("integerValue")) {
            try { return Long.parseLong(v.optString("integerValue", "0")); } catch (Exception e) { return 0L; }
        }
        return (long) v.optDouble("doubleValue", 0);
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() > MAX_LINE_CHARS ? t.substring(0, MAX_LINE_CHARS) + "…" : t;
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
                        t.append(oneLine((time.isEmpty() ? "" : time + " ") + text));
                        tc++;
                    } else if (cmp > 0 && uc < MAX_ITEMS && date.length() >= 10) {
                        if (uc > 0) u.append('\n');
                        int month = Integer.parseInt(date.substring(5, 7));
                        int day = Integer.parseInt(date.substring(8, 10));
                        u.append(oneLine(month + "/" + day + " " + text));
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
