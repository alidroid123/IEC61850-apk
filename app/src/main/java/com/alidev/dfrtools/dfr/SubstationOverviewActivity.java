package com.alidev.dfrtools.dfr;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-mostly "at a glance" status board: devices from the Device Database grouped by
 * Gardu Induk, tiled and colored by their last known ping status (same persisted state
 * DeviceListActivity writes to "dfr_prefs"). Editing stays in Device Database - tapping a
 * tile only offers quick actions (ping/explorer/download) via the shared action dialog.
 */
public class SubstationOverviewActivity extends BaseActivity {

    private static final int[] GI_ACCENT_COLORS = {
            0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50,
            0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFF795548, 0xFF9E9E9E,
            0xFF607D8B, 0xFFE91E63, 0xFFF44336, 0xFF311B92
    };

    private LinearLayout containerGi;
    private View tvEmpty;
    private TextView tvStatGi, tvStatDevice, tvStatOnline, tvStatOffline;
    private final List<DeviceListActivity.DeviceItem> deviceList = new ArrayList<>();
    private ExecutorService mPingExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_substation_overview);

        containerGi = findViewById(R.id.containerGi);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvStatGi = findViewById(R.id.tvStatGi);
        tvStatDevice = findViewById(R.id.tvStatDevice);
        tvStatOnline = findViewById(R.id.tvStatOnline);
        tvStatOffline = findViewById(R.id.tvStatOffline);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> loadAndRender());

        loadAndRender();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAndRender();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPingExecutor != null) mPingExecutor.shutdownNow();
    }

    private void loadAndRender() {
        loadDevices();
        render();
    }

    private void loadDevices() {
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        deviceList.clear();
        try {
            JSONArray arr = new JSONArray(pref.getString("device_list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                DeviceListActivity.DeviceItem item = new DeviceListActivity.DeviceItem();
                item.gi = obj.optString("gi", "").trim();
                item.bay = obj.optString("bay", "").trim();
                item.device = obj.optString("device", "").trim();
                item.ip = obj.optString("ip", "").trim();
                item.merk = obj.optString("merk", "").trim();
                item.type = obj.optString("type", "").trim();
                deviceList.add(item);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            JSONObject results = new JSONObject(pref.getString("last_ping_results", "{}"));
            for (DeviceListActivity.DeviceItem item : deviceList) {
                if (results.has(item.ip)) {
                    JSONObject obj = results.getJSONObject(item.ip);
                    item.pingStatus = obj.optString("status", null);
                    item.isPort102Open = obj.optBoolean("port102", false);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void render() {
        containerGi.removeAllViews();

        if (deviceList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvStatGi.setText("0");
            tvStatDevice.setText("0");
            tvStatOnline.setText("0");
            tvStatOffline.setText("0");
            return;
        }
        tvEmpty.setVisibility(View.GONE);

        String online = getString(R.string.lbl_dev_status_online);
        String offline = getString(R.string.lbl_dev_status_offline);

        Map<String, List<DeviceListActivity.DeviceItem>> byGi = new LinkedHashMap<>();
        List<String> giOrder = new ArrayList<>();
        for (DeviceListActivity.DeviceItem item : deviceList) {
            String gi = item.gi.isEmpty() ? "-" : item.gi;
            if (!byGi.containsKey(gi)) {
                byGi.put(gi, new ArrayList<>());
                giOrder.add(gi);
            }
            byGi.get(gi).add(item);
        }
        Collections.sort(giOrder, String::compareToIgnoreCase);

        int onlineCount = 0, offlineCount = 0;
        for (DeviceListActivity.DeviceItem item : deviceList) {
            if (online.equals(item.pingStatus)) onlineCount++;
            else if (offline.equals(item.pingStatus)) offlineCount++;
        }
        tvStatGi.setText(String.valueOf(giOrder.size()));
        tvStatDevice.setText(String.valueOf(deviceList.size()));
        tvStatOnline.setText(String.valueOf(onlineCount));
        tvStatOffline.setText(String.valueOf(offlineCount));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String gi : giOrder) {
            List<DeviceListActivity.DeviceItem> devices = byGi.get(gi);
            View section = inflater.inflate(R.layout.item_sld_gi_section, containerGi, false);

            ((TextView) section.findViewById(R.id.tvGiName)).setText(gi);
            section.findViewById(R.id.viewGiAccent).setBackgroundColor(
                    GI_ACCENT_COLORS[Math.abs(gi.hashCode()) % GI_ACCENT_COLORS.length]);

            int giOnline = 0;
            for (DeviceListActivity.DeviceItem d : devices) if (online.equals(d.pingStatus)) giOnline++;
            ((TextView) section.findViewById(R.id.tvGiSummary))
                    .setText(getString(R.string.lbl_sld_gi_summary, giOnline, devices.size()));

            RecyclerView rv = section.findViewById(R.id.rvGiTiles);
            rv.setLayoutManager(new GridLayoutManager(this, 3));
            rv.setNestedScrollingEnabled(false);
            rv.setAdapter(new TileAdapter(devices, online, offline, this::showDeviceActionDialog));

            containerGi.addView(section);
        }
    }

    private interface OnTileSelected {
        void onSelected(DeviceListActivity.DeviceItem item);
    }

    private static class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
        private final List<DeviceListActivity.DeviceItem> items;
        private final String online, offline;
        private final OnTileSelected listener;

        TileAdapter(List<DeviceListActivity.DeviceItem> items, String online, String offline, OnTileSelected listener) {
            this.items = items;
            this.online = online;
            this.offline = offline;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sld_device_tile, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DeviceListActivity.DeviceItem item = items.get(position);
            holder.tvBay.setText(item.bay.isEmpty() ? item.device : item.bay);
            holder.tvDevice.setText(item.device);
            holder.tvIp.setText(item.ip);

            int color;
            if (online.equals(item.pingStatus)) {
                color = ContextCompat.getColor(holder.itemView.getContext(), R.color.status_safe);
            } else if (offline.equals(item.pingStatus)) {
                color = ContextCompat.getColor(holder.itemView.getContext(), R.color.status_danger);
            } else {
                color = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_hint);
            }
            holder.dot.setBackgroundTintList(ColorStateList.valueOf(color));

            holder.itemView.setOnClickListener(v -> listener.onSelected(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvBay, tvDevice, tvIp;
            View dot;

            VH(View v) {
                super(v);
                tvBay = v.findViewById(R.id.tvBayName);
                tvDevice = v.findViewById(R.id.tvDeviceName);
                tvIp = v.findViewById(R.id.tvIp);
                dot = v.findViewById(R.id.viewStatusDot);
            }
        }
    }

    private void showDeviceActionDialog(DeviceListActivity.DeviceItem item) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_device_actions, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        TextView tvTitle = dialogView.findViewById(R.id.tvActionTitle);
        tvTitle.setText(getString(R.string.ttl_dev_action_select, item.device, item.gi, item.bay));

        dialogView.findViewById(R.id.btnActionEdit).setVisibility(View.GONE);
        dialogView.findViewById(R.id.btnActionDelete).setVisibility(View.GONE);

        dialogView.findViewById(R.id.btnActionPing).setOnClickListener(v -> {
            dialog.dismiss();
            checkIntranetAndExecute(() -> pingSingleDevice(item));
        });

        dialogView.findViewById(R.id.btnActionExplorer).setOnClickListener(v -> {
            dialog.dismiss();
            checkIntranetAndExecute(() -> {
                Intent intent = new Intent(this, MmsExplorerActivity.class);
                intent.putExtra("ip", item.ip);
                startActivity(intent);
            });
        });

        dialogView.findViewById(R.id.btnActionDownload).setOnClickListener(v -> {
            dialog.dismiss();
            checkIntranetAndExecute(() -> {
                Intent intent = new Intent(this, DfrDownloadActivity.class);
                intent.putExtra("ip", item.ip);
                intent.putExtra("gi", item.gi);
                intent.putExtra("bay", item.bay);
                intent.putExtra("device", item.device);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            });
        });

        dialogView.findViewById(R.id.btnActionCancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void initPingExecutor() {
        if (mPingExecutor != null && !mPingExecutor.isShutdown()) return;
        int poolSize = com.alidev.dfrtools.utils.ConfigHelper.getThreadPoolSize(this);
        mPingExecutor = Executors.newFixedThreadPool(poolSize);
    }

    private void pingSingleDevice(DeviceListActivity.DeviceItem item) {
        initPingExecutor();
        int count = com.alidev.dfrtools.utils.ConfigHelper.getPingCountSingle(this);
        int timeout = getResources().getInteger(R.integer.config_ping_timeout_seconds);
        Toast.makeText(this, getString(R.string.lbl_dev_status_pinging) + " " + item.ip, Toast.LENGTH_SHORT).show();

        mPingExecutor.execute(() -> {
            boolean isOnline = false;
            try {
                String cmd = String.format(java.util.Locale.US, "ping -c %d -W %d %s", count, timeout, item.ip);
                Process process = Runtime.getRuntime().exec(cmd);
                isOnline = (process.waitFor() == 0);
            } catch (Exception ignored) {}

            boolean port102 = false;
            if (isOnline) {
                try (java.net.Socket socket = new java.net.Socket()) {
                    int socketTimeout = getResources().getInteger(R.integer.config_ping_socket_timeout_ms);
                    socket.connect(new java.net.InetSocketAddress(item.ip, 102), socketTimeout);
                    port102 = true;
                } catch (Exception ignored) {}
            }

            item.pingStatus = getString(isOnline ? R.string.lbl_dev_status_online : R.string.lbl_dev_status_offline);
            item.isPort102Open = port102;
            persistPingResult(item);

            runOnUiThread(this::render);
        });
    }

    /** Merges into the same "last_ping_results" map DeviceListActivity reads/writes, so a ping
     *  fired from this screen is reflected there too (and vice versa) without clobbering other
     *  devices' cached status. */
    private void persistPingResult(DeviceListActivity.DeviceItem item) {
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        try {
            JSONObject results = new JSONObject(pref.getString("last_ping_results", "{}"));
            JSONObject obj = new JSONObject();
            obj.put("status", item.pingStatus);
            obj.put("port102", item.isPort102Open);
            results.put(item.ip, obj);
            pref.edit().putString("last_ping_results", results.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void checkIntranetAndExecute(Runnable onSuccess) {
        Toast.makeText(this, R.string.msg_dev_ping_precheck, Toast.LENGTH_SHORT).show();
        String intranetIp = com.alidev.dfrtools.utils.ConfigHelper.getIntranetIp(this);
        initPingExecutor();
        mPingExecutor.execute(() -> {
            boolean intranetOk = false;
            try {
                Process process = Runtime.getRuntime().exec("ping -c 1 -W 2 " + intranetIp);
                intranetOk = (process.waitFor() == 0);
            } catch (Exception ignored) {}

            final boolean success = intranetOk;
            runOnUiThread(() -> {
                if (success) {
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
}
