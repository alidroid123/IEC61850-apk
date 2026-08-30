package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Bundles/restores the app's user-authored configuration - device database, monitoring points,
 * relay templates, and node definitions - into one JSON file, so it survives a phone swap or can
 * be shared to a teammate working on the same substations. Operates directly on the same raw
 * JSON blobs the respective managers persist (dfr_prefs/device_list, monitoring_prefs/
 * monitored_nodes, relay_templates_prefs/templates, node_definition_prefs/definitions) rather
 * than through their model classes, so a backup from an older app version still merges whatever
 * fields are present instead of failing to parse - this is also why a new MonitoredNode field
 * (e.g. invertColor) never needs a BackupManager change: it rides along in the raw blob
 * automatically. Only a whole new persisted store (a new SharedPreferences file, like
 * node_definition_prefs was when it was added) needs to be wired in here explicitly.
 */
public class BackupManager {
    private static final int BACKUP_FORMAT_VERSION = 1;

    public static class ImportResult {
        public int devicesAdded, nodesAdded, templatesAdded, definitionsAdded;
    }

    public static JSONObject exportConfig(Context context) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("app", "ComtradeDownloader");
        root.put("backupVersion", BACKUP_FORMAT_VERSION);
        root.put("exportedAtMillis", System.currentTimeMillis());

        SharedPreferences dfrPrefs = context.getSharedPreferences("dfr_prefs", Context.MODE_PRIVATE);
        root.put("deviceList", new JSONArray(dfrPrefs.getString("device_list", "[]")));

        SharedPreferences monPrefs = context.getSharedPreferences("monitoring_prefs", Context.MODE_PRIVATE);
        root.put("monitoredNodes", new JSONArray(monPrefs.getString("monitored_nodes", "[]")));

        SharedPreferences tmplPrefs = context.getSharedPreferences("relay_templates_prefs", Context.MODE_PRIVATE);
        root.put("relayTemplates", new JSONObject(tmplPrefs.getString("templates", "{}")));

        SharedPreferences defPrefs = context.getSharedPreferences("node_definition_prefs", Context.MODE_PRIVATE);
        root.put("nodeDefinitions", new JSONArray(defPrefs.getString("definitions", "[]")));

        return root;
    }

    /** Merges every section of a backup into the current data, skipping entries that already exist (by IP / fullPath+IP / template name). Never deletes anything. */
    public static ImportResult importConfig(Context context, JSONObject root) throws JSONException {
        ImportResult result = new ImportResult();

        SharedPreferences dfrPrefs = context.getSharedPreferences("dfr_prefs", Context.MODE_PRIVATE);
        JSONArray importedDevices = root.optJSONArray("deviceList");
        if (importedDevices != null) {
            JSONArray merged = new JSONArray(dfrPrefs.getString("device_list", "[]"));
            Set<String> existingIps = new HashSet<>();
            for (int i = 0; i < merged.length(); i++) {
                existingIps.add(merged.getJSONObject(i).optString("ip").toLowerCase());
            }
            for (int i = 0; i < importedDevices.length(); i++) {
                JSONObject d = importedDevices.getJSONObject(i);
                String ip = d.optString("ip").toLowerCase();
                if (!ip.isEmpty() && existingIps.add(ip)) {
                    merged.put(d);
                    result.devicesAdded++;
                }
            }
            dfrPrefs.edit().putString("device_list", merged.toString()).apply();
        }

        SharedPreferences monPrefs = context.getSharedPreferences("monitoring_prefs", Context.MODE_PRIVATE);
        JSONArray importedNodes = root.optJSONArray("monitoredNodes");
        if (importedNodes != null) {
            JSONArray merged = new JSONArray(monPrefs.getString("monitored_nodes", "[]"));
            Set<String> existingKeys = new HashSet<>();
            for (int i = 0; i < merged.length(); i++) {
                JSONObject n = merged.getJSONObject(i);
                existingKeys.add(n.optString("fullPath") + "|" + n.optString("ipAddress"));
            }
            for (int i = 0; i < importedNodes.length(); i++) {
                JSONObject n = importedNodes.getJSONObject(i);
                String key = n.optString("fullPath") + "|" + n.optString("ipAddress");
                if (existingKeys.add(key)) {
                    merged.put(n);
                    result.nodesAdded++;
                }
            }
            monPrefs.edit().putString("monitored_nodes", merged.toString()).apply();
        }

        SharedPreferences tmplPrefs = context.getSharedPreferences("relay_templates_prefs", Context.MODE_PRIVATE);
        JSONObject importedTemplates = root.optJSONObject("relayTemplates");
        if (importedTemplates != null) {
            JSONObject merged = new JSONObject(tmplPrefs.getString("templates", "{}"));
            Iterator<String> keys = importedTemplates.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (!merged.has(name)) {
                    merged.put(name, importedTemplates.get(name));
                    result.templatesAdded++;
                }
            }
            tmplPrefs.edit().putString("templates", merged.toString()).apply();
        }

        SharedPreferences defPrefs = context.getSharedPreferences("node_definition_prefs", Context.MODE_PRIVATE);
        JSONArray importedDefinitions = root.optJSONArray("nodeDefinitions");
        if (importedDefinitions != null) {
            JSONArray merged = new JSONArray(defPrefs.getString("definitions", "[]"));
            Set<String> existingKeys = new HashSet<>();
            for (int i = 0; i < merged.length(); i++) {
                JSONObject d = merged.getJSONObject(i);
                existingKeys.add(d.optString("ip") + "|" + d.optString("nodeAddress"));
            }
            for (int i = 0; i < importedDefinitions.length(); i++) {
                JSONObject d = importedDefinitions.getJSONObject(i);
                String key = d.optString("ip") + "|" + d.optString("nodeAddress");
                if (existingKeys.add(key)) {
                    merged.put(d);
                    result.definitionsAdded++;
                }
            }
            defPrefs.edit().putString("definitions", merged.toString()).apply();
        }

        return result;
    }
}
