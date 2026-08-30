package com.alidev.dfrtools.dfr;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MmsExplorerActivity extends BaseActivity {

    private EditText etIp1, etIp2, etIp3, etIp4, etSearch;
    private View layoutSearch;
    private ImageButton btnClearSearch;
    private androidx.appcompat.widget.SwitchCompat swIntranetCheck;
    private TextView tvDeviceInfo;
    private ProgressBar topProgressBar;
    private RecyclerView rvExplorer;
    private ExplorerAdapter adapter;
    private Iec61850DfrClient client = new Iec61850DfrClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.Map<String, String> nodeToFcMap = new java.util.HashMap<>();
    private boolean isRealtimeActive = false;
    private final Runnable realtimeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRealtimeActive) {
                refreshVisibleValues();
                int rate = getResources().getInteger(R.integer.config_mms_refresh_rate_ms);
                mainHandler.postDelayed(this, rate);
            }
        }
    };

    enum NodeType { LD, LN, DO, DA }

    static class MmsNode {
        String name;
        String fullPath;
        NodeType type;
        int level;
        boolean isExpanded = false;
        boolean isLoaded = false;
        boolean isLeaf = false;
        String value = "";
        List<MmsNode> children = new ArrayList<>();

        MmsNode(String name, String fullPath, NodeType type, int level) {
            this.name = name;
            this.fullPath = fullPath;
            this.type = type;
            this.level = level;
        }
    }

    private Button btnConnect, btnDisconnect;
    private ImageButton btnMmsMoreOptions;
    private boolean isFetchingDefinitions = false;
    private java.util.Set<String> pathsToRestore = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mms_explorer);

        etIp1 = findViewById(R.id.etIp1);
        etIp2 = findViewById(R.id.etIp2);
        etIp3 = findViewById(R.id.etIp3);
        etIp4 = findViewById(R.id.etIp4);
        swIntranetCheck = findViewById(R.id.swIntranetCheck);
        com.alidev.dfrtools.utils.IpAddressHelper.setupIpInputs(etIp1, etIp2, etIp3, etIp4);

        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        topProgressBar = findViewById(R.id.topProgressBar);
        rvExplorer = findViewById(R.id.rvExplorer);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnMmsMoreOptions = findViewById(R.id.btnMmsMoreOptions);
        layoutSearch = findViewById(R.id.layoutSearch);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnConnect.setOnClickListener(v -> checkIntranetAndExecute(this::startExploration));
        btnDisconnect.setOnClickListener(v -> stopExploration());
        btnMmsMoreOptions.setOnClickListener(this::showMoreOptionsMenu);

        setupSearch();
        findViewById(R.id.btnListDevice).setOnClickListener(v -> {
            Intent intent = new Intent(this, DeviceListActivity.class);
            intent.putExtra("is_pick_mode", true);
            startActivityForResult(intent, 1001);
        });

        rvExplorer.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExplorerAdapter();
        rvExplorer.setAdapter(adapter);

        setupIpWatcher();
        loadLastIp();

        String ip = getIntent().getStringExtra("ip");
        if (ip != null) {
            com.alidev.dfrtools.utils.IpAddressHelper.setIpToInputs(ip, etIp1, etIp2, etIp3, etIp4);
            checkIntranetAndExecute(this::startExploration);
        }
    }

    private void setupIpWatcher() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                lookupDeviceByIp(com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4));
            }
        };
        etIp1.addTextChangedListener(watcher);
        etIp2.addTextChangedListener(watcher);
        etIp3.addTextChangedListener(watcher);
        etIp4.addTextChangedListener(watcher);
    }

    private void lookupDeviceByIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) {
            tvDeviceInfo.setVisibility(View.GONE);
            return;
        }

        SharedPreferences prefs = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String listJson = prefs.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(listJson);
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (ip.equals(obj.optString("ip"))) {
                    String gi = obj.optString("gi");
                    String bay = obj.optString("bay");
                    String device = obj.optString("device");
                    tvDeviceInfo.setText(String.format("%s - %s (%s)", gi, bay, device));
                    tvDeviceInfo.setVisibility(View.VISIBLE);
                    found = true;
                    break;
                }
            }
            if (!found) tvDeviceInfo.setVisibility(View.GONE);
        } catch (Exception e) {
            tvDeviceInfo.setVisibility(View.GONE);
        }
    }

    private void saveLastIp(String ip) {
        getSharedPreferences("mms_prefs", MODE_PRIVATE)
                .edit().putString("last_ip", ip).apply();
    }

    private void loadLastIp() {
        String lastIp = getSharedPreferences("mms_prefs", MODE_PRIVATE)
                .getString("last_ip", "192.168.1.10");
        com.alidev.dfrtools.utils.IpAddressHelper.setIpToInputs(lastIp, etIp1, etIp2, etIp3, etIp4);
        lookupDeviceByIp(lastIp);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            String ip = data.getStringExtra("ip");
            if (ip != null) {
                com.alidev.dfrtools.utils.IpAddressHelper.setIpToInputs(ip, etIp1, etIp2, etIp3, etIp4);
                lookupDeviceByIp(ip);
                checkIntranetAndExecute(this::startExploration);
            }
        }
    }

    private void checkIntranetAndExecute(Runnable onSuccess) {
        if (!swIntranetCheck.isChecked()) {
            onSuccess.run();
            return;
        }
        Toast.makeText(this, R.string.msg_dev_ping_precheck, Toast.LENGTH_SHORT).show();
        String intranetIp = com.alidev.dfrtools.utils.ConfigHelper.getIntranetIp(this);
        executor.execute(() -> {
            boolean intranetOk = false;
            try {
                java.lang.Process process = Runtime.getRuntime().exec("ping -c 1 -W 2 " + intranetIp);
                int exitCode = process.waitFor();
                intranetOk = (exitCode == 0);
            } catch (Exception e) {
                e.printStackTrace();
            }

            final boolean finalIntranetOk = intranetOk;
            mainHandler.post(() -> {
                if (finalIntranetOk) {
                    onSuccess.run();
                } else {
                    showVpnPrompt();
                }
            });
        });
    }

    private void showVpnPrompt() {
        View v = getLayoutInflater().inflate(R.layout.dialog_vpn_prompt, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        v.findViewById(R.id.btnOpenVpn).setOnClickListener(view -> {
            dialog.dismiss();
            openVpnApp();
        });
        v.findViewById(R.id.btnCancelVpn).setOnClickListener(view -> dialog.dismiss());

        dialog.show();
    }

    private void openVpnApp() {
        String packageName = "com.fortinet.forticlient_vpn";
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            startActivity(launchIntent);
        } else {
            try {
                Intent vpnIntent = new Intent("android.net.vpn.SETTINGS");
                vpnIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(vpnIntent);
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        }
    }

    private void startExploration() {
        String host = com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        if (host.isEmpty() || host.equals("0.0.0.0")) return;

        saveLastIp(host);
        topProgressBar.setVisibility(View.VISIBLE);
        btnConnect.setEnabled(false);
        int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
        int port = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
        executor.execute(() -> {
            boolean cached = loadExplorerCache(host);
            boolean ok = client.isConnected() || client.connect(host, port, timeout);
            
            if (ok && !cached) {
                // Pre-fetch all variables and their FCs for each LD
                List<String> lds = client.getLogicalDevices();
                for (String ld : lds) {
                    List<String> vars = client.getLogicalDeviceVariables(ld);
                    for (String var : vars) {
                        String[] parts = var.split("\\$");
                        if (parts.length >= 3) {
                            String fc = parts[1];
                            String mmsPath = var.replace("$" + fc + "$", ".");
                            if (mmsPath.contains("$")) mmsPath = mmsPath.replace("$", ".");
                            nodeToFcMap.put(ld + "/" + mmsPath, fc);
                        }
                    }
                }
                saveExplorerCache(host);
            }
            mainHandler.post(() -> {
                topProgressBar.setVisibility(View.GONE);
                btnConnect.setEnabled(true);
                if (ok) {
                    btnConnect.setVisibility(View.GONE);
                    btnDisconnect.setVisibility(View.VISIBLE);
                    layoutSearch.setVisibility(View.VISIBLE);
                    loadLogicalDevices();
                    startRealtimeRefresh();
                } else {
                    Toast.makeText(this, getString(R.string.msg_mms_connect_fail, client.getLastError()), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                adapter.filter(query);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));
    }

    private void refreshStructure() {
        String host = com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        if (host.isEmpty()) return;

        java.util.Set<String> expandedPaths = new java.util.HashSet<>();
        collectExpandedPaths(adapter.originalNodesSnapshot(), expandedPaths);
        pathsToRestore = expandedPaths.isEmpty() ? null : expandedPaths;

        java.io.File cacheFile = new java.io.File(getCacheDir(), "mms_cache_" + host.replace(".", "_") + ".json");
        if (cacheFile.exists()) cacheFile.delete();

        cachedLds.clear();
        nodeToFcMap.clear();
        cachedFolders.clear();
        adapter.setNodes(new ArrayList<>());
        startExploration();
    }

    private void collectExpandedPaths(List<MmsNode> nodes, java.util.Set<String> out) {
        for (MmsNode n : nodes) {
            if (n.isExpanded) {
                out.add(n.fullPath);
                if (!n.children.isEmpty()) collectExpandedPaths(n.children, out);
            }
        }
    }

    private void restoreExpandedNodes(List<MmsNode> topLevelNodes) {
        if (pathsToRestore == null || pathsToRestore.isEmpty()) return;
        for (MmsNode node : topLevelNodes) {
            if (pathsToRestore.remove(node.fullPath)) {
                fetchChildren(node);
            }
        }
    }

    private void restoreExpandedChildren(MmsNode node) {
        if (pathsToRestore == null || pathsToRestore.isEmpty()) return;
        for (MmsNode child : node.children) {
            if (pathsToRestore.remove(child.fullPath)) {
                fetchChildren(child);
            }
        }
    }

    private final List<String> cachedLds = new ArrayList<>();

    private void saveExplorerCache(String ip) {
        try {
            JSONObject cache = new JSONObject();
            JSONObject fcJson = new JSONObject();
            for (java.util.Map.Entry<String, String> entry : nodeToFcMap.entrySet()) {
                fcJson.put(entry.getKey(), entry.getValue());
            }
            cache.put("nodeToFcMap", fcJson);
            
            JSONArray ldJson = new JSONArray(cachedLds);
            cache.put("logicalDevices", ldJson);

            // Cache expanded folder contents
            JSONObject folderCache = new JSONObject();
            for (MmsNode node : adapter.visibleNodes) {
                if (node.isLoaded && !node.children.isEmpty()) {
                    JSONArray childArray = new JSONArray();
                    for (MmsNode child : node.children) {
                        JSONObject cObj = new JSONObject();
                        cObj.put("name", child.name);
                        cObj.put("path", child.fullPath);
                        cObj.put("type", child.type.name());
                        cObj.put("level", child.level);
                        cObj.put("isLeaf", child.isLeaf);
                        childArray.put(cObj);
                    }
                    folderCache.put(node.fullPath, childArray);
                }
            }
            cache.put("folderCache", folderCache);
            
            java.io.File cacheFile = new java.io.File(getCacheDir(), "mms_cache_" + ip.replace(".", "_") + ".json");
            java.io.FileWriter writer = new java.io.FileWriter(cacheFile);
            writer.write(cache.toString());
            writer.close();
        } catch (Exception e) {
            Log.e("MmsExplorer", "Failed to save cache", e);
        }
    }

    private final java.util.Map<String, List<MmsNode>> cachedFolders = new java.util.HashMap<>();

    private boolean loadExplorerCache(String ip) {
        try {
            java.io.File cacheFile = new java.io.File(getCacheDir(), "mms_cache_" + ip.replace(".", "_") + ".json");
            if (!cacheFile.exists()) return false;
            
            // Limit cache to 7 days
            if (System.currentTimeMillis() - cacheFile.lastModified() > 7 * 24 * 60 * 60 * 1000) {
                if (cacheFile.delete()) return false;
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cacheFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject cache = new JSONObject(sb.toString());
            
            // Restore FC Map
            JSONObject fcJson = cache.getJSONObject("nodeToFcMap");
            nodeToFcMap.clear();
            java.util.Iterator<String> keys = fcJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                nodeToFcMap.put(key, fcJson.getString(key));
            }

            // Restore LDs
            cachedLds.clear();
            JSONArray ldJson = cache.optJSONArray("logicalDevices");
            if (ldJson != null) {
                for (int i = 0; i < ldJson.length(); i++) cachedLds.add(ldJson.getString(i));
            }

            // Restore Folder Cache
            cachedFolders.clear();
            JSONObject folderCache = cache.optJSONObject("folderCache");
            if (folderCache != null) {
                java.util.Iterator<String> fKeys = folderCache.keys();
                while (fKeys.hasNext()) {
                    String path = fKeys.next();
                    JSONArray children = folderCache.getJSONArray(path);
                    List<MmsNode> nodes = new ArrayList<>();
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject c = children.getJSONObject(i);
                        MmsNode mn = new MmsNode(c.getString("name"), c.getString("path"), 
                                NodeType.valueOf(c.getString("type")), c.getInt("level"));
                        mn.isLeaf = c.getBoolean("isLeaf");
                        nodes.add(mn);
                    }
                    cachedFolders.put(path, nodes);
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e("MmsExplorer", "Failed to load cache", e);
            return false;
        }
    }

    private void stopExploration() {
        isRealtimeActive = false;
        mainHandler.removeCallbacks(realtimeRunnable);
        executor.execute(() -> {
            client.disconnect();
            mainHandler.post(() -> {
                btnDisconnect.setVisibility(View.GONE);
                layoutSearch.setVisibility(View.GONE);
                etSearch.setText("");
                btnConnect.setVisibility(View.VISIBLE);
                cachedLds.clear();
                nodeToFcMap.clear();
                pathsToRestore = null;
                adapter.setNodes(new ArrayList<>());
                Toast.makeText(this, R.string.msg_mms_connection_closed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void startRealtimeRefresh() {
        if (!isRealtimeActive) {
            isRealtimeActive = true;
            mainHandler.postDelayed(realtimeRunnable, 5000);
        }
    }

    private void refreshVisibleValues() {
        List<MmsNode> nodesToUpdate = new ArrayList<>();
        for (MmsNode node : adapter.visibleNodes) {
            if (node.type == NodeType.DA) {
                nodesToUpdate.add(node);
            }
        }

        if (nodesToUpdate.isEmpty()) return;

        executor.execute(() -> {
            if (!client.isConnected()) return;
            boolean changed = false;

            for (MmsNode node : nodesToUpdate) {
                Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(node.fullPath, nodeToFcMap.get(node.fullPath));
                if (result != null) {
                    nodeToFcMap.put(node.fullPath, result.fc);
                    if (!result.value.equals(node.value)) {
                        node.value = result.value;
                        changed = true;
                    }
                }
            }

            if (changed) {
                mainHandler.post(() -> adapter.refreshValues());
            }
        });
    }

    private void loadLogicalDevices() {
        if (!cachedLds.isEmpty()) {
            List<MmsNode> nodes = new ArrayList<>();
            for (String ld : cachedLds) nodes.add(new MmsNode(ld, ld, NodeType.LD, 0));
            adapter.setNodes(nodes);
            restoreExpandedNodes(nodes);
            prefetchPriorityFolder(nodes);
            return;
        }

        topProgressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<String> lds = client.getLogicalDevices();
            cachedLds.clear();
            cachedLds.addAll(lds);
            List<MmsNode> nodes = new ArrayList<>();
            for (String ld : lds) {
                nodes.add(new MmsNode(ld, ld, NodeType.LD, 0));
            }
            mainHandler.post(() -> {
                topProgressBar.setVisibility(View.GONE);
                adapter.setNodes(nodes);
                saveExplorerCache(com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4));
                restoreExpandedNodes(nodes);
                prefetchPriorityFolder(nodes);
            });
        });
    }

    /**
     * Eagerly walks the "Measurements" logical device's full subtree (LN -> DO -> DA, including
     * leaf value reads) right after connecting, instead of leaving it to the normal lazy
     * fetch-on-first-expand behavior - it's the folder users check first on almost every relay, so
     * by the time they tap into it, it's already loaded from cachedFolders with no spinner. Every
     * other logical device is intentionally left to the existing lazy load + cache, since eagerly
     * walking the entire data model on every connect (potentially hundreds of points across many
     * logical devices) would make connecting itself slow for no benefit most of the time.
     */
    private void prefetchPriorityFolder(List<MmsNode> rootNodes) {
        MmsNode measurements = null;
        for (MmsNode n : rootNodes) {
            if (n.name.equalsIgnoreCase("Measurements")) {
                measurements = n;
                break;
            }
        }
        if (measurements == null || measurements.isLoaded) return;

        MmsNode target = measurements;
        executor.execute(() -> {
            eagerLoadSubtree(target);
            mainHandler.post(() -> saveExplorerCache(com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4)));
        });
    }

    /** Runs on the background executor - synchronously walks and caches node's full subtree. */
    private void eagerLoadSubtree(MmsNode node) {
        if (node.isLoaded) {
            for (MmsNode child : node.children) eagerLoadSubtree(child);
            return;
        }

        List<MmsNode> children = new ArrayList<>();
        try {
            if (node.type == NodeType.LD) {
                List<String> lns = client.getLogicalDeviceDirectory(node.name);
                for (String ln : lns) children.add(new MmsNode(ln, node.fullPath + "/" + ln, NodeType.LN, node.level + 1));
            } else if (node.type == NodeType.LN) {
                List<String> dos = client.getLogicalNodeDirectory(node.fullPath);
                for (String doName : dos) children.add(new MmsNode(doName, node.fullPath + "." + doName, NodeType.DO, node.level + 1));
            } else if (node.type == NodeType.DO || node.type == NodeType.DA) {
                List<String> subItems = client.getDataDirectory(node.fullPath);
                if (subItems != null && !subItems.isEmpty()) {
                    for (String subName : subItems) {
                        children.add(new MmsNode(subName, node.fullPath + "." + subName, NodeType.DA, node.level + 1));
                    }
                } else {
                    node.isLeaf = true;
                    Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(node.fullPath, nodeToFcMap.get(node.fullPath));
                    if (result != null) {
                        node.value = result.value;
                        nodeToFcMap.put(node.fullPath, result.fc);
                    }
                }
            }
        } catch (Exception ignored) {}

        node.children = children;
        node.isLoaded = true;
        if (!node.isLeaf) cachedFolders.put(node.fullPath, children);

        for (MmsNode child : children) eagerLoadSubtree(child);
    }

    private void toggleNode(MmsNode node) {
        int position = adapter.visibleNodes.indexOf(node);
        if (position == -1) return;

        if (node.isExpanded) {
            node.isExpanded = false;
            adapter.collapse(node, position);
        } else {
            if (node.isLoaded) {
                node.isExpanded = true;
                adapter.expand(node, position);
            } else if (cachedFolders.containsKey(node.fullPath)) {
                node.children = cachedFolders.get(node.fullPath);
                node.isLoaded = true;
                node.isExpanded = true;
                adapter.expand(node, position);
            } else {
                fetchChildren(node);
            }
        }
    }

    private void fetchChildren(MmsNode node) {
        topProgressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<MmsNode> children = new ArrayList<>();
            try {
                if (node.type == NodeType.LD) {
                    List<String> lns = client.getLogicalDeviceDirectory(node.name);
                    for (String ln : lns) children.add(new MmsNode(ln, node.fullPath + "/" + ln, NodeType.LN, node.level + 1));
                } else if (node.type == NodeType.LN) {
                    List<String> dos = client.getLogicalNodeDirectory(node.fullPath);
                    for (String doName : dos) children.add(new MmsNode(doName, node.fullPath + "." + doName, NodeType.DO, node.level + 1));
                } else if (node.type == NodeType.DO || node.type == NodeType.DA) {
                    List<String> subItems = client.getDataDirectory(node.fullPath);
                    if (subItems != null && !subItems.isEmpty()) {
                        for (String subName : subItems) {
                            children.add(new MmsNode(subName, node.fullPath + "." + subName, NodeType.DA, node.level + 1));
                        }
                    } else {
                        node.isLeaf = true;
                        // Try to read value if it's a leaf
                        Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(node.fullPath, nodeToFcMap.get(node.fullPath));
                        if (result != null) {
                            node.value = result.value;
                            nodeToFcMap.put(node.fullPath, result.fc);
                        }
                    }
                }
            } catch (Exception ignored) {}

            mainHandler.post(() -> {
                topProgressBar.setVisibility(View.GONE);
                node.children = children;
                node.isLoaded = true;
                if (!node.isLeaf) {
                    node.isExpanded = true;
                    int position = adapter.visibleNodes.indexOf(node);
                    if (position != -1) {
                        adapter.expand(node, position);
                    }
                    saveExplorerCache(com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4));
                    restoreExpandedChildren(node);
                } else {
                    adapter.notifyItemChanged(adapter.visibleNodes.indexOf(node));
                }
            });
        });
    }

    class ExplorerAdapter extends RecyclerView.Adapter<ExplorerViewHolder> {
        final List<MmsNode> visibleNodes = new ArrayList<>();
        private List<MmsNode> originalNodes = new ArrayList<>();
        private String currentFilter = "";

        void setNodes(List<MmsNode> nodes) {
            originalNodes = new ArrayList<>(nodes);
            applyFilterInternal();
        }

        List<MmsNode> originalNodesSnapshot() {
            return originalNodes;
        }

        void filter(String query) {
            currentFilter = query;
            applyFilterInternal();
        }

        private void applyFilterInternal() {
            visibleNodes.clear();
            if (currentFilter.isEmpty()) {
                addNodesRecursively(originalNodes);
            } else {
                searchRecursively(originalNodes);
            }
            notifyDataSetChanged();
        }

        private void addNodesRecursively(List<MmsNode> nodes) {
            for (MmsNode n : nodes) {
                visibleNodes.add(n);
                if (n.isExpanded && !n.children.isEmpty()) {
                    addNodesRecursively(n.children);
                }
            }
        }

        private void searchRecursively(List<MmsNode> nodes) {
            for (MmsNode n : nodes) {
                if (n.name.toLowerCase().contains(currentFilter) || n.fullPath.toLowerCase().contains(currentFilter)) {
                    visibleNodes.add(n);
                }
                if (!n.children.isEmpty()) {
                    searchRecursively(n.children);
                }
            }
        }

        void expand(MmsNode node, int position) {
            if (!currentFilter.isEmpty()) return; // Disable manual toggle during search
            if (position < 0 || position >= visibleNodes.size()) return;
            visibleNodes.addAll(position + 1, node.children);
            notifyItemRangeInserted(position + 1, node.children.size());
            notifyItemChanged(position);
        }

        void collapse(MmsNode node, int position) {
            if (!currentFilter.isEmpty()) return;
            if (position < 0 || position >= visibleNodes.size()) return;
            int count = 0;
            int nextPos = position + 1;
            while (nextPos < visibleNodes.size() && visibleNodes.get(nextPos).level > node.level) {
                visibleNodes.remove(nextPos);
                count++;
            }
            notifyItemRangeRemoved(position + 1, count);
            notifyItemChanged(position);
        }

        void refreshValues() {
            notifyDataSetChanged();
        }

        @NonNull @Override public ExplorerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ExplorerViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mms_node, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull ExplorerViewHolder holder, int position) {
            holder.bind(visibleNodes.get(position), position);
        }

        @Override public int getItemCount() { return visibleNodes.size(); }
    }

    class ExplorerViewHolder extends RecyclerView.ViewHolder {
        View root, divider; ImageView imgExpand, imgIcon, btnAddMonitor; TextView txtName, txtInfo, txtValue;

        ExplorerViewHolder(View v) {
            super(v);
            root = v.findViewById(R.id.nodeRoot);
            divider = v.findViewById(R.id.nodeDivider);
            imgExpand = v.findViewById(R.id.imgExpand);
            imgIcon = v.findViewById(R.id.imgIcon);
            btnAddMonitor = v.findViewById(R.id.btnAddMonitor);
            txtName = v.findViewById(R.id.txtNodeName);
            txtInfo = v.findViewById(R.id.txtNodeInfo);
            txtValue = v.findViewById(R.id.txtValue);
        }

        void bind(MmsNode node, int position) {
            txtName.setText(node.name);
            txtValue.setText(node.value);
            txtValue.setVisibility(node.isLeaf && !node.value.isEmpty() ? View.VISIBLE : View.GONE);
            btnAddMonitor.setVisibility(node.isLeaf ? View.VISIBLE : View.GONE);
            
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
            lp.setMarginStart(node.level * 48); // Fixed indentation for better hierarchy
            root.setLayoutParams(lp);

            if (node.isLeaf) {
                imgExpand.setVisibility(View.INVISIBLE);
                imgIcon.setImageResource(R.drawable.ic_list);
                imgIcon.setAlpha(0.7f);
            } else {
                imgExpand.setVisibility(View.VISIBLE);
                imgExpand.setRotation(node.isExpanded ? 90 : 0);
                imgIcon.setImageResource(node.type == NodeType.LD ? R.drawable.ic_save : R.drawable.ic_folder);
                imgIcon.setAlpha(1.0f);
            }

            root.setOnClickListener(v -> toggleNode(node));
            btnAddMonitor.setOnClickListener(v -> showAddMonitorDialog(node));

            if (divider != null) {
                divider.setVisibility(position == adapter.getItemCount() - 1 ? View.GONE : View.VISIBLE);
            }
        }
    }

    /**
     * Lets the user set the custom name/unit/multiplier and pick the value type right when a
     * point is added from IED Explorer, instead of adding it with just the raw node name and
     * having to open IED Monitoring's separate edit dialog afterward to set those - mirrors the
     * same fields (name/unit/multiplier) already editable per-point in Bulk Edit there.
     */
    private void showAddMonitorDialog(MmsNode node) {
        View v = getLayoutInflater().inflate(R.layout.dialog_add_to_monitoring, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvPath = v.findViewById(R.id.tvAddNodePath);
        EditText etCustomName = v.findViewById(R.id.etAddCustomName);
        TextView optFloat = v.findViewById(R.id.optTypeFloat);
        TextView optBool = v.findViewById(R.id.optTypeBool);
        TextView optString = v.findViewById(R.id.optTypeString);
        View layoutUnit = v.findViewById(R.id.layoutAddUnit);
        View layoutMultiplier = v.findViewById(R.id.layoutAddMultiplier);
        EditText etUnit = v.findViewById(R.id.etAddUnit);
        EditText etMultiplier = v.findViewById(R.id.etAddMultiplier);

        tvPath.setText(node.fullPath);
        etCustomName.setText(node.name);

        TextView[] options = {optFloat, optBool, optString};
        String[] typeForOption = {"float", "boolean", "string"};
        int[] selectedIndex = {0};

        Runnable applySelection = () -> {
            for (int i = 0; i < options.length; i++) {
                boolean selected = i == selectedIndex[0];
                options[i].setBackgroundResource(selected ? R.drawable.bg_type_option_selected : R.drawable.bg_edit_text);
                options[i].setTextColor(ContextCompat.getColor(this, selected ? R.color.white : R.color.text_secondary));
            }
            boolean isFloat = "float".equals(typeForOption[selectedIndex[0]]);
            layoutUnit.setVisibility(isFloat ? View.VISIBLE : View.GONE);
            layoutMultiplier.setVisibility(isFloat ? View.VISIBLE : View.GONE);
        };
        applySelection.run();

        for (int i = 0; i < options.length; i++) {
            int index = i;
            options[i].setOnClickListener(opt -> {
                selectedIndex[0] = index;
                applySelection.run();
            });
        }

        v.findViewById(R.id.btnAddCancel).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnAddConfirm).setOnClickListener(view -> {
            String type = typeForOption[selectedIndex[0]];
            String customName = etCustomName.getText().toString().trim();
            if (customName.isEmpty()) customName = node.name;

            float multiplier = 1.0f;
            if ("float".equals(type)) {
                try {
                    multiplier = Float.parseFloat(etMultiplier.getText().toString());
                } catch (Exception ignored) {}
            }

            addToMonitoring(node, type, customName, "float".equals(type) ? etUnit.getText().toString().trim() : "", multiplier);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void addToMonitoring(MmsNode node, String type, String customName, String unit, float multiplier) {
        String host = com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        String deviceName = tvDeviceInfo.getText().toString();
        if (deviceName.isEmpty()) deviceName = host;
        else if (deviceName.contains("(")) {
             deviceName = deviceName.substring(deviceName.indexOf("(") + 1, deviceName.lastIndexOf(")"));
        }

        MonitoredNode mn = new MonitoredNode(deviceName, host, node.name, node.fullPath, type);
        mn.customName = customName;
        mn.unit = unit;
        mn.multiplier = multiplier;
        new MonitoringManager(this).addNode(mn);
        Toast.makeText(this, R.string.lbl_mon_added, Toast.LENGTH_SHORT).show();
    }

    /** Themed replacement for a stock overflow menu - matches the app's card/dropdown look
     *  (bg_popup_menu_card, theme-aware surface/text colors), same as IED Monitoring's header
     *  menu. Refresh/Get Definition only make sense with a live connection, so they're left out
     *  of the menu entirely while disconnected instead of showing them disabled. */
    private void showMoreOptionsMenu(View anchor) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_popup_menu_card);

        PopupWindow popup = new PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dpToPx(8));
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        if (client.isConnected()) {
            addMoreOption(container, popup, R.drawable.ic_sync, R.string.btn_mms_refresh, this::refreshStructure);
            addMoreOption(container, popup, R.drawable.ic_definition, R.string.btn_mms_get_definition, this::fetchAllDefinitions);
        }
        addMoreOption(container, popup, R.drawable.ic_table, R.string.btn_mms_open_definitions,
                this::showPickDefinitionDeviceDialog);
        addMoreOption(container, popup, R.drawable.ic_ied_monitor, R.string.btn_mms_open_monitoring,
                () -> startActivity(new Intent(this, IEDMonitoringActivity.class)));

        if (container.getChildCount() > 0) {
            View lastRow = container.getChildAt(container.getChildCount() - 1);
            View divider = lastRow.findViewById(R.id.dividerMenuOption);
            if (divider != null) divider.setVisibility(View.GONE);
        }
        container.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int xOff = anchor.getWidth() - container.getMeasuredWidth();
        popup.showAsDropDown(anchor, xOff, 0);
    }

    private void addMoreOption(LinearLayout container, PopupWindow popup, int iconRes, int labelRes, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_header_menu_option, container, false);
        ImageView icon = row.findViewById(R.id.imgMenuOptionIcon);
        TextView label = row.findViewById(R.id.txtMenuOptionLabel);
        icon.setImageResource(iconRes);
        label.setText(labelRes);
        // The clickable/ripple widget is the inner rowMenuOption (see item_header_menu_option.xml),
        // not the row's own root - listening on the root instead lets the inner view swallow the
        // tap (it's still clickable=true for its ripple) without ever firing this listener.
        row.findViewById(R.id.rowMenuOption).setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        container.addView(row);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /** Lists every device that has a saved Node Definitions table (name + IP), so "Table" in the
     *  overflow menu opens straight to the one the user means instead of one giant mixed table. */
    private void showPickDefinitionDeviceDialog() {
        List<NodeDefinition> all = new NodeDefinitionManager(this).getAll();
        java.util.LinkedHashMap<String, List<NodeDefinition>> grouped = new java.util.LinkedHashMap<>();
        for (NodeDefinition d : all) {
            List<NodeDefinition> list = grouped.get(d.ip);
            if (list == null) { list = new ArrayList<>(); grouped.put(d.ip, list); }
            list.add(d);
        }
        if (grouped.isEmpty()) {
            Toast.makeText(this, R.string.msg_mms_definitions_empty_pick, Toast.LENGTH_SHORT).show();
            return;
        }

        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(R.string.ttl_mms_definitions_pick);
        LinearLayout container = v.findViewById(R.id.llPickListItems);

        for (java.util.Map.Entry<String, List<NodeDefinition>> entry : grouped.entrySet()) {
            String ip = entry.getKey();
            List<NodeDefinition> defs = entry.getValue();
            String deviceName = defs.get(0).deviceName;

            View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
            ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText(deviceName);
            ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(
                    getString(R.string.lbl_mms_definitions_pick_subtitle, ip, defs.size()));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                Intent intent = new Intent(this, NodeDefinitionListActivity.class);
                intent.putExtra("ip", ip);
                startActivity(intent);
            });
            container.addView(row);
        }

        v.findViewById(R.id.btnPickListCancel).setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    /**
     * Walks the whole connected device's data model (every LD -> LN -> DO, recursing into any
     * nested SDO structure) looking for attributes literally named "d" (IEC 61850's free-text
     * description field, e.g. "System/AlmGGIO1.Alm10.d") and saves them to the Node Definitions
     * table - a per-node human-readable lookup, independent of IED Monitoring. Only the "d"
     * leaves themselves are read (a live MMS value read); every other node along the way is just
     * directory-listed to find its children, same cost model as prefetchPriorityFolder's
     * eagerLoadSubtree. Can take a while on a device with many logical nodes.
     */
    private void fetchAllDefinitions() {
        String host = com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        if (host.isEmpty() || !client.isConnected() || isFetchingDefinitions) return;

        MonitoringManager.DeviceHeaderData headerData = MonitoringManager.getDeviceHeaderData(this, host);
        // Unlike IED Monitoring's plain "[GI] bay [Bay]" header, the Definitions table also spells
        // out device/merk/type - Get Definition is exactly the tool for telling apart several
        // similar relays that happen to share a GI/Bay naming pattern.
        String deviceName = headerData != null
                ? String.format("%s - %s (%s %s)", headerData.title, headerData.device, headerData.merk, headerData.type)
                : host;

        topProgressBar.setVisibility(View.VISIBLE);
        isFetchingDefinitions = true;
        executor.execute(() -> {
            List<NodeDefinition> found = new ArrayList<>();
            try {
                List<String> lds = client.getLogicalDevices();
                for (String ld : lds) {
                    List<String> lns = client.getLogicalDeviceDirectory(ld);
                    for (String ln : lns) {
                        String lnPath = ld + "/" + ln;
                        List<String> dos = client.getLogicalNodeDirectory(lnPath);
                        for (String doName : dos) {
                            collectDefinitionsUnderDO(lnPath + "." + doName, host, deviceName, found);
                        }
                    }
                }
            } catch (Exception ignored) {}

            new NodeDefinitionManager(this).replaceForIp(host, found);
            int count = found.size();
            mainHandler.post(() -> {
                topProgressBar.setVisibility(View.GONE);
                isFetchingDefinitions = false;
                Toast.makeText(this, count > 0
                        ? getString(R.string.msg_mms_definitions_saved, count)
                        : getString(R.string.msg_mms_definitions_none_found), Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Runs on the background executor - recurses into path's sub-structure, reading only the
     *  leaves literally named "d" (see fetchAllDefinitions()). */
    private void collectDefinitionsUnderDO(String path, String host, String deviceName, List<NodeDefinition> out) {
        List<String> subItems;
        try {
            subItems = client.getDataDirectory(path);
        } catch (Exception e) {
            return;
        }
        if (subItems == null || subItems.isEmpty()) return; // leaf - nothing under it to check

        for (String sub : subItems) {
            String subPath = path + "." + sub;
            if (sub.equals("d")) {
                Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(subPath, nodeToFcMap.get(subPath));
                NodeDefinition def = new NodeDefinition(host, deviceName, subPath, result != null ? result.value : "");
                if (subItems.contains("general")) {
                    // Common data classes like ACD/ACT pair a "general" boolean alarm state with
                    // "d" as its description at the same DO - surface it alongside the description
                    // since that's usually the actual point worth monitoring.
                    String generalPath = path + ".general";
                    Iec61850DfrClient.FcReadResult genResult = client.readWithFcFallback(generalPath, nodeToFcMap.get(generalPath));
                    def.hasGeneralStatus = true;
                    def.generalStatusValue = genResult != null ? genResult.value : "";
                }
                out.add(def);
            } else {
                collectDefinitionsUnderDO(subPath, host, deviceName, out);
            }
        }
    }

    @Override
    protected void onDestroy() {
        isRealtimeActive = false;
        mainHandler.removeCallbacks(realtimeRunnable);
        executor.shutdown();
        client.disconnect();
        super.onDestroy();
    }
}
