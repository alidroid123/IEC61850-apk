package com.alidev.dfrtools.dfr;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.alidev.dfrtools.R;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class IEDMonitoringActivity extends BaseActivity {

    private RecyclerView rvMonitoring;
    private MonitoringAdapter adapter;
    private MonitoringManager manager;
    private final Map<String, Iec61850DfrClient> clients = new HashMap<>();
    // IPs confirmed connected as of the last poll; used to detect connected->lost transitions so
    // we can pop a one-time notice instead of silently dropping data or (previously) crashing.
    private final Set<String> connectedIps = java.util.Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProgressBar topProgressBar;
    private ImageButton btnRefresh;
    private EditText etSearch;
    private ImageButton btnClearSearch;
    private SwipeRefreshLayout swipeRefresh;
    private View layoutEmptyState;
    private SwitchCompat swIntranetCheck;
    private SwitchCompat swAutoRefresh;
    private Spinner spinnerGiFilter;
    private FrameLayout stickyHeaderContainer;
    private HeaderVH stickyHolder;
    private View layoutBulkProgress;
    private TextView tvBulkProgress;

    private static final int REQUEST_CSV = 2001;
    // Pull-to-refresh against a big node list hammers every device at once - confirm first past this size.
    private static final int REFRESH_CONFIRM_THRESHOLD = 100;
    private String pendingImportIp, pendingImportDeviceName;

    /** Self-rescheduling tick for the opt-in auto-refresh toggle; interval comes from Settings. */
    private final Runnable autoRefreshRunnable = () -> {
        manualRefresh();
        scheduleNextAutoRefresh();
    };

    private void scheduleNextAutoRefresh() {
        int intervalMs = com.alidev.dfrtools.utils.ConfigHelper.getMonUpdateIntervalSeconds(this) * 1000;
        mainHandler.postDelayed(autoRefreshRunnable, intervalMs);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ied_monitoring);

        manager = new MonitoringManager(this);
        rvMonitoring = findViewById(R.id.rvMonitoring);
        topProgressBar = findViewById(R.id.topProgressBar);
        btnRefresh = findViewById(R.id.btnRefresh);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        swIntranetCheck = findViewById(R.id.swIntranetCheck);
        swAutoRefresh = findViewById(R.id.swAutoRefresh);
        spinnerGiFilter = findViewById(R.id.spinnerGiFilter);
        stickyHeaderContainer = findViewById(R.id.stickyHeaderContainer);
        layoutBulkProgress = findViewById(R.id.layoutBulkProgress);
        tvBulkProgress = findViewById(R.id.tvBulkProgress);
        spinnerGiFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object item = parent.getItemAtPosition(position);
                if (item instanceof GiFilterOption) adapter.filterByGi(((GiFilterOption) item).key);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> checkIntranetAndExecute(this::manualRefresh));
        findViewById(R.id.btnAddTemplate).setOnClickListener(v -> showApplyTemplateDialog());
        swAutoRefresh.setOnCheckedChangeListener((btn, checked) -> {
            mainHandler.removeCallbacks(autoRefreshRunnable);
            if (checked) {
                checkIntranetAndExecute(() -> {
                    manualRefresh();
                    scheduleNextAutoRefresh();
                }, () -> swAutoRefresh.setChecked(false));
            }
        });
        // Swipe-to-refresh needs its spinner stopped on a failed check too, or it spins forever.
        swipeRefresh.setOnRefreshListener(() -> {
            int nodeCount = adapter.getNodes().size();
            if (nodeCount > REFRESH_CONFIRM_THRESHOLD) {
                swipeRefresh.setRefreshing(false); // don't spin while the confirmation is up
                confirmRefreshMany(nodeCount);
            } else {
                checkIntranetAndExecute(this::manualRefresh, () -> swipeRefresh.setRefreshing(false));
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnClearSearch.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));

        rvMonitoring.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MonitoringAdapter();
        rvMonitoring.setAdapter(adapter);
        rvMonitoring.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateStickyHeader();
            }
        });
        // Toggling a group, refreshing values, filtering, etc. all change the list without the
        // user's finger moving a pixel - onScrolled() alone would leave the pinned header stale
        // (or still shown/hidden wrongly) until the next manual scroll, so re-evaluate on every
        // adapter change too. Posted so it runs after RecyclerView's layout pass for this update.
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { rvMonitoring.post(() -> updateStickyHeader()); }
            @Override public void onItemRangeChanged(int p, int c) { onChanged(); }
            @Override public void onItemRangeChanged(int p, int c, Object payload) { onChanged(); }
            @Override public void onItemRangeInserted(int p, int c) { onChanged(); }
            @Override public void onItemRangeRemoved(int p, int c) { onChanged(); }
            @Override public void onItemRangeMoved(int f, int t, int c) { onChanged(); }
        });

        loadNodes();
    }

    /**
     * Loads persisted nodes and displays their last-known value/update-time as-is - no connect,
     * no refresh. Refreshing (global or per-group) is entirely manual, triggered by the user.
     */
    private void loadNodes() {
        List<MonitoredNode> nodes = manager.getNodes();
        adapter.setNodes(nodes);
        layoutEmptyState.setVisibility(nodes.isEmpty() ? View.VISIBLE : View.GONE);
        reconcileClients(nodes);
        updateGiFilterOptions(nodes);
    }

    /**
     * Rebuilds the GI filter spinner from whichever devices actually have monitored points right
     * now (not the full Device Database - a device with zero monitored points has no reason to
     * show up here). Only visible once there's at least one group to filter.
     */
    private void updateGiFilterOptions(List<MonitoredNode> nodes) {
        if (nodes.isEmpty()) {
            spinnerGiFilter.setVisibility(View.GONE);
            return;
        }

        Set<String> seenIps = new HashSet<>();
        Map<String, Integer> giCounts = new HashMap<>();
        for (MonitoredNode n : nodes) {
            if (!seenIps.add(n.ipAddress)) continue; // count each device group once
            MonitoringManager.DeviceHeaderData d = getDeviceHeaderData(n.ipAddress);
            String gi = (d != null && !d.gi.isEmpty()) ? d.gi : getString(R.string.lbl_mon_gi_unknown);
            giCounts.merge(gi, 1, Integer::sum);
        }

        List<String> sortedGis = new ArrayList<>(giCounts.keySet());
        Collections.sort(sortedGis, String.CASE_INSENSITIVE_ORDER);

        List<GiFilterOption> options = new ArrayList<>();
        options.add(new GiFilterOption(null, getString(R.string.lbl_mon_gi_filter_all), seenIps.size()));
        for (String gi : sortedGis) options.add(new GiFilterOption(gi, gi, giCounts.get(gi)));

        spinnerGiFilter.setAdapter(new GiFilterAdapter(options));
        spinnerGiFilter.setVisibility(View.VISIBLE);
    }

    /** Disconnects clients for devices no longer monitored (avoids leaked polling connections). Never auto-connects new ones. */
    private void reconcileClients(List<MonitoredNode> nodes) {
        Set<String> activeIps = new HashSet<>();
        for (MonitoredNode node : nodes) activeIps.add(node.ipAddress);
        Iterator<Map.Entry<String, Iec61850DfrClient>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Iec61850DfrClient> entry = it.next();
            String staleIp = entry.getKey();
            if (!activeIps.contains(staleIp)) {
                Iec61850DfrClient stale = entry.getValue();
                it.remove();
                executor.execute(stale::disconnect);
            }
        }
    }

    private void connectClient(Iec61850DfrClient client, String ip) {
        executor.execute(() -> {
            int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
            int port = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
            client.connect(ip, port, timeout);
        });
    }

    private void checkIntranetAndExecute(Runnable onSuccess) {
        checkIntranetAndExecute(onSuccess, null);
    }

    private void checkIntranetAndExecute(Runnable onSuccess, Runnable onFailure) {
        if (!swIntranetCheck.isChecked()) {
            onSuccess.run();
            return;
        }
        Toast.makeText(this, R.string.msg_dev_ping_precheck, Toast.LENGTH_SHORT).show();
        String intranetIp = com.alidev.dfrtools.utils.ConfigHelper.getIntranetIp(this);
        executor.execute(() -> {
            boolean intranetOk = false;
            try {
                Process process = Runtime.getRuntime().exec("ping -c 1 -W 2 " + intranetIp);
                intranetOk = (process.waitFor() == 0);
            } catch (Exception e) {
                e.printStackTrace();
            }
            final boolean finalIntranetOk = intranetOk;
            mainHandler.post(() -> {
                if (finalIntranetOk) {
                    onSuccess.run();
                } else {
                    showVpnPrompt();
                    if (onFailure != null) onFailure.run();
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

    /**
     * Kicks off a full "refresh every monitored point" pass via MonitoringRefreshService instead
     * of reading nodes in-line here, so the pass survives the user leaving this screen (backing
     * out, switching apps, screen off). Progress/completion land through bulkListener while this
     * Activity is visible (see onResume/onPause), and through the service's own notification the
     * rest of the time.
     */
    private void manualRefresh() {
        if (MonitoringRefreshService.isRunning()) {
            Toast.makeText(this, R.string.msg_mon_bulk_already_running, Toast.LENGTH_SHORT).show();
            swipeRefresh.setRefreshing(false);
            return;
        }
        requestNotificationPermissionIfNeeded();
        swipeRefresh.setRefreshing(true);
        topProgressBar.setVisibility(View.VISIBLE);
        layoutBulkProgress.setVisibility(View.VISIBLE);
        tvBulkProgress.setText(R.string.msg_mon_bulk_notif_starting);
        MonitoringRefreshService.start(this);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9001);
        }
    }

    /** Mirrors MonitoringRefreshService's progress/completion while this Activity is visible - see onResume()/onPause(). */
    private final MonitoringRefreshService.ProgressListener bulkListener = new MonitoringRefreshService.ProgressListener() {
        @Override
        public void onProgress(String groupTitle, int doneInGroup, int totalInGroup, int doneOverall, int totalOverall) {
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                tvBulkProgress.setText(getString(R.string.msg_mon_bulk_progress, groupTitle, doneInGroup, totalInGroup, doneOverall, totalOverall));
            });
        }

        @Override
        public void onComplete(int successCount, int failCount) {
            mainHandler.post(() -> {
                swipeRefresh.setRefreshing(false);
                topProgressBar.setVisibility(View.GONE);
                layoutBulkProgress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                loadNodes(); // reflect the values/timestamps the service just saved
                showBulkRefreshResultDialog(successCount, failCount);
            });
        }
    };

    private void showBulkRefreshResultDialog(int successCount, int failCount) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_bulk_refresh_result, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        String message = failCount == 0
                ? getString(R.string.msg_mon_bulk_result_all_ok, successCount)
                : getString(R.string.msg_mon_bulk_result, successCount, failCount);
        ((TextView) dialogView.findViewById(R.id.tvBulkResultMessage)).setText(message);

        ImageView icon = dialogView.findViewById(R.id.imgBulkResultIcon);
        if (failCount > 0) {
            icon.setImageResource(R.drawable.ic_warning);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_danger)));
            icon.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x20F85149));
        }

        dialogView.findViewById(R.id.btnBulkResultClose).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** Refreshes only the points belonging to one device group, connecting it first if needed. */
    private void manualRefreshGroup(String ip) {
        ensureConnecting(ip);
        refreshValues(ip);
    }

    private void ensureConnecting(String ip) {
        Iec61850DfrClient client = clients.get(ip);
        if (client == null) {
            client = new Iec61850DfrClient();
            clients.put(ip, client);
            connectClient(client, ip);
        } else if (!client.isConnected()) {
            connectClient(client, ip);
        }
    }

    /**
     * Reads the current value of every node matching ipFilter (or every node, when null). The
     * unfiltered full node list is what's handed to adapter.setNodes() afterwards so a per-group
     * refresh never drops the other groups from the adapter - the read nodes are the same object
     * instances found in that full list, so their mutated fields are already reflected in it.
     */
    private void refreshValues(String ipFilter) {
        List<MonitoredNode> currentNodes = adapter.getNodes();
        List<MonitoredNode> nodesToRead = currentNodes;
        if (ipFilter != null) {
            nodesToRead = new ArrayList<>();
            for (MonitoredNode n : currentNodes) if (n.ipAddress.equals(ipFilter)) nodesToRead.add(n);
        }
        if (nodesToRead.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            return;
        }

        topProgressBar.setVisibility(View.VISIBLE);
        final List<MonitoredNode> finalNodesToRead = nodesToRead;
        executor.execute(() -> {
            boolean changed = false;
            long now = System.currentTimeMillis();

            for (MonitoredNode node : finalNodesToRead) {
                Iec61850DfrClient client = clients.get(node.ipAddress);
                boolean connected = client != null && client.isConnected();
                if (connected) {
                    connectedIps.add(node.ipAddress);
                    Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(node.fullPath, node.cachedFc);
                    if (result != null) {
                        node.cachedFc = result.fc;
                        String finalVal = processValue(node, result.value);

                        if (!finalVal.equals(node.lastValue)) {
                            node.lastValue = finalVal;
                            changed = true;
                        }
                        if ("float".equals(node.type)) {
                            try {
                                node.pushHistory(Float.parseFloat(finalVal));
                            } catch (NumberFormatException ignored) {}
                        }
                        node.lastUpdateMillis = now;
                        changed = true; // persist the new timestamp even when the value read is unchanged
                    }
                } else if (connectedIps.remove(node.ipAddress)) {
                    // Was connected as of the previous poll and just dropped (relay closed the
                    // socket, network hiccup, etc.) - surface it once instead of failing silently.
                    notifyDeviceDisconnected(node.ipAddress);
                }
            }

            if (changed) manager.saveNodes(currentNodes);

            final boolean hasChanged = changed;
            mainHandler.post(() -> {
                topProgressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                if (hasChanged) {
                    adapter.setNodes(currentNodes); // Triggers DiffUtil update
                }
                // Always refresh stale-state dots and group header timestamps, even when values didn't change
                adapter.notifyItemRangeChanged(0, adapter.getItemCount(), "STALE_CHECK");
            });
        });
    }

    private String processValue(MonitoredNode node, String raw) {
        return node.processRawValue(raw);
    }

    private void showEditDialog(MonitoredNode node) {
        View v = getLayoutInflater().inflate(R.layout.dialog_edit_monitored_node_custom, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        EditText etName = v.findViewById(R.id.etCustomName);
        EditText etNodeAddress = v.findViewById(R.id.etNodeAddress);
        TextInputLayout layoutNodeAddress = v.findViewById(R.id.layoutNodeAddress);
        layoutNodeAddress.setEndIconOnClickListener(view -> showBrowseAddressDialog(node, etNodeAddress));
        EditText etMultiplier = v.findViewById(R.id.etMultiplier);
        EditText etUnit = v.findViewById(R.id.etUnit);
        CheckBox cbInvert = v.findViewById(R.id.cbInvert);
        CheckBox cbInvertColor = v.findViewById(R.id.cbInvertColor);
        View layoutMultiplier = v.findViewById(R.id.layoutMultiplier);
        View layoutUnit = v.findViewById(R.id.layoutUnit);
        CheckBox cbAlarmEnabled = v.findViewById(R.id.cbAlarmEnabled);
        View layoutThresholds = v.findViewById(R.id.layoutThresholds);
        EditText etThresholdLow = v.findViewById(R.id.etThresholdLow);
        EditText etThresholdHigh = v.findViewById(R.id.etThresholdHigh);
        CheckBox cbAlarmOnTrue = v.findViewById(R.id.cbAlarmOnTrue);
        View layoutAlarmMatchText = v.findViewById(R.id.layoutAlarmMatchText);
        EditText etAlarmMatchText = v.findViewById(R.id.etAlarmMatchText);

        boolean isBoolean = "boolean".equals(node.type);
        boolean isString = "string".equals(node.type);
        boolean isFloat = !isBoolean && !isString;

        etName.setText(node.customName);
        etNodeAddress.setText(node.fullPath);
        // Addresses are long (full IEC 61850 path) and this field shows only one line, so a tap
        // should jump straight to the end (the LN.DO.DA tail) instead of wherever the tap landed
        // or the start of the string.
        View.OnClickListener moveCursorToEnd = clickedView -> etNodeAddress.setSelection(etNodeAddress.getText().length());
        etNodeAddress.setOnClickListener(moveCursorToEnd);
        etNodeAddress.setOnFocusChangeListener((focusedView, hasFocus) -> {
            if (hasFocus) etNodeAddress.setSelection(etNodeAddress.getText().length());
        });
        etMultiplier.setText(String.valueOf(node.multiplier));
        etUnit.setText(node.unit);
        cbInvert.setChecked(node.invert);
        cbInvertColor.setChecked(node.invertColor);
        cbAlarmEnabled.setChecked(node.alarmEnabled);
        etThresholdLow.setText(node.thresholdLow != null ? String.valueOf(node.thresholdLow) : "");
        etThresholdHigh.setText(node.thresholdHigh != null ? String.valueOf(node.thresholdHigh) : "");
        cbAlarmOnTrue.setChecked(node.alarmOnValue);
        cbAlarmOnTrue.setText(isString ? R.string.lbl_mon_alarm_on_match : R.string.lbl_mon_alarm_on_true);
        etAlarmMatchText.setText(node.alarmMatchText);

        layoutMultiplier.setVisibility(isFloat ? View.VISIBLE : View.GONE);
        layoutUnit.setVisibility(isFloat ? View.VISIBLE : View.GONE);
        cbInvert.setVisibility(isBoolean ? View.VISIBLE : View.GONE);
        cbInvertColor.setVisibility(isBoolean ? View.VISIBLE : View.GONE);

        Runnable updateAlarmFieldsVisibility = () -> {
            boolean alarmOn = cbAlarmEnabled.isChecked();
            layoutThresholds.setVisibility(isFloat && alarmOn ? View.VISIBLE : View.GONE);
            cbAlarmOnTrue.setVisibility((isBoolean || isString) && alarmOn ? View.VISIBLE : View.GONE);
            layoutAlarmMatchText.setVisibility(isString && alarmOn ? View.VISIBLE : View.GONE);
        };
        updateAlarmFieldsVisibility.run();
        cbAlarmEnabled.setOnCheckedChangeListener((btn, checked) -> updateAlarmFieldsVisibility.run());

        v.findViewById(R.id.btnSave).setOnClickListener(view -> {
            String newFullPath = etNodeAddress.getText().toString().trim();
            if (newFullPath.isEmpty()) {
                etNodeAddress.setError(getString(R.string.msg_all_fields_required));
                return;
            }

            String oldFullPath = node.fullPath;
            String oldIp = node.ipAddress;

            node.customName = etName.getText().toString();
            if (!newFullPath.equals(node.fullPath)) {
                node.fullPath = newFullPath;
                node.nodeName = newFullPath.contains(".") ? newFullPath.substring(newFullPath.lastIndexOf('.') + 1) : newFullPath;
                node.cachedFc = null;
                node.lastUpdateMillis = 0;
            }
            node.unit = etUnit.getText().toString();
            try {
                node.multiplier = Float.parseFloat(etMultiplier.getText().toString());
            } catch (Exception ignored) {}
            boolean newInvert = cbInvert.isChecked();
            if (isBoolean && newInvert != node.invert
                    && (node.lastValue.equalsIgnoreCase("true") || node.lastValue.equalsIgnoreCase("false"))) {
                // lastValue already has the old invert baked in from the last read, so flipping
                // invert alone won't change what's displayed until the next poll - flip it here too.
                node.lastValue = node.lastValue.equalsIgnoreCase("true") ? "false" : "true";
            }
            node.invert = newInvert;
            node.invertColor = cbInvertColor.isChecked();
            node.alarmEnabled = cbAlarmEnabled.isChecked();
            node.alarmOnValue = cbAlarmOnTrue.isChecked();
            node.alarmMatchText = etAlarmMatchText.getText().toString();
            String lowStr = etThresholdLow.getText().toString().trim();
            String highStr = etThresholdHigh.getText().toString().trim();
            try {
                node.thresholdLow = lowStr.isEmpty() ? null : Float.parseFloat(lowStr);
            } catch (Exception ignored) {
                node.thresholdLow = null;
            }
            try {
                node.thresholdHigh = highStr.isEmpty() ? null : Float.parseFloat(highStr);
            } catch (Exception ignored) {
                node.thresholdHigh = null;
            }
            manager.updateNode(oldFullPath, oldIp, node);
            adapter.notifyDataSetChanged();
            dialog.dismiss();
        });

        v.findViewById(R.id.btnRemove).setOnClickListener(view -> {
            manager.removeNode(node);
            loadNodes();
            dialog.dismiss();
        });

        v.findViewById(R.id.btnCancel).setOnClickListener(view -> dialog.dismiss());

        dialog.show();
    }

    /**
     * Lets the user browse the node's own device live (LD -> LN -> DO/SDO -> DA) and pick a
     * replacement address, instead of typing/pasting a full MMS path by hand. Only a leaf whose
     * live value classifies as the SAME type as the node being edited (float/boolean/string) can
     * be picked, since swapping in a differently-typed address would silently break processValue()/
     * isAlarming() for this node.
     */
    private void showBrowseAddressDialog(MonitoredNode node, EditText etNodeAddress) {
        showBrowseAddressDialog(node, etNodeAddress, false, null);
    }

    /**
     * @param freeType     when true, any leaf can be picked and node.type is updated to match it -
     *                     used for Bulk Edit rows that don't have an established type to protect yet
     *                     (a brand-new row, or a row whose address the user is still filling in).
     * @param spTypeToUpdate row's type spinner to refresh when freeType picks a new type; null if none.
     */
    private void showBrowseAddressDialog(MonitoredNode node, EditText etNodeAddress, boolean freeType, Spinner spTypeToUpdate) {
        View v = getLayoutInflater().inflate(R.layout.dialog_browse_address, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        TextView tvTypeHint = v.findViewById(R.id.tvBrowseTypeHint);
        tvTypeHint.setText(freeType ? getString(R.string.msg_mon_browse_type_hint_any)
                : getString(R.string.msg_mon_browse_type_hint, node.type.toUpperCase(Locale.ROOT)));

        RecyclerView rv = v.findViewById(R.id.rvBrowseNodes);
        rv.setLayoutManager(new LinearLayoutManager(this));
        ProgressBar progress = v.findViewById(R.id.progressBrowse);
        TextView tvEmpty = v.findViewById(R.id.tvBrowseEmpty);

        Iec61850DfrClient client = clients.get(node.ipAddress);
        if (client == null) {
            client = new Iec61850DfrClient();
            clients.put(node.ipAddress, client);
        }
        final Iec61850DfrClient finalClient = client;

        BrowseAdapter browseAdapter = new BrowseAdapter(finalClient, node.type, freeType, leaf -> {
            if (freeType) {
                node.type = classifyValueType(leaf.value);
                if (spTypeToUpdate != null) {
                    int idx = TYPE_OPTIONS.indexOf(node.type);
                    spTypeToUpdate.setSelection(idx >= 0 ? idx : 1);
                }
            }
            etNodeAddress.setText(leaf.fullPath);
            dialog.dismiss();
        });
        rv.setAdapter(browseAdapter);

        v.findViewById(R.id.btnCancelBrowse).setOnClickListener(view -> dialog.dismiss());

        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            if (!finalClient.isConnected()) {
                int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
                int port = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
                finalClient.connect(node.ipAddress, port, timeout);
            }

            if (!finalClient.isConnected()) {
                mainHandler.post(() -> {
                    progress.setVisibility(View.GONE);
                    tvEmpty.setText(getString(R.string.msg_mon_template_connect_fail, node.ipAddress));
                    tvEmpty.setVisibility(View.VISIBLE);
                });
                return;
            }

            List<String> lds = finalClient.getLogicalDevices();
            mainHandler.post(() -> {
                progress.setVisibility(View.GONE);
                if (lds.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    List<MmsExplorerActivity.MmsNode> roots = new ArrayList<>();
                    for (String ld : lds) roots.add(new MmsExplorerActivity.MmsNode(ld, ld, MmsExplorerActivity.NodeType.LD, 0));
                    browseAdapter.setRoots(roots);
                }
            });
        });

        dialog.show();
    }

    // Same order/default ("float" at index 1) as RelayTemplateEditActivity's row type spinner, for consistency.
    private static final List<String> TYPE_OPTIONS = java.util.Arrays.asList("string", "float", "boolean");

    private ArrayAdapter<String> createTypeSpinnerAdapter() {
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_futuristic, TYPE_OPTIONS);
        spAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_futuristic);
        return spAdapter;
    }

    /** Classifies a raw formatted MMS value the same way MonitoredNode's own type system does. */
    private String classifyValueType(String raw) {
        if (raw == null || raw.isEmpty()) return "string";
        if (raw.equalsIgnoreCase("TRUE") || raw.equalsIgnoreCase("FALSE")) return "boolean";
        try {
            Float.parseFloat(raw);
            return "float";
        } catch (NumberFormatException e) {
            return "string";
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private static final int[] UNIT_ACCENT_COLORS = {
        R.color.unit_accent_1, R.color.unit_accent_2, R.color.unit_accent_3, R.color.unit_accent_4,
        R.color.unit_accent_5, R.color.unit_accent_6, R.color.unit_accent_7, R.color.unit_accent_8,
    };

    /** Deterministic color per unit string, so every "A" pill matches every other "A" pill, distinct from "kV", "°", etc. */
    private int getUnitAccentColor(String unit) {
        int idx = Math.abs((unit == null ? "" : unit).hashCode()) % UNIT_ACCENT_COLORS.length;
        return ContextCompat.getColor(this, UNIT_ACCENT_COLORS[idx]);
    }

    /** Deterministic color per Device name, so every group header for that device always carries the same accent line. */
    private int getDeviceAccentColor(String device) {
        int idx = Math.abs((device == null ? "" : device).hashCode()) % UNIT_ACCENT_COLORS.length;
        return ContextCompat.getColor(this, UNIT_ACCENT_COLORS[idx]);
    }

    private interface OnLeafPicked { void onPicked(MmsExplorerActivity.MmsNode leaf); }

    /** Flat, expandable LD/LN/DO/DA tree for the address picker dialog - reuses MmsExplorerActivity's node model. */
    private class BrowseAdapter extends RecyclerView.Adapter<BrowseAdapter.VH> {
        private final List<MmsExplorerActivity.MmsNode> visible = new ArrayList<>();
        private final Iec61850DfrClient client;
        private final String targetType;
        private final boolean freeType;
        private final OnLeafPicked callback;

        BrowseAdapter(Iec61850DfrClient client, String targetType, boolean freeType, OnLeafPicked callback) {
            this.client = client;
            this.targetType = targetType;
            this.freeType = freeType;
            this.callback = callback;
        }

        void setRoots(List<MmsExplorerActivity.MmsNode> roots) {
            visible.clear();
            visible.addAll(roots);
            notifyDataSetChanged();
        }

        private void pickLeaf(MmsExplorerActivity.MmsNode leaf) {
            String actualType = classifyValueType(leaf.value);
            if (freeType || actualType.equals(targetType)) {
                callback.onPicked(leaf);
            } else {
                Toast.makeText(IEDMonitoringActivity.this, getString(R.string.msg_mon_browse_type_mismatch,
                        actualType.toUpperCase(Locale.ROOT), targetType.toUpperCase(Locale.ROOT)), Toast.LENGTH_LONG).show();
            }
        }

        private int countDescendants(MmsExplorerActivity.MmsNode node) {
            int count = 0;
            for (MmsExplorerActivity.MmsNode child : node.children) {
                count++;
                if (child.isExpanded) count += countDescendants(child);
            }
            return count;
        }

        private void toggle(MmsExplorerActivity.MmsNode node, int position) {
            if (position < 0) return;
            if (node.isExpanded) {
                node.isExpanded = false;
                int count = countDescendants(node);
                for (int i = 0; i < count; i++) visible.remove(position + 1);
                notifyItemRangeRemoved(position + 1, count);
                notifyItemChanged(position);
            } else if (node.isLoaded) {
                node.isExpanded = true;
                visible.addAll(position + 1, node.children);
                notifyItemRangeInserted(position + 1, node.children.size());
                notifyItemChanged(position);
            } else {
                fetchChildren(node, position);
            }
        }

        private void fetchChildren(MmsExplorerActivity.MmsNode node, int position) {
            executor.execute(() -> {
                List<MmsExplorerActivity.MmsNode> children = new ArrayList<>();
                boolean[] leaf = {false};
                String[] value = {""};
                try {
                    if (node.type == MmsExplorerActivity.NodeType.LD) {
                        List<String> lns = client.getLogicalDeviceDirectory(node.name);
                        for (String ln : lns) children.add(new MmsExplorerActivity.MmsNode(ln, node.fullPath + "/" + ln, MmsExplorerActivity.NodeType.LN, node.level + 1));
                    } else if (node.type == MmsExplorerActivity.NodeType.LN) {
                        List<String> dos = client.getLogicalNodeDirectory(node.fullPath);
                        for (String doName : dos) children.add(new MmsExplorerActivity.MmsNode(doName, node.fullPath + "." + doName, MmsExplorerActivity.NodeType.DO, node.level + 1));
                    } else {
                        List<String> subItems = client.getDataDirectory(node.fullPath);
                        if (subItems != null && !subItems.isEmpty()) {
                            for (String subName : subItems) {
                                children.add(new MmsExplorerActivity.MmsNode(subName, node.fullPath + "." + subName, MmsExplorerActivity.NodeType.DA, node.level + 1));
                            }
                        } else {
                            leaf[0] = true;
                            Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(node.fullPath, null);
                            if (result != null) value[0] = result.value;
                        }
                    }
                } catch (Exception ignored) {}

                mainHandler.post(() -> {
                    node.children = children;
                    node.isLoaded = true;
                    node.isLeaf = leaf[0];
                    node.value = value[0];
                    int pos = visible.indexOf(node);
                    if (pos == -1) return;
                    if (!node.isLeaf) {
                        node.isExpanded = true;
                        visible.addAll(pos + 1, children);
                        notifyItemRangeInserted(pos + 1, children.size());
                    }
                    notifyItemChanged(pos);
                });
            });
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_browse_node, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            MmsExplorerActivity.MmsNode node = visible.get(position);
            holder.itemView.setPaddingRelative(dpToPx(8 + node.level * 16), holder.itemView.getPaddingTop(),
                    holder.itemView.getPaddingEnd(), holder.itemView.getPaddingBottom());
            holder.txtName.setText(node.name);
            if (node.isLoaded && node.isLeaf) {
                holder.imgArrow.setVisibility(View.INVISIBLE);
                holder.txtValue.setVisibility(View.VISIBLE);
                holder.txtValue.setText(node.value);
            } else {
                holder.imgArrow.setVisibility(View.VISIBLE);
                holder.imgArrow.setRotation(node.isExpanded ? 90f : 0f);
                holder.txtValue.setVisibility(View.GONE);
            }
            holder.itemView.setOnClickListener(view -> {
                if (node.isLoaded && node.isLeaf) {
                    pickLeaf(node);
                } else {
                    toggle(node, holder.getAdapterPosition());
                }
            });
        }

        @Override
        public int getItemCount() { return visible.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView imgArrow;
            TextView txtName, txtValue;
            VH(View v) {
                super(v);
                imgArrow = v.findViewById(R.id.imgBrowseArrow);
                txtName = v.findViewById(R.id.txtBrowseName);
                txtValue = v.findViewById(R.id.txtBrowseValue);
            }
        }
    }

    private void showApplyTemplateDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_apply_relay_template, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        Spinner spinnerTemplate = v.findViewById(R.id.spinnerRelayTemplate);
        spinnerTemplate.setAdapter(createFuturisticSpinnerAdapter(RelayTemplates.getTemplateNames(this)));

        RadioGroup rgTarget = v.findViewById(R.id.rgTemplateTarget);
        View layoutTargetDevice = v.findViewById(R.id.layoutTargetDevice);
        View layoutTargetIp = v.findViewById(R.id.layoutTargetIp);
        RadioGroup rgApplyMode = v.findViewById(R.id.rgApplyMode);

        LinearLayout containerDeviceChecklist = v.findViewById(R.id.containerDeviceChecklist);
        List<DeviceOption> deviceOptions = loadDeviceOptions();
        List<CheckBox> deviceCheckBoxes = new ArrayList<>();
        for (DeviceOption option : deviceOptions) {
            View row = getLayoutInflater().inflate(R.layout.item_template_device_checkbox, containerDeviceChecklist, false);
            ((TextView) row.findViewById(R.id.txtLine1)).setText(option.line1);
            ((TextView) row.findViewById(R.id.txtLine2)).setText(option.line2);
            ((TextView) row.findViewById(R.id.txtTypeBold)).setText(option.type);
            CheckBox cb = row.findViewById(R.id.cbDeviceOption);
            row.setOnClickListener(rowView -> cb.setChecked(!cb.isChecked()));
            deviceCheckBoxes.add(cb);
            containerDeviceChecklist.addView(row);
        }

        EditText etIp1 = v.findViewById(R.id.etIp1);
        EditText etIp2 = v.findViewById(R.id.etIp2);
        EditText etIp3 = v.findViewById(R.id.etIp3);
        EditText etIp4 = v.findViewById(R.id.etIp4);
        com.alidev.dfrtools.utils.IpAddressHelper.setupIpInputs(etIp1, etIp2, etIp3, etIp4);

        rgTarget.setOnCheckedChangeListener((group, checkedId) -> {
            boolean pickDevice = checkedId == R.id.rbTargetDevice;
            layoutTargetDevice.setVisibility(pickDevice ? View.VISIBLE : View.GONE);
            layoutTargetIp.setVisibility(pickDevice ? View.GONE : View.VISIBLE);
        });

        v.findViewById(R.id.btnCancelTemplate).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnApplyTemplate).setOnClickListener(view -> {
            String templateName = (String) spinnerTemplate.getSelectedItem();
            List<RelayTemplates.Point> points = RelayTemplates.get(this, templateName);
            if (points == null || points.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_mon_template_not_ready, templateName), Toast.LENGTH_LONG).show();
                return;
            }

            List<String> targetIps = new ArrayList<>();
            if (rgTarget.getCheckedRadioButtonId() == R.id.rbTargetDevice) {
                for (int i = 0; i < deviceOptions.size(); i++) {
                    if (deviceCheckBoxes.get(i).isChecked()) targetIps.add(deviceOptions.get(i).ip);
                }
                if (targetIps.isEmpty()) {
                    Toast.makeText(this, R.string.msg_mon_template_no_device, Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                String ip = com.alidev.dfrtools.utils.IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
                if (ip.equals("0.0.0.0")) {
                    Toast.makeText(this, R.string.msg_mon_template_invalid_ip, Toast.LENGTH_SHORT).show();
                    return;
                }
                targetIps.add(ip);
            }

            boolean replaceExisting = rgApplyMode.getCheckedRadioButtonId() == R.id.rbApplyModeReplace;

            dialog.dismiss();
            // No checkIntranetAndExecute gate here on purpose: that's a single ping to a fixed
            // gateway IP, and a failed/flaky ping used to block every target device's template
            // apply outright - including ones that were actually reachable - with nothing added
            // and only a generic VPN prompt shown. applyRelayTemplate() below already opens its
            // own real MMS connection per device and reports a clear per-IP failure
            // (msg_mon_template_connect_fail) if that fails, which is the connectivity check that
            // actually matters here.
            for (String ip : targetIps) applyRelayTemplate(templateName, points, ip, replaceExisting);
        });

        dialog.show();
    }

    /**
     * Applies a relay template to a specific device. Each template point's path is only the
     * LDInst/LN.DO.DA suffix (no IEDName), since IEDName is a per-device project naming choice,
     * not something fixed by the relay model - so we connect to the target, read its real logical
     * device (domain) list, and match each point's LDInst against a domain ending in that LDInst to
     * recover the actual IEDName prefix for this specific device before building the full address.
     * When replaceExisting is true, every point already monitored on this device is deleted first
     * (only once the connection succeeds, so a failed connect never destroys existing data);
     * otherwise points are merged in, skipping addresses already being monitored (existing dedup
     * in MonitoringManager.addNode()).
     */
    private void applyRelayTemplate(String templateName, List<RelayTemplates.Point> points, String ip, boolean replaceExisting) {
        Iec61850DfrClient client = clients.get(ip);
        if (client == null) {
            client = new Iec61850DfrClient();
            clients.put(ip, client);
        }
        final Iec61850DfrClient finalClient = client;
        Toast.makeText(this, getString(R.string.msg_mon_template_applying, templateName), Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            if (!finalClient.isConnected()) {
                int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
                int port = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
                finalClient.connect(ip, port, timeout);
            }

            if (!finalClient.isConnected()) {
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.msg_mon_template_connect_fail, ip), Toast.LENGTH_LONG).show());
                return;
            }

            if (replaceExisting) manager.removeNodesForIp(ip);

            List<String> domains = finalClient.getLogicalDevices();
            MonitoringManager.DeviceHeaderData headerData = getDeviceHeaderData(ip);
            String deviceName = headerData != null ? headerData.device : ip;

            int added = 0, skipped = 0;
            for (RelayTemplates.Point p : points) {
                int slash = p.path.indexOf('/');
                if (slash < 0) { skipped++; continue; }
                String ldInst = p.path.substring(0, slash);
                String suffix = p.path.substring(slash);

                String matchedDomain = null;
                for (String d : domains) {
                    if (d.endsWith(ldInst)) { matchedDomain = d; break; }
                }
                if (matchedDomain == null) { skipped++; continue; }

                MonitoredNode mn = new MonitoredNode(deviceName, ip, p.customName, matchedDomain + suffix, p.type);
                mn.unit = p.unit;
                mn.multiplier = p.multiplier;
                manager.addNode(mn);
                added++;
            }

            final int finalAdded = added, finalSkipped = skipped;
            mainHandler.post(() -> {
                Toast.makeText(this, getString(R.string.msg_mon_template_result, deviceName, finalAdded, finalSkipped), Toast.LENGTH_LONG).show();
                loadNodes();
            });
        });
    }

    private static class DeviceOption {
        final String line1, line2, type, ip;
        DeviceOption(String line1, String line2, String type, String ip) {
            this.line1 = line1;
            this.line2 = line2;
            this.type = type;
            this.ip = ip;
        }
    }

    /** Reads the device database into checklist rows: "[GI] bay [Bay]" / "[Device]_[Merk] [Type]([IP])" + bold [Type]. */
    private List<DeviceOption> loadDeviceOptions() {
        List<DeviceOption> result = new ArrayList<>();
        SharedPreferences prefs = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String listJson = prefs.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(listJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String gi = obj.optString("gi");
                String bay = obj.optString("bay");
                String device = obj.optString("device");
                String merk = obj.optString("merk");
                String type = obj.optString("type");
                String ip = obj.optString("ip");
                String line1 = String.format("%s bay %s", gi, bay);
                String line2 = String.format("%s_%s %s(%s)", device, merk, type, ip);
                result.add(new DeviceOption(line1, line2, type, ip));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private ArrayAdapter<String> createFuturisticSpinnerAdapter(List<String> items) {
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_futuristic, items);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_futuristic);
        return spinnerAdapter;
    }

    private static class GiFilterOption {
        final String key;   // null = "All" (no filter)
        final String label;
        final int count;
        GiFilterOption(String key, String label, int count) {
            this.key = key;
            this.label = label;
            this.count = count;
        }
    }

    /** Icon + bold GI name + colored count badge, instead of a plain "GI NAME (3)" string. */
    private class GiFilterAdapter extends ArrayAdapter<GiFilterOption> {
        GiFilterAdapter(List<GiFilterOption> items) {
            super(IEDMonitoringActivity.this, R.layout.spinner_item_gi_filter, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            return bind(position, convertView, parent, R.layout.spinner_item_gi_filter);
        }

        @NonNull
        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            return bind(position, convertView, parent, R.layout.spinner_dropdown_item_gi_filter);
        }

        private View bind(int position, View convertView, ViewGroup parent, int layoutRes) {
            View view = convertView != null ? convertView : getLayoutInflater().inflate(layoutRes, parent, false);
            GiFilterOption option = getItem(position);
            ((TextView) view.findViewById(R.id.txtGiName)).setText(option.label);
            ((TextView) view.findViewById(R.id.txtGiCount)).setText(String.valueOf(option.count));
            return view;
        }
    }

    /** Themed replacement for the stock PopupMenu - matches the app's card/dropdown look
     *  (bg_popup_menu_card, theme-aware surface/text colors) instead of the platform default. */
    private void showHeaderOptionsMenu(View anchor, HeaderInfo info) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_popup_menu_card);

        PopupWindow popup = new PopupWindow(container, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dpToPx(8));
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        addHeaderMenuOption(container, popup, R.drawable.ic_edit_small, R.string.ttl_mon_bulk_edit, -1,
                () -> showBulkEditDialog(info.ip, info.title));
        if (info.isUnknown) {
            addHeaderMenuOption(container, popup, R.drawable.ic_add, R.string.lbl_mon_add_device, -1, () -> {
                Intent intent = new Intent(IEDMonitoringActivity.this, DeviceListActivity.class);
                intent.putExtra("ip_prefill", info.ip);
                startActivity(intent);
            });
        }
        addHeaderMenuOption(container, popup, R.drawable.ic_delete, R.string.lbl_mon_delete_group,
                R.color.status_danger, () -> confirmDeleteGroup(info));

        container.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int xOff = anchor.getWidth() - container.getMeasuredWidth();
        popup.showAsDropDown(anchor, xOff, 0);
    }

    private void addHeaderMenuOption(LinearLayout container, PopupWindow popup, int iconRes, int labelRes,
                                      int tintColorRes, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_header_menu_option, container, false);
        ImageView icon = row.findViewById(R.id.imgMenuOptionIcon);
        TextView label = row.findViewById(R.id.txtMenuOptionLabel);
        icon.setImageResource(iconRes);
        label.setText(labelRes);
        if (tintColorRes != -1) {
            int color = ContextCompat.getColor(this, tintColorRes);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
            label.setTextColor(color);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        container.addView(row);
    }

    private void confirmDeleteGroup(HeaderInfo info) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage))
                .setText(getString(R.string.msg_mon_delete_group_confirm, info.title));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            manager.removeNodesForIp(info.ip);
            dialog.dismiss();
            loadNodes();
        });

        dialog.show();
    }

    /** Pull-to-refresh guard for large node lists - refreshing hundreds of nodes at once ties up
     *  every connected device and the network, so make sure the user meant to do that. */
    private void confirmRefreshMany(int nodeCount) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_refresh_many, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage))
                .setText(getString(R.string.msg_mon_refresh_many_confirm, nodeCount));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            dialog.dismiss();
            swipeRefresh.setRefreshing(true);
            checkIntranetAndExecute(this::manualRefresh, () -> swipeRefresh.setRefreshing(false));
        });

        dialog.show();
    }

    private void showImportCsvDialog(String ip, String deviceName) {
        pendingImportIp = ip;
        pendingImportDeviceName = deviceName;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_import_monitoring_csv, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnContinueImport).setOnClickListener(v -> {
            dialog.dismiss();
            pickMonitoringCsv();
        });
        dialogView.findViewById(R.id.btnCancelImport).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnDownloadTemplate).setOnClickListener(v -> downloadMonitoringCsvTemplate());

        dialog.show();
    }

    /** One row's editable draft state in the Bulk Edit popup - see showBulkEditDialog(). */
    private static class BulkEditRow {
        final MonitoredNode original; // same instance the adapter/manager hold; mutated only on Save
        final String oldFullPath, oldIp; // captured before any edit, needed to find the node again at save time
        String customName, fullPath, unit;
        float multiplier;
        boolean isNew = false; // added via "Add Row" in this session - saved with addNode() instead of updateNode()

        BulkEditRow(MonitoredNode original) {
            this.original = original;
            this.oldFullPath = original.fullPath;
            this.oldIp = original.ipAddress;
            this.customName = original.customName;
            this.fullPath = original.fullPath;
            this.unit = original.unit;
            this.multiplier = original.multiplier;
        }
    }

    /**
     * Compact popup for quickly renaming/re-addressing/deleting several points of one device
     * group at once, instead of opening showEditDialog() one point at a time. Mirrors the
     * draft-until-Save pattern used by RelayTemplateEditActivity: field edits and row deletions
     * only take effect when "Save" is pressed - cancelling or dismissing discards them.
     */
    private void showBulkEditDialog(String ip, String deviceName) {
        List<BulkEditRow> rows = new ArrayList<>();
        for (MonitoredNode n : adapter.getNodes()) {
            if (n.ipAddress.equals(ip)) rows.add(new BulkEditRow(n));
        }
        if (rows.isEmpty()) return;
        List<MonitoredNode> pendingRemovals = new ArrayList<>(); // rows deleted in this session, applied on Save

        View v = getLayoutInflater().inflate(R.layout.dialog_bulk_edit_nodes, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        ((TextView) v.findViewById(R.id.tvBulkEditSubtitle)).setText(
                getString(R.string.lbl_mon_bulk_edit_subtitle, deviceName, rows.size()));

        RecyclerView rvRows = v.findViewById(R.id.rvBulkEditRows);
        rvRows.setLayoutManager(new LinearLayoutManager(this));
        BulkEditRowAdapter rowsAdapter = new BulkEditRowAdapter(rows, pendingRemovals);
        rvRows.setAdapter(rowsAdapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(new BulkRowDragCallback(rowsAdapter));
        touchHelper.attachToRecyclerView(rvRows);
        rowsAdapter.setTouchHelper(touchHelper);

        v.findViewById(R.id.btnAddBulkRow).setOnClickListener(view -> {
            // Manually-added point, same "type defaults to float" convention as CSV import -
            // there's no live device read here to auto-detect it from.
            BulkEditRow newRow = new BulkEditRow(new MonitoredNode(deviceName, ip, "", "", "float"));
            newRow.isNew = true;
            rows.add(newRow);
            rowsAdapter.notifyItemInserted(rows.size() - 1);
        });
        v.findViewById(R.id.btnBulkImportCsv).setOnClickListener(view -> {
            dialog.dismiss();
            showImportCsvDialog(ip, deviceName);
        });
        v.findViewById(R.id.btnCancelBulkEdit).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnSaveBulkEdit).setOnClickListener(view -> {
            for (MonitoredNode removed : pendingRemovals) manager.removeNode(removed);
            int deletedCount = pendingRemovals.size();
            int updated = 0;
            for (BulkEditRow row : rows) {
                if (row.isNew) {
                    if (row.fullPath.trim().isEmpty()) continue; // no address entered - drop silently
                    row.original.customName = row.customName;
                    row.original.fullPath = row.fullPath;
                    row.original.nodeName = row.fullPath.contains(".")
                            ? row.fullPath.substring(row.fullPath.lastIndexOf('.') + 1) : row.fullPath;
                    row.original.unit = row.unit;
                    row.original.multiplier = row.multiplier;
                    manager.addNode(row.original);
                    updated++;
                    continue;
                }
                boolean addressChanged = !row.fullPath.equals(row.oldFullPath);
                row.original.customName = row.customName;
                row.original.fullPath = row.fullPath;
                if (addressChanged) {
                    // Same reset showEditDialog() does when the address changes: the old cached
                    // functional constraint and last value no longer apply to the new address.
                    row.original.nodeName = row.fullPath.contains(".")
                            ? row.fullPath.substring(row.fullPath.lastIndexOf('.') + 1) : row.fullPath;
                    row.original.cachedFc = null;
                    row.original.lastUpdateMillis = 0;
                }
                row.original.unit = row.unit;
                row.original.multiplier = row.multiplier;
                manager.updateNode(row.oldFullPath, row.oldIp, row.original);
                updated++;
            }
            reorderNodesForIp(ip, rows);
            Toast.makeText(this, getString(R.string.msg_mon_bulk_edit_saved, updated, deletedCount), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            loadNodes();
        });

        dialog.show();
    }

    /**
     * After Bulk Edit's add/update/removal calls above, re-lays the persisted node list so this
     * ip's nodes come out in the exact order the user left them in the table - so a drag-reorder
     * in the Bulk Edit dialog carries over to the main IED Monitoring list too. Every other ip's
     * nodes keep their current relative order (only this ip's slots in the master list are
     * reassigned, not moved as a block), since group membership itself is unaffected by this.
     */
    private void reorderNodesForIp(String ip, List<BulkEditRow> rows) {
        List<MonitoredNode> allNodes = manager.getNodes();
        List<Integer> ipSlots = new ArrayList<>();
        List<MonitoredNode> pool = new ArrayList<>();
        for (int i = 0; i < allNodes.size(); i++) {
            if (allNodes.get(i).ipAddress.equals(ip)) {
                ipSlots.add(i);
                pool.add(allNodes.get(i));
            }
        }
        List<MonitoredNode> ordered = new ArrayList<>();
        for (BulkEditRow row : rows) {
            for (Iterator<MonitoredNode> it = pool.iterator(); it.hasNext(); ) {
                MonitoredNode n = it.next();
                if (n.fullPath.equals(row.fullPath)) {
                    ordered.add(n);
                    it.remove();
                    break;
                }
            }
        }
        ordered.addAll(pool); // leftovers that couldn't be matched (shouldn't normally happen) keep their old relative order at the end
        for (int i = 0; i < ipSlots.size() && i < ordered.size(); i++) {
            allNodes.set(ipSlots.get(i), ordered.get(i));
        }
        manager.saveNodes(allNodes);
    }

    /** Lets the user drag rows up/down by their handle to reorder the Bulk Edit table; swiping is unused. */
    private static class BulkRowDragCallback extends ItemTouchHelper.SimpleCallback {
        private final BulkEditRowAdapter rowsAdapter;
        BulkRowDragCallback(BulkEditRowAdapter rowsAdapter) {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            this.rowsAdapter = rowsAdapter;
        }
        @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
            int from = vh.getAdapterPosition(), to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
            rowsAdapter.moveRow(from, to);
            return true;
        }
        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
    }

    /** Backs the Bulk Edit table's RecyclerView - draft rows are edited/reordered/removed in place, applied on Save. */
    private class BulkEditRowAdapter extends RecyclerView.Adapter<BulkEditRowAdapter.VH> {
        private final List<BulkEditRow> rows;
        private final List<MonitoredNode> pendingRemovals;
        private ItemTouchHelper touchHelper;

        BulkEditRowAdapter(List<BulkEditRow> rows, List<MonitoredNode> pendingRemovals) {
            this.rows = rows;
            this.pendingRemovals = pendingRemovals;
        }

        void setTouchHelper(ItemTouchHelper touchHelper) { this.touchHelper = touchHelper; }

        void moveRow(int from, int to) {
            Collections.swap(rows, from, to);
            notifyItemMoved(from, to);
        }

        void removeRow(int position) {
            BulkEditRow row = rows.get(position);
            if (!row.isNew) pendingRemovals.add(row.original);
            rows.remove(position);
            notifyItemRemoved(position);
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_bulk_edit_node_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(rows.get(position));
        }

        @Override public int getItemCount() { return rows.size(); }

        class VH extends RecyclerView.ViewHolder {
            private BulkEditRow row;
            private final EditText etName, etPath, etUnit, etMultiplier;
            private final Spinner spType;

            @SuppressLint("ClickableViewAccessibility")
            VH(View item) {
                super(item);
                etName = item.findViewById(R.id.etRowName);
                etPath = item.findViewById(R.id.etRowPath);
                spType = item.findViewById(R.id.spRowType);
                etUnit = item.findViewById(R.id.etRowUnit);
                etMultiplier = item.findViewById(R.id.etRowMultiplier);
                spType.setAdapter(createTypeSpinnerAdapter());

                // Listeners are wired once here (not per bind()) since the RecyclerView reuses this
                // View across rows - they always act on whichever row `bind()` last pointed them at.
                etName.addTextChangedListener(bulkTextWatcher(s -> { if (row != null) row.customName = s; }));
                etPath.addTextChangedListener(bulkTextWatcher(s -> { if (row != null) row.fullPath = s; }));
                etUnit.addTextChangedListener(bulkTextWatcher(s -> { if (row != null) row.unit = s; }));
                etMultiplier.addTextChangedListener(bulkTextWatcher(s -> {
                    if (row == null) return;
                    try {
                        row.multiplier = Float.parseFloat(s);
                    } catch (NumberFormatException ignored) {
                        // Leave the last valid multiplier in place while the user is mid-edit.
                    }
                }));
                spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (row != null) row.original.type = TYPE_OPTIONS.get(position);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
                item.findViewById(R.id.btnRowBrowse).setOnClickListener(v -> {
                    if (row != null) showBrowseAddressDialog(row.original, etPath, row.isNew, spType);
                });
                item.findViewById(R.id.btnRowDelete).setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) confirmDeleteBulkRow(rows.get(pos), pos, BulkEditRowAdapter.this);
                });
                item.findViewById(R.id.btnRowDrag).setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && touchHelper != null) {
                        touchHelper.startDrag(this);
                    }
                    return false;
                });
            }

            void bind(BulkEditRow r) {
                row = r;
                etName.setText(r.customName);
                etPath.setText(r.fullPath);
                etUnit.setText(r.unit);
                etMultiplier.setText(String.valueOf(r.multiplier));
                int typeIndex = TYPE_OPTIONS.indexOf(r.original.type);
                spType.setSelection(typeIndex >= 0 ? typeIndex : 1); // default to "float" if unset/unrecognized
            }
        }
    }

    private void confirmDeleteBulkRow(BulkEditRow row, int position, BulkEditRowAdapter rowsAdapter) {
        String label = !row.customName.isEmpty() ? row.customName : row.fullPath;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmTitle)).setText(R.string.ttl_tmpl_delete_row_confirm);
        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage)).setText(getString(R.string.msg_tmpl_delete_row_confirm, label));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            rowsAdapter.removeRow(position);
            dialog.dismiss();
        });

        dialog.show();
    }

    private TextWatcher bulkTextWatcher(Consumer<String> onChanged) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { onChanged.accept(s.toString()); }
        };
    }

    private void pickMonitoringCsv() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimetypes = {"text/comma-separated-values", "text/csv"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        startActivityForResult(intent, REQUEST_CSV);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitoringRefreshService.setListener(bulkListener);
        if (MonitoringRefreshService.isRunning()) {
            // A bulk refresh kept running while this screen was gone - restore the in-progress
            // UI immediately instead of waiting for the next tick to arrive.
            swipeRefresh.setRefreshing(true);
            topProgressBar.setVisibility(View.VISIBLE);
            layoutBulkProgress.setVisibility(View.VISIBLE);
            String last = MonitoringRefreshService.getLastProgressText();
            tvBulkProgress.setText(last.isEmpty() ? getString(R.string.msg_mon_bulk_notif_starting) : last);
        }
    }

    @Override
    protected void onPause() {
        MonitoringRefreshService.setListener(null);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CSV && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importMonitoringCsv(data.getData(), pendingImportIp, pendingImportDeviceName);
        }
    }

    /**
     * CSV columns: custom name, node address (full path), unit. Nodes default to type "float"
     * since the CSV has no type column and a unit only makes sense for numeric points.
     */
    private void importMonitoringCsv(Uri uri, String ip, String deviceName) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            List<MonitoredNode> existing = manager.getNodes();
            int count = 0;
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (firstLine) {
                    firstLine = false;
                    String lower = line.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("nama") || lower.contains("custom")) continue;
                }
                String[] p = line.split(",", -1);
                if (p.length < 2) continue;

                String customName = p[0].replace("\"", "").trim();
                String fullPath = p[1].replace("\"", "").trim();
                String unit = p.length > 2 ? p[2].replace("\"", "").trim() : "";
                if (customName.isEmpty() || fullPath.isEmpty()) continue;

                boolean duplicate = false;
                for (MonitoredNode n : existing) {
                    if (n.fullPath.equals(fullPath) && n.ipAddress.equals(ip)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                String nodeName = fullPath.contains(".") ? fullPath.substring(fullPath.lastIndexOf('.') + 1) : fullPath;
                MonitoredNode node = new MonitoredNode(deviceName, ip, nodeName, fullPath, "float");
                node.customName = customName;
                node.unit = unit;
                existing.add(node);
                count++;
            }
            reader.close();
            if (count > 0) {
                manager.saveNodes(existing);
                loadNodes();
            }
            Toast.makeText(this, getString(R.string.msg_mon_import_ok, count), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_import_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void downloadMonitoringCsvTemplate() {
        try {
            File file = new File(getExternalFilesDir(null), "template_monitoring.csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("Nama Custom,Alamat Node,Satuan\n".getBytes(StandardCharsets.UTF_8));
            fos.write("Arus R,PriFouMMXU1.A.phsA.cVal.mag.f,A\n".getBytes(StandardCharsets.UTF_8));
            fos.write("Tegangan Phasa A,PriFouMMXU1.PPV.phsAB.cVal.mag.f,V\n".getBytes(StandardCharsets.UTF_8));
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

    private MonitoringManager.DeviceHeaderData getDeviceHeaderData(String ip) {
        return MonitoringManager.getDeviceHeaderData(this, ip);
    }

    /** Thin one-line header shown instead of the full header while searching: "Device GI Bay - Merk Type". */
    private String getCompactHeaderLine(String ip) {
        MonitoringManager.DeviceHeaderData d = getDeviceHeaderData(ip);
        if (d == null) return ip;
        return String.format("%s %s %s - %s %s", d.device, d.gi, d.bay, d.merk, d.type).trim();
    }

    /** Shows a one-shot popup naming the affected device when its monitoring connection is lost. */
    private void notifyDeviceDisconnected(String ip) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            MonitoringManager.DeviceHeaderData data = getDeviceHeaderData(ip);
            String identity = data != null ? (data.title + " - " + data.device) : ip;
            Toast.makeText(IEDMonitoringActivity.this,
                    getString(R.string.msg_mon_device_disconnected, identity),
                    Toast.LENGTH_LONG).show();
        });
    }

    /** Latest lastUpdateMillis across every node in this device's group, or 0 if it's never had a successful read. */
    private long getLatestUpdateMillis(String ip) {
        long latest = 0;
        for (MonitoredNode n : adapter.getNodes()) {
            if (n.ipAddress.equals(ip) && n.lastUpdateMillis > latest) latest = n.lastUpdateMillis;
        }
        return latest;
    }

    /** [nodes with at least one successful read, total nodes] for this device's group. */
    private int[] getGroupNodeStats(String ip) {
        int total = 0, success = 0;
        for (MonitoredNode n : adapter.getNodes()) {
            if (!n.ipAddress.equals(ip)) continue;
            total++;
            if (n.lastUpdateMillis > 0) success++;
        }
        return new int[]{success, total};
    }

    /**
     * Always renders in Indonesian ("Kamis, 27 Agustus 2026 18:00:09 WIB"), independent of the
     * app's selected UI language, since WIB (Asia/Jakarta) is the fixed timezone these relays
     * report in regardless of which language the operator has the app set to.
     */
    private static final java.text.SimpleDateFormat HEADER_UPDATE_DATE_FORMAT =
            new java.text.SimpleDateFormat("EEEE, d MMMM yyyy HH:mm:ss", new java.util.Locale("in", "ID"));
    static {
        HEADER_UPDATE_DATE_FORMAT.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Jakarta"));
    }

    private String getLastUpdateText(String ip) {
        long latest = getLatestUpdateMillis(ip);
        String timePart = latest == 0
                ? getString(R.string.lbl_mon_no_update)
                : getString(R.string.lbl_mon_last_update,
                        HEADER_UPDATE_DATE_FORMAT.format(new java.util.Date(latest)) + " WIB");
        int[] stats = getGroupNodeStats(ip);
        return getString(R.string.lbl_mon_header_status, timePart, stats[0], stats[1]);
    }

    static class HeaderInfo {
        String title, ip, ipLine, device;
        boolean isUnknown;
        boolean isCollapsed;
        boolean isSearchMode; // true while a search query is active: forces expansion + compact header row
        HeaderInfo(String t, String i, String ipLine, String device, boolean u) {
            title = t; ip = i; this.ipLine = ipLine; this.device = device; isUnknown = u;
        }
    }

    class MonitoringAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;
        private final List<Object> items = new ArrayList<>();
        private final List<MonitoredNode> nodes = new ArrayList<>(); // full unfiltered set; polling always uses this
        // Tracks groups the user has explicitly expanded - everything else defaults to collapsed.
        private final Set<String> expandedIps = new HashSet<>();
        private String currentFilter = "";
        private String currentGiFilter = null; // null = no GI filter ("All")

        void setNodes(List<MonitoredNode> newNodes) {
            nodes.clear();
            nodes.addAll(newNodes);
            rebuildItems();
        }

        void filter(String query) {
            currentFilter = query == null ? "" : query.trim().toLowerCase();
            rebuildItems();
        }

        void filterByGi(String gi) {
            currentGiFilter = gi;
            rebuildItems();
        }

        private void rebuildItems() {
            boolean searching = !currentFilter.isEmpty();
            List<MonitoredNode> visible = nodes;
            if (searching) {
                // Search matches only the point's own identity (custom name / node address) -
                // not the device name or IP, which belong to the header, not the point.
                visible = new ArrayList<>();
                for (MonitoredNode n : nodes) {
                    if (n.customName.toLowerCase().contains(currentFilter)
                            || n.fullPath.toLowerCase().contains(currentFilter)) {
                        visible.add(n);
                    }
                }
            }
            if (currentGiFilter != null) {
                List<MonitoredNode> giFiltered = new ArrayList<>();
                for (MonitoredNode n : visible) {
                    MonitoringManager.DeviceHeaderData d = getDeviceHeaderData(n.ipAddress);
                    String gi = (d != null && !d.gi.isEmpty()) ? d.gi : getString(R.string.lbl_mon_gi_unknown);
                    if (currentGiFilter.equals(gi)) giFiltered.add(n);
                }
                visible = giFiltered;
            }

            List<Object> newItems = new ArrayList<>();
            Map<String, List<MonitoredNode>> grouped = new HashMap<>();
            for (MonitoredNode n : visible) {
                String key = n.ipAddress;
                if (!grouped.containsKey(key)) grouped.put(key, new ArrayList<>());
                grouped.get(key).add(n);
            }
            List<String> sortedIps = new ArrayList<>(grouped.keySet());
            java.util.Collections.sort(sortedIps, (a, b) -> {
                MonitoringManager.DeviceHeaderData da = getDeviceHeaderData(a);
                MonitoringManager.DeviceHeaderData db = getDeviceHeaderData(b);
                String ta = da != null ? da.title : a;
                String tb = db != null ? db.title : b;
                return ta.compareToIgnoreCase(tb);
            });
            for (String ip : sortedIps) {
                HeaderInfo header;
                MonitoringManager.DeviceHeaderData data = getDeviceHeaderData(ip);
                if (data != null) {
                    header = new HeaderInfo(data.title, ip, data.ipLine, data.device, false);
                } else {
                    header = new HeaderInfo(ip, ip, ip, ip, true);
                }
                header.isCollapsed = searching ? false : !expandedIps.contains(ip);
                header.isSearchMode = searching;
                newItems.add(header);
                if (!header.isCollapsed) {
                    List<MonitoredNode> groupNodes = grouped.get(ip);
                    if (groupNodes != null) newItems.addAll(groupNodes);
                }
            }

            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new MonitoringDiffCallback(items, newItems));
            items.clear();
            items.addAll(newItems);
            result.dispatchUpdatesTo(this);
        }

        void toggleGroup(String ip) {
            if (!expandedIps.remove(ip)) expandedIps.add(ip);
            rebuildItems();
        }

        List<MonitoredNode> getNodes() { return new ArrayList<>(nodes); }

        @Override public int getItemViewType(int position) {
            return items.get(position) instanceof HeaderInfo ? TYPE_HEADER : TYPE_ITEM;
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_monitoring_header, parent, false), false);
            }
            return new ItemVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_monitored_node, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind((HeaderInfo) items.get(position));
            } else {
                ((ItemVH) holder).bind((MonitoredNode) items.get(position));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains("STALE_CHECK")) {
                if (holder instanceof ItemVH) {
                    // Must fully rebind (not just updateStaleUI): node.lastValue is mutated in place
                    // by refreshValues() before DiffUtil compares old/new, so DiffUtil's content check
                    // always sees the same (already-updated) object and never reports a value change.
                    // This payload fires on every poll regardless, so it's the reliable place to
                    // actually push the current value/pill/dot to the view.
                    ((ItemVH) holder).bind((MonitoredNode) items.get(position));
                } else if (holder instanceof HeaderVH) {
                    ((HeaderVH) holder).bind((HeaderInfo) items.get(position));
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /**
     * Keeps the current group's header pinned at the top of rvMonitoring while scrolling through
     * its items, sliding out only once the next group's real header pushes it up from below.
     * Unlike a canvas-drawn ghost header, stickyHeaderContainer is a real, attached View sitting
     * on top of the RecyclerView (see activity_ied_monitoring.xml) - its buttons (expand toggle,
     * refresh/import/edit/delete) work exactly like any other view's, no manual touch relay needed.
     */
    private void updateStickyHeader() {
        if (rvMonitoring.getChildCount() == 0) { hideStickyHeader(); return; }

        View firstChild = rvMonitoring.getChildAt(0);
        int firstPos = rvMonitoring.getChildAdapterPosition(firstChild);
        if (firstPos == RecyclerView.NO_POSITION) { hideStickyHeader(); return; }

        int headerPos = findHeaderPositionAtOrBefore(firstPos);
        if (headerPos == RecyclerView.NO_POSITION) { hideStickyHeader(); return; }

        // If the real header for this section is still sitting in its natural place, it doesn't
        // need pinning yet - let it render normally instead of showing the pinned copy over it.
        View naturalHeaderView = findChildAtAdapterPosition(rvMonitoring, headerPos);
        if (naturalHeaderView != null && naturalHeaderView.getTop() >= 0) { hideStickyHeader(); return; }

        if (stickyHolder == null) {
            View v = getLayoutInflater().inflate(R.layout.item_monitoring_header, stickyHeaderContainer, false);
            View banner = v.findViewById(R.id.layoutHeaderBanner);
            banner.setBackgroundResource(R.drawable.bg_monitoring_group_header_pinned);
            int extraPad = dpToPx(6);
            banner.setPadding(banner.getPaddingLeft(), banner.getPaddingTop() + extraPad, banner.getPaddingRight(), banner.getPaddingBottom() + extraPad);
            stickyHolder = new HeaderVH(v, true);
            stickyHeaderContainer.addView(v);
        }
        adapter.onBindViewHolder(stickyHolder, headerPos); // always rebind so a live refresh never shows stale text while pinned
        stickyHeaderContainer.setVisibility(View.VISIBLE);

        int headerHeight = stickyHeaderContainer.getHeight();
        if (headerHeight == 0) {
            // Not laid out yet (first frame this becomes visible) - measure directly so the
            // push-up calculation below still has a usable height instead of treating it as 0.
            View headerView = stickyHolder.itemView;
            headerView.measure(
                    View.MeasureSpec.makeMeasureSpec(rvMonitoring.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            headerHeight = headerView.getMeasuredHeight();
        }

        float translationY = 0;
        int nextHeaderPos = findHeaderPositionAfter(headerPos);
        if (nextHeaderPos != RecyclerView.NO_POSITION) {
            View nextHeaderView = findChildAtAdapterPosition(rvMonitoring, nextHeaderPos);
            if (nextHeaderView != null) {
                int overlap = headerHeight - nextHeaderView.getTop();
                if (overlap > 0) translationY = -overlap;
            }
        }
        stickyHeaderContainer.setTranslationY(translationY);
    }

    private void hideStickyHeader() {
        stickyHeaderContainer.setVisibility(View.GONE);
    }

    private int findHeaderPositionAtOrBefore(int position) {
        for (int i = position; i >= 0; i--) {
            if (adapter.getItemViewType(i) == MonitoringAdapter.TYPE_HEADER) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private int findHeaderPositionAfter(int position) {
        for (int i = position + 1; i < adapter.getItemCount(); i++) {
            if (adapter.getItemViewType(i) == MonitoringAdapter.TYPE_HEADER) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private View findChildAtAdapterPosition(RecyclerView parent, int adapterPosition) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (parent.getChildAdapterPosition(child) == adapterPosition) return child;
        }
        return null;
    }

    private class MonitoringDiffCallback extends DiffUtil.Callback {
        private final List<Object> oldList, newList;
        MonitoringDiffCallback(List<Object> oldList, List<Object> newList) { this.oldList = oldList; this.newList = newList; }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int oldPos, int newPos) {
            Object oldObj = oldList.get(oldPos);
            Object newObj = newList.get(newPos);
            if (oldObj instanceof HeaderInfo && newObj instanceof HeaderInfo) return ((HeaderInfo) oldObj).ip.equals(((HeaderInfo) newObj).ip);
            if (oldObj instanceof MonitoredNode && newObj instanceof MonitoredNode) {
                MonitoredNode o = (MonitoredNode) oldObj;
                MonitoredNode n = (MonitoredNode) newObj;
                return o.fullPath.equals(n.fullPath) && o.ipAddress.equals(n.ipAddress);
            }
            return false;
        }
        @Override public boolean areContentsTheSame(int oldPos, int newPos) {
            Object oldObj = oldList.get(oldPos);
            Object newObj = newList.get(newPos);
            if (oldObj instanceof HeaderInfo && newObj instanceof HeaderInfo) {
                HeaderInfo o = (HeaderInfo) oldObj;
                HeaderInfo n = (HeaderInfo) newObj;
                return o.title.equals(n.title) && o.ipLine.equals(n.ipLine) && o.isCollapsed == n.isCollapsed
                        && o.isSearchMode == n.isSearchMode;
            }
            if (oldObj instanceof MonitoredNode && newObj instanceof MonitoredNode) {
                MonitoredNode o = (MonitoredNode) oldObj;
                MonitoredNode n = (MonitoredNode) newObj;
                return o.lastValue.equals(n.lastValue) && o.customName.equals(n.customName);
            }
            return false;
        }
    }

    private static final int[] GROUP_HEADER_VARIANTS = {
            R.drawable.bg_monitoring_group_header_1,
            R.drawable.bg_monitoring_group_header_2,
            R.drawable.bg_monitoring_group_header_3,
    };

    class HeaderVH extends RecyclerView.ViewHolder {
        TextView txtHeader, txtHeaderIp, txtHeaderUpdate, txtHeaderCompact;
        View layoutHeaderDetails, layoutHeaderBanner;
        ImageView btnRefreshGroup, btnMoreOptions, imgExpand;
        View statusDot, viewDeviceAccent;
        final boolean isSticky;
        HeaderVH(View v, boolean isSticky) {
            super(v);
            this.isSticky = isSticky;
            txtHeader = v.findViewById(R.id.txtHeader);
            txtHeaderIp = v.findViewById(R.id.txtHeaderIp);
            txtHeaderUpdate = v.findViewById(R.id.txtHeaderUpdate);
            txtHeaderCompact = v.findViewById(R.id.txtHeaderCompact);
            layoutHeaderDetails = v.findViewById(R.id.layoutHeaderDetails);
            layoutHeaderBanner = v.findViewById(R.id.layoutHeaderBanner);
            btnRefreshGroup = v.findViewById(R.id.btnRefreshGroup);
            btnMoreOptions = v.findViewById(R.id.btnMoreOptions);
            imgExpand = v.findViewById(R.id.imgExpand);
            statusDot = v.findViewById(R.id.statusDot);
            viewDeviceAccent = v.findViewById(R.id.viewDeviceAccent);
        }
        void bind(HeaderInfo info) {
            // Cycle through a small set of theme-native gradient variants per device (stable via
            // the IP's hash, not position, so a given device always keeps the same look across
            // scrolls/refreshes) - breaks up the "wall of identical bars" look a long device list
            // otherwise has, without touching the rest of the app's palette. The pinned sticky
            // copy keeps its own fixed look instead, so it doesn't change hue as you scroll past
            // different groups underneath it.
            if (!isSticky) {
                int variant = Math.floorMod(info.ip.hashCode(), GROUP_HEADER_VARIANTS.length);
                layoutHeaderBanner.setBackgroundResource(GROUP_HEADER_VARIANTS[variant]);
            }
            // While searching, collapse the header down to one thin identification line and hide
            // every action/status affordance - the point is to scan the flat match list quickly,
            // not manage the group.
            if (info.isSearchMode) {
                layoutHeaderDetails.setVisibility(View.GONE);
                txtHeaderCompact.setVisibility(View.VISIBLE);
                txtHeaderCompact.setText(getCompactHeaderLine(info.ip));

                if (statusDot != null) statusDot.setVisibility(View.GONE);
                if (viewDeviceAccent != null) viewDeviceAccent.setVisibility(View.GONE);
                if (imgExpand != null) imgExpand.setVisibility(View.GONE);
                if (btnMoreOptions != null) btnMoreOptions.setVisibility(View.GONE);
                if (btnRefreshGroup != null) btnRefreshGroup.setVisibility(View.GONE);
                layoutHeaderBanner.setOnClickListener(null);
                return;
            }

            layoutHeaderDetails.setVisibility(View.VISIBLE);
            txtHeaderCompact.setVisibility(View.GONE);
            if (statusDot != null) statusDot.setVisibility(View.VISIBLE);
            if (viewDeviceAccent != null) {
                viewDeviceAccent.setVisibility(View.VISIBLE);
                viewDeviceAccent.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getDeviceAccentColor(info.device)));
            }
            if (imgExpand != null) imgExpand.setVisibility(View.VISIBLE);
            if (btnMoreOptions != null) btnMoreOptions.setVisibility(View.VISIBLE);
            if (btnRefreshGroup != null) btnRefreshGroup.setVisibility(View.VISIBLE);

            txtHeader.setText(info.title);
            txtHeaderUpdate.setText(getLastUpdateText(info.ip));

            if (!info.isUnknown) {
                txtHeaderIp.setVisibility(View.VISIBLE);
                txtHeaderIp.setText(info.ipLine);
            } else {
                txtHeaderIp.setVisibility(View.GONE);
            }

            if (imgExpand != null) imgExpand.setRotation(info.isCollapsed ? 0 : 90);
            layoutHeaderBanner.setOnClickListener(v -> adapter.toggleGroup(info.ip));
            if (btnMoreOptions != null) {
                btnMoreOptions.setOnClickListener(v -> showHeaderOptionsMenu(v, info));
            }
            if (btnRefreshGroup != null) {
                btnRefreshGroup.setOnClickListener(v -> checkIntranetAndExecute(() -> manualRefreshGroup(info.ip)));
            }

            // LED reflects data freshness, not live connection state (refreshing is manual now):
            // red = never had a successful read, gray = has a value but it's stale (>15 min old),
            // green = has a value refreshed within the last 15 minutes.
            if (statusDot != null) {
                long latest = getLatestUpdateMillis(info.ip);
                int colorRes;
                if (latest == 0) {
                    colorRes = R.color.status_danger;
                } else if (System.currentTimeMillis() - latest <= 15 * 60 * 1000L) {
                    colorRes = R.color.status_safe;
                } else {
                    colorRes = R.color.status_neutral;
                }
                statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(IEDMonitoringActivity.this, colorRes)));
            }
        }
    }

    class ItemVH extends RecyclerView.ViewHolder {
        TextView txtCustomName, txtValue, txtFullPath, txtBooleanValue;
        View root, pillValue, viewAlarmAccent, layoutBooleanValue;
        ImageView btnEdit, imgToggle;

        ItemVH(View v) {
            super(v);
            root = v;
            txtCustomName = v.findViewById(R.id.txtCustomName);
            txtValue = v.findViewById(R.id.txtValue);
            txtFullPath = v.findViewById(R.id.txtFullPath);
            pillValue = v.findViewById(R.id.pillValue);
            layoutBooleanValue = v.findViewById(R.id.layoutBooleanValue);
            imgToggle = v.findViewById(R.id.imgToggle);
            txtBooleanValue = v.findViewById(R.id.txtBooleanValue);
            btnEdit = v.findViewById(R.id.btnEdit);
            viewAlarmAccent = v.findViewById(R.id.viewAlarmAccent);
        }

        void updateStaleUI(MonitoredNode node) {
            // Refreshing is manual now (see loadNodes()), so "stale" no longer means "polling broke" -
            // it just means "hasn't been refreshed in a while". Use a day-long window instead of the
            // old few-seconds one, which would otherwise dim almost everything almost all the time.
            boolean isStale = node.lastUpdateMillis == 0
                    || (System.currentTimeMillis() - node.lastUpdateMillis > 24 * 60 * 60 * 1000L);
            root.setAlpha(isStale ? 0.5f : 1.0f);
        }

        void bind(MonitoredNode node) {
            txtCustomName.setText(node.customName);
            txtFullPath.setText(node.fullPath);
            updateStaleUI(node);

            boolean isBad;
            if (node.type.equals("boolean")) {
                layoutBooleanValue.setVisibility(View.VISIBLE);
                pillValue.setVisibility(View.GONE);
                boolean b = node.lastValue.equalsIgnoreCase("true");
                // The toggle icon/text always reflects the raw value (green=FALSE, red=TRUE by
                // default, swappable per-node via node.invertColor); whether that value counts as
                // an alarm is a separate concern, shown via viewAlarmAccent.
                boolean showRed = node.invertColor != b; // invertColor flips which state reads red
                if (b) {
                    imgToggle.setImageResource(node.invertColor ? R.drawable.ic_toggle_on_inverted : R.drawable.ic_toggle_on);
                } else {
                    imgToggle.setImageResource(node.invertColor ? R.drawable.ic_toggle_off_inverted : R.drawable.ic_toggle_off);
                }
                txtBooleanValue.setText(b ? "TRUE" : "FALSE");
                txtBooleanValue.setTextColor(ContextCompat.getColor(IEDMonitoringActivity.this,
                        showRed ? R.color.status_danger : R.color.status_safe));
                isBad = node.alarmEnabled ? (b == node.alarmOnValue) : !b;
            } else {
                layoutBooleanValue.setVisibility(View.GONE);
                pillValue.setVisibility(View.VISIBLE);
                String v = node.lastValue;
                if (!node.unit.isEmpty()) v += " " + node.unit;
                txtValue.setText(v);
                isBad = node.isAlarming();
                if (pillValue != null) {
                    // Alarm state always wins (needs to stand out); otherwise the pill is colored
                    // by unit so different measurement types (A, kV, MW, °...) read apart at a glance.
                    int pillColor = isBad
                            ? ContextCompat.getColor(IEDMonitoringActivity.this, R.color.status_danger)
                            : getUnitAccentColor(node.unit);
                    pillValue.setBackgroundTintList(android.content.res.ColorStateList.valueOf(pillColor));
                }
            }
            if (viewAlarmAccent != null) {
                viewAlarmAccent.setBackgroundTintList(isBad
                        ? android.content.res.ColorStateList.valueOf(ContextCompat.getColor(IEDMonitoringActivity.this, R.color.status_danger))
                        : null);
            }
            btnEdit.setOnClickListener(v -> showEditDialog(node));
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(autoRefreshRunnable);
        // Disconnect goes through the same executor (and the now-synchronized Iec61850DfrClient
        // methods) instead of running here on the main thread, so it can never race a still-running
        // background poll for the same client - queued before shutdown() so it's still accepted.
        for (Iec61850DfrClient c : clients.values()) executor.execute(c::disconnect);
        executor.shutdown();
        super.onDestroy();
    }
}
