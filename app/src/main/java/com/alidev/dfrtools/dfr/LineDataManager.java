package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted Line Database (Database Saluran) - line length and sequence impedances per relay bay,
 * used by FL Double Ended to fill in the line parameters. Same SharedPreferences+JSON pattern as
 * NodeDefinitionManager; BackupManager carries the raw "lines" blob in the config export.
 */
public class LineDataManager {
    static final String PREF_NAME = "line_data_prefs";
    static final String KEY_ITEMS = "lines";
    /** Conductor data shipped with the app (UPT Purwokerto sheet), loaded once on first use. */
    private static final String DEFAULT_ASSET = "line_database_default.csv";
    private static final String KEY_DEFAULTS_LOADED = "defaults_loaded_v1";
    private final Context appContext;
    private final SharedPreferences prefs;

    public LineDataManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        // Only ever automatic once and only into an empty table, so a user who cleared the
        // database (or curated their own) never gets the bundled rows pushed back on them.
        if (!prefs.getBoolean(KEY_DEFAULTS_LOADED, false)) {
            prefs.edit().putBoolean(KEY_DEFAULTS_LOADED, true).apply();
            if (getAll().isEmpty()) loadDefaults();
        }
    }

    /** Merges the bundled conductor data in (same rules as a CSV import). Returns {added, updated}, or null on failure. */
    public int[] loadDefaults() {
        try (InputStream is = appContext.getAssets().open(DEFAULT_ASSET)) {
            LineDataCsv.ParseResult r = LineDataCsv.parse(new InputStreamReader(is, StandardCharsets.UTF_8));
            return importRows(r.rows);
        } catch (IOException e) {
            return null;
        }
    }

    public List<LineData> getAll() {
        List<LineData> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) items.add(fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return items;
    }

    public LineData get(String id) {
        if (id == null) return null;
        for (LineData d : getAll()) if (d.id.equals(id)) return d;
        return null;
    }

    public void saveAll(List<LineData> items) {
        JSONArray arr = new JSONArray();
        for (LineData d : items) arr.put(toJson(d));
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    /** Adds a new row or replaces the one with the same id. */
    public void upsert(LineData d) {
        if (d.id.isEmpty()) d.id = UUID.randomUUID().toString();
        List<LineData> all = getAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(d.id)) { all.set(i, d); replaced = true; break; }
        }
        if (!replaced) all.add(d);
        saveAll(all);
    }

    public void delete(String id) {
        List<LineData> all = getAll();
        all.removeIf(d -> d.id.equals(id));
        saveAll(all);
    }

    public void deleteAll() {
        saveAll(new ArrayList<>());
    }

    /**
     * Merges an imported CSV: a row with the same GI + bay + remote GI as an existing one updates it
     * in place (keeping its id, so FL Double Ended's remembered selection still points at it),
     * anything else is added. Returns {added, updated}.
     */
    public int[] importRows(List<LineData> rows) {
        List<LineData> all = getAll();
        Map<String, Integer> byKey = new HashMap<>();
        for (int i = 0; i < all.size(); i++) byKey.put(all.get(i).key(), i);
        int added = 0, updated = 0;
        for (LineData d : rows) {
            Integer idx = byKey.get(d.key());
            if (idx != null) {
                d.id = all.get(idx).id;
                all.set(idx, d);
                updated++;
            } else {
                d.id = UUID.randomUUID().toString();
                byKey.put(d.key(), all.size());
                all.add(d);
                added++;
            }
        }
        saveAll(all);
        return new int[]{added, updated};
    }

    // ------------------------------------------------------------------
    // Matching the two relays picked in FL Double Ended to a line row
    // ------------------------------------------------------------------

    /**
     * How well a line row fits "relay at A towards the substation of relay B". Rows are stored per
     * bay at one end, so the same physical line may only exist as seen from B - that direction is
     * scored too (same impedance either way). Both substation names matching = 8+.
     */
    public static int score(LineData l, DeviceListActivity.DeviceItem a, DeviceListActivity.DeviceItem b) {
        return Math.max(directional(l, a, b), directional(l, b, a));
    }

    private static int directional(LineData l, DeviceListActivity.DeviceItem near, DeviceListActivity.DeviceItem far) {
        if (near == null) return 0;
        int s = 0;
        if (same(l.gi, near.gi) || same(l.giUip2b, near.gi)) s += 4;
        if (far != null && same(l.giLawan, far.gi)) s += 4;
        String bay = LineData.norm(l.bay), nearBay = LineData.norm(near.bay);
        if (!bay.isEmpty() && bay.equals(nearBay)) s += 3;
        else if (!bay.isEmpty() && !nearBay.isEmpty() && (bay.contains(nearBay) || nearBay.contains(bay))) s += 2;
        if (!l.merk.isEmpty() && l.merk.equalsIgnoreCase(near.merk.trim())) s += 1;
        if (!l.tipe.isEmpty() && LineData.norm(l.tipe).equals(LineData.norm(near.type))) s += 1;
        return s;
    }

    private static boolean same(String x, String y) {
        String a = LineData.norm(x), b = LineData.norm(y);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.startsWith(b + " ") || b.startsWith(a + " ");
    }

    /** Highest-scoring row, or null when nothing ties both substations together. */
    public LineData bestMatch(DeviceListActivity.DeviceItem a, DeviceListActivity.DeviceItem b) {
        LineData best = null;
        int bestScore = 0;
        for (LineData l : getAll()) {
            int sc = score(l, a, b);
            if (sc > bestScore) { bestScore = sc; best = l; }
        }
        return bestScore >= 8 ? best : null;
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    static JSONObject toJson(LineData d) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", d.id);
            o.put("no", d.no);
            o.put("upt", d.upt);
            o.put("ultg", d.ultg);
            o.put("gi", d.gi);
            o.put("giUip2b", d.giUip2b);
            o.put("bay", d.bay);
            o.put("giLawan", d.giLawan);
            o.put("grade", d.grade);
            o.put("merk", d.merk);
            o.put("tipe", d.tipe);
            o.put("konduktor", d.konduktor);
            o.put("standarKonduktor", d.standarKonduktor);
            putNum(o, "kv", d.kv);
            putNum(o, "ccc", d.ccc);
            putNum(o, "lengthKm", d.lengthKm);
            putNum(o, "r1", d.r1);
            putNum(o, "x1", d.x1);
            putNum(o, "z1", d.z1);
            putNum(o, "ang1", d.ang1);
            putNum(o, "r0", d.r0);
            putNum(o, "x0", d.x0);
            putNum(o, "z0", d.z0);
            putNum(o, "ang0", d.ang0);
            putNum(o, "b1", d.b1);
        } catch (JSONException ignored) { }
        return o;
    }

    static LineData fromJson(JSONObject o) {
        LineData d = new LineData();
        d.id = o.optString("id", "");
        if (d.id.isEmpty()) d.id = UUID.randomUUID().toString();
        d.no = o.optString("no", "");
        d.upt = o.optString("upt", "");
        d.ultg = o.optString("ultg", "");
        d.gi = o.optString("gi", "");
        d.giUip2b = o.optString("giUip2b", "");
        d.bay = o.optString("bay", "");
        d.giLawan = o.optString("giLawan", "");
        d.grade = o.optString("grade", "");
        d.merk = o.optString("merk", "");
        d.tipe = o.optString("tipe", "");
        d.konduktor = o.optString("konduktor", "");
        d.standarKonduktor = o.optString("standarKonduktor", "");
        d.kv = o.optDouble("kv", Double.NaN);
        d.ccc = o.optDouble("ccc", Double.NaN);
        d.lengthKm = o.optDouble("lengthKm", Double.NaN);
        d.r1 = o.optDouble("r1", Double.NaN);
        d.x1 = o.optDouble("x1", Double.NaN);
        d.z1 = o.optDouble("z1", Double.NaN);
        d.ang1 = o.optDouble("ang1", Double.NaN);
        d.r0 = o.optDouble("r0", Double.NaN);
        d.x0 = o.optDouble("x0", Double.NaN);
        d.z0 = o.optDouble("z0", Double.NaN);
        d.ang0 = o.optDouble("ang0", Double.NaN);
        d.b1 = o.optDouble("b1", Double.NaN);
        return d;
    }

    /** JSONObject.put() rejects NaN - an unknown value is simply left out. */
    private static void putNum(JSONObject o, String key, double v) throws JSONException {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(key, v);
    }
}
