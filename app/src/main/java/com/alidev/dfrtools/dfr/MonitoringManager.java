package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MonitoringManager {
    private static final String PREF_NAME = "monitoring_prefs";
    private static final String KEY_NODES = "monitored_nodes";
    private final SharedPreferences prefs;

    public MonitoringManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static class DeviceHeaderData {
        public String title;  // "[GI] bay [Bay]"
        public String gi;     // "[GI]" alone, for the GI filter spinner
        public String bay;
        public String device; // "[Device]"
        public String merk;
        public String type;
        public String ipLine; // "[Device] - [Merk]_[Type] - [IP]"
    }

    /**
     * Looks up a device's header info (title/GI/name/IP line) from the Device Database by IP.
     * Static + Context-parameterized (rather than an instance method tied to one Activity) so
     * both IEDMonitoringActivity and MonitoringRefreshService - which runs independently of any
     * Activity - can resolve the same group titles without duplicating this lookup.
     */
    public static DeviceHeaderData getDeviceHeaderData(Context context, String ip) {
        SharedPreferences prefs = context.getSharedPreferences("dfr_prefs", Context.MODE_PRIVATE);
        String listJson = prefs.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(listJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (ip.equals(obj.optString("ip"))) {
                    String gi = obj.optString("gi");
                    String bay = obj.optString("bay");
                    String device = obj.optString("device");
                    String merk = obj.optString("merk");
                    String type = obj.optString("type");
                    DeviceHeaderData data = new DeviceHeaderData();
                    data.title = String.format("%s bay %s", gi, bay);
                    data.gi = gi;
                    data.bay = bay;
                    data.device = device;
                    data.merk = merk;
                    data.type = type;
                    data.ipLine = String.format("%s - %s_%s - %s", device, merk, type, ip);
                    return data;
                }
            }
        } catch (Exception ignored) {}
        return null; // Unknown
    }

    public List<MonitoredNode> getNodes() {
        List<MonitoredNode> nodes = new ArrayList<>();
        String json = prefs.getString(KEY_NODES, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                nodes.add(MonitoredNode.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return nodes;
    }

    public void saveNodes(List<MonitoredNode> nodes) {
        JSONArray arr = new JSONArray();
        for (MonitoredNode node : nodes) {
            try {
                arr.put(node.toJson());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        prefs.edit().putString(KEY_NODES, arr.toString()).apply();
    }

    public void addNode(MonitoredNode node) {
        List<MonitoredNode> nodes = getNodes();
        // Check if already exists by fullPath and IP
        for (MonitoredNode n : nodes) {
            if (n.fullPath.equals(node.fullPath) && n.ipAddress.equals(node.ipAddress)) {
                return; // Already added
            }
        }
        nodes.add(node);
        saveNodes(nodes);
    }

    public void updateNode(MonitoredNode updatedNode) {
        updateNode(updatedNode.fullPath, updatedNode.ipAddress, updatedNode);
    }

    public void updateNode(String oldFullPath, String oldIpAddress, MonitoredNode updatedNode) {
        List<MonitoredNode> nodes = getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            MonitoredNode n = nodes.get(i);
            if (n.fullPath.equals(oldFullPath) && n.ipAddress.equals(oldIpAddress)) {
                nodes.set(i, updatedNode);
                break;
            }
        }
        saveNodes(nodes);
    }

    public void removeNode(MonitoredNode node) {
        List<MonitoredNode> nodes = getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            MonitoredNode n = nodes.get(i);
            if (n.fullPath.equals(node.fullPath) && n.ipAddress.equals(node.ipAddress)) {
                nodes.remove(i);
                break;
            }
        }
        saveNodes(nodes);
    }

    /** Removes every monitored point belonging to one device (used when deleting a whole group). */
    public void removeNodesForIp(String ipAddress) {
        List<MonitoredNode> nodes = getNodes();
        nodes.removeIf(n -> n.ipAddress.equals(ipAddress));
        saveNodes(nodes);
    }
}
