package com.alidev.dfrtools.dfr;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MonitoredNode {
    public String deviceName;
    public String ipAddress;
    public String nodeName;
    public String fullPath;
    public String customName;
    public String unit = "";
    public float multiplier = 1.0f;
    public boolean invert = false;
    public boolean invertColor = false; // boolean type only: swaps which state (TRUE/FALSE) reads as red vs green, independent of the alarm condition
    public String type; // "boolean", "float", or "string"
    public String lastValue = "";
    public long lastUpdateMillis = 0; // wall-clock time of the last successful read; persisted so the last-known value/time survives app restarts
    public String cachedFc = null; // functional constraint that worked last time; not persisted, rediscovered each session

    public boolean alarmEnabled = false;
    public Float thresholdHigh = null; // float type: alarm if value > thresholdHigh (when set)
    public Float thresholdLow = null;  // float type: alarm if value < thresholdLow (when set)
    public boolean alarmOnValue = true; // boolean/string type: whether matching the condition (TRUE / alarmMatchText) is the alarm state
    public String alarmMatchText = ""; // string type: text compared against lastValue when alarmEnabled

    // Runtime-only trend history for the sparkline (not persisted; rebuilt each session).
    // CopyOnWriteArrayList so the background polling thread can append safely while the UI
    // thread reads/iterates it during bind() without external synchronization.
    private static final int HISTORY_MAX = 30;
    public final List<Float> history = new CopyOnWriteArrayList<>();

    public void pushHistory(float v) {
        history.add(v);
        while (history.size() > HISTORY_MAX) {
            try {
                history.remove(0);
            } catch (IndexOutOfBoundsException ignored) {}
        }
    }

    public boolean isAlarming() {
        if (!alarmEnabled || lastValue == null || lastValue.isEmpty()) return false;
        if ("boolean".equals(type)) {
            return lastValue.equalsIgnoreCase("true") == alarmOnValue;
        }
        if ("string".equals(type)) {
            if (alarmMatchText == null || alarmMatchText.isEmpty()) return false;
            return lastValue.equalsIgnoreCase(alarmMatchText) == alarmOnValue;
        }
        try {
            float v = Float.parseFloat(lastValue);
            if (thresholdHigh != null && v > thresholdHigh) return true;
            if (thresholdLow != null && v < thresholdLow) return true;
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Applies this node's type/invert/multiplier/unit rules to a freshly-read raw MMS value.
     * Shared by IEDMonitoringActivity (foreground per-group/manual reads) and
     * MonitoringRefreshService (background bulk refresh) so both format values identically.
     */
    public String processRawValue(String raw) {
        if (type.equals("boolean")) {
            boolean b = raw.equalsIgnoreCase("true") || raw.equals("1");
            if (invert) b = !b;
            return b ? "true" : "false";
        } else if (type.equals("string")) {
            return raw;
        } else {
            try {
                float f = Float.parseFloat(raw);
                // Fixed decimal places - raw float math otherwise produces long, inconsistent tails
                // (or scientific notation for tiny multipliers like 0.000001). Degrees only need 1.
                String decimalFormat = "°".equals(unit) ? "%.1f" : "%.3f";
                return String.format(java.util.Locale.US, decimalFormat, f * multiplier);
            } catch (Exception e) {
                return raw;
            }
        }
    }

    public MonitoredNode() {}

    public MonitoredNode(String deviceName, String ipAddress, String nodeName, String fullPath, String type) {
        this.deviceName = deviceName;
        this.ipAddress = ipAddress;
        this.nodeName = nodeName;
        this.fullPath = fullPath;
        this.customName = nodeName;
        this.type = type;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("deviceName", deviceName);
        json.put("ipAddress", ipAddress);
        json.put("nodeName", nodeName);
        json.put("fullPath", fullPath);
        json.put("customName", customName);
        json.put("unit", unit);
        json.put("multiplier", multiplier);
        json.put("invert", invert);
        json.put("invertColor", invertColor);
        json.put("type", type);
        json.put("lastValue", lastValue);
        json.put("lastUpdateMillis", lastUpdateMillis);
        json.put("alarmEnabled", alarmEnabled);
        if (thresholdHigh != null) json.put("thresholdHigh", thresholdHigh);
        if (thresholdLow != null) json.put("thresholdLow", thresholdLow);
        json.put("alarmOnValue", alarmOnValue);
        json.put("alarmMatchText", alarmMatchText);
        return json;
    }

    public static MonitoredNode fromJson(JSONObject json) throws JSONException {
        MonitoredNode node = new MonitoredNode();
        node.deviceName = json.getString("deviceName");
        node.ipAddress = json.getString("ipAddress");
        node.nodeName = json.getString("nodeName");
        node.fullPath = json.getString("fullPath");
        node.customName = json.optString("customName", node.nodeName);
        node.unit = json.optString("unit", "");
        node.multiplier = (float) json.optDouble("multiplier", 1.0);
        node.invert = json.optBoolean("invert", false);
        node.invertColor = json.optBoolean("invertColor", false);
        node.type = json.getString("type");
        node.lastValue = json.optString("lastValue", "");
        node.lastUpdateMillis = json.optLong("lastUpdateMillis", 0);
        node.alarmEnabled = json.optBoolean("alarmEnabled", false);
        node.thresholdHigh = json.has("thresholdHigh") ? (float) json.optDouble("thresholdHigh") : null;
        node.thresholdLow = json.has("thresholdLow") ? (float) json.optDouble("thresholdLow") : null;
        node.alarmOnValue = json.optBoolean("alarmOnValue", true);
        node.alarmMatchText = json.optString("alarmMatchText", "");
        return node;
    }
}
