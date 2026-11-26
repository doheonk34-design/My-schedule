package com.shiftnote.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 앱을 열지 않아도 다른 기기에서 바꾼 근무표·메모를 위젯과 알람에 반영한다.
 *
 * 기기 간 동기화를 쓰는 경우, 위젯 갱신 주기(30분)와 매일 알람 재계산 때
 * 동기화 문서를 한 번만 받아서(여러 위젯이 같은 요청을 공유, 5분 안 재요청 생략)
 * - 오늘 근무 위젯(날짜별 60일치), 달력 위젯(이번 달 + 다음 두 달), 메모 위젯
 * - 네이티브 알람 규칙
 * 을 앱(웹 코드)과 같은 방식으로 다시 계산해 저장한다.
 */
public class WidgetSync {

    static final String PREFS = "com.shiftnote.app.widgetSync";
    static final String KEY_URL = "syncUrl";
    static final String KEY_KNOWN_VERSION = "knownVersion";
    static final String KEY_LAST_FETCH = "lastFetch";
    private static final long MIN_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long DAY_MS = 86400000L;

    /** JS에서 동기화 주소와 "이 기기가 이미 아는 버전"을 저장 */
    static void saveConfig(Context ctx, String syncUrl, long knownVersion) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (syncUrl != null && !syncUrl.isEmpty()) e.putString(KEY_URL, syncUrl); else e.remove(KEY_URL);
        e.putLong(KEY_KNOWN_VERSION, knownVersion);
        e.commit();
    }

    /** BroadcastReceiver.onReceive 안에서 호출: 백그라운드 스레드로 받아오고 끝나면 pending.finish() */
    static void refreshAsync(Context context, BroadcastReceiver.PendingResult pending, Runnable after) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                refresh(app, false);
            } catch (Exception ignored) {
            } finally {
                if (after != null) {
                    try { after.run(); } catch (Exception ignored) {}
                }
                if (pending != null) pending.finish();
            }
        }).start();
    }

    /** 서버에서 새 데이터가 있으면 위젯·알람 데이터를 다시 계산해 저장하고 위젯을 갱신. 반영했으면 true */
    static synchronized boolean refresh(Context ctx, boolean force) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = prefs.getString(KEY_URL, null);
        if (url == null || url.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(KEY_LAST_FETCH, 0L) < MIN_INTERVAL_MS) return false;
        prefs.edit().putLong(KEY_LAST_FETCH, now).commit();

        JSONObject doc = fetch(url);
        if (doc == null) return false;
        JSONObject fields = doc.optJSONObject("fields");
        if (fields == null) return false;
        long version = (long) toNumber(fields.optJSONObject("clientUpdatedAt"));
        if (version <= prefs.getLong(KEY_KNOWN_VERSION, 0L)) return false; // 이미 아는 데이터(또는 이 기기에 못 올린 변경이 있음)

        Object p = fromValue(fields.optJSONObject("payload"));
        if (!(p instanceof JSONObject)) return false;
        JSONObject payload = (JSONObject) p;
        try {
            Model m = new Model(payload);
            writeShiftWidget(ctx, m);
            writeCalendarWidget(ctx, m);
            writeNotesWidget(ctx, payload.optJSONObject("notes"));
            writeAlarmModel(ctx, m, payload.optJSONObject("alarms"));
        } catch (Exception e) {
            return false;
        }
        prefs.edit().putLong(KEY_KNOWN_VERSION, version).commit();
        ShiftWidgetProvider.updateAll(ctx);
        ShiftCalendarWidgetProvider.updateAll(ctx);
        ShiftNotesWidgetProvider.updateAll(ctx);
        return true;
    }

    private static JSONObject fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- Firestore REST(타입이 붙은 JSON) → 일반 JSON ----------------
    static Object fromValue(JSONObject v) {
        if (v == null) return JSONObject.NULL;
        try {
            if (v.has("stringValue")) return v.getString("stringValue");
            if (v.has("integerValue")) return Long.parseLong(v.getString("integerValue"));
            if (v.has("doubleValue")) return v.getDouble("doubleValue");
            if (v.has("booleanValue")) return v.getBoolean("booleanValue");
            if (v.has("nullValue")) return JSONObject.NULL;
            if (v.has("timestampValue")) return v.getString("timestampValue");
            if (v.has("mapValue")) {
                JSONObject out = new JSONObject();
                JSONObject f = v.getJSONObject("mapValue").optJSONObject("fields");
                if (f != null) {
                    Iterator<String> it = f.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        out.put(k, fromValue(f.optJSONObject(k)));
                    }
                }
                return out;
            }
            if (v.has("arrayValue")) {
                JSONArray out = new JSONArray();
                JSONArray vals = v.getJSONObject("arrayValue").optJSONArray("values");
                if (vals != null) for (int i = 0; i < vals.length(); i++) out.put(fromValue(vals.optJSONObject(i)));
                return out;
            }
        } catch (Exception ignored) {}
        return JSONObject.NULL;
    }

    private static double toNumber(JSONObject v) {
        Object o = fromValue(v);
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    // ---------------- 근무 계산 (index.html의 shiftForTeamOnDate와 같은 규칙) ----------------
    static final class Shift {
        final String label, color, typeId;
        Shift(String label, String color, String typeId) { this.label = label; this.color = color; this.typeId = typeId; }
    }

    static final class Model {
        final JSONArray shiftTypes, teams;
        final JSONObject overrides, shiftTimes;
        final String myTeamId;
        final Calendar ref;

        Model(JSONObject p) {
            shiftTypes = p.optJSONArray("shiftTypes") != null ? p.optJSONArray("shiftTypes") : new JSONArray();
            teams = p.optJSONArray("teams") != null ? p.optJSONArray("teams") : new JSONArray();
            overrides = p.optJSONObject("overrides") != null ? p.optJSONObject("overrides") : new JSONObject();
            shiftTimes = p.optJSONObject("shiftTimes") != null ? p.optJSONObject("shiftTimes") : new JSONObject();
            myTeamId = p.optString("myTeamId", "");
            ref = parseIso(p.optString("referenceDate", ""));
        }

        JSONObject typeById(String id) {
            for (int i = 0; i < shiftTypes.length(); i++) {
                JSONObject st = shiftTypes.optJSONObject(i);
                if (st != null && id.equals(st.optString("id"))) return st;
            }
            return null;
        }

        JSONObject typeByName(String name) {
            for (int i = 0; i < shiftTypes.length(); i++) {
                JSONObject st = shiftTypes.optJSONObject(i);
                if (st != null && name.equals(st.optString("name"))) return st;
            }
            return null;
        }

        JSONObject team(String id) {
            for (int i = 0; i < teams.length(); i++) {
                JSONObject t = teams.optJSONObject(i);
                if (t != null && id.equals(t.optString("id"))) return t;
            }
            return null;
        }

        static boolean usable(JSONObject st) {
            return !(st.optBoolean("locked", false) && st.has("enabled") && !st.optBoolean("enabled", true));
        }

        static String color(JSONObject st) {
            String c = st.optString("color", "");
            if (c.matches("#[0-9a-fA-F]{3,8}")) return c;
            switch (st.optString("role", "")) {
                case "day": return "#FFB648";
                case "night": return "#6C7BFF";
                case "standby": return "#37D6B0";
                case "off": return "#3A4150";
                default: return "#FF9F6C";
            }
        }

        Shift shiftFor(Calendar day, String teamId, String iso) {
            JSONObject ov = overrides.optJSONObject(iso);
            if (ov != null && ov.has(teamId)) {
                String v = ov.optString(teamId, "");
                JSONObject st = typeById(v);
                if (st == null) st = typeByName(v);
                if (st != null) {
                    if (!usable(st)) return new Shift("", "", null);
                    return new Shift(st.optString("name", ""), color(st), st.optString("id"));
                }
                if (v.matches("st\\d+")) return new Shift("", "", null);
                return new Shift(v, "#8992A6", null);
            }
            JSONObject t = team(teamId);
            JSONArray seq = t == null ? null : t.optJSONArray("sequence");
            if (seq == null || seq.length() == 0 || ref == null) return new Shift("-", "#3A4150", null);
            int L = seq.length();
            long diff = Math.round((day.getTimeInMillis() - ref.getTimeInMillis()) / (double) DAY_MS);
            int idx = (int) (((diff % L) + L) % L);
            String id = seq.optString(idx, "");
            JSONObject st = typeById(id);
            if (st == null) return new Shift("?", "#FF9F6C", null);
            if (!usable(st)) return new Shift("", "", null);
            return new Shift(st.optString("name", ""), color(st), id);
        }
    }

    // ---------------- 위젯/알람 데이터 저장 ----------------
    private static void writeShiftWidget(Context ctx, Model m) throws Exception {
        Calendar today = midnight(Calendar.getInstance());
        SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        JSONObject days = new JSONObject();
        for (int i = 0; i < 60; i++) {
            Calendar d = (Calendar) today.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            String iso = isoFmt.format(d.getTime());
            Shift s = m.shiftFor(d, m.myTeamId, iso);
            String range = "";
            if (s.typeId != null) {
                JSONObject tm = m.shiftTimes.optJSONObject(s.typeId);
                if (tm != null && !tm.optString("start", "").isEmpty()) range = tm.optString("start") + "–" + tm.optString("end", "");
            }
            JSONObject o = new JSONObject();
            o.put("label", s.label.isEmpty() ? "-" : s.label);
            o.put("range", range);
            o.put("color", s.color.isEmpty() ? "#6C7BFF" : s.color);
            days.put(iso, o);
        }
        JSONObject team = m.team(m.myTeamId);
        ctx.getSharedPreferences(ShiftWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(ShiftWidgetProvider.PREF_DAYS, days.toString())
            .putString(ShiftWidgetProvider.PREF_TEAM, team != null ? team.optString("name", "") : "")
            .commit();
    }

    private static void writeCalendarWidget(Context ctx, Model m) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(ShiftCalendarWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        // 공휴일(빨간 날)은 앱이 마지막으로 넘겨준 값을 그대로 쓰고, 그 밖은 일요일만 빨간 날로 표시
        Set<String> redDays = new HashSet<>();
        JSONObject oldMonths;
        try { oldMonths = new JSONObject(prefs.getString(ShiftCalendarWidgetProvider.PREF_MONTHS, "{}")); }
        catch (Exception e) { oldMonths = new JSONObject(); }
        Iterator<String> mk = oldMonths.keys();
        while (mk.hasNext()) {
            String ym = mk.next();
            JSONArray cells = oldMonths.optJSONObject(ym) == null ? null : oldMonths.optJSONObject(ym).optJSONArray("cells");
            if (cells == null) continue;
            for (int i = 0; i < cells.length(); i++) {
                JSONObject c = cells.optJSONObject(i);
                if (c != null && c.optBoolean("red", false) && !c.optString("day", "").isEmpty()) {
                    redDays.add(ym + "-" + String.format(Locale.US, "%02d", Integer.parseInt(c.optString("day"))));
                }
            }
        }

        SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar now = Calendar.getInstance();
        JSONObject months = new JSONObject();
        for (int k = 0; k < 3; k++) {
            Calendar first = midnight(Calendar.getInstance());
            first.set(Calendar.DAY_OF_MONTH, 1);
            first.add(Calendar.MONTH, k);
            int y = first.get(Calendar.YEAR), mo = first.get(Calendar.MONTH);
            int startOffset = first.get(Calendar.DAY_OF_WEEK) - 1; // 일요일=0
            int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            JSONArray cells = new JSONArray();
            for (int i = 0; i < 42; i++) {
                int dayNum = i - startOffset + 1;
                JSONObject cell = new JSONObject();
                if (dayNum < 1 || dayNum > daysInMonth) {
                    cell.put("day", "").put("label", "").put("labelColor", "").put("today", false).put("dots", new JSONArray());
                    cells.put(cell);
                    continue;
                }
                Calendar d = (Calendar) first.clone();
                d.set(Calendar.DAY_OF_MONTH, dayNum);
                String iso = isoFmt.format(d.getTime());
                Shift mine = m.shiftFor(d, m.myTeamId, iso);
                JSONArray dots = new JSONArray();
                for (int t = 0; t < m.teams.length(); t++) {
                    JSONObject team = m.teams.optJSONObject(t);
                    if (team == null) continue;
                    Shift s = m.shiftFor(d, team.optString("id"), iso);
                    if (!s.color.isEmpty()) dots.put(s.color);
                }
                boolean isToday = d.get(Calendar.YEAR) == now.get(Calendar.YEAR) && d.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
                boolean red = d.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY || redDays.contains(iso);
                cell.put("day", String.valueOf(dayNum)).put("label", mine.label).put("labelColor", mine.color)
                    .put("today", isToday).put("red", red).put("dots", dots);
                cells.put(cell);
            }
            JSONObject month = new JSONObject();
            month.put("title", y + "년 " + (mo + 1) + "월");
            month.put("cells", cells);
            months.put(String.format(Locale.US, "%04d-%02d", y, mo + 1), month);
        }
        prefs.edit().putString(ShiftCalendarWidgetProvider.PREF_MONTHS, months.toString()).commit();
    }

    private static void writeNotesWidget(Context ctx, JSONObject notes) throws Exception {
        String todayIso = ShiftWidgetProvider.todayIso();
        List<String[]> list = new ArrayList<>();
        if (notes != null) {
            Iterator<String> it = notes.keys();
            while (it.hasNext()) {
                String iso = it.next();
                if (iso.compareTo(todayIso) < 0) continue;
                JSONArray arr = notes.optJSONArray(iso);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject n = arr.optJSONObject(i);
                    if (n == null) continue;
                    Object time = n.opt("time");
                    list.add(new String[]{iso, time instanceof String ? (String) time : "", n.optString("text", "")});
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
            items.put(new JSONObject().put("date", list.get(i)[0]).put("time", list.get(i)[1]).put("text", list.get(i)[2]));
        }
        ctx.getSharedPreferences(ShiftNotesWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(ShiftNotesWidgetProvider.PREF_ITEMS, items.toString()).commit();
    }

    /** index.html의 buildNativeAlarmModel과 같은 형식으로 알람 규칙을 다시 만들어, 바뀌었으면 알람 재등록 */
    private static void writeAlarmModel(Context ctx, Model m, JSONObject alarms) throws Exception {
        JSONObject team = m.team(m.myTeamId);
        String todayIso = ShiftWidgetProvider.todayIso();
        JSONObject ovOut = new JSONObject();
        Iterator<String> it = m.overrides.keys();
        while (it.hasNext()) {
            String iso = it.next();
            if (iso.compareTo(todayIso) < 0) continue;
            JSONObject day = m.overrides.optJSONObject(iso);
            if (day == null || !day.has(m.myTeamId)) continue;
            String v = day.optString(m.myTeamId, "");
            JSONObject st = m.typeById(v);
            if (st == null) st = m.typeByName(v);
            ovOut.put(iso, st != null ? st.optString("id") : "");
        }
        JSONObject types = new JSONObject();
        for (int i = 0; i < m.shiftTypes.length(); i++) {
            JSONObject st = m.shiftTypes.optJSONObject(i);
            if (st == null) continue;
            String id = st.optString("id");
            JSONObject a = alarms == null ? null : alarms.optJSONObject(id);
            JSONObject t = new JSONObject();
            t.put("name", st.optString("name", ""));
            t.put("usable", Model.usable(st));
            if (a != null) {
                String sound = a.optString("sound", "classic");
                String customUri = a.optString("customUri", "");
                boolean custom = "custom".equals(sound) && !customUri.isEmpty();
                t.put("alarm", new JSONObject()
                    .put("enabled", a.optBoolean("enabled", false))
                    .put("time", a.optString("time", ""))
                    .put("repeat", a.optInt("repeat", 1))
                    .put("channelId", custom ? "alarm_custom_" + id + "_" + shortHash(customUri) : "alarm_" + (sound.isEmpty() ? "classic" : sound)));
            } else {
                t.put("alarm", JSONObject.NULL);
            }
            types.put(id, t);
        }
        JSONObject model = new JSONObject();
        model.put("refDate", m.ref != null ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(m.ref.getTime()) : "");
        model.put("sequence", team != null && team.optJSONArray("sequence") != null ? team.optJSONArray("sequence") : new JSONArray());
        model.put("overrides", ovOut);
        model.put("types", types);

        String json = model.toString();
        String prev = ctx.getSharedPreferences(AlarmScheduler.PREFS, Context.MODE_PRIVATE).getString(AlarmScheduler.KEY_MODEL, null);
        if (prev == null) return; // 이 기기에서 앱 알람을 한 번도 설정한 적 없으면 건드리지 않음
        if (!json.equals(prev)) {
            AlarmScheduler.saveModel(ctx, json);
            AlarmScheduler.scheduleAll(ctx);
        }
    }

    /** index.html의 shortHash와 같은 결과 (사용자 지정 알람음 채널 id 계산용) */
    static String shortHash(String s) {
        int h = 5381;
        for (int i = 0; i < s.length(); i++) h = ((h << 5) + h + s.charAt(i));
        return Long.toString(h & 0xffffffffL, 36);
    }

    private static Calendar midnight(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static Calendar parseIso(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}.*")) return null;
        Calendar c = midnight(Calendar.getInstance());
        c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10)));
        return c;
    }
}
