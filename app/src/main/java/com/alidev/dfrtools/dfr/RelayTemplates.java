package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-relay-model monitoring point templates. Each point's path is the LDInst/LN.DO.DA suffix
 * only - it deliberately excludes the IEDName prefix, since that's assigned per physical device
 * (project naming convention) rather than being fixed by the relay model/firmware. Applying a
 * template resolves the real IEDName by matching each point's LDInst against the live logical
 * device list read from the target relay (see IEDMonitoringActivity.applyRelayTemplate()).
 *
 * Persisted (SharedPreferences, JSON) so templates can be authored/edited from
 * RelayTemplateEditActivity instead of being hardcoded. Seeded once with the original
 * "MiCom P442" template on first run.
 */
public class RelayTemplates {
    private static final String PREF_NAME = "relay_templates_prefs";
    private static final String KEY_TEMPLATES = "templates";

    public static class Point {
        public String path;       // e.g. "Records/PriRFLO1.FltDiskm" - no IEDName prefix
        public String customName;
        public String type;       // "float", "boolean", "string"
        public String unit;
        public float multiplier;

        public Point(String path, String customName, String type, String unit, float multiplier) {
            this.path = path;
            this.customName = customName;
            this.type = type;
            this.unit = unit;
            this.multiplier = multiplier;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static Map<String, List<Point>> load(Context context) {
        SharedPreferences p = prefs(context);
        // No seeded template on first run - IED Monitoring's Template picker stays empty until
        // the user has actually authored and saved one via RelayTemplateEditActivity themselves.
        if (!p.contains(KEY_TEMPLATES)) {
            Map<String, List<Point>> empty = new LinkedHashMap<>();
            save(context, empty);
            return empty;
        }
        Map<String, List<Point>> result = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(p.getString(KEY_TEMPLATES, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONArray arr = root.getJSONArray(name);
                List<Point> points = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    points.add(new Point(
                            o.optString("path", ""),
                            o.optString("customName", ""),
                            o.optString("type", "float"),
                            o.optString("unit", ""),
                            (float) o.optDouble("multiplier", 1.0)));
                }
                result.put(name, points);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return result;
    }

    private static void save(Context context, Map<String, List<Point>> templates) {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, List<Point>> entry : templates.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Point p : entry.getValue()) {
                    JSONObject o = new JSONObject();
                    o.put("path", p.path);
                    o.put("customName", p.customName);
                    o.put("type", p.type);
                    o.put("unit", p.unit);
                    o.put("multiplier", p.multiplier);
                    arr.put(o);
                }
                root.put(entry.getKey(), arr);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        prefs(context).edit().putString(KEY_TEMPLATES, root.toString()).apply();
    }

    public static List<String> getTemplateNames(Context context) {
        return new ArrayList<>(load(context).keySet());
    }

    public static List<Point> get(Context context, String templateName) {
        return load(context).get(templateName);
    }

    /** Returns false without adding anything if a template with this name already exists. */
    public static boolean addTemplate(Context context, String name) {
        Map<String, List<Point>> templates = load(context);
        if (templates.containsKey(name)) return false;
        templates.put(name, new ArrayList<>());
        save(context, templates);
        return true;
    }

    public static void removeTemplate(Context context, String name) {
        Map<String, List<Point>> templates = load(context);
        templates.remove(name);
        save(context, templates);
    }

    /** Persists the (possibly reordered/edited/trimmed) point list for one template. */
    public static void savePoints(Context context, String name, List<Point> points) {
        Map<String, List<Point>> templates = load(context);
        templates.put(name, points);
        save(context, templates);
    }
}
