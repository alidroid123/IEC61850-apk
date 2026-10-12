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
    private TextView tvStatTotal, tvStatOnline, tvStatOpen, tvStatClosed, tvStatOffline;
    private View btnExportCsv, btnPingAll, layoutExport;
    private final List<DeviceItem> deviceList = new ArrayList<>();
    private final List<DeviceItem> filteredList = new ArrayList<>();

    private interface DeviceField {
        String of(DeviceItem d);
    }

    /** One filter spinner: values.get(i) is what option i stands for (null = the "All" option). */
    private static class Filter {
        final Spinner spinner;
        final int allLabel;
        final DeviceField field;
        final List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        ArrayAdapter<String> adapter;

        Filter(Spinner spinner, int allLabel, DeviceField field) {
            this.spinner = spinner;
            this.allLabel = allLabel;
            this.field = field;
        }

        @Nullable
        String selected() {
            int p = spinner.getSelectedItemPosition();
            return p > 0 && p < values.size() ? values.get(p) : null;
        }
    }

    /** In screen order: UPT, ULTG, GI, BAY, DEVICE. */
    private Filter[] filters;

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
        android.widget.EditText etUpt = dialogView.findViewById(R.id.etDialogUpt);
        android.widget.EditText etUltg = dialogView.findViewById(R.id.etDialogUltg);
        android.widget.EditText etGi = dialogView.findViewById(R.id.etDialogGi);
        android.widget.EditText etBay = dialogView.findViewById(R.id.etDialogBay);
        android.widget.EditText etDevice = dialogView.findViewById(R.id.etDialogDevice);
        android.widget.EditText etMerk = dialogView.findViewById(R.id.etDialogMerk);
        android.widget.EditText etType = dialogView.findViewById(R.id.etDialogType);

        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_dev_save)
                .setView(dialogView)
                .setPositiveButton(R.string.btn_all_save_small, (dialog, which) -> {
                    DeviceItem item = new DeviceItem();
                    item.upt = etUpt.getText().toString().trim();
                    item.ultg = etUltg.getText().toString().trim();
                    item.gi = etGi.getText().toString().trim();
                    item.bay = etBay.getText().toString().trim();
                    item.device = etDevice.getText().toString().trim();
                    item.ip = ip;
                    item.merk = etMerk.getText().toString().trim();
                    item.type = etType.getText().toString().trim();

                    if (item.gi.isEmpty() || item.bay.isEmpty() || item.device.isEmpty()) {
                        Toast.makeText(this, R.string.msg_all_fields_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveDeviceToList(item);
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    /** Manual "add one device" entry point from the header + icon - unlike showAddDeviceDialog(),
     *  the IP isn't known ahead of time (that one only opens pre-filled from an "ip_prefill"
     *  intent extra), so this form also collects it via the same 4-octet input used elsewhere. */
    private void showAddSingleDeviceDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_device, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        android.widget.EditText etUpt = dialogView.findViewById(R.id.etAddUpt);
        android.widget.EditText etUltg = dialogView.findViewById(R.id.etAddUltg);
        android.widget.EditText etGi = dialogView.findViewById(R.id.etAddGi);
        android.widget.EditText etBay = dialogView.findViewById(R.id.etAddBay);
        android.widget.EditText etDevice = dialogView.findViewById(R.id.etAddDevice);
        android.widget.EditText etMerk = dialogView.findViewById(R.id.etAddMerk);
        android.widget.EditText etType = dialogView.findViewById(R.id.etAddType);
        View ipLayout = dialogView.findViewById(R.id.layoutAddDeviceIp);
        android.widget.EditText etIp1 = ipLayout.findViewById(R.id.etIp1);
        android.widget.EditText etIp2 = ipLayout.findViewById(R.id.etIp2);
        android.widget.EditText etIp3 = ipLayout.findViewById(R.id.etIp3);
        android.widget.EditText etIp4 = ipLayout.findViewById(R.id.etIp4);
        com.alidev.dfrtools.utils.IpAddressHelper.setupIpInputs(etIp1, etIp2, etIp3, etIp4);

        dialogView.findViewById(R.id.btnAddDeviceCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnAddDeviceSave).setOnClickListener(v -> {
            DeviceItem item = new DeviceItem();
            item.upt = etUpt.getText().toString().trim();
            item.ultg = etUltg.getText().toString().trim();
            item.gi = etGi.getText().toString().trim();
            item.bay = etBay.getText().toString().trim();
            item.device = etDevice.getText().toString().trim();
            item.ip = com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
            item.merk = etMerk.getText().toString().trim();
            item.type = etType.getText().toString().trim();

            if (item.gi.isEmpty() || item.bay.isEmpty() || item.device.isEmpty() || item.ip.isEmpty() || item.ip.equals("0.0.0.0")) {
                Toast.makeText(this, R.string.msg_all_fields_required, Toast.LENGTH_SHORT).show();
                return;
            }
            saveDeviceToList(item);
            dialog.dismiss();
        });

        dialog.show();
    }

    @Nullable
    private DeviceItem findByIp(String ip) {
        for (DeviceItem d : deviceList) if (d.ip.equalsIgnoreCase(ip)) return d;
        return null;
    }

    /** Adds `item`; when its IP is already in the list, asks before overwriting that device. */
    private void saveDeviceToList(DeviceItem item) {
        DeviceItem existing = findByIp(item.ip);
        if (existing == null) {
            deviceList.add(item);
            afterDeviceListChanged();
            Toast.makeText(this, R.string.msg_dl_save_device_ok, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_dev_ip_exists)
                .setMessage(getString(R.string.msg_dev_ip_exists, item.ip, existing.label(), item.label()))
                .setPositiveButton(R.string.btn_dev_overwrite, (d, w) -> {
                    for (int f = 0; f < DeviceCsv.HEADER.length; f++) existing.set(f, item.get(f));
                    afterDeviceListChanged();
                    Toast.makeText(this, R.string.msg_dl_update_device_ok, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    private void afterDeviceListChanged() {
        sortDevices();
        saveDevices();
        applyFilters();
    }

    private void initViews() {
        rvDevices = findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(this));

        filters = new Filter[]{
                new Filter(findViewById(R.id.spinnerUpt), R.string.lbl_dev_filter_all_upt, d -> d.upt),
                new Filter(findViewById(R.id.spinnerUltg), R.string.lbl_dev_filter_all_ultg, d -> d.ultg),
                new Filter(findViewById(R.id.spinnerGi), R.string.lbl_dev_filter_all_gi, d -> d.gi),
                new Filter(findViewById(R.id.spinnerBay), R.string.lbl_dev_filter_all_bay, d -> d.bay),
                new Filter(findViewById(R.id.spinnerDevice), R.string.lbl_dev_filter_all_device, d -> d.device),
        };

        tvStatTotal = findViewById(R.id.tvStatTotal);
        tvStatOnline = findViewById(R.id.tvStatOnline);
        tvStatOpen = findViewById(R.id.tvStatOpen);
        tvStatClosed = findViewById(R.id.tvStatClosed);
        tvStatOffline = findViewById(R.id.tvStatOffline);

        findViewById(R.id.btnAddDevice).setOnClickListener(v -> showAddSingleDeviceDialog());
        findViewById(R.id.btnImportCsv).setOnClickListener(v -> showImportInfoDialog());
        btnExportCsv = findViewById(R.id.btnExportCsv);
        layoutExport = findViewById(R.id.layoutExport);
        btnExportCsv.setOnClickListener(v -> exportToCsv());
        btnPingAll = findViewById(R.id.btnPingAll);
        btnPingAll.setOnClickListener(v -> pingAll());

        for (Filter f : filters) {
            f.adapter = createSpinnerAdapter(f.labels);
            f.spinner.setAdapter(f.adapter);
        }
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

        for (Filter f : filters) f.spinner.setOnItemSelectedListener(listener);
    }

    private void applyFilters() {
        String[] sel = new String[filters.length];
        for (int k = 0; k < filters.length; k++) sel[k] = filters[k].selected();

        filteredList.clear();
        for (DeviceItem item : deviceList) {
            if (matches(item, sel, -1)) filteredList.add(item);
        }

        if (adapter != null) adapter.notifyDataSetChanged();
        updateFilterSpinners(sel);
        refreshStats();
        if (layoutExport != null) layoutExport.setVisibility(deviceList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** `item` passes every filter's selection, ignoring filter `skip` (-1 = none). */
    private boolean matches(DeviceItem item, String[] sel, int skip) {
        for (int k = 0; k < filters.length; k++) {
            if (k != skip && sel[k] != null && !filters[k].field.of(item).equals(sel[k])) return false;
        }
        return true;
    }

    /** Each spinner offers the values (with counts) left over by the other filters' selections. */
    private void updateFilterSpinners(String[] sel) {
        isUpdatingSpinners = true;
        for (int k = 0; k < filters.length; k++) {
            Map<String, Integer> counts = new HashMap<>();
            for (DeviceItem item : deviceList) {
                if (!matches(item, sel, k)) continue;
                String v = filters[k].field.of(item);
                counts.put(v, counts.getOrDefault(v, 0) + 1);
            }
            updateSpinner(filters[k], counts, sel[k]);
        }
        isUpdatingSpinners = false;
    }

    private void updateSpinner(Filter f, Map<String, Integer> counts, @Nullable String selected) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        int total = 0;
        for (int c : counts.values()) total += c;
        labels.add(getString(f.allLabel) + " (" + total + ")");
        values.add(null);

        List<String> keys = new ArrayList<>(counts.keySet());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        int selectedIndex = 0;
        for (String key : keys) {
            // Devices saved before UPT/ULTG existed have them blank - still filterable as such.
            labels.add((key.isEmpty() ? getString(R.string.lbl_dev_filter_blank) : key) + " (" + counts.get(key) + ")");
            values.add(key);
            if (key.equals(selected)) selectedIndex = values.size() - 1;
        }

        f.values.clear();
        f.values.addAll(values);
        if (!labels.equals(f.labels)) {
            f.labels.clear();
            f.labels.addAll(labels);
            f.adapter.notifyDataSetChanged();
        }
        if (f.spinner.getSelectedItemPosition() != selectedIndex) {
            f.spinner.setSelection(selectedIndex);
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
            
            fos.write(DeviceCsv.headerLine().getBytes(StandardCharsets.UTF_8));
            for (DeviceItem item : deviceList) {
                String line = DeviceCsv.line(item.upt, item.ultg, item.gi, item.bay, item.device, item.ip, item.merk, item.type);
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
            fos.write(DeviceCsv.headerLine().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.write("UPT CONTOH,ULTG CONTOH,GI CONTOH 1,BAY TRAFO 1,RELAY DISTANCE,192.168.1.100,SIEMENS,7SA\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.write("UPT CONTOH,ULTG CONTOH,GI CONTOH 2,BAY LINE 1,RELAY DIFF,192.168.1.200,ABB,RED670\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /**
     * Reads the CSV, then adds the new IPs right away and asks before overwriting devices whose IP
     * is already listed with different data. Blank CSV cells (or columns an older CSV lacks, like
     * UPT/ULTG) never erase what is already stored.
     */
    private void importCsv(Uri uri) {
        List<DeviceCsv.Row> rows;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new java.io.IOException(uri.toString());
            rows = DeviceCsv.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_import_fail, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.msg_dev_import_empty, Toast.LENGTH_LONG).show();
            return;
        }

        // An IP listed twice in the file: its last line wins.
        java.util.LinkedHashMap<String, DeviceCsv.Row> byIp = new java.util.LinkedHashMap<>();
        for (DeviceCsv.Row r : rows) byIp.put(r.get(DeviceCsv.IP).toLowerCase(java.util.Locale.ROOT), r);

        List<DeviceItem> added = new ArrayList<>();
        List<DeviceItem> changedExisting = new ArrayList<>();
        List<DeviceCsv.Row> changedRows = new ArrayList<>();
        int unchanged = 0;
        for (DeviceCsv.Row r : byIp.values()) {
            DeviceItem existing = findByIp(r.get(DeviceCsv.IP));
            if (existing == null) {
                DeviceItem item = new DeviceItem();
                for (int f = 0; f < DeviceCsv.HEADER.length; f++) item.set(f, r.get(f));
                added.add(item);
            } else if (differs(existing, r)) {
                changedExisting.add(existing);
                changedRows.add(r);
            } else {
                unchanged++;
            }
        }

        if (changedExisting.isEmpty()) {
            finishImport(added, changedExisting, changedRows, false, unchanged);
            return;
        }
        int kept = unchanged;
        StringBuilder msg = new StringBuilder(getString(R.string.msg_dev_import_duplicate,
                byIp.size(), added.size(), changedExisting.size()));
        int shown = Math.min(8, changedExisting.size());
        for (int i = 0; i < shown; i++) {
            DeviceItem now = changedExisting.get(i);
            DeviceItem after = new DeviceItem();
            for (int f = 0; f < DeviceCsv.HEADER.length; f++) after.set(f, merged(now, changedRows.get(i), f));
            msg.append("\n\n• ").append(now.ip)
                    .append("\n   ").append(regionLabel(now)).append(now.label())
                    .append("\n   → ").append(regionLabel(after)).append(after.label());
        }
        if (changedExisting.size() > shown) {
            msg.append("\n\n").append(getString(R.string.msg_dev_import_more, changedExisting.size() - shown));
        }
        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_dev_import_duplicate)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.btn_dev_overwrite, (d, w) -> finishImport(added, changedExisting, changedRows, true, kept))
                .setNeutralButton(R.string.btn_dev_skip_duplicates, (d, w) -> finishImport(added, changedExisting, changedRows, false, kept))
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    /** A non-blank CSV value that differs from what is stored. */
    private static boolean differs(DeviceItem existing, DeviceCsv.Row row) {
        for (int f = 0; f < DeviceCsv.HEADER.length; f++) {
            if (f == DeviceCsv.IP) continue;
            String v = row.get(f);
            if (!v.isEmpty() && !v.equals(existing.get(f))) return true;
        }
        return false;
    }

    private static String merged(DeviceItem existing, DeviceCsv.Row row, int field) {
        String v = row.get(field);
        return v.isEmpty() || field == DeviceCsv.IP ? existing.get(field) : v;
    }

    private static String regionLabel(DeviceItem d) {
        if (d.upt.isEmpty() && d.ultg.isEmpty()) return "";
        return (d.upt.isEmpty() ? "-" : d.upt) + " / " + (d.ultg.isEmpty() ? "-" : d.ultg) + " / ";
    }

    private void finishImport(List<DeviceItem> added, List<DeviceItem> changedExisting, List<DeviceCsv.Row> changedRows,
                              boolean overwrite, int unchanged) {
        deviceList.addAll(added);
        if (overwrite) {
            for (int i = 0; i < changedExisting.size(); i++) {
                DeviceItem d = changedExisting.get(i);
                DeviceCsv.Row r = changedRows.get(i);
                for (int f = 0; f < DeviceCsv.HEADER.length; f++) d.set(f, merged(d, r, f));
            }
        }
        int overwritten = overwrite ? changedExisting.size() : 0;
        int kept = unchanged + (overwrite ? 0 : changedExisting.size());
        if (!added.isEmpty() || overwritten > 0) afterDeviceListChanged();
        Toast.makeText(this, getString(R.string.msg_dev_import_result, added.size(), overwritten, kept), Toast.LENGTH_LONG).show();
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
                item.upt = obj.optString("upt", "").replace("\"", "").trim();
                item.ultg = obj.optString("ultg", "").replace("\"", "").trim();
                item.gi = obj.optString("gi", "").replace("\"", "").trim();
                item.bay = obj.optString("bay", "").replace("\"", "").trim();
                item.device = obj.optString("device", "").replace("\"", "").trim();
                item.ip = obj.optString("ip", "").replace("\"", "").trim();
                item.merk = obj.optString("merk", "").replace("\"", "").trim();
                item.type = obj.optString("type", "").replace("\"", "").trim();
                deviceList.add(item);
            }
            sortDevices();
            layoutExport.setVisibility(deviceList.isEmpty() ? View.GONE : View.VISIBLE);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** UPT, then ULTG, then GI, then BAY (devices without UPT/ULTG keep their GI order up top). */
    private void sortDevices() {
        Collections.sort(deviceList, (a, b) -> {
            int c = a.upt.compareToIgnoreCase(b.upt);
            if (c == 0) c = a.ultg.compareToIgnoreCase(b.ultg);
            if (c == 0) c = a.gi.compareToIgnoreCase(b.gi);
            if (c == 0) c = a.bay.compareToIgnoreCase(b.bay);
            return c;
        });
    }

    private void saveDevices() {
        JSONArray arr = new JSONArray();
        try {
            for (DeviceItem item : deviceList) {
                JSONObject obj = new JSONObject();
                obj.put("upt", item.upt);
                obj.put("ultg", item.ultg);
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
        android.widget.EditText etUpt = dialogView.findViewById(R.id.etDialogUpt);
        android.widget.EditText etUltg = dialogView.findViewById(R.id.etDialogUltg);
        android.widget.EditText etGi = dialogView.findViewById(R.id.etDialogGi);
        android.widget.EditText etBay = dialogView.findViewById(R.id.etDialogBay);
        android.widget.EditText etDevice = dialogView.findViewById(R.id.etDialogDevice);
        android.widget.EditText etIp = dialogView.findViewById(R.id.etDialogIp);
        android.widget.EditText etMerk = dialogView.findViewById(R.id.etDialogMerk);
        android.widget.EditText etType = dialogView.findViewById(R.id.etDialogType);

        etUpt.setText(item.upt);
        etUltg.setText(item.ultg);
        etGi.setText(item.gi);
        etBay.setText(item.bay);
        etDevice.setText(item.device);
        etIp.setText(item.ip);
        etMerk.setText(item.merk);
        etType.setText(item.type);

        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
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

                    item.upt = etUpt.getText().toString().trim();
                    item.ultg = etUltg.getText().toString().trim();
                    item.gi = gi;
                    item.bay = bay;
                    item.device = device;
                    item.ip = ip;
                    item.merk = merk;
                    item.type = type;

                    afterDeviceListChanged();
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
        public String upt = "", ultg = "", gi = "", bay = "", device = "", ip = "", merk = "", type = "";
        public String pingStatus = null;
        public boolean isPort102Open = false;

        /** Field by DeviceCsv column index (DeviceCsv.UPT .. DeviceCsv.TYPE). */
        String get(int field) {
            switch (field) {
                case DeviceCsv.UPT: return upt;
                case DeviceCsv.ULTG: return ultg;
                case DeviceCsv.GI: return gi;
                case DeviceCsv.BAY: return bay;
                case DeviceCsv.DEVICE: return device;
                case DeviceCsv.IP: return ip;
                case DeviceCsv.MERK: return merk;
                default: return type;
            }
        }

        void set(int field, String value) {
            switch (field) {
                case DeviceCsv.UPT: upt = value; break;
                case DeviceCsv.ULTG: ultg = value; break;
                case DeviceCsv.GI: gi = value; break;
                case DeviceCsv.BAY: bay = value; break;
                case DeviceCsv.DEVICE: device = value; break;
                case DeviceCsv.IP: ip = value; break;
                case DeviceCsv.MERK: merk = value; break;
                default: type = value; break;
            }
        }

        /** "GI / BAY / DEVICE", for messages. */
        String label() {
            return gi + " / " + bay + " / " + device;
        }
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
            String region = item.upt.isEmpty() ? item.ultg
                    : item.ultg.isEmpty() ? item.upt : item.upt + "  •  " + item.ultg;
            holder.tvUptUltg.setText(region);
            holder.tvUptUltg.setVisibility(region.isEmpty() ? View.GONE : View.VISIBLE);
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
            TextView tvUptUltg, tvGi, tvBay, tvDevice, tvIp, tvIec;
            ImageView ivStatus;
            View viewAccent, layoutStatus;
            VH(View v) {
                super(v);
                tvUptUltg = v.findViewById(R.id.tvUptUltg);
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
