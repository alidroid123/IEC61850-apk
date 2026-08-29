package com.alidev.dfrtools.dfr;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceListActivity extends BaseActivity {

    private static final int REQUEST_CSV = 123;
    private RecyclerView rvDevices;
    private DeviceAdapter adapter;
    private Spinner spinnerGi, spinnerBay, spinnerDevice;
    private TextView tvStatTotal, tvStatOnline, tvStatOpen, tvStatClosed, tvStatOffline;
    private View btnExportCsv, btnPingAll, layoutExport;
    private final List<DeviceItem> deviceList = new ArrayList<>();
    private final List<DeviceItem> filteredList = new ArrayList<>();

    private ArrayAdapter<String> adapterGi, adapterBay, adapterDev;
    private final List<String> listGi = new ArrayList<>(), listBay = new ArrayList<>(), listDev = new ArrayList<>();

    private boolean isUpdatingSpinners = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_list);

        initViews();
        loadDevices();
        loadPingResults();
        setupFilters();
        
        adapter = new DeviceAdapter(filteredList, this::onDeviceSelected);
        rvDevices.setAdapter(adapter);

        applyFilters();

        String prefillIp = getIntent().getStringExtra("ip_prefill");
        if (prefillIp != null) {
            showAddDeviceDialog(prefillIp);
        }
    }

    private void showAddDeviceDialog(String ip) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_save_device, null);
        android.widget.EditText etGi = dialogView.findViewById(R.id.etDialogGi);
        android.widget.EditText etBay = dialogView.findViewById(R.id.etDialogBay);
        android.widget.EditText etDevice = dialogView.findViewById(R.id.etDialogDevice);
        android.widget.EditText etMerk = dialogView.findViewById(R.id.etDialogMerk);
        android.widget.EditText etType = dialogView.findViewById(R.id.etDialogType);

        new AlertDialog.Builder(this, R.style.Theme_DFRtools)
                .setTitle(R.string.ttl_dev_save)
                .setView(dialogView)
                .setPositiveButton(R.string.btn_all_save_small, (dialog, which) -> {
                    String gi = etGi.getText().toString().trim();
                    String bay = etBay.getText().toString().trim();
                    String device = etDevice.getText().toString().trim();
                    String merk = etMerk.getText().toString().trim();
                    String type = etType.getText().toString().trim();

                    if (gi.isEmpty() || bay.isEmpty() || device.isEmpty()) {
                        Toast.makeText(this, R.string.msg_all_fields_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveDeviceToList(gi, bay, device, ip, merk, type);
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    private void saveDeviceToList(String gi, String bay, String device, String ip, String merk, String type) {
        JSONArray arr = new JSONArray();
        try {
            SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
            arr = new JSONArray(pref.getString("device_list", "[]"));
            
            JSONObject obj = new JSONObject();
            obj.put("gi", gi);
            obj.put("bay", bay);
            obj.put("device", device);
            obj.put("ip", ip);
            obj.put("merk", merk);
            obj.put("type", type);
            arr.put(obj);

            pref.edit().putString("device_list", arr.toString()).apply();
            Toast.makeText(this, R.string.msg_dl_save_device_ok, Toast.LENGTH_SHORT).show();
            loadDevices();
            applyFilters();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initViews() {
        rvDevices = findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(this));

        spinnerGi = findViewById(R.id.spinnerGi);
        spinnerBay = findViewById(R.id.spinnerBay);
        spinnerDevice = findViewById(R.id.spinnerDevice);

        tvStatTotal = findViewById(R.id.tvStatTotal);
        tvStatOnline = findViewById(R.id.tvStatOnline);
        tvStatOpen = findViewById(R.id.tvStatOpen);
        tvStatClosed = findViewById(R.id.tvStatClosed);
        tvStatOffline = findViewById(R.id.tvStatOffline);

        findViewById(R.id.btnImportCsv).setOnClickListener(v -> showImportInfoDialog());
        btnExportCsv = findViewById(R.id.btnExportCsv);
        layoutExport = findViewById(R.id.layoutExport);
        btnExportCsv.setOnClickListener(v -> exportToCsv());
        btnPingAll = findViewById(R.id.btnPingAll);
        btnPingAll.setOnClickListener(v -> pingAll());

        adapterGi = createSpinnerAdapter(listGi);
        adapterBay = createSpinnerAdapter(listBay);
        adapterDev = createSpinnerAdapter(listDev);

        spinnerGi.setAdapter(adapterGi);
        spinnerBay.setAdapter(adapterBay);
        spinnerDevice.setAdapter(adapterDev);
    }

    private ArrayAdapter<String> createSpinnerAdapter(List<String> list) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.spinner_item_futuristic, list) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setSingleLine(false);
                tv.setMaxLines(2);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
                return tv;
            }
        };
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_futuristic);
        return adapter;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPingExecutor != null) mPingExecutor.shutdownNow();
    }

    private void pingAll() {
        if (filteredList.isEmpty()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_batch_ping, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        TextView tvMsg = dialogView.findViewById(R.id.tvPingMessage);
        tvMsg.setText(getString(R.string.msg_dev_ping_confirm, filteredList.size()));

        dialogView.findViewById(R.id.btnConfirmPing).setOnClickListener(v -> {
            dialog.dismiss();
            checkIntranetAndExecute(this::runPingAllLogic);
        });

        dialogView.findViewById(R.id.btnCancelPing).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private ExecutorService mPingExecutor;

    private void initPingExecutor() {
        if (mPingExecutor != null && !mPingExecutor.isShutdown()) return;
        int poolSize = com.alidev.dfrtools.utils.ConfigHelper.getThreadPoolSize(this);
        mPingExecutor = Executors.newFixedThreadPool(poolSize);
    }

    private void runPingAllLogic() {
        Toast.makeText(this, R.string.msg_dev_ping_start, Toast.LENGTH_SHORT).show();
        initPingExecutor();
        int count = com.alidev.dfrtools.utils.ConfigHelper.getPingCountSingle(this);
        int timeout = getResources().getInteger(R.integer.config_ping_timeout_seconds);
        for (int i = 0; i < filteredList.size(); i++) {
            pingDeviceInternal(filteredList.get(i), i, count, timeout);
        }
    }

    private void pingSingleDevice(DeviceItem item) {
        checkIntranetAndExecute(() -> {
            initPingExecutor();
            int pos = filteredList.indexOf(item);
            int count = com.alidev.dfrtools.utils.ConfigHelper.getPingCountSingle(this);
            int timeout = getResources().getInteger(R.integer.config_ping_timeout_seconds);
            pingDeviceInternal(item, pos, count, timeout);
        });
    }

    private void pingDeviceInternal(DeviceItem item, int pos, int count, int timeout) {
        item.pingStatus = getString(R.string.lbl_dev_status_pinging);
        if (pos != -1) adapter.notifyItemChanged(pos);

        mPingExecutor.execute(() -> {
            boolean online = false;
            try {
                String cmd = String.format(java.util.Locale.US, "ping -c %d -W %d %s", count, timeout, item.ip);
                java.lang.Process process = Runtime.getRuntime().exec(cmd);
                online = (process.waitFor() == 0);
            } catch (Exception ignored) {}

            boolean port102 = false;
            if (online) {
                try (java.net.Socket socket = new java.net.Socket()) {
                    int socketTimeout = getResources().getInteger(R.integer.config_ping_socket_timeout_ms);
                    socket.connect(new java.net.InetSocketAddress(item.ip, 102), socketTimeout);
                    port102 = true;
                } catch (Exception ignored) {}
            }

            item.pingStatus = online ? getString(R.string.lbl_dev_status_online) : getString(R.string.lbl_dev_status_offline);
            item.isPort102Open = port102;

            runOnUiThread(() -> {
                if (pos != -1) adapter.notifyItemChanged(pos);
                savePingResults();
                refreshStats(); // Update counters if visible list is affected
            });
        });
    }

    private void checkIntranetAndExecute(Runnable onSuccess) {
        Toast.makeText(this, R.string.msg_dev_ping_precheck, Toast.LENGTH_SHORT).show();
        String intranetIp = com.alidev.dfrtools.utils.ConfigHelper.getIntranetIp(this);
        initPingExecutor();
        mPingExecutor.execute(() -> {
            boolean intranetOk = false;
            try {
                java.lang.Process process = Runtime.getRuntime().exec("ping -c 1 -W 2 " + intranetIp);
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

    private void savePingResults() {
        JSONObject results = new JSONObject();
        try {
            for (DeviceItem item : deviceList) {
                if (item.pingStatus != null) {
                    JSONObject obj = new JSONObject();
                    obj.put("status", item.pingStatus);
                    obj.put("port102", item.isPort102Open);
                    results.put(item.ip, obj);
                }
            }
            getSharedPreferences("dfr_prefs", MODE_PRIVATE).edit()
                    .putString("last_ping_results", results.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void loadPingResults() {
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String json = pref.getString("last_ping_results", "{}");
        try {
            JSONObject results = new JSONObject(json);
            for (DeviceItem item : deviceList) {
                if (results.has(item.ip)) {
                    JSONObject obj = results.getJSONObject(item.ip);
                    item.pingStatus = obj.getString("status");
                    item.isPort102Open = obj.getBoolean("port102");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void setupFilters() {
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!isUpdatingSpinners) applyFilters();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };

        spinnerGi.setOnItemSelectedListener(listener);
        spinnerBay.setOnItemSelectedListener(listener);
        spinnerDevice.setOnItemSelectedListener(listener);
    }

    private void applyFilters() {
        String giSelRaw = spinnerGi.getSelectedItem() != null ? spinnerGi.getSelectedItem().toString() : "";
        String baySelRaw = spinnerBay.getSelectedItem() != null ? spinnerBay.getSelectedItem().toString() : "";
        String devSelRaw = spinnerDevice.getSelectedItem() != null ? spinnerDevice.getSelectedItem().toString() : "";

        String giSel = cleanSpinnerLabel(giSelRaw);
        String baySel = cleanSpinnerLabel(baySelRaw);
        String devSel = cleanSpinnerLabel(devSelRaw);

        filteredList.clear();
        for (DeviceItem item : deviceList) {
            boolean matchGi = isAllSelection(giSel, R.string.lbl_dev_filter_all_gi) || item.gi.equals(giSel);
            boolean matchBay = isAllSelection(baySel, R.string.lbl_dev_filter_all_bay) || item.bay.equals(baySel);
            boolean matchDev = isAllSelection(devSel, R.string.lbl_dev_filter_all_device) || item.device.equals(devSel);

            if (matchGi && matchBay && matchDev) {
                filteredList.add(item);
            }
        }
        
        if (adapter != null) adapter.notifyDataSetChanged();
        updateFilterSpinners(giSel, baySel, devSel);
        refreshStats();
        if (layoutExport != null) layoutExport.setVisibility(deviceList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean isAllSelection(String selection, int resourceId) {
        if (selection == null || selection.isEmpty()) return true;
        // Check current localized string and English fallback
        String localizedAll = getString(resourceId);
        if (selection.equalsIgnoreCase(localizedAll)) return true;
        
        // Hardcoded fallbacks to be extremely safe during language transition
        if (resourceId == R.string.lbl_dev_filter_all_gi) return selection.equalsIgnoreCase("All GI") || selection.equalsIgnoreCase("Semua GI");
        if (resourceId == R.string.lbl_dev_filter_all_bay) return selection.equalsIgnoreCase("All BAY") || selection.equalsIgnoreCase("Semua BAY");
        if (resourceId == R.string.lbl_dev_filter_all_device) return selection.equalsIgnoreCase("All Device") || selection.equalsIgnoreCase("Semua Device");
        
        return false;
    }

    private String cleanSpinnerLabel(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int lastParen = raw.lastIndexOf(" (");
        if (lastParen != -1) return raw.substring(0, lastParen);
        return raw;
    }

    private void updateFilterSpinners(String currentGi, String currentBay, String currentDev) {
        isUpdatingSpinners = true;

        Map<String, Integer> giMap = new HashMap<>();
        Map<String, Integer> bayMap = new HashMap<>();
        Map<String, Integer> devMap = new HashMap<>();

        boolean allGi = isAllSelection(currentGi, R.string.lbl_dev_filter_all_gi);
        boolean allBay = isAllSelection(currentBay, R.string.lbl_dev_filter_all_bay);
        boolean allDev = isAllSelection(currentDev, R.string.lbl_dev_filter_all_device);

        for (DeviceItem item : deviceList) {
            // GI count depends on current Bay and Dev selection
            if ((allBay || item.bay.equals(currentBay)) && (allDev || item.device.equals(currentDev))) {
                giMap.put(item.gi, giMap.getOrDefault(item.gi, 0) + 1);
            }

            // Bay count depends on current GI and Dev selection
            if ((allGi || item.gi.equals(currentGi)) && (allDev || item.device.equals(currentDev))) {
                bayMap.put(item.bay, bayMap.getOrDefault(item.bay, 0) + 1);
            }

            // Dev count depends on current GI and Bay selection
            if ((allGi || item.gi.equals(currentGi)) && (allBay || item.bay.equals(currentBay))) {
                devMap.put(item.device, devMap.getOrDefault(item.device, 0) + 1);
            }
        }

        updateSpinnerWithMap(spinnerGi, adapterGi, listGi, getString(R.string.lbl_dev_filter_all_gi), giMap, currentGi);
        updateSpinnerWithMap(spinnerBay, adapterBay, listBay, getString(R.string.lbl_dev_filter_all_bay), bayMap, currentBay);
        updateSpinnerWithMap(spinnerDevice, adapterDev, listDev, getString(R.string.lbl_dev_filter_all_device), devMap, currentDev);

        isUpdatingSpinners = false;
    }

    private void updateSpinnerWithMap(Spinner spinner, ArrayAdapter<String> adapter, List<String> dataList, String defaultLabel, Map<String, Integer> counts, String selectedValue) {
        List<String> newOptions = new ArrayList<>();
        int totalFiltered = 0;
        for (int c : counts.values()) totalFiltered += c;
        
        newOptions.add(defaultLabel + " (" + totalFiltered + ")");
        
        List<String> sortedKeys = new ArrayList<>(counts.keySet());
        Collections.sort(sortedKeys);
        
        int selectedIndex = 0;
        for (String key : sortedKeys) {
            String label = key + " (" + counts.get(key) + ")";
            newOptions.add(label);
            if (key.equals(selectedValue)) selectedIndex = newOptions.size() - 1;
        }

        if (!newOptions.equals(dataList)) {
            dataList.clear();
            dataList.addAll(newOptions);
            adapter.notifyDataSetChanged();
        }
        
        if (spinner.getSelectedItemPosition() != selectedIndex) {
            spinner.setSelection(selectedIndex);
        }
    }

    private void refreshStats() {
        int total = filteredList.size();
        int online = 0, open = 0, closed = 0, offline = 0;

        for (DeviceItem item : filteredList) {
            if (getString(R.string.lbl_dev_status_online).equals(item.pingStatus)) {
                online++;
                if (item.isPort102Open) open++;
                else closed++;
            } else if (getString(R.string.lbl_dev_status_offline).equals(item.pingStatus)) {
                offline++;
            }
        }

        tvStatTotal.setText(getString(R.string.stat_dev_total, total));
        tvStatOnline.setText(getString(R.string.stat_dev_online, online));
        tvStatOpen.setText(getString(R.string.stat_dev_open, open));
        tvStatClosed.setText(getString(R.string.stat_dev_closed, closed));
        tvStatOffline.setText(getString(R.string.stat_dev_offline, offline));
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void exportToCsv() {
        if (deviceList.isEmpty()) {
            Toast.makeText(this, R.string.msg_dev_export_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File exportDir = new File(getExternalFilesDir(null), "Exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            
            File file = new File(exportDir, "device.csv");
            FileOutputStream fos = new FileOutputStream(file);
            
            // Header
            fos.write("NAMA GI,BAY,DEVICE,IP,MERK,TYPE\n".getBytes(StandardCharsets.UTF_8));
            
            for (DeviceItem item : deviceList) {
                // Wrap values in quotes to handle commas within names safely
                String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n", 
                    item.gi, item.bay, item.device, item.ip, item.merk, item.type);
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
            fos.close();
            showExportSuccessDialog(file);
            
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showExportSuccessDialog(File file) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_export_success, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnOpenFolder).setOnClickListener(v -> {
            startActivity(new Intent(this, InternalFileManagerActivity.class));
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnShare).setOnClickListener(v -> {
            shareFile(file);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_dev_share_chooser)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_all_share_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void showImportInfoDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_import_info, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnContinueImport).setOnClickListener(v -> {
            dialog.dismiss();
            pickCsv();
        });

        dialogView.findViewById(R.id.btnCancelImport).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnDownloadTemplate).setOnClickListener(v -> {
            downloadCsvTemplate();
        });

        dialog.show();
    }

    private void downloadCsvTemplate() {
        try {
            File file = new File(getExternalFilesDir(null), "template_devices.csv");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write("NAMA GI,BAY,DEVICE,IP,MERK,TYPE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.write("GI CONTOH 1,BAY TRAFO 1,RELAY DISTANCE,192.168.1.100,SIEMENS,7SA\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.write("GI CONTOH 2,BAY LINE 1,RELAY DIFF,192.168.1.200,ABB,RED670\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_dev_share_template)));
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void pickCsv() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimetypes = {"text/comma-separated-values", "text/csv"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        startActivityForResult(intent, REQUEST_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CSV && resultCode == Activity.RESULT_OK && data != null) {
            importCsv(data.getData());
        }
    }

    private void importCsv(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            int count = 0;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; if (line.contains("NAMA GI")) continue; }
                String[] p = line.split(",");
                if (p.length >= 4) {
                    String ip = p[3].replace("\"", "").trim();
                    if (ip.isEmpty()) continue;

                    DeviceItem existing = null;
                    for (DeviceItem di : deviceList) {
                        if (di.ip.equalsIgnoreCase(ip)) {
                            existing = di;
                            break;
                        }
                    }

                    DeviceItem item = (existing != null) ? existing : new DeviceItem();
                    item.gi = p[0].replace("\"", "").trim();
                    item.bay = (p.length > 1) ? p[1].replace("\"", "").trim() : "";
                    item.device = (p.length > 2) ? p[2].replace("\"", "").trim() : "";
                    item.ip = ip;
                    item.merk = (p.length > 4) ? p[4].replace("\"", "").trim() : "";
                    item.type = (p.length > 5) ? p[5].replace("\"", "").trim() : "";

                    if (existing == null) {
                        deviceList.add(item);
                    }
                    count++;
                }
            }
            reader.close();
            if (count > 0) {
                saveDevices();
                applyFilters();
                layoutExport.setVisibility(deviceList.isEmpty() ? View.GONE : View.VISIBLE);
                Toast.makeText(this, getString(R.string.msg_dev_import_ok, count), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_import_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void loadDevices() {
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String json = pref.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            deviceList.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                DeviceItem item = new DeviceItem();
                item.gi = obj.optString("gi", "").replace("\"", "").trim();
                item.bay = obj.optString("bay", "").replace("\"", "").trim();
                item.device = obj.optString("device", "").replace("\"", "").trim();
                item.ip = obj.optString("ip", "").replace("\"", "").trim();
                item.merk = obj.optString("merk", "").replace("\"", "").trim();
                item.type = obj.optString("type", "").replace("\"", "").trim();
                deviceList.add(item);
            }
            Collections.sort(deviceList, (a, b) -> a.gi.compareToIgnoreCase(b.gi));
            layoutExport.setVisibility(deviceList.isEmpty() ? View.GONE : View.VISIBLE);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveDevices() {
        JSONArray arr = new JSONArray();
        try {
            for (DeviceItem item : deviceList) {
                JSONObject obj = new JSONObject();
                obj.put("gi", item.gi);
                obj.put("bay", item.bay);
                obj.put("device", item.device);
                obj.put("ip", item.ip);
                obj.put("merk", item.merk);
                obj.put("type", item.type);
                arr.put(obj);
            }
            getSharedPreferences("dfr_prefs", MODE_PRIVATE).edit()
                    .putString("device_list", arr.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void onDeviceSelected(DeviceItem item) {
        if (getIntent().getBooleanExtra("is_pick_mode", false)) {
            Intent result = new Intent();
            result.putExtra("ip", item.ip);
            result.putExtra("gi", item.gi);
            result.putExtra("bay", item.bay);
            result.putExtra("device", item.device);
            setResult(Activity.RESULT_OK, result);
            finish();
        } else {
            showActionDialog(item);
        }
    }

    private void showActionDialog(DeviceItem item) {
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

        dialogView.findViewById(R.id.btnActionEdit).setOnClickListener(v -> {
            dialog.dismiss();
            showEditDeviceDialog(item);
        });

        dialogView.findViewById(R.id.btnActionDelete).setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteDevice(item);
        });

        dialogView.findViewById(R.id.btnActionCancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showEditDeviceDialog(DeviceItem item) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_device, null);
        android.widget.EditText etGi = dialogView.findViewById(R.id.etDialogGi);
        android.widget.EditText etBay = dialogView.findViewById(R.id.etDialogBay);
        android.widget.EditText etDevice = dialogView.findViewById(R.id.etDialogDevice);
        android.widget.EditText etIp = dialogView.findViewById(R.id.etDialogIp);
        android.widget.EditText etMerk = dialogView.findViewById(R.id.etDialogMerk);
        android.widget.EditText etType = dialogView.findViewById(R.id.etDialogType);

        etGi.setText(item.gi);
        etBay.setText(item.bay);
        etDevice.setText(item.device);
        etIp.setText(item.ip);
        etMerk.setText(item.merk);
        etType.setText(item.type);

        new AlertDialog.Builder(this, R.style.Theme_DFRtools)
                .setTitle(R.string.ttl_dev_update)
                .setView(dialogView)
                .setPositiveButton(R.string.btn_all_update, (dialog, which) -> {
                    String gi = etGi.getText().toString().trim();
                    String bay = etBay.getText().toString().trim();
                    String device = etDevice.getText().toString().trim();
                    String ip = etIp.getText().toString().trim();
                    String merk = etMerk.getText().toString().trim();
                    String type = etType.getText().toString().trim();

                    if (gi.isEmpty() || bay.isEmpty() || device.isEmpty() || ip.isEmpty()) {
                        Toast.makeText(this, R.string.msg_all_fields_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    item.gi = gi;
                    item.bay = bay;
                    item.device = device;
                    item.ip = ip;
                    item.merk = merk;
                    item.type = type;

                    saveDevices();
                    applyFilters();
                    Toast.makeText(this, R.string.msg_dl_update_device_ok, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    private void confirmDeleteDevice(DeviceItem item) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage)).setText(getString(R.string.msg_file_delete_file_body, item.device));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            deviceList.remove(item);
            saveDevices();
            applyFilters();
            dialog.dismiss();
        });

        dialog.show();
    }

    public static class DeviceItem {
        public String gi = "", bay = "", device = "", ip = "", merk = "", type = "";
        public String pingStatus = null; 
        public boolean isPort102Open = false;
    }

    private static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {
        private final List<DeviceItem> items;
        private final OnItemSelected listener;
        private final int[] brandColors = {
            0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50,
            0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFF795548, 0xFF9E9E9E,
            0xFF607D8B, 0xFFE91E63, 0xFFF44336, 0xFF311B92
        };

        public DeviceAdapter(List<DeviceItem> items, OnItemSelected listener) {
            this.items = items;
            this.listener = listener;
        }

        interface OnItemSelected {
            void onSelected(DeviceItem item);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DeviceItem item = items.get(position);
            holder.tvGi.setText(item.gi);
            holder.tvBay.setText(item.bay);
            
            // Format: [DEVICE] | [MERK] | [TYPE]
            // Only [MERK] gets colored
            int brandColor = brandColors[Math.abs(item.merk.hashCode()) % brandColors.length];
            SpannableStringBuilder deviceBuilder = new SpannableStringBuilder();
            deviceBuilder.append(item.device).append(" | ");
            int brandStart = deviceBuilder.length();
            deviceBuilder.append(item.merk);
            deviceBuilder.setSpan(new ForegroundColorSpan(brandColor), brandStart, deviceBuilder.length(), 0);
            deviceBuilder.append(" | ").append(item.type);
            
            holder.tvDevice.setText(deviceBuilder);
            holder.tvIp.setText(item.ip);

            if (item.pingStatus != null) {
                holder.layoutStatus.setVisibility(View.VISIBLE);
                
                int colorStatus, colorPort;
                if (item.pingStatus.equals("ONLINE")) {
                    colorStatus = ContextCompat.getColor(holder.itemView.getContext(), R.color.status_safe);
                    colorPort = item.isPort102Open ? colorStatus : ContextCompat.getColor(holder.itemView.getContext(), R.color.status_danger);
                } else if (item.pingStatus.equals("OFFLINE")) {
                    colorStatus = ContextCompat.getColor(holder.itemView.getContext(), R.color.status_danger);
                    colorPort = colorStatus;
                } else {
                    colorStatus = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary);
                    colorPort = colorStatus;
                }

                holder.ivStatus.setColorFilter(colorStatus);
                holder.tvIec.setTextColor(colorPort);
            } else {
                holder.layoutStatus.setVisibility(View.GONE);
            }

            // GI based accent color (Consistent for same GI)
            int giColor = brandColors[Math.abs(item.gi.hashCode()) % brandColors.length];
            holder.viewAccent.setBackgroundColor(giColor);

            holder.itemView.setOnClickListener(v -> listener.onSelected(item));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvGi, tvBay, tvDevice, tvIp, tvIec;
            ImageView ivStatus;
            View viewAccent, layoutStatus;
            VH(View v) {
                super(v);
                tvGi = v.findViewById(R.id.tvGiName);
                tvBay = v.findViewById(R.id.tvBayName);
                tvDevice = v.findViewById(R.id.tvDeviceName);
                tvIp = v.findViewById(R.id.tvIp);
                ivStatus = v.findViewById(R.id.ivPingStatus);
                tvIec = v.findViewById(R.id.tvIecStatus);
                layoutStatus = v.findViewById(R.id.layoutStatusContainer);
                viewAccent = v.findViewById(R.id.viewGiAccent);
            }
        }
    }
}
