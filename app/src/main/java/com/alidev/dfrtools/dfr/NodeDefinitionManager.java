package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted table of "d" (description) attributes fetched via MMS Explorer's "Get Definition" -
 * a per-node human-readable lookup, separate from IED Monitoring's live points. Same
 * SharedPreferences+JSON pattern as MonitoringManager/AppNotifications.
 */
public class NodeDefinitionManager {
    private static final String PREF_NAME = "node_definition_prefs";
    private static final String KEY_ITEMS = "definitions";
    private final SharedPreferences prefs;

    public NodeDefinitionManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<NodeDefinition> getAll() {
        List<NodeDefinition> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                NodeDefinition d = new NodeDefinition();
                d.ip = o.optString("ip", "");
                d.deviceName = o.optString("deviceName", "");
                d.nodeAddress = o.optString("nodeAddress", "");
                d.value = o.optString("value", "");
                d.hasGeneralStatus = o.optBoolean("hasGeneralStatus", false);
                d.generalStatusValue = o.optString("generalStatusValue", "");
                items.add(d);
            }
        } catch (JSONException ignored) {}
        return items;
    }

    private void saveAll(List<NodeDefinition> items) {
        JSONArray arr = new JSONArray();
        try {
            for (NodeDefinition d : items) {
                JSONObject o = new JSONObject();
                o.put("ip", d.ip);
                o.put("deviceName", d.deviceName);
                o.put("nodeAddress", d.nodeAddress);
                o.put("value", d.value);
                o.put("hasGeneralStatus", d.hasGeneralStatus);
                o.put("generalStatusValue", d.generalStatusValue);
                arr.put(o);
            }
        } catch (JSONException ignored) {}
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    /** Replaces every stored row for this ip with a freshly-fetched batch, so re-running
     *  "Get Definition" on the same device updates its rows instead of duplicating them. */
    public void replaceForIp(String ip, List<NodeDefinition> fresh) {
        List<NodeDefinition> all = getAll();
        all.removeIf(d -> d.ip.equals(ip));
        all.addAll(fresh);
        saveAll(all);
    }

    public List<NodeDefinition> getForIp(String ip) {
        List<NodeDefinition> result = new ArrayList<>();
        for (NodeDefinition d : getAll()) {
            if (d.ip.equals(ip)) result.add(d);
        }
        return result;
    }

    /** Deletes every stored row for this ip - used by the "delete table" action. */
    public void removeForIp(String ip) {
        List<NodeDefinition> all = getAll();
        all.removeIf(d -> d.ip.equals(ip));
        saveAll(all);
    }
}
