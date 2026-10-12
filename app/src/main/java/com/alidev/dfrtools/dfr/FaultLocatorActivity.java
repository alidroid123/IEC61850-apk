package com.alidev.dfrtools.dfr;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.ChannelMap;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Cx;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.EndSummary;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Phasors;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Result;
import com.alidev.dfrtools.utils.ConfigHelper;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * "FL Double Ended": locates a line fault from the DFR (COMTRADE) records of the line relays at
 * both substations. Each end picks its GI + relay from the Relay Database and either downloads a
 * record straight from the relay over MMS (same smart search as DfrDownloadActivity, saved into
 * the same Download/DFR/<GI>_<BAY> folder) or reuses one already on the phone. The math lives in
 * DoubleEndedFaultLocator.
 */
public class FaultLocatorActivity extends BaseActivity {

    private static final String PREFS = "fl_prefs";
    private static final int REQ_STORAGE_A = 401, REQ_STORAGE_B = 402, REQ_PICK_LINE = 403, REQ_PAIR_A = 404, REQ_PAIR_B = 405;
    private static final String[] ROLE_LABELS = {"Va", "Vb", "Vc", "Ia", "Ib", "Ic"};

    /** Everything known about one line end. */
    private static class EndState {
        final boolean isA;
        View root;
        Spinner spGi, spRelay;
        TextView tvRecord, tvStatus;
        View actions, btnOpenViewer;
        final List<DeviceListActivity.DeviceItem> relays = new ArrayList<>();
        /** GI the relay spinner was last filled for. */
        String relaysForGi;
        File cfg, dat;
        ComtradeRecord rec;
        ChannelMap map;
        /** MMS directory timestamp of the record picked from the relay, 0 for local files. */
        long remoteFileTime;
        /** GI / bay / relay IP typed by hand for a relay not in the Device List (null = none). */
        DeviceListActivity.DeviceItem manual;

        EndState(boolean isA) { this.isA = isA; }
    }

    private final EndState endA = new EndState(true), endB = new EndState(false);
    private final List<DeviceListActivity.DeviceItem> devices = new ArrayList<>();
    private final List<String> giNames = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextInputEditText etLength, etR1, etX1, etR0, etX0, etB1, etOffset;
    private TextInputLayout tilR1, tilX1, tilR0, tilX0, tilB1;
    private SwitchCompat swTotal;
    private View overlay, cardResult;
    private TextView tvProgress;
    private Result lastResult;
    private String lastLinePairKey;
    /** The line fields currently show values remembered for lastLinePairKey (not user typing). */
    private boolean lineParamsRestored;
    /** Relay connection kept open while the user picks a record, so it can be closed on exit. */
    private volatile Iec61850DfrClient openClient;
    private TextView tvLineConfig;
    /** Vectors of both ends at the shift currently chosen with the slider (or auto). */
    private DoubleEndedFaultLocator.Alignment currentAlignment;
    /** Main result = voltage-only synchronised location instead of the unsynchronised one. */
    private boolean syncAsMain, bindingSync;
    private double shiftLo;
    private static final double SHIFT_STEP = 0.00005; // 0.05 ms per slider step (0.9 deg at 50 Hz)
    private String resultNameA = "", resultNameB = "";
    /** Non-null while a saved analysis (not a live one) is on screen. */
    @Nullable private FlResultStore.Saved viewingSaved;
    /** Line Database row the line fields were filled from (null = typed by hand). */
    private LineData selectedLine;
    private boolean selectedLineAuto, selectedLineEdited, fillingLineFields;
    /** Lone .cfg/.dat files opened with this screen, kept until their partner arrives too. */
    private File inboxDir;
    /** Copy folder of a lone .cfg/.dat whose partner the user is now picking by hand. */
    private File pendingPairDir;
    /** When the result on screen was computed (PDF footer); 0 = none yet. */
    private long lastAnalyzedAt;
    /** sessionInputs() at the time of the last analysis - restore re-runs it only if unchanged. */
    private String analyzedInputs = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fault_locator);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFlInfo).setOnClickListener(v -> showInfoDialog(getString(R.string.ttl_fl_info), getString(R.string.msg_fl_info)));

        overlay = findViewById(R.id.overlayFlProgress);
        tvProgress = findViewById(R.id.tvFlProgress);
        cardResult = findViewById(R.id.cardFlResult);

        etLength = findViewById(R.id.etFlLength);
        etR1 = findViewById(R.id.etFlR1);
        etX1 = findViewById(R.id.etFlX1);
        etR0 = findViewById(R.id.etFlR0);
        etX0 = findViewById(R.id.etFlX0);
        etB1 = findViewById(R.id.etFlB1);
        etOffset = findViewById(R.id.etFlOffset);
        tilR1 = findViewById(R.id.tilFlR1);
        tilX1 = findViewById(R.id.tilFlX1);
        tilR0 = findViewById(R.id.tilFlR0);
        tilX0 = findViewById(R.id.tilFlX0);
        tilB1 = findViewById(R.id.tilFlB1);
        swTotal = findViewById(R.id.swFlTotal);
        swTotal.setOnCheckedChangeListener((b, checked) -> updateLineHints());
        updateLineHints();
        tvLineConfig = findViewById(R.id.tvFlLineConfig);
        findViewById(R.id.btnFlPickLine).setOnClickListener(v -> pickLineFromDatabase());
        android.text.TextWatcher editedWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (fillingLineFields || selectedLine == null || selectedLineEdited) return;
                selectedLineEdited = true;
                renderLineConfig();
            }
        };
        for (EditText et : new EditText[]{etLength, etR1, etX1, etR0, etX0, etB1}) et.addTextChangedListener(editedWatcher);

        loadDevices();
        bindEnd(endA, findViewById(R.id.endA));
        bindEnd(endB, findViewById(R.id.endB));
        if (devices.isEmpty()) Toast.makeText(this, R.string.msg_fl_no_devices, Toast.LENGTH_LONG).show();

        findViewById(R.id.btnFlAnalyze).setOnClickListener(v -> runAnalysis());
        findViewById(R.id.btnFlShare).setOnClickListener(v -> shareReport());
        findViewById(R.id.btnFlSave).setOnClickListener(v -> saveResult());
        findViewById(R.id.btnFlHistory).setOnClickListener(v -> showHistory());
        setupSyncControls();

        inboxDir = new File(getCacheDir(), "fl_inbox_" + System.currentTimeMillis());
        if (savedInstanceState == null) {
            restoreSession();
            handleOpenWith(getIntent());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession();
    }

    /** launchMode singleTask: a file opened with this screen while it is open lands here. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenWith(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        Iec61850DfrClient c = openClient;
        if (c != null) new Thread(c::disconnect).start();
    }

    // ------------------------------------------------------------------
    // Relay database / end selection
    // ------------------------------------------------------------------

    private void loadDevices() {
        devices.clear();
        giNames.clear();
        TreeSet<String> gis = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            JSONArray arr = new JSONArray(getSharedPreferences("dfr_prefs", MODE_PRIVATE).getString("device_list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                DeviceListActivity.DeviceItem d = new DeviceListActivity.DeviceItem();
                d.gi = obj.optString("gi", "").replace("\"", "").trim();
                d.bay = obj.optString("bay", "").replace("\"", "").trim();
                d.device = obj.optString("device", "").replace("\"", "").trim();
                d.ip = obj.optString("ip", "").replace("\"", "").trim();
                d.merk = obj.optString("merk", "").trim();
                d.type = obj.optString("type", "").trim();
                if (d.ip.isEmpty()) continue;
                devices.add(d);
                if (!d.gi.isEmpty()) gis.add(d.gi);
            }
        } catch (Exception ignored) { }
        giNames.addAll(gis);
    }

    private void bindEnd(EndState st, View root) {
        st.root = root;
        st.spGi = root.findViewById(R.id.spFlGi);
        st.spRelay = root.findViewById(R.id.spFlRelay);
        st.tvRecord = root.findViewById(R.id.tvFlRecord);
        st.tvStatus = root.findViewById(R.id.tvFlEndStatus);
        st.actions = root.findViewById(R.id.layoutFlRecordActions);
        st.btnOpenViewer = root.findViewById(R.id.btnFlOpenViewer);
        ((TextView) root.findViewById(R.id.tvFlEndBadge)).setText(st.isA ? "A" : "B");
        ((TextView) root.findViewById(R.id.tvFlEndTitle)).setText(st.isA ? R.string.lbl_fl_end_a : R.string.lbl_fl_end_b);

        st.manual = loadManual(st);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String lastIp = prefs.getString(st.isA ? "last_ip_a" : "last_ip_b", "");
        if (st.manual != null && prefs.getBoolean(manualSelKey(st), false)) {
            fillGiSpinner(st, 0);
        } else {
            DeviceListActivity.DeviceItem last = findDevice(lastIp);
            int giIdx = last != null ? giNames.indexOf(last.gi) : (!st.isA && giNames.size() > 1 ? 1 : 0);
            fillGiSpinner(st, Math.max(0, giIdx) + giOffset(st));
        }
        populateRelays(st, lastIp);

        // No "skip the first callback" trick here: setSelection(.., false) above already lays the
        // spinner out and consumes its initial event, so the first callback that arrives is the
        // user's own pick. Both handlers are instead safe to run on a repeated/identical event.
        st.spGi.setOnItemSelectedListener(new Selection(() -> {
            if (!TextUtils.equals(giKey(st), st.relaysForGi)) populateRelays(st, null);
        }));
        st.spRelay.setOnItemSelectedListener(new Selection(() -> onRelayChanged(st)));

        root.findViewById(R.id.btnFlManual).setOnClickListener(v -> showManualDialog(st, null));
        root.findViewById(R.id.btnFlScan).setOnClickListener(v -> onScanClicked(st));
        root.findViewById(R.id.btnFlLocal).setOnClickListener(v -> onLocalClicked(st));
        root.findViewById(R.id.btnFlMapping).setOnClickListener(v -> showMappingDialog(st));
        st.btnOpenViewer.setOnClickListener(v -> openInViewer(st));
        restoreLineParams();
    }

    /** The GI spinner: this end's manual entry (if any) first, then the Device List's GIs. */
    private void fillGiSpinner(EndState st, int selection) {
        List<String> items = new ArrayList<>();
        if (st.manual != null) items.add(getString(R.string.lbl_fl_manual_gi_item, st.manual.gi));
        items.addAll(giNames);
        if (items.isEmpty()) items.add(getString(R.string.lbl_fl_no_devices_item));
        st.spGi.setAdapter(styledAdapter(items));
        st.spGi.setSelection(Math.max(0, Math.min(selection, items.size() - 1)), false);
    }

    private int giOffset(EndState st) {
        return st.manual != null ? 1 : 0;
    }

    private boolean manualSelected(EndState st) {
        return st.manual != null && st.spGi.getSelectedItemPosition() == 0;
    }

    /** What the relay spinner is filled for: the selected GI, or this end's manual entry. */
    private String giKey(EndState st) {
        return manualSelected(st) ? "#manual#" : selectedGi(st);
    }

    @Nullable
    private String selectedGi(EndState st) {
        if (manualSelected(st)) return st.manual.gi;
        int idx = st.spGi.getSelectedItemPosition() - giOffset(st);
        return idx >= 0 && idx < giNames.size() ? giNames.get(idx) : null;
    }

    private void populateRelays(EndState st, @Nullable String preferIp) {
        st.relays.clear();
        st.relaysForGi = giKey(st);
        List<String> labels = new ArrayList<>();
        int sel = 0;
        if (manualSelected(st)) {
            st.relays.add(st.manual);
            labels.add((st.manual.bay.isEmpty() ? getString(R.string.lbl_fl_manual_relay) : st.manual.bay)
                    + "  (" + (st.manual.ip.isEmpty() ? getString(R.string.lbl_fl_manual_no_ip) : st.manual.ip) + ")");
        } else {
            String gi = selectedGi(st);
            for (DeviceListActivity.DeviceItem d : devices) if (gi != null && gi.equalsIgnoreCase(d.gi)) st.relays.add(d);
            for (int i = 0; i < st.relays.size(); i++) {
                DeviceListActivity.DeviceItem d = st.relays.get(i);
                labels.add(d.bay + " • " + d.device + "  (" + d.ip + ")");
                if (d.ip.equals(preferIp)) sel = i;
            }
        }
        if (labels.isEmpty()) labels.add(getString(R.string.lbl_fl_no_devices_item));
        st.spRelay.setAdapter(styledAdapter(labels));
        st.spRelay.setSelection(sel, false);
        // A new adapter whose selection stays at the same position fires no selection callback,
        // so a GI change has to report the (possibly new) relay itself.
        if (preferIp == null) onRelayChanged(st);
    }

    private void onRelayChanged(EndState st) {
        DeviceListActivity.DeviceItem d = selectedRelay(st);
        if (d == null) return;
        android.content.SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(manualSelKey(st), d == st.manual);
        if (d != st.manual) e.putString(st.isA ? "last_ip_a" : "last_ip_b", d.ip);
        e.apply();
        restoreLineParams();
    }

    // ------------------------------------------------------------------
    // GI / relay typed by hand (not in the Device List)
    // ------------------------------------------------------------------

    private String manualKey(EndState st) {
        return st.isA ? "manual_end_a" : "manual_end_b";
    }

    private String manualSelKey(EndState st) {
        return st.isA ? "manual_sel_a" : "manual_sel_b";
    }

    @Nullable
    private DeviceListActivity.DeviceItem loadManual(EndState st) {
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(manualKey(st), null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            DeviceListActivity.DeviceItem d = new DeviceListActivity.DeviceItem();
            d.gi = o.optString("gi", "");
            d.bay = o.optString("bay", "");
            d.ip = o.optString("ip", "");
            d.device = getString(R.string.lbl_fl_manual_device);
            return d.gi.isEmpty() ? null : d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Mapping / line memory key of a relay: its IP, or GI + bay for a manual entry without one. */
    private static String relayKey(DeviceListActivity.DeviceItem d) {
        return !d.ip.isEmpty() ? d.ip : "manual:" + d.gi + "|" + d.bay;
    }

    /**
     * Name (and optionally bay + relay IP) of this end typed by hand, for a GI or relay that
     * isn't in the Device List yet. `onSaved` runs after it was saved or removed.
     */
    private void showManualDialog(EndState st, @Nullable Runnable onSaved) {
        View v = getLayoutInflater().inflate(R.layout.dialog_fl_manual_end, null);
        EditText etGi = v.findViewById(R.id.etFlManualGi), etBay = v.findViewById(R.id.etFlManualBay), etIp = v.findViewById(R.id.etFlManualIp);
        TextInputLayout tilGi = v.findViewById(R.id.tilFlManualGi), tilIp = v.findViewById(R.id.tilFlManualIp);
        if (st.manual != null) {
            etGi.setText(st.manual.gi);
            etBay.setText(st.manual.bay);
            etIp.setText(st.manual.ip);
        } else if (st.rec != null && !st.rec.stationName.isEmpty()) {
            etGi.setText(st.rec.stationName);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(getString(R.string.ttl_fl_manual, getString(st.isA ? R.string.lbl_fl_gi_a_short : R.string.lbl_fl_gi_b_short)))
                .setView(v)
                .setPositiveButton(R.string.btn_all_save_small, null)
                .setNegativeButton(R.string.btn_all_cancel, null);
        if (st.manual != null) {
            b.setNeutralButton(R.string.btn_fl_manual_remove, (d, w) -> {
                st.manual = null;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(manualKey(st)).putBoolean(manualSelKey(st), false).apply();
                fillGiSpinner(st, !st.isA && giNames.size() > 1 ? 1 : 0);
                populateRelays(st, null);
                if (st.rec != null) renderEnd(st);
                if (onSaved != null) onSaved.run();
            });
        }
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(di -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String gi = etGi.getText().toString().trim();
            String bay = etBay.getText().toString().trim();
            String ip = etIp.getText().toString().trim();
            tilGi.setError(gi.isEmpty() ? getString(R.string.err_fl_manual_gi) : null);
            tilIp.setError(!ip.isEmpty() && !DeviceCsv.looksLikeIp(ip) ? getString(R.string.err_fl_manual_ip) : null);
            if (gi.isEmpty() || (!ip.isEmpty() && !DeviceCsv.looksLikeIp(ip))) return;
            DeviceListActivity.DeviceItem m = new DeviceListActivity.DeviceItem();
            m.gi = gi;
            m.bay = bay;
            m.ip = ip;
            m.device = getString(R.string.lbl_fl_manual_device);
            st.manual = m;
            try {
                JSONObject o = new JSONObject();
                o.put("gi", gi);
                o.put("bay", bay);
                o.put("ip", ip);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(manualKey(st), o.toString())
                        .putBoolean(manualSelKey(st), true).apply();
            } catch (Exception ignored) { }
            fillGiSpinner(st, 0);
            populateRelays(st, null);
            if (st.rec != null) renderEnd(st);
            dialog.dismiss();
            if (onSaved != null) onSaved.run();
        }));
        dialog.show();
    }

    @Nullable
    private DeviceListActivity.DeviceItem selectedRelay(EndState st) {
        int pos = st.spRelay.getSelectedItemPosition();
        return pos >= 0 && pos < st.relays.size() ? st.relays.get(pos) : null;
    }

    @Nullable
    private DeviceListActivity.DeviceItem findDevice(String ip) {
        if (TextUtils.isEmpty(ip)) return null;
        for (DeviceListActivity.DeviceItem d : devices) if (d.ip.equals(ip)) return d;
        return null;
    }

    private String endName(EndState st) {
        DeviceListActivity.DeviceItem d = selectedRelay(st);
        if (d != null && !d.gi.isEmpty()) return d.gi;
        if (st.rec != null && !st.rec.stationName.isEmpty()) return st.rec.stationName;
        return getString(st.isA ? R.string.lbl_fl_gi_a_short : R.string.lbl_fl_gi_b_short);
    }

    // ------------------------------------------------------------------
    // Download from relay
    // ------------------------------------------------------------------

    private void onScanClicked(EndState st) {
        DeviceListActivity.DeviceItem d = selectedRelay(st);
        if (d == null) { // nothing in the Device List for this end: type the relay in by hand
            Toast.makeText(this, R.string.msg_fl_pick_relay_first, Toast.LENGTH_SHORT).show();
            showManualDialog(st, null);
            return;
        }
        if (d.ip.isEmpty()) { // manual GI without a relay IP: ask for it right away
            Toast.makeText(this, R.string.msg_fl_manual_need_ip, Toast.LENGTH_LONG).show();
            showManualDialog(st, null);
            return;
        }
        showProgress(getString(R.string.msg_fl_connecting, d.ip));
        executor.execute(() -> {
            Iec61850DfrClient client = new Iec61850DfrClient();
            openClient = client;
            int port = ConfigHelper.getMmsPort(this);
            int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
            // Same three ISO-stack profiles as the Download screen's profile spinner, tried in
            // turn so the user doesn't have to know which one this relay vendor needs.
            boolean ok = false;
            for (int profile = 0; profile <= 2 && !ok; profile++) {
                if (profile > 0) showProgress(getString(R.string.msg_fl_connect_retry, d.ip, profile));
                ok = client.connect(d.ip, port, timeout, profile);
            }
            if (!ok) {
                String why = client.getLastError();
                openClient = null;
                runOnUiThread(() -> {
                    hideProgress();
                    showInfoDialog(getString(R.string.ttl_fl_error), getString(R.string.msg_fl_connect_fail, d.ip, why));
                });
                return;
            }
            ComtradeSmartSearch.Logger logger = message -> showProgress(message.replaceFirst("^[!iv]\\s*", ""));
            showProgress(getString(R.string.msg_dl_detecting_vendor));
            ComtradeSmartSearch.VendorInfo vendor = ComtradeSmartSearch.detectVendor(this, client, logger);
            showProgress(getString(R.string.msg_dl_scanning));
            ComtradeSmartSearch.ScanResult scan = ComtradeSmartSearch.scan(this, client, vendor.deepScan, logger);
            runOnUiThread(() -> {
                hideProgress();
                if (isGone() || scan.targetFiles.isEmpty()) {
                    if (!isGone()) Toast.makeText(this, R.string.msg_fl_no_remote_files, Toast.LENGTH_LONG).show();
                    disconnectLater(client);
                    return;
                }
                showRemotePicker(st, d, client, scan);
            });
        });
    }

    private void showRemotePicker(EndState st, DeviceListActivity.DeviceItem d, Iec61850DfrClient client, ComtradeSmartSearch.ScanResult scan) {
        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(getString(R.string.ttl_fl_pick_record, d.gi + " • " + d.bay));
        LinearLayout container = v.findViewById(R.id.llPickListItems);

        DfrFileEntry closest = closestToOtherEnd(st, scan.targetFiles);
        final boolean[] picked = {false};
        // Opens on the page holding the record closest in time to the other end's, if any.
        int startPage = closest != null ? ListPager.pageOf(scan.targetFiles.indexOf(closest)) : 0;
        showPaged(container, null, scan.targetFiles.size(), startPage, i -> {
            DfrFileEntry entry = scan.targetFiles.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
            ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText("#" + (i + 1) + "  " + entry.cleanName());
            String sub = entry.formattedDate() + "  •  " + entry.formattedSize();
            if (entry == closest) sub += "\n" + getString(R.string.lbl_fl_closest_match);
            ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(sub);
            row.setOnClickListener(view -> {
                picked[0] = true;
                dialog.dismiss();
                downloadRemote(st, d, client, entry, scan.allFiles);
            });
            return row;
        });
        v.findViewById(R.id.btnPickListCancel).setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(di -> { if (!picked[0]) disconnectLater(client); });
        dialog.show();
    }

    private interface RowFactory {
        View row(int index);
    }

    /**
     * Fills a pick-list dialog's `container` one ListPager page at a time: `header` (if any) stays
     * on top, then the pager, then only that page's rows.
     */
    private void showPaged(LinearLayout container, @Nullable View header, int total, int page, RowFactory rows) {
        int p = ListPager.clamp(page, total);
        container.removeAllViews();
        if (header != null) container.addView(header);
        View pager = ListPager.build(this, p, total, next -> showPaged(container, header, total, next, rows));
        if (pager != null) container.addView(pager);
        for (int i = ListPager.from(p); i < ListPager.to(p, total); i++) container.addView(rows.row(i));
        if (container.getParent() instanceof View) ((View) container.getParent()).scrollTo(0, 0);
    }

    /**
     * The record on this relay most likely to be the same event as the one already loaded for the
     * other end. MMS file times and cfg timestamps can be in different time zones (relay UTC vs
     * local), so the distance is taken modulo whole hours - this is only a visual hint.
     */
    @Nullable
    private DfrFileEntry closestToOtherEnd(EndState st, List<DfrFileEntry> files) {
        EndState other = st.isA ? endB : endA;
        long ref;
        if (other.remoteFileTime > 0) ref = other.remoteFileTime;
        else if (other.rec != null && other.rec.triggerMicros != Long.MIN_VALUE) ref = other.rec.triggerMicros / 1000;
        else return null;
        DfrFileEntry best = null;
        long bestDiff = Long.MAX_VALUE;
        for (DfrFileEntry f : files) {
            if (f.lastModifiedEpochMillis <= 0) continue;
            long diff = Math.abs(f.lastModifiedEpochMillis - ref) % 3_600_000L;
            diff = Math.min(diff, 3_600_000L - diff);
            if (diff < bestDiff) { bestDiff = diff; best = f; }
        }
        return bestDiff <= 5 * 60_000L ? best : null;
    }

    private File outputDir(@Nullable DeviceListActivity.DeviceItem d) {
        String sub = "General";
        if (d != null && !TextUtils.isEmpty(d.gi) && !TextUtils.isEmpty(d.bay)) {
            sub = (d.gi + "_" + d.bay).replaceAll("[^a-zA-Z0-9_]", "_");
        }
        File target = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DFR"), sub);
        if (target.exists() || target.mkdirs()) return target;
        File fallback = new File(new File(getExternalFilesDir(null), "DFR"), sub);
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    private void downloadRemote(EndState st, DeviceListActivity.DeviceItem d, Iec61850DfrClient client,
                                DfrFileEntry target, List<DfrFileEntry> allFiles) {
        showProgress(getString(R.string.msg_dl_downloading_file, target.cleanName()));
        executor.execute(() -> {
            File outDir = outputDir(d);
            List<File> saved = new ArrayList<>();
            try {
                for (DfrFileEntry f : ComtradeSmartSearch.resolveFileSet(target, allFiles)) {
                    String localName = f.cleanName();
                    File out = new File(outDir, localName);
                    showProgress(getString(R.string.msg_dl_downloading_file, localName));
                    if (client.downloadFile(f.fullPath(), out.getAbsolutePath())) saved.add(out);
                }
            } finally {
                client.disconnect();
                openClient = null;
            }
            if (saved.isEmpty()) {
                runOnUiThread(() -> {
                    hideProgress();
                    Toast.makeText(this, R.string.msg_fl_download_failed, Toast.LENGTH_LONG).show();
                });
                return;
            }
            List<ComtradeImport.Record> recs = resolveRecords(saved, null);
            loadRecordInBackground(st, recs.isEmpty() ? null : pairOf(recs.get(0)), target.lastModifiedEpochMillis);
        });
    }

    /**
     * The .cfg/.dat records in a downloaded/imported file set, plain pairs first, then those in
     * .zip/.rar archives (unpacked into a folder beside each). The first archive failure, if any,
     * goes to error[0]. Must run on the executor.
     */
    private List<ComtradeImport.Record> resolveRecords(List<File> files, @Nullable Exception[] error) {
        List<File> all = new ArrayList<>();
        for (File f : files) if (!ComtradeImport.isArchive(f.getName())) all.add(f);
        for (File f : files) {
            if (!ComtradeImport.isArchive(f.getName())) continue;
            try {
                showProgress(getString(R.string.msg_fl_extracting, f.getName()));
                all.addAll(ComtradeImport.unpack(f, new File(f.getParentFile(), ComtradeImport.baseName(f.getName()))));
            } catch (Exception e) {
                if (error != null && error[0] == null) error[0] = e;
            }
        }
        return ComtradeImport.findRecords(all);
    }

    private static File[] pairOf(ComtradeImport.Record rec) {
        return new File[]{rec.cfg, rec.dat};
    }

    /**
     * Loads `recs` into `st`: the only one directly, otherwise the one the user picks. Must run on
     * the executor.
     */
    private void deliverRecords(EndState st, List<ComtradeImport.Record> recs, @Nullable Exception error) {
        if (recs.isEmpty()) {
            runOnUiThread(() -> {
                hideProgress();
                showInfoDialog(getString(R.string.ttl_fl_error), error != null
                        ? ComtradeImport.describeError(this, error) : getString(R.string.msg_fl_no_cfg_in_files));
            });
        } else if (recs.size() == 1) {
            loadRecordInBackground(st, pairOf(recs.get(0)), 0);
        } else {
            runOnUiThread(() -> {
                hideProgress();
                pickImportedRecord(endShortName(st), recs, null, rec -> loadInto(st, rec));
            });
        }
    }

    private void loadInto(EndState st, ComtradeImport.Record rec) {
        if (isGone()) return; // a pick list's dismiss listener also fires when the screen closes
        showProgress(getString(R.string.msg_fl_reading, rec.cfg.getName()));
        executor.execute(() -> loadRecordInBackground(st, pairOf(rec), 0));
    }

    private String endShortName(EndState st) {
        return getString(st.isA ? R.string.lbl_fl_gi_a_short : R.string.lbl_fl_gi_b_short);
    }

    private interface RecordChoice {
        void onChosen(ComtradeImport.Record rec);
    }

    /**
     * Lists the records found in an archive/file set for one end. `onSkip` (when non-null) is run
     * if the list is closed without a pick.
     */
    private void pickImportedRecord(String endLabel, List<ComtradeImport.Record> recs, @Nullable Runnable onSkip, RecordChoice choice) {
        if (isGone()) return;
        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(getString(R.string.ttl_fl_pick_record, endLabel));
        LinearLayout container = v.findViewById(R.id.llPickListItems);
        File root = commonParent(recs);
        final boolean[] picked = {false};
        showPaged(container, null, recs.size(), 0, i -> {
            ComtradeImport.Record rec = recs.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
            ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText(rec.relativeName(root));
            ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(rec.station.isEmpty() ? "-" : rec.station);
            row.setOnClickListener(view -> {
                picked[0] = true;
                dialog.dismiss();
                choice.onChosen(rec);
            });
            return row;
        });
        v.findViewById(R.id.btnPickListCancel).setOnClickListener(view -> dialog.dismiss());
        if (onSkip != null) dialog.setOnDismissListener(di -> { if (!picked[0]) onSkip.run(); });
        dialog.show();
    }

    /** Deepest folder holding every record's .cfg, so the pick list can show short paths. */
    private static File commonParent(List<ComtradeImport.Record> recs) {
        File root = recs.get(0).cfg.getParentFile();
        for (ComtradeImport.Record r : recs) {
            while (root != null && !r.cfg.getAbsolutePath().startsWith(root.getAbsolutePath() + File.separator)) {
                root = root.getParentFile();
            }
        }
        return root != null ? root : new File("/");
    }

    /** Must run on the executor. */
    private void loadRecordInBackground(EndState st, @Nullable File[] pair, long remoteTime) {
        if (pair == null) {
            runOnUiThread(() -> {
                hideProgress();
                showInfoDialog(getString(R.string.ttl_fl_error), getString(R.string.msg_fl_no_cfg_in_files));
            });
            return;
        }
        showProgress(getString(R.string.msg_fl_reading, pair[0].getName()));
        try {
            ComtradeRecord rec = ComtradeRecord.load(pair[0], pair[1]);
            ChannelMap map = restoreMapping(st, rec);
            runOnUiThread(() -> {
                hideProgress();
                if (isGone()) return;
                st.cfg = pair[0];
                st.dat = pair[1];
                st.rec = rec;
                st.map = map;
                st.remoteFileTime = remoteTime;
                renderEnd(st);
                if (!map.complete(rec.analogCount())) showMappingDialog(st);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                hideProgress();
                showInfoDialog(getString(R.string.ttl_fl_error), getString(R.string.msg_fl_load_fail, String.valueOf(e.getMessage())));
            });
        }
    }

    // ------------------------------------------------------------------
    // Local records
    // ------------------------------------------------------------------

    private void onLocalClicked(EndState st) {
        DeviceListActivity.DeviceItem d = selectedRelay(st);
        String preferFolder = d != null ? outputDir(d).getName() : null;
        List<File> records = new ArrayList<>();
        File[] roots = {
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DFR"),
                new File(getExternalFilesDir(null), "DFR")
        };
        for (File root : roots) collectRecords(root, records, 0);
        Collections.sort(records, (x, y) -> {
            boolean px = x.getParentFile() != null && x.getParentFile().getName().equals(preferFolder);
            boolean py = y.getParentFile() != null && y.getParentFile().getName().equals(preferFolder);
            if (px != py) return px ? -1 : 1;
            return Long.compare(y.lastModified(), x.lastModified());
        });

        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(getString(R.string.ttl_fl_local_pick, getString(st.isA ? R.string.lbl_fl_gi_a_short : R.string.lbl_fl_gi_b_short)));
        TextView info = v.findViewById(R.id.tvPickListInfo);
        info.setText(R.string.lbl_fl_local_formats);
        info.setVisibility(View.VISIBLE);
        LinearLayout container = v.findViewById(R.id.llPickListItems);

        // Top rows kept on every page: this end's GI name (editable for a GI not in the Device
        // List), then "browse storage".
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        View nameRow = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, header, false);
        TextView nameTitle = nameRow.findViewById(R.id.tvPickRowTitle);
        nameTitle.setText(getString(R.string.lbl_fl_local_name_title, endName(st)));
        ((TextView) nameRow.findViewById(R.id.tvPickRowSubtitle)).setText(R.string.lbl_fl_local_name_sub);
        nameRow.setOnClickListener(view -> showManualDialog(st,
                () -> nameTitle.setText(getString(R.string.lbl_fl_local_name_title, endName(st)))));
        header.addView(nameRow);

        View browse = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
        ((TextView) browse.findViewById(R.id.tvPickRowTitle)).setText(R.string.lbl_fl_pick_storage);
        ((TextView) browse.findViewById(R.id.tvPickRowSubtitle)).setText(R.string.lbl_fl_pick_storage_sub);
        browse.setOnClickListener(view -> {
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(intent, st.isA ? REQ_STORAGE_A : REQ_STORAGE_B);
            } catch (Exception e) {
                Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
            }
        });
        header.addView(browse);
        if (records.isEmpty()) {
            container.addView(header);
            TextView empty = new TextView(this);
            empty.setText(R.string.msg_fl_no_local);
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            container.addView(empty);
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault());
            showPaged(container, header, records.size(), 0, i -> {
                File f = records.get(i);
                View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
                ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText(f.getName());
                String folder = f.getParentFile() != null ? f.getParentFile().getName() : "";
                ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(folder + "  •  " + fmt.format(new Date(f.lastModified())));
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    showProgress(getString(R.string.msg_fl_reading, f.getName()));
                    executor.execute(() -> {
                        Exception[] error = new Exception[1];
                        deliverRecords(st, resolveRecords(Collections.singletonList(f), error), error[0]);
                    });
                });
                return row;
            });
        }
        v.findViewById(R.id.btnPickListCancel).setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    /** .cfg files that have a .dat next to them, plus .zip/.rar archives (not a vendor's "h.zip" pair). */
    private void collectRecords(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < 2) collectRecords(f, out, depth + 1);
                continue;
            }
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".cfg") && ComtradeRecord.findDatFor(f) != null) out.add(f);
            else if (lower.endsWith(".rar") || (lower.endsWith(".zip") && !lower.endsWith("h.zip"))) out.add(f);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_LINE) {
            if (resultCode != RESULT_OK || data == null) return;
            LineData d = new LineDataManager(this).get(data.getStringExtra(LineDatabaseActivity.EXTRA_RESULT_ID));
            if (d != null) applyLineData(d, false);
            return;
        }
        boolean pair = requestCode == REQ_PAIR_A || requestCode == REQ_PAIR_B;
        if ((requestCode != REQ_STORAGE_A && requestCode != REQ_STORAGE_B && !pair) || resultCode != RESULT_OK || data == null) return;
        EndState st = requestCode == REQ_STORAGE_A || requestCode == REQ_PAIR_A ? endA : endB;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        // The partner picked after "pick its .dat" joins the files already copied for this end.
        File pairDir = pair ? pendingPairDir : null;
        showProgress(getString(R.string.msg_fl_reading, ""));
        executor.execute(() -> {
            // Copied into private storage so the .cfg/.dat pairing can use plain File lookups no
            // matter which document provider the files came from.
            File dir = pairDir != null ? pairDir
                    : new File(getCacheDir(), "fl_import_" + (st.isA ? "a" : "b") + "_" + System.currentTimeMillis());
            dir.mkdirs();
            List<File> copied = new ArrayList<>();
            Uri lone = null;
            String loneName = null;
            for (Uri uri : uris) {
                String name = ComtradeImport.displayName(this, uri);
                File out = new File(dir, ComtradeImport.safeName(name));
                try {
                    ComtradeImport.copy(this, uri, out);
                    copied.add(out);
                } catch (Exception ignored) {
                    continue;
                }
                // Pickers only hand over the files that were ticked: a .cfg picked alone still
                // needs the .dat from its folder (and the other way round).
                String lower = ComtradeImport.lower(name);
                if ((lower.endsWith(".cfg") || lower.endsWith(".dat")) && ComtradeImport.pairFor(out) == null) {
                    File partner = ComtradeImport.fetchPartner(this, uri, name, dir);
                    if (partner != null) copied.add(partner);
                    else if (lone == null) { lone = uri; loneName = name; }
                }
            }
            if (pairDir != null) {
                File[] all = dir.listFiles();
                if (all != null) for (File f : all) if (!copied.contains(f)) copied.add(f);
            }
            Exception[] error = new Exception[1];
            List<ComtradeImport.Record> recs = resolveRecords(copied, error);
            if (recs.isEmpty() && lone != null && error[0] == null) {
                Uri missingFor = lone;
                String missingName = loneName;
                runOnUiThread(() -> {
                    hideProgress();
                    askForPartner(st, dir, missingFor, missingName);
                });
                return;
            }
            deliverRecords(st, recs, error[0]);
        });
    }

    /**
     * The partner of a lone .cfg/.dat couldn't be read from its folder (no storage access to it):
     * let the user pick it, starting the picker in that same folder.
     */
    private void askForPartner(EndState st, File dir, Uri lone, String loneName) {
        if (isGone()) return;
        String lower = ComtradeImport.lower(loneName);
        String partner = ComtradeImport.baseName(loneName) + (lower.endsWith(".cfg") ? ".dat" : ".cfg");
        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_fl_pick_partner)
                .setMessage(getString(R.string.msg_fl_pick_partner, loneName, partner))
                .setPositiveButton(R.string.btn_fl_pick_partner, (d, w) -> {
                    pendingPairDir = dir;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    if (android.os.Build.VERSION.SDK_INT >= 26) intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, lone);
                    try {
                        startActivityForResult(intent, st.isA ? REQ_PAIR_A : REQ_PAIR_B);
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // "Open with" from other apps (manifest intent filters)
    // ------------------------------------------------------------------

    /**
     * A .zip/.rar, .cfg or .dat opened with this screen from a file manager or chat app. Archives
     * are unpacked into DFR/Imported. A lone .cfg/.dat waits in this screen's inbox until its
     * partner is opened too (chat apps send them as two separate files).
     */
    private void handleOpenWith(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        Uri uri = intent.getData();
        showProgress(getString(R.string.msg_fl_reading, ""));
        executor.execute(() -> {
            String name = ComtradeImport.displayName(this, uri);
            String lower = name.toLowerCase(Locale.ROOT);
            try {
                if (ComtradeImport.isArchive(lower)) {
                    showProgress(getString(R.string.msg_fl_extracting, name));
                    List<ComtradeImport.Record> recs = ComtradeImport.importArchive(this, uri, name);
                    runOnUiThread(() -> {
                        hideProgress();
                        if (recs.isEmpty()) showInfoDialog(getString(R.string.ttl_fl_error), getString(R.string.msg_import_no_record, name));
                        else assignOpenedRecords(recs);
                    });
                } else if (lower.endsWith(".cfg") || lower.endsWith(".dat")) {
                    File out = new File(inboxDir, ComtradeImport.safeName(name));
                    ComtradeImport.copy(this, uri, out);
                    ComtradeImport.Record rec = ComtradeImport.pairFor(out);
                    // Opened from a file manager: its partner usually sits in the same folder.
                    if (rec == null && ComtradeImport.fetchPartner(this, uri, name, inboxDir) != null) {
                        rec = ComtradeImport.pairFor(out);
                    }
                    ComtradeImport.Record found = rec;
                    runOnUiThread(() -> {
                        hideProgress();
                        if (found != null) {
                            assignOpenedRecords(Collections.singletonList(found));
                        } else {
                            String partner = ComtradeImport.baseName(name) + (lower.endsWith(".cfg") ? ".dat" : ".cfg");
                            showInfoDialog(getString(R.string.ttl_fl_open_with), getString(R.string.msg_fl_waiting_pair, name, partner));
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        hideProgress();
                        showInfoDialog(getString(R.string.ttl_fl_error), getString(R.string.msg_import_unsupported, name));
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideProgress();
                    showInfoDialog(getString(R.string.ttl_fl_error), ComtradeImport.describeError(this, e));
                });
            }
        });
    }

    /** One record: ask which end it is. Several: pick the record for end A, then for end B. */
    private void assignOpenedRecords(List<ComtradeImport.Record> recs) {
        if (recs.size() == 1) {
            askEndFor(recs.get(0));
            return;
        }
        pickImportedRecord(endShortName(endA), recs, null, recA -> {
            List<ComtradeImport.Record> rest = new ArrayList<>(recs);
            rest.remove(recA);
            // Closing the second list loads end A alone.
            pickImportedRecord(endShortName(endB), rest, () -> loadInto(endA, recA), recB -> {
                loadInto(endA, recA);
                loadInto(endB, recB);
            });
        });
    }

    private void askEndFor(ComtradeImport.Record rec) {
        if (isGone()) return;
        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(getString(R.string.ttl_fl_load_as, rec.cfg.getName()));
        LinearLayout container = v.findViewById(R.id.llPickListItems);
        for (EndState st : new EndState[]{endA, endB}) {
            View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
            ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText(st.isA ? R.string.lbl_fl_end_a : R.string.lbl_fl_end_b);
            ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(st.cfg != null
                    ? getString(R.string.lbl_fl_end_replace, st.cfg.getName()) : getString(R.string.msg_fl_no_record));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                loadInto(st, rec);
            });
            container.addView(row);
        }
        v.findViewById(R.id.btnPickListCancel).setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    // ------------------------------------------------------------------
    // Record summary + channel mapping
    // ------------------------------------------------------------------

    private void renderEnd(EndState st) {
        if (st.rec == null) {
            st.tvRecord.setText(R.string.msg_fl_no_record);
            st.actions.setVisibility(View.GONE);
            st.tvStatus.setVisibility(View.GONE);
            return;
        }
        ComtradeRecord r = st.rec;
        String info = getString(R.string.msg_fl_record_info, st.cfg.getName(),
                r.stationName.isEmpty() ? "-" : r.stationName, r.triggerText.isEmpty() ? "-" : r.triggerText,
                fmt(estimateFs(r), 0), r.analogCount(), r.fileType);
        boolean complete = st.map != null && st.map.complete(r.analogCount());
        String mapping = complete
                ? getString(R.string.msg_fl_mapping_summary, chName(r, st.map.va), chName(r, st.map.vb), chName(r, st.map.vc),
                chName(r, st.map.ia), chName(r, st.map.ib), chName(r, st.map.ic))
                : getString(R.string.msg_fl_mapping_incomplete);
        if (complete && st.map.invertCurrent) mapping += "  " + getString(R.string.lbl_fl_inverted_tag);
        String values = complete && missingRatio(r, st.map) && st.map.vMult == 1 && st.map.iMult == 1
                ? getString(R.string.lbl_fl_values_no_ratio) : getString(R.string.lbl_fl_values_primary);
        st.tvRecord.setText(info + "\n" + mapping + "\n" + values);
        st.actions.setVisibility(View.VISIBLE);
        st.btnOpenViewer.setVisibility(r.fileType.startsWith("ASCII") ? View.VISIBLE : View.GONE);
        st.tvStatus.setVisibility(View.VISIBLE);
        st.tvStatus.setText(complete ? R.string.lbl_fl_status_ready : R.string.lbl_fl_status_check_map);
        st.tvStatus.setTextColor(ContextCompat.getColor(this, complete ? R.color.status_safe : R.color.status_warning));
    }

    private static double estimateFs(ComtradeRecord r) {
        if (r.sampleCount < 2) return 0;
        double span = r.time[r.sampleCount - 1] - r.time[0];
        return span > 0 ? (r.sampleCount - 1) / span : 0;
    }

    private static String chName(ComtradeRecord r, int idx) {
        return idx >= 0 && idx < r.analogCount() ? r.analogs.get(idx).id : "?";
    }

    private ChannelMap restoreMapping(EndState st, ComtradeRecord rec) {
        ChannelMap auto = DoubleEndedFaultLocator.autoMap(rec);
        DeviceListActivity.DeviceItem d = selectedRelay(st);
        if (d == null) return auto;
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString("map_" + relayKey(d), null);
        if (json == null) return auto;
        try {
            JSONObject o = new JSONObject(json);
            ChannelMap m = auto.copy();
            int[] idx = new int[6];
            for (int k = 0; k < 6; k++) {
                idx[k] = indexOfChannel(rec, o.optString(ROLE_LABELS[k], ""));
                if (idx[k] < 0) return auto; // this record doesn't have the saved channels
            }
            m.va = idx[0]; m.vb = idx[1]; m.vc = idx[2]; m.ia = idx[3]; m.ib = idx[4]; m.ic = idx[5];
            m.invertCurrent = o.optBoolean("invert", false);
            m.vMult = o.optDouble("vMult", 1);
            m.iMult = o.optDouble("iMult", 1);
            return m;
        } catch (Exception e) {
            return auto;
        }
    }

    private static int indexOfChannel(ComtradeRecord rec, String id) {
        for (int i = 0; i < rec.analogCount(); i++) if (rec.analogs.get(i).id.equals(id)) return i;
        return -1;
    }

    private void saveMapping(EndState st) {
        DeviceListActivity.DeviceItem d = selectedRelay(st);
        if (d == null || st.rec == null || st.map == null) return;
        try {
            JSONObject o = new JSONObject();
            int[] idx = {st.map.va, st.map.vb, st.map.vc, st.map.ia, st.map.ib, st.map.ic};
            for (int k = 0; k < 6; k++) o.put(ROLE_LABELS[k], chName(st.rec, idx[k]));
            o.put("invert", st.map.invertCurrent);
            o.put("vMult", st.map.vMult);
            o.put("iMult", st.map.iMult);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("map_" + relayKey(d), o.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void showMappingDialog(EndState st) {
        if (st.rec == null) return;
        ComtradeRecord rec = st.rec;
        ChannelMap working = st.map != null ? st.map.copy() : DoubleEndedFaultLocator.autoMap(rec);
        View v = getLayoutInflater().inflate(R.layout.dialog_fl_mapping, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvFlMapTitle)).setText(getString(R.string.ttl_fl_mapping, endName(st)));
        ((TextView) v.findViewById(R.id.tvFlMapScaling)).setText(scalingDescription(rec, working));

        List<String> options = new ArrayList<>();
        options.add(getString(R.string.lbl_fl_none_option));
        for (ComtradeRecord.AnalogChannel ch : rec.analogs) options.add(ch.label());
        LinearLayout rows = v.findViewById(R.id.containerFlMapRows);
        Spinner[] spinners = new Spinner[6];
        for (int k = 0; k < 6; k++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            TextView label = new TextView(this);
            label.setText(ROLE_LABELS[k]);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            label.setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary));
            label.setWidth(dp(40));
            row.addView(label);
            Spinner sp = new Spinner(this);
            sp.setBackgroundResource(R.drawable.bg_spinner);
            sp.setAdapter(styledAdapter(options));
            row.addView(sp, new LinearLayout.LayoutParams(0, dp(40), 1f));
            rows.addView(row);
            spinners[k] = sp;
        }
        SwitchCompat swInvert = v.findViewById(R.id.swFlInvert);
        EditText etV = v.findViewById(R.id.etFlVMult), etI = v.findViewById(R.id.etFlIMult);
        Runnable fill = () -> {
            int[] idx = {working.va, working.vb, working.vc, working.ia, working.ib, working.ic};
            for (int k = 0; k < 6; k++) spinners[k].setSelection(idx[k] + 1);
            swInvert.setChecked(working.invertCurrent);
            etV.setText(fmtPlain(working.vMult));
            etI.setText(fmtPlain(working.iMult));
        };
        fill.run();
        // Values always come from the DFR as primary: the manual multipliers are only offered
        // when the cfg has secondary values without a VT/CT ratio (or were already used).
        v.findViewById(R.id.layoutFlMult).setVisibility(
                missingRatio(rec, working) || working.vMult != 1 || working.iMult != 1 ? View.VISIBLE : View.GONE);

        v.findViewById(R.id.btnFlMapAuto).setOnClickListener(view -> {
            ChannelMap auto = DoubleEndedFaultLocator.autoMap(rec);
            working.va = auto.va; working.vb = auto.vb; working.vc = auto.vc;
            working.ia = auto.ia; working.ib = auto.ib; working.ic = auto.ic;
            fill.run();
        });
        v.findViewById(R.id.btnFlMapCancel).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnFlMapSave).setOnClickListener(view -> {
            ChannelMap m = new ChannelMap();
            int[] idx = new int[6];
            for (int k = 0; k < 6; k++) idx[k] = spinners[k].getSelectedItemPosition() - 1;
            m.va = idx[0]; m.vb = idx[1]; m.vc = idx[2]; m.ia = idx[3]; m.ib = idx[4]; m.ic = idx[5];
            m.invertCurrent = swInvert.isChecked();
            m.vMult = parsePositive(etV.getText().toString(), 1);
            m.iMult = parsePositive(etI.getText().toString(), 1);
            st.map = m;
            saveMapping(st);
            renderEnd(st);
            dialog.dismiss();
        });
        dialog.show();
    }

    private static boolean missingRatio(ComtradeRecord rec, ChannelMap map) {
        for (int idx : new int[]{map.va, map.vb, map.vc, map.ia, map.ib, map.ic}) {
            if (idx >= 0 && idx < rec.analogCount() && rec.analogs.get(idx).isSecondaryWithoutRatio()) return true;
        }
        return false;
    }

    private String scalingDescription(ComtradeRecord rec, ChannelMap map) {
        boolean anySecondary = false, missingRatio = false;
        String vt = "-", ct = "-";
        int[] all = {map.va, map.vb, map.vc, map.ia, map.ib, map.ic};
        for (int k = 0; k < 6; k++) {
            if (all[k] < 0 || all[k] >= rec.analogCount()) continue;
            ComtradeRecord.AnalogChannel ch = rec.analogs.get(all[k]);
            if (ch.ps != 'S') continue;
            anySecondary = true;
            if (!ch.hasRatio()) { missingRatio = true; continue; }
            String ratio = fmtPlain(ch.primary / ch.secondary);
            if (k < 3) vt = ratio; else ct = ratio;
        }
        if (missingRatio) return getString(R.string.msg_fl_scaling_no_ratio);
        if (anySecondary) return getString(R.string.msg_fl_scaling_secondary, vt, ct);
        return getString(R.string.msg_fl_scaling_primary);
    }

    private void openInViewer(EndState st) {
        if (st.cfg == null || st.dat == null) return;
        try {
            Intent intent = new Intent(this, DfrViewerActivity.class);
            intent.setData(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", st.cfg));
            intent.putExtra("paired_dat_uri", FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", st.dat).toString());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_view_general_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Line parameters (remembered per pair of relays, either direction)
    // ------------------------------------------------------------------

    private String linePairKey() {
        DeviceListActivity.DeviceItem a = selectedRelay(endA), b = selectedRelay(endB);
        if (a == null || b == null) return null;
        String x = relayKey(a), y = relayKey(b);
        return "line_" + (x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x);
    }

    private void restoreLineParams() {
        if (endA.spRelay == null || endB.spRelay == null) return;
        String key = linePairKey();
        if (key == null || key.equals(lastLinePairKey)) return;
        lastLinePairKey = key;
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null);
        JSONObject o = null;
        try {
            if (json != null) o = new JSONObject(json);
        } catch (Exception ignored) { }
        if (o == null) {
            // First time for this pair of relays: take the Line Database row that ties both
            // substations together, if there is one.
            LineData match = new LineDataManager(this).bestMatch(selectedRelay(endA), selectedRelay(endB));
            if (match != null) {
                applyLineData(match, true);
                return;
            }
            // Never leave another line's remembered data on screen for a pair it doesn't belong
            // to - but don't wipe what the user is typing for a brand-new pair either.
            if (lineParamsRestored) fillLineParams(new JSONObject());
            lineParamsRestored = false;
            setSelectedLine(null, false, false);
            return;
        }
        fillLineParams(o);
        lineParamsRestored = true;
        LineData line = new LineDataManager(this).get(o.optString("lineId", null));
        setSelectedLine(line, false, o.optBoolean("lineEdited", false));
    }

    private void fillLineParams(JSONObject o) {
        fillingLineFields = true;
        swTotal.setChecked(o.optBoolean("total", false));
        etLength.setText(o.optString("len", ""));
        etR1.setText(o.optString("r1", ""));
        etX1.setText(o.optString("x1", ""));
        etR0.setText(o.optString("r0", ""));
        etX0.setText(o.optString("x0", ""));
        etB1.setText(o.optString("b1", ""));
        fillingLineFields = false;
    }

    private void pickLineFromDatabase() {
        Intent intent = new Intent(this, LineDatabaseActivity.class);
        intent.putExtra(LineDatabaseActivity.EXTRA_PICK, true);
        DeviceListActivity.DeviceItem a = selectedRelay(endA), b = selectedRelay(endB);
        if (a != null) intent.putExtra(LineDatabaseActivity.EXTRA_RELAY_A, new String[]{a.gi, a.bay, a.merk, a.type});
        if (b != null) intent.putExtra(LineDatabaseActivity.EXTRA_RELAY_B, new String[]{b.gi, b.bay, b.merk, b.type});
        startActivityForResult(intent, REQ_PICK_LINE);
    }

    /** Fills the line fields from a Line Database row (per-km values, Z2 = Z1). */
    private void applyLineData(LineData d, boolean auto) {
        fillingLineFields = true;
        swTotal.setChecked(false);
        etLength.setText(plain(d.lengthKm));
        etR1.setText(plain(d.r1));
        etX1.setText(plain(d.x1));
        etR0.setText(plain(d.r0));
        etX0.setText(plain(d.x0));
        etB1.setText(plain(d.b1));
        fillingLineFields = false;
        lineParamsRestored = true;
        setSelectedLine(d, auto, false);
    }

    private void setSelectedLine(@Nullable LineData d, boolean auto, boolean edited) {
        selectedLine = d;
        selectedLineAuto = auto;
        selectedLineEdited = edited;
        renderLineConfig();
    }

    private void renderLineConfig() {
        if (tvLineConfig == null) return;
        if (selectedLine == null) {
            tvLineConfig.setText(R.string.msg_fl_line_config_manual);
            return;
        }
        String text = selectedLine.routeLabel() + "\n" + selectedLine.detailLabel();
        if (selectedLineAuto) text = getString(R.string.lbl_fl_line_config_auto) + " " + text;
        if (selectedLineEdited) text += "\n" + getString(R.string.lbl_fl_line_config_edited);
        tvLineConfig.setText(text);
    }

    private static String plain(double v) {
        if (Double.isNaN(v)) return "";
        String s = String.format(Locale.US, "%.6f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private void saveLineParams() {
        String key = linePairKey();
        if (key == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("total", swTotal.isChecked());
            o.put("len", text(etLength));
            o.put("r1", text(etR1));
            o.put("x1", text(etX1));
            o.put("r0", text(etR0));
            o.put("x0", text(etX0));
            o.put("b1", text(etB1));
            if (selectedLine != null) {
                o.put("lineId", selectedLine.id);
                o.put("lineEdited", selectedLineEdited);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, o.toString()).apply();
            lastLinePairKey = key;
            lineParamsRestored = true;
        } catch (Exception ignored) { }
    }

    private void updateLineHints() {
        boolean total = swTotal.isChecked();
        tilR1.setHint(getString(total ? R.string.hint_fl_r1_total : R.string.hint_fl_r1));
        tilX1.setHint(getString(total ? R.string.hint_fl_x1_total : R.string.hint_fl_x1));
        tilR0.setHint(getString(total ? R.string.hint_fl_r0_total : R.string.hint_fl_r0));
        tilX0.setHint(getString(total ? R.string.hint_fl_x0_total : R.string.hint_fl_x0));
        tilB1.setHint(getString(total ? R.string.hint_fl_b1_total : R.string.hint_fl_b1));
    }

    @Nullable
    private DoubleEndedFaultLocator.LineParams readLineParams() {
        DoubleEndedFaultLocator.LineParams lp = new DoubleEndedFaultLocator.LineParams();
        lp.lengthKm = parseNumber(text(etLength));
        double k = swTotal.isChecked() && lp.lengthKm > 0 ? 1.0 / lp.lengthKm : 1.0;
        lp.r1 = parseNumber(text(etR1)) * k;
        lp.x1 = parseNumber(text(etX1)) * k;
        lp.r0 = orZero(parseNumber(text(etR0))) * k;
        lp.x0 = orZero(parseNumber(text(etX0))) * k;
        lp.b1 = orZero(parseNumber(text(etB1))) * k;
        if (!(lp.lengthKm > 0) || !(lp.x1 > 0) || !(lp.r1 >= 0)) return null;
        return lp;
    }

    // ------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------

    private void runAnalysis() {
        if (endA.rec == null || endB.rec == null) {
            Toast.makeText(this, R.string.msg_fl_need_both, Toast.LENGTH_LONG).show();
            return;
        }
        DoubleEndedFaultLocator.LineParams lp = readLineParams();
        if (lp == null) {
            Toast.makeText(this, R.string.msg_fl_invalid_line, Toast.LENGTH_LONG).show();
            etLength.requestFocus();
            return;
        }
        saveLineParams();
        double offset = parseNumber(text(etOffset));
        ComtradeRecord recA = endA.rec, recB = endB.rec;
        ChannelMap mapA = endA.map, mapB = endB.map;
        showProgress(getString(R.string.msg_fl_analyzing));
        executor.execute(() -> {
            Result res;
            try {
                res = DoubleEndedFaultLocator.analyze(recA, mapA, recB, mapB, lp, offset);
            } catch (Exception e) {
                res = new Result();
                res.error = "EXCEPTION";
                res.errorArgs = new Object[]{String.valueOf(e.getMessage())};
            }
            Result finalRes = res;
            runOnUiThread(() -> {
                hideProgress();
                if (finalRes.error != null) {
                    cardResult.setVisibility(View.GONE);
                    showInfoDialog(getString(R.string.ttl_fl_error), errorText(finalRes));
                    return;
                }
                lastResult = finalRes;
                lastAnalyzedAt = System.currentTimeMillis();
                analyzedInputs = sessionInputs();
                viewingSaved = null;
                currentAlignment = finalRes.alignment;
                // The voltage-only synchronised location is the main result by default; the
                // switch under the vector diagram still turns it back to the unsynchronised one.
                syncAsMain = finalRes.alignment != null && finalRes.alignment.valid;
                renderResult(finalRes, endName(endA), endName(endB));
                saveSession();
            });
        });
    }

    private String errorText(Result r) {
        String a = endName(endA), b = endName(endB);
        switch (r.error) {
            case DoubleEndedFaultLocator.ERR_MAPPING_A: return getString(R.string.err_fl_mapping, a);
            case DoubleEndedFaultLocator.ERR_MAPPING_B: return getString(R.string.err_fl_mapping, b);
            case DoubleEndedFaultLocator.ERR_RATE_A: return getString(R.string.err_fl_rate, a, fmt((Double) r.errorArgs[0], 0));
            case DoubleEndedFaultLocator.ERR_RATE_B: return getString(R.string.err_fl_rate, b, fmt((Double) r.errorArgs[0], 0));
            case DoubleEndedFaultLocator.ERR_SHORT_A: return getString(R.string.err_fl_short, a);
            case DoubleEndedFaultLocator.ERR_SHORT_B: return getString(R.string.err_fl_short, b);
            case DoubleEndedFaultLocator.ERR_LINE: return getString(R.string.msg_fl_invalid_line);
            case DoubleEndedFaultLocator.ERR_NO_SIGNAL: return getString(R.string.err_fl_no_signal);
            default: return getString(R.string.err_fl_exception, r.errorArgs.length > 0 ? r.errorArgs[0] : r.error);
        }
    }

    private void renderResult(Result r, String nameA, String nameB) {
        renderResult(r, nameA, nameB, true);
    }

    private void renderResult(Result r, String nameA, String nameB, boolean scrollToResult) {
        resultNameA = nameA;
        resultNameB = nameB;
        cardResult.setVisibility(View.VISIBLE);
        TextView banner = findViewById(R.id.tvFlSavedBanner);
        if (viewingSaved != null) {
            banner.setVisibility(View.VISIBLE);
            banner.setText(getString(R.string.lbl_fl_saved_banner, dateText(viewingSaved.savedAt)));
        } else {
            banner.setVisibility(View.GONE);
        }
        findViewById(R.id.btnFlSave).setVisibility(viewingSaved == null ? View.VISIBLE : View.GONE);

        renderMain(r);
        renderWarnings(r, nameA, nameB);
        renderMethods(r, nameA, nameB);
        renderSync(r);
        renderChart(r, nameA, nameB);
        renderDetails(r, nameA, nameB);
        renderPhasorTables(r, nameA, nameB);

        if (!scrollToResult) return;
        View scroll = findViewById(R.id.scrollFl);
        scroll.post(() -> ((androidx.core.widget.NestedScrollView) scroll).smoothScrollTo(0, cardResult.getTop()));
    }

    /** Fraction from A of whichever result the user made the main one. */
    private double mainFraction(Result r) {
        double m = syncAsMain && currentAlignment != null && currentAlignment.valid ? currentAlignment.m : r.m;
        return Math.min(1, Math.max(0, m));
    }

    private double mainKmA(Result r) {
        return mainFraction(r) * r.lengthKm;
    }

    private void renderMain(Result r) {
        double kmA = mainKmA(r), kmB = r.lengthKm - kmA;
        boolean useSpread = !syncAsMain && !Double.isNaN(r.spreadKm);
        FaultLineView line = findViewById(R.id.faultLineView);
        line.setEnds(resultNameA, resultNameB);
        line.setFault(kmA / r.lengthKm, useSpread ? r.spreadKm / r.lengthKm : Double.NaN, fmt(kmA, 2) + " km");
        ((TextView) findViewById(R.id.tvFlFromALabel)).setText(getString(R.string.lbl_fl_from, resultNameA));
        ((TextView) findViewById(R.id.tvFlFromBLabel)).setText(getString(R.string.lbl_fl_from, resultNameB));
        ((TextView) findViewById(R.id.tvFlFromA)).setText(fmt(kmA, 2) + " km");
        ((TextView) findViewById(R.id.tvFlFromB)).setText(fmt(kmB, 2) + " km");
        ((TextView) findViewById(R.id.tvFlCurrentA)).setText(faultCurrentText(r.endA, r.faultType));
        ((TextView) findViewById(R.id.tvFlCurrentB)).setText(faultCurrentText(r.endB, r.faultType));
        ((TextView) findViewById(R.id.tvFlLineLength)).setText(getString(R.string.lbl_fl_line_length_total, fmt(r.lengthKm, 2)));
        String summary = getString(R.string.msg_fl_pct, fmt(kmA / r.lengthKm * 100, 1), fmt(r.lengthKm, 2));
        if (useSpread) summary = getString(R.string.msg_fl_spread, fmt(r.spreadKm, 2), r.windowsUsed) + "  •  " + summary;
        if (syncAsMain) summary = getString(R.string.lbl_fl_method_sync_v) + "  •  " + summary;
        ((TextView) findViewById(R.id.tvFlSummary)).setText(summary);
        ((TextView) findViewById(R.id.tvFlFaultType)).setText(getString(R.string.lbl_fl_fault_type, rstName(r.faultType), r.faultType));
    }

    // ------------------------------------------------------------------
    // Voltage-only vector synchronisation (A fixed, B shifted)
    // ------------------------------------------------------------------

    private void setupSyncControls() {
        SeekBar seek = findViewById(R.id.seekFlShift);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) applyShift(shiftLo + progress * SHIFT_STEP, false);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override public void onStopTrackingTouch(SeekBar sb) { refreshAfterShift(); }
        });
        findViewById(R.id.btnFlShiftMinus).setOnClickListener(v -> nudgeShift(-0.0001));
        findViewById(R.id.btnFlShiftPlus).setOnClickListener(v -> nudgeShift(0.0001));
        findViewById(R.id.btnFlShiftAuto).setOnClickListener(v -> {
            if (lastResult == null || Double.isNaN(lastResult.tauAuto)) return;
            applyShift(lastResult.tauAuto, true);
            refreshAfterShift();
        });
        SwitchCompat sw = findViewById(R.id.swFlSyncMain);
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (bindingSync || lastResult == null) return;
            syncAsMain = checked;
            renderMain(lastResult);
            renderMethods(lastResult, resultNameA, resultNameB);
            renderChart(lastResult, resultNameA, resultNameB);
        });
    }

    private void nudgeShift(double deltaSeconds) {
        if (currentAlignment == null) return;
        applyShift(currentAlignment.tau + deltaSeconds, true);
        refreshAfterShift();
    }

    /** Re-aligns record B at shift tau - cheap enough to run on every slider movement. */
    private void applyShift(double tau, boolean moveSlider) {
        Result r = lastResult;
        if (r == null || r.session == null) return;
        SeekBar seek = findViewById(R.id.seekFlShift);
        tau = Math.max(shiftLo, Math.min(shiftLo + seek.getMax() * SHIFT_STEP, tau));
        currentAlignment = r.session.alignAt(tau);
        if (moveSlider) seek.setProgress((int) Math.round((tau - shiftLo) / SHIFT_STEP));
        updateSyncViews(r);
    }

    /** What depends on the shift but is too heavy to redo on every slider pixel. */
    private void refreshAfterShift() {
        if (lastResult == null) return;
        renderMethods(lastResult, resultNameA, resultNameB);
        if (syncAsMain) {
            renderMain(lastResult);
            renderChart(lastResult, resultNameA, resultNameB);
        }
    }

    private void renderSync(Result r) {
        boolean live = r.session != null && viewingSaved == null;
        findViewById(R.id.layoutFlSyncControls).setVisibility(live ? View.VISIBLE : View.GONE);
        bindingSync = true;
        ((SwitchCompat) findViewById(R.id.swFlSyncMain)).setChecked(syncAsMain);
        bindingSync = false;
        if (live) {
            double centre = !Double.isNaN(r.tauAuto) ? r.tauAuto : currentAlignment != null ? currentAlignment.tau : 0;
            double span = 1.5 * r.session.period;
            double lo = Math.max(r.session.tauMin, centre - span), hi = Math.min(r.session.tauMax, centre + span);
            shiftLo = lo;
            SeekBar seek = findViewById(R.id.seekFlShift);
            seek.setMax(Math.max(1, (int) Math.round((hi - lo) / SHIFT_STEP)));
            double tau = currentAlignment != null ? currentAlignment.tau : centre;
            seek.setProgress((int) Math.round((tau - lo) / SHIFT_STEP));
        }
        updateSyncViews(r);
    }

    private void updateSyncViews(Result r) {
        PhasorDiagramView diagram = findViewById(R.id.phasorDiagram);
        diagram.setLegend(resultNameA, resultNameB);
        TextView info = findViewById(R.id.tvFlSyncInfo);
        RelayPhasorView relays = findViewById(R.id.relayPhasors);
        android.widget.TableLayout table = findViewById(R.id.tableFlAngles);
        DoubleEndedFaultLocator.Alignment al = currentAlignment;
        if (al == null || !al.valid || al.a == null || al.b == null) {
            diagram.setVectors(null, null, null, null, false);
            relays.setRelays(null, null, resultNameA, resultNameB);
            table.removeAllViews();
            info.setText(R.string.msg_fl_sync_out);
            return;
        }
        relays.setRelays(al.a, al.b, resultNameA, resultNameB);
        renderAngleTable(table, al);
        diagram.setVectors(al.a.v1, al.b.v1, al.a.v0, al.b.v0, al.v0Used);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.val_fl_sync_ref, resultNameA, fmt(r.alignRefTimeA * 1000, 1), fmt(r.alignRefCurrentA, 0)));
        boolean ts = !Double.isNaN(r.tauTimestamp);
        double base = ts ? r.tauTimestamp : r.tauInception;
        sb.append('\n').append(getString(ts ? R.string.val_fl_sync_shift_ts : R.string.val_fl_sync_shift_inc, resultNameB,
                signed((al.tau - base) * 1000, 2), signed((r.tauAuto - base) * 1000, 2)));
        sb.append('\n').append(al.v0Used
                ? getString(R.string.val_fl_sync_angles, signed(al.dV1Deg, 1), signed(al.dV0Deg, 1))
                : getString(R.string.val_fl_sync_angles_nov0, signed(al.dV1Deg, 1)));
        sb.append('\n').append(getString(R.string.val_fl_sync_loc, fmt(al.m * r.lengthKm, 2), resultNameA));
        info.setText(sb.toString());
    }

    /**
     * Magnitude and angle of every phase at both relays at the aligned instant, angles from VR of
     * GI A, plus the B-minus-A angle - the numbers behind the side-by-side diagram.
     */
    private String angleTable(DoubleEndedFaultLocator.Alignment al) {
        double ref = al.a.v[0].arg();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-3s %-19s %-19s %7s%n", "", clip(resultNameA, 19), clip(resultNameB, 19), "B-A"));
        String[] names = {"VR", "VS", "VT", "IR", "IS", "IT", "V1", "V0"};
        for (int k = 0; k < names.length; k++) {
            Cx x = angleRow(al.a, k), y = angleRow(al.b, k);
            boolean isV = names[k].startsWith("V");
            sb.append(String.format(Locale.US, "%-3s %-19s %-19s %6.1f°%n", names[k],
                    magAngle(x, ref, isV), magAngle(y, ref, isV), wrap180(Math.toDegrees(y.arg() - x.arg()))));
        }
        return sb.toString().trim();
    }

    /** angleTable() as an on-screen table: |X| and angle per relay, then the B-minus-A angle. */
    private void renderAngleTable(android.widget.TableLayout table, DoubleEndedFaultLocator.Alignment al) {
        double ref = al.a.v[0].arg();
        String mag = getString(R.string.lbl_fl_col_mag), ang = getString(R.string.lbl_fl_col_angle);
        ValueTable t = new ValueTable(this, table)
                .header(new String[]{"", resultNameA, resultNameB, "B−A"}, new int[]{1, 2, 2, 1})
                .header(new String[]{"", mag, ang, mag, ang, ang}, null);
        String[] names = {"VR", "VS", "VT", "IR", "IS", "IT", "V1", "V0"};
        boolean[] faulted = faultedPhases(lastResult);
        for (int k = 0; k < names.length; k++) {
            if (k == 3 || k == 6) t.divider();
            Cx x = angleRow(al.a, k), y = angleRow(al.b, k);
            boolean isV = names[k].startsWith("V");
            t.row(k < 6 && faulted[k % 3], names[k] + (isV ? " (kV)" : " (A)"),
                    magText(x, isV), angleText(x, ref), magText(y, isV), angleText(y, ref),
                    String.format(Locale.US, "%.1f°", wrap180(Math.toDegrees(y.arg() - x.arg()))));
        }
    }

    /** phasorTable() as on-screen tables, one per end: pre-fault and fault |X| and angle. */
    private void renderPhasorTables(Result r, String nameA, String nameB) {
        LinearLayout box = findViewById(R.id.containerFlPhasors);
        box.removeAllViews();
        String mag = getString(R.string.lbl_fl_col_mag), ang = getString(R.string.lbl_fl_col_angle);
        String[] names = {"Va", "Vb", "Vc", "Ia", "Ib", "Ic", "V1", "V2", "V0", "I1", "I2", "I0"};
        EndSummary[] ends = {r.endA, r.endB};
        String[] endNames = {nameA, nameB};
        boolean[] faulted = faultedPhases(r);
        for (int e = 0; e < 2; e++) {
            EndSummary end = ends[e];
            TextView title = new TextView(this);
            title.setText(endNames[e]);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary));
            title.setPadding(0, dp(e == 0 ? 2 : 10), 0, dp(2));
            box.addView(title);
            android.widget.TableLayout table = new android.widget.TableLayout(this);
            box.addView(table, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ValueTable t = new ValueTable(this, table)
                    .header(new String[]{"", getString(R.string.lbl_fl_prefault), getString(R.string.lbl_fl_fault)}, new int[]{1, 2, 2})
                    .header(new String[]{"", mag, ang, mag, ang}, null);
            for (int k = 0; k < names.length; k++) {
                if (k == 3 || k == 6 || k == 9) t.divider();
                boolean isV = names[k].startsWith("V");
                Cx pre = end.hasPrefault && end.pre != null ? pick(end.pre, k) : null;
                Cx flt = end.flt != null ? pick(end.flt, k) : null;
                t.row(k < 6 && faulted[k % 3], names[k] + (isV ? " (kV)" : " (A)"),
                        pre != null ? magText(pre, isV) : "-", pre != null ? angleText(pre, end.displayRefAngle) : "-",
                        flt != null ? magText(flt, isV) : "-", flt != null ? angleText(flt, end.displayRefAngle) : "-");
            }
        }
    }

    /**
     * "Arus gangguan" + the fault-window current of each faulted phase at this end (IN = 3·I0
     * added for earth faults); the highest phase current when the fault type is unknown.
     */
    private String faultCurrentText(EndSummary e, String faultType) {
        if (e == null || e.flt == null) return "";
        String[] rst = {"IR", "IS", "IT"};
        boolean[] faulted = faultedPhases(faultType);
        StringBuilder sb = new StringBuilder();
        int strongest = 0;
        for (int p = 0; p < 3; p++) {
            if (e.flt.i[p].abs() > e.flt.i[strongest].abs()) strongest = p;
        }
        for (int p = 0; p < 3; p++) {
            boolean show = faulted[0] || faulted[1] || faulted[2] ? faulted[p] : p == strongest;
            if (!show) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(rst[p]).append(' ').append(fmt(e.flt.i[p].abs(), 0)).append(" A");
        }
        if (faultType != null && faultType.endsWith("G") && e.flt.i0 != null) {
            sb.append(" · IN ").append(fmt(3 * e.flt.i0.abs(), 0)).append(" A");
        }
        return getString(R.string.lbl_fl_fault_current) + "\n" + sb;
    }

    /** Phases (0 = R/A, 1 = S/B, 2 = T/C) the classified fault involves; none when unknown. */
    private static boolean[] faultedPhases(@Nullable Result r) {
        return faultedPhases(r != null ? r.faultType : null);
    }

    private static boolean[] faultedPhases(@Nullable String faultType) {
        boolean[] f = new boolean[3];
        if (faultType == null || faultType.equals("?")) return f;
        f[0] = faultType.contains("A");
        f[1] = faultType.contains("B");
        f[2] = faultType.contains("C");
        return f;
    }

    private static String magText(Cx x, boolean isV) {
        return isV ? String.format(Locale.US, "%.2f", x.abs() / 1000) : String.format(Locale.US, "%.1f", x.abs());
    }

    private static String angleText(Cx x, double ref) {
        return String.format(Locale.US, "%.1f°", wrap180(Math.toDegrees(x.arg() - ref)));
    }

    private static Cx angleRow(Phasors p, int k) {
        if (k < 3) return p.v[k];
        if (k < 6) return p.i[k - 3];
        return k == 6 ? p.v1 : p.v0;
    }

    private static String magAngle(Cx x, double ref, boolean isV) {
        String mag = isV ? String.format(Locale.US, "%.2fkV", x.abs() / 1000) : String.format(Locale.US, "%.0fA", x.abs());
        return String.format(Locale.US, "%9s %6.1f°", mag, wrap180(Math.toDegrees(x.arg() - ref)));
    }

    private static double wrap180(double d) {
        while (d > 180) d -= 360;
        while (d <= -180) d += 360;
        return d;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + ".";
    }

    private static String signed(double v, int decimals) {
        return (v >= 0 ? "+" : "") + fmt(v, decimals);
    }

    // ------------------------------------------------------------------
    // Saving / reopening results
    // ------------------------------------------------------------------

    private void saveResult() {
        Result r = lastResult;
        if (r == null) return;
        FlResultStore.Saved saved = new FlResultStore.Saved();
        saved.savedAt = System.currentTimeMillis();
        saved.analyzedAt = lastAnalyzedAt > 0 ? lastAnalyzedAt : saved.savedAt;
        saved.id = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(saved.savedAt));
        saved.nameA = resultNameA;
        saved.nameB = resultNameB;
        saved.lineConfig = selectedLine != null ? selectedLine.routeLabel() + " (" + selectedLine.bay + ")" : "";
        saved.recordA = endA.cfg != null ? endA.cfg.getName() : "";
        saved.recordB = endB.cfg != null ? endB.cfg.getName() : "";
        saved.tauUsed = currentAlignment != null ? currentAlignment.tau : Double.NaN;
        saved.syncAsMain = syncAsMain;
        DoubleEndedFaultLocator.Alignment auto = r.alignment;
        r.alignment = currentAlignment; // the vectors as the user left them
        try {
            saved.result = r; // the PDF header reads each end's fault time from it
            File pdf = writePdf(saved);
            saved.pdfPath = pdf.getAbsolutePath();
            new FlResultStore(this).save(saved);
            showSavedDialog(pdf);
        } catch (Exception e) {
            showInfoDialog(getString(R.string.ttl_fl_error), getString(R.string.msg_fl_save_fail, String.valueOf(e.getMessage())));
        } finally {
            r.alignment = auto;
        }
    }

    /**
     * The result card as a one-page PDF report, without the on-screen-only controls, between a
     * letterhead (title, line, both DFR files) and a footer (logo, app name + version, analysis time).
     */
    private File writePdf(FlResultStore.Saved saved) throws Exception {
        View card = cardResult;
        TextView banner = findViewById(R.id.tvFlSavedBanner);
        View[] hide = {findViewById(R.id.layoutFlResultActions), findViewById(R.id.layoutFlSyncControls),
                findViewById(R.id.btnFlFixPolarity), findViewById(R.id.tvFlSyncHint), findViewById(R.id.swFlSyncMain), banner};
        int[] vis = new int[hide.length];
        for (int i = 0; i < hide.length; i++) { vis[i] = hide[i].getVisibility(); hide[i].setVisibility(View.GONE); }
        android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
        try {
            int w = card.getWidth();
            View header = buildPdfHeader(saved);
            View footer = buildPdfFooter(saved);
            int hh = measureDetached(header, w), fh = measureDetached(footer, w);
            card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = card.getMeasuredHeight();
            card.layout(card.getLeft(), card.getTop(), card.getLeft() + w, card.getTop() + h);
            float scale = 595f / w;
            android.graphics.pdf.PdfDocument.PageInfo info =
                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, (int) Math.ceil((hh + h + fh) * scale) + 1, 1).create();
            android.graphics.pdf.PdfDocument.Page page = doc.startPage(info);
            android.graphics.Canvas c = page.getCanvas();
            c.drawColor(ContextCompat.getColor(this, R.color.bg_card));
            c.scale(scale, scale);
            header.draw(c);
            c.translate(0, hh);
            card.draw(c);
            c.translate(0, h);
            footer.draw(c);
            doc.finishPage(page);
            File dir = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DFR"), "FL_Reports");
            if (!dir.exists() && !dir.mkdirs()) dir = new File(new File(getExternalFilesDir(null), "DFR"), "FL_Reports");
            if (!dir.exists()) dir.mkdirs();
            String name = ("FL_" + saved.nameA + "_" + saved.nameB + "_" + saved.id).replaceAll("[^a-zA-Z0-9_]+", "_") + ".pdf";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.writeTo(fos);
            }
            return out;
        } finally {
            doc.close();
            for (int i = 0; i < hide.length; i++) hide[i].setVisibility(vis[i]);
            card.requestLayout();
        }
    }

    /** The cfg trigger stamp "dd/mm/yyyy,hh:mm:ss.ssssss" as "dd/mm/yyyy hh:mm:ss.sss". */
    private static String faultTimeText(@Nullable String trigger) {
        if (trigger == null || trigger.trim().isEmpty()) return "-";
        String[] p = trigger.split(",");
        if (p.length < 2) return trigger.trim();
        String time = p[1].trim();
        int dot = time.indexOf('.');
        if (dot >= 0 && time.length() > dot + 4) time = time.substring(0, dot + 4);
        return p[0].trim() + " " + time;
    }

    /** Lays a view that is not attached to the window out at width w; returns its height. */
    private static int measureDetached(View v, int w) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
        return v.getMeasuredHeight();
    }

    /** ANALISA DOUBLE ENDED / line name / DFR file of GI A / DFR file of GI B. */
    private View buildPdfHeader(FlResultStore.Saved saved) {
        int primary = resolveColor(com.google.android.material.R.attr.colorPrimary);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), 0);
        String line = !saved.lineConfig.isEmpty() ? saved.lineConfig : saved.nameA + " – " + saved.nameB;
        box.addView(pdfText(getString(R.string.ttl_fl_pdf_title), 18, true, primary));
        box.addView(pdfText(line, 14, true, ContextCompat.getColor(this, R.color.text_primary)));
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);
        Result r = saved.result;
        String[] names = {saved.nameA, saved.nameB}, records = {saved.recordA, saved.recordB};
        EndSummary[] ends = {r != null ? r.endA : null, r != null ? r.endB : null};
        for (int k = 0; k < 2; k++) {
            String text = getString(R.string.lbl_fl_pdf_dfr, names[k], records[k].isEmpty() ? "-" : records[k]);
            if (ends[k] != null) text += "\n" + getString(R.string.lbl_fl_pdf_fault_time, faultTimeText(ends[k].triggerText));
            box.addView(pdfText(text, 11, false, secondary));
        }
        View rule = new View(this);
        rule.setBackgroundColor(primary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        lp.topMargin = dp(8);
        box.addView(rule, lp);
        return box;
    }

    /** Bottom right: app logo, "name vX.Y.Z" and when the analysis was run. */
    private View buildPdfFooter(FlResultStore.Saved saved) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(14));
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.rightMargin = dp(6);
        row.addView(logo, lp);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);
        TextView app = pdfText(getString(R.string.lbl_fl_pdf_app, getString(R.string.app_name), version), 10, true, secondary);
        TextView when = pdfText(getString(R.string.lbl_fl_pdf_analyzed, dateText(saved.analyzedAt > 0 ? saved.analyzedAt : saved.savedAt)), 10, false, secondary);
        app.setGravity(android.view.Gravity.END);
        when.setGravity(android.view.Gravity.END);
        text.addView(app);
        text.addView(when);
        row.addView(text);
        return row;
    }

    private TextView pdfText(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(1), 0, dp(1));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void showSavedDialog(File pdf) {
        View v = getLayoutInflater().inflate(R.layout.dialog_export_success, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvExportMessage)).setText(getString(R.string.msg_fl_saved, pdf.getAbsolutePath()));
        Button open = v.findViewById(R.id.btnSaveAs);
        open.setVisibility(View.VISIBLE);
        open.setText(R.string.btn_fl_open_pdf);
        open.setOnClickListener(x -> {
            dialog.dismiss();
            openPdf(pdf);
        });
        v.findViewById(R.id.btnOpenFolder).setOnClickListener(x -> {
            dialog.dismiss();
            startActivity(new Intent(this, InternalFileManagerActivity.class));
        });
        v.findViewById(R.id.btnShare).setOnClickListener(x -> {
            dialog.dismiss();
            try {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdf));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, getString(R.string.btn_fl_share)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.msg_all_share_fail, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    private void openPdf(File pdf) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdf), "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_view_general_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void showHistory() {
        FlResultStore store = new FlResultStore(this);
        List<FlResultStore.Saved> items = store.list();
        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(R.string.ttl_fl_history);
        LinearLayout container = v.findViewById(R.id.llPickListItems);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.msg_fl_history_empty);
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            container.addView(empty);
        }
        for (FlResultStore.Saved it : items) {
            View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
            ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText(it.nameA + " – " + it.nameB);
            Result r = it.result;
            double m = it.syncAsMain && r.alignment != null && r.alignment.valid ? r.alignment.m : r.m;
            ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(dateText(it.savedAt) + "  •  "
                    + fmt(Math.min(1, Math.max(0, m)) * r.lengthKm, 2) + " km " + getString(R.string.lbl_fl_from, it.nameA).toLowerCase(Locale.getDefault())
                    + "  •  " + rstName(r.faultType));
            row.setOnClickListener(x -> {
                dialog.dismiss();
                openSaved(it);
            });
            row.setOnLongClickListener(x -> {
                confirmDeleteSaved(store, it, dialog);
                return true;
            });
            container.addView(row);
        }
        v.findViewById(R.id.btnPickListCancel).setOnClickListener(x -> dialog.dismiss());
        dialog.show();
    }

    private void openSaved(FlResultStore.Saved saved) {
        openSaved(saved, true);
    }

    private void openSaved(FlResultStore.Saved saved, boolean scrollToResult) {
        viewingSaved = saved;
        lastAnalyzedAt = saved.analyzedAt;
        lastResult = saved.result;
        currentAlignment = saved.result.alignment;
        syncAsMain = saved.syncAsMain;
        renderResult(saved.result, saved.nameA, saved.nameB, scrollToResult);
    }

    // ------------------------------------------------------------------
    // Last session: the screen reopens as it was left, even after the app was closed
    // ------------------------------------------------------------------

    private File sessionFile() {
        return new File(getFilesDir(), "fl_session.json");
    }

    /** Both ends' records + channel maps, the line fields and the result on screen. */
    private void saveSession() {
        try {
            JSONObject o = new JSONObject();
            o.put("A", sessionEnd(endA));
            o.put("B", sessionEnd(endB));
            o.put("line", lineFieldsJson());
            o.put("offset", text(etOffset));
            if (viewingSaved != null) {
                o.put("savedId", viewingSaved.id);
            } else if (lastResult != null) {
                DoubleEndedFaultLocator.Alignment auto = lastResult.alignment;
                lastResult.alignment = currentAlignment; // the vectors as the user left them
                try {
                    o.put("result", FlResultStore.resultToJson(lastResult));
                } finally {
                    lastResult.alignment = auto;
                }
                o.put("nameA", resultNameA);
                o.put("nameB", resultNameB);
                o.put("syncAsMain", syncAsMain);
                if (currentAlignment != null && !Double.isNaN(currentAlignment.tau)) o.put("tau", currentAlignment.tau);
                o.put("analyzedAt", lastAnalyzedAt);
                o.put("analyzedInputs", analyzedInputs);
            }
            try (FileOutputStream fos = new FileOutputStream(sessionFile())) {
                fos.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    private static JSONObject sessionEnd(EndState st) throws Exception {
        JSONObject o = new JSONObject();
        if (st.cfg == null || st.dat == null) return o;
        o.put("cfg", st.cfg.getAbsolutePath());
        o.put("dat", st.dat.getAbsolutePath());
        o.put("remote", st.remoteFileTime);
        if (st.map != null) {
            ChannelMap m = st.map;
            o.put("map", new JSONArray(new int[]{m.va, m.vb, m.vc, m.ia, m.ib, m.ic}));
            o.put("invert", m.invertCurrent);
            o.put("vMult", m.vMult);
            o.put("iMult", m.iMult);
        }
        return o;
    }

    private JSONObject lineFieldsJson() throws Exception {
        JSONObject line = new JSONObject();
        line.put("total", swTotal.isChecked());
        line.put("len", text(etLength));
        line.put("r1", text(etR1));
        line.put("x1", text(etX1));
        line.put("r0", text(etR0));
        line.put("x0", text(etX0));
        line.put("b1", text(etB1));
        if (selectedLine != null) {
            line.put("lineId", selectedLine.id);
            line.put("lineAuto", selectedLineAuto);
            line.put("lineEdited", selectedLineEdited);
        }
        return line;
    }

    /** Everything the analysis depends on, as one comparable string. */
    private String sessionInputs() {
        try {
            JSONObject line = lineFieldsJson();
            line.remove("lineAuto");
            line.remove("lineEdited");
            return sessionEnd(endA) + "|" + sessionEnd(endB) + "|" + line + "|" + text(etOffset);
        } catch (Exception e) {
            return "";
        }
    }

    private static class RestoredEnd {
        File cfg, dat;
        long remote;
        ComtradeRecord rec;
        ChannelMap map;
    }

    /** Reopens the last session: line fields now, records in the background, then the result. */
    private void restoreSession() {
        JSONObject o;
        try {
            o = new JSONObject(new String(ComtradeRecord.readAll(sessionFile()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        JSONObject line = o.optJSONObject("line");
        if (line != null && line.has("len")) {
            fillLineParams(line);
            lineParamsRestored = true;
            setSelectedLine(new LineDataManager(this).get(line.optString("lineId", null)),
                    line.optBoolean("lineAuto", false), line.optBoolean("lineEdited", false));
        }
        etOffset.setText(o.optString("offset", ""));
        JSONObject ja = o.optJSONObject("A"), jb = o.optJSONObject("B");
        showProgress(getString(R.string.msg_fl_restoring));
        executor.execute(() -> {
            RestoredEnd ra = loadRestoredEnd(ja), rb = loadRestoredEnd(jb);
            runOnUiThread(() -> {
                hideProgress();
                if (isGone()) return;
                applyRestoredEnd(endA, ra);
                applyRestoredEnd(endB, rb);
                restoreResult(o);
            });
        });
    }

    /** Must run on the executor; null when the record files are gone or unreadable. */
    @Nullable
    private static RestoredEnd loadRestoredEnd(@Nullable JSONObject j) {
        if (j == null || !j.has("cfg")) return null;
        try {
            RestoredEnd r = new RestoredEnd();
            r.cfg = new File(j.getString("cfg"));
            r.dat = new File(j.getString("dat"));
            if (!r.cfg.isFile() || !r.dat.isFile()) return null;
            r.rec = ComtradeRecord.load(r.cfg, r.dat);
            r.remote = j.optLong("remote", 0);
            JSONArray idx = j.optJSONArray("map");
            if (idx != null && idx.length() == 6) {
                ChannelMap m = new ChannelMap();
                m.va = idx.getInt(0); m.vb = idx.getInt(1); m.vc = idx.getInt(2);
                m.ia = idx.getInt(3); m.ib = idx.getInt(4); m.ic = idx.getInt(5);
                m.invertCurrent = j.optBoolean("invert", false);
                m.vMult = j.optDouble("vMult", 1);
                m.iMult = j.optDouble("iMult", 1);
                if (m.complete(r.rec.analogCount())) r.map = m;
            }
            if (r.map == null) r.map = DoubleEndedFaultLocator.autoMap(r.rec);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyRestoredEnd(EndState st, @Nullable RestoredEnd r) {
        if (r == null) return;
        st.cfg = r.cfg;
        st.dat = r.dat;
        st.rec = r.rec;
        st.map = r.map;
        st.remoteFileTime = r.remote;
        renderEnd(st);
    }

    /**
     * Shows the stored result. When both records and every input are still the same, the analysis
     * is run again first, so the shift slider (whose state isn't stored) works as before.
     */
    private void restoreResult(JSONObject o) {
        String savedId = o.optString("savedId", "");
        if (!savedId.isEmpty()) {
            for (FlResultStore.Saved s : new FlResultStore(this).list()) {
                if (s.id.equals(savedId)) {
                    openSaved(s, false);
                    break;
                }
            }
            return;
        }
        JSONObject jr = o.optJSONObject("result");
        if (jr == null) return;
        Result stored;
        try {
            stored = FlResultStore.resultFromJson(jr);
        } catch (Exception e) {
            return;
        }
        String nameA = o.optString("nameA", ""), nameB = o.optString("nameB", "");
        boolean sync = o.optBoolean("syncAsMain", false);
        double tau = o.optDouble("tau", Double.NaN);
        lastAnalyzedAt = o.optLong("analyzedAt", 0);
        analyzedInputs = o.optString("analyzedInputs", "");
        DoubleEndedFaultLocator.LineParams lp = readLineParams();
        boolean rerun = endA.rec != null && endB.rec != null && lp != null
                && !analyzedInputs.isEmpty() && analyzedInputs.equals(sessionInputs());
        if (!rerun) {
            showRestoredResult(stored, stored.alignment, nameA, nameB, sync);
            return;
        }
        double offset = parseNumber(text(etOffset));
        ComtradeRecord recA = endA.rec, recB = endB.rec;
        ChannelMap mapA = endA.map, mapB = endB.map;
        showProgress(getString(R.string.msg_fl_restoring));
        executor.execute(() -> {
            Result fresh = null;
            try {
                fresh = DoubleEndedFaultLocator.analyze(recA, mapA, recB, mapB, lp, offset);
            } catch (Exception ignored) { }
            Result res = fresh != null && fresh.error == null ? fresh : null;
            runOnUiThread(() -> {
                hideProgress();
                if (isGone()) return;
                if (res == null) {
                    showRestoredResult(stored, stored.alignment, nameA, nameB, sync);
                } else {
                    showRestoredResult(res, res.session != null && !Double.isNaN(tau) ? res.session.alignAt(tau) : res.alignment,
                            nameA, nameB, sync);
                }
            });
        });
    }

    private void showRestoredResult(Result r, @Nullable DoubleEndedFaultLocator.Alignment alignment, String nameA, String nameB, boolean sync) {
        lastResult = r;
        viewingSaved = null;
        currentAlignment = alignment;
        syncAsMain = sync && alignment != null && alignment.valid;
        renderResult(r, nameA, nameB, false);
    }

    private void confirmDeleteSaved(FlResultStore store, FlResultStore.Saved saved, AlertDialog listDialog) {
        View v = getLayoutInflater().inflate(R.layout.dialog_fl_info, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvFlInfoTitle)).setText(R.string.ttl_fl_delete_saved);
        ((TextView) v.findViewById(R.id.tvFlInfoBody)).setText(getString(R.string.msg_fl_delete_saved,
                saved.nameA + " – " + saved.nameB + " (" + dateText(saved.savedAt) + ")"));
        TextView btn = v.findViewById(R.id.btnFlInfoClose);
        btn.setText(R.string.btn_all_delete);
        btn.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
        btn.setOnClickListener(x -> {
            store.delete(saved.id);
            dialog.dismiss();
            listDialog.dismiss();
            showHistory();
        });
        dialog.show();
    }

    private static String dateText(long millis) {
        return new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private void renderWarnings(Result r, String nameA, String nameB) {
        LinearLayout box = findViewById(R.id.containerFlWarnings);
        box.removeAllViews();
        for (DoubleEndedFaultLocator.Warning w : r.warnings) {
            String text = warningText(w, nameA, nameB);
            if (text == null) continue;
            TextView tv = new TextView(this);
            tv.setText("• " + text);
            tv.setTextColor(ContextCompat.getColor(this, R.color.status_warning));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_size_small));
            tv.setPadding(0, dp(2), 0, dp(4));
            box.addView(tv);
        }
        Button fix = findViewById(R.id.btnFlFixPolarity);
        // Exactly one end looking reverse is the classic signature of an inverted CT polarity
        // setting in that relay's DFR - offer the one-tap retry instead of making the user dig
        // into the mapping dialog.
        EndState suspect = r.dirA < 0 && r.dirB >= 0 ? endA : r.dirB < 0 && r.dirA >= 0 ? endB : null;
        if (suspect == null || viewingSaved != null || suspect.map == null) {
            fix.setVisibility(View.GONE);
            return;
        }
        fix.setVisibility(View.VISIBLE);
        fix.setText(getString(R.string.btn_fl_fix_polarity, suspect == endA ? nameA : nameB));
        fix.setOnClickListener(v -> {
            suspect.map.invertCurrent = !suspect.map.invertCurrent;
            saveMapping(suspect);
            renderEnd(suspect);
            runAnalysis();
        });
    }

    @Nullable
    private String warningText(DoubleEndedFaultLocator.Warning w, String nameA, String nameB) {
        Object[] a = w.args;
        switch (w.code) {
            case "SEC_NO_RATIO_A": return getString(R.string.warn_fl_sec_no_ratio, nameA);
            case "SEC_NO_RATIO_B": return getString(R.string.warn_fl_sec_no_ratio, nameB);
            case "FREQ_DIFF": return getString(R.string.warn_fl_freq_diff, fmt((Double) a[0], 1), fmt((Double) a[1], 1));
            case "INC_TRIGGER_A": return getString(R.string.warn_fl_inc_trigger, nameA);
            case "INC_TRIGGER_B": return getString(R.string.warn_fl_inc_trigger, nameB);
            case "NO_PREFAULT_A": return getString(R.string.warn_fl_no_prefault, nameA);
            case "NO_PREFAULT_B": return getString(R.string.warn_fl_no_prefault, nameB);
            case "SHORT_FAULT": return getString(R.string.warn_fl_short_fault);
            case "UNSTABLE": return getString(R.string.warn_fl_unstable, fmt((Double) a[0], 0));
            case "NO_ROOT": return getString(R.string.warn_fl_no_root);
            case "TWO_ROOTS": return getString(R.string.warn_fl_two_roots, fmt((Double) a[0], 2), nameA);
            case "OUT_OF_LINE": return getString(R.string.warn_fl_out_of_line, fmt((Double) a[0], 1));
            case "DIR_A_REV": return getString(R.string.warn_fl_dir_rev, nameA);
            case "DIR_B_REV": return getString(R.string.warn_fl_dir_rev, nameB);
            case "NO_Z0": return getString(R.string.warn_fl_no_z0);
            case "TIME_GAP": return getString(R.string.warn_fl_time_gap, fmt(Math.abs((Double) a[0]), 1));
            case "PHASE_ROT_A": return getString(R.string.warn_fl_phase_rot, nameA);
            case "PHASE_ROT_B": return getString(R.string.warn_fl_phase_rot, nameB);
            case "PRE_V_MISMATCH": return getString(R.string.warn_fl_pre_v_mismatch, fmt((Double) a[0], 1), fmt((Double) a[1], 1));
            case "PRE_I_MISMATCH": return getString(R.string.warn_fl_pre_i_mismatch, fmt((Double) a[0], 0), fmt((Double) a[1], 0));
            case "PRE_POLARITY": return getString(R.string.warn_fl_pre_polarity);
            default: return null;
        }
    }

    private void renderMethods(Result r, String nameA, String nameB) {
        LinearLayout box = findViewById(R.id.containerFlMethods);
        box.removeAllViews();
        String main;
        if (r.sequenceUsed == 2) main = getString(R.string.lbl_fl_method_de_neg);
        else if (r.superimposed) main = getString(R.string.lbl_fl_method_de_pos_sup);
        else main = getString(R.string.lbl_fl_method_de_pos_total);
        addRow(box, main, syncAsMain ? methodValue(r, r.distA) : fmt(r.distA, 2) + " km", !syncAsMain);
        if (currentAlignment != null && currentAlignment.valid) {
            double km = currentAlignment.m * r.lengthKm;
            addRow(box, getString(R.string.lbl_fl_method_sync_v), syncAsMain ? fmt(km, 2) + " km" : methodValue(r, km), syncAsMain);
        }
        if (!Double.isNaN(r.mPos)) addRow(box, getString(R.string.lbl_fl_method_de_pos), methodValue(r, r.mPos * r.lengthKm), false);
        if (!Double.isNaN(r.mSync)) {
            String v = methodValue(r, r.mSync * r.lengthKm);
            if (!r.syncTrusted) v += "\n" + getString(R.string.lbl_fl_sync_untrusted);
            addRow(box, getString(R.string.lbl_fl_method_sync), v, false);
        }
        addRow(box, getString(R.string.lbl_fl_method_takagi, nameA),
                Double.isNaN(r.takagiA) ? getString(R.string.val_fl_na) : methodValue(r, r.takagiA), false);
        addRow(box, getString(R.string.lbl_fl_method_takagi, nameB),
                Double.isNaN(r.takagiB) ? getString(R.string.val_fl_na)
                        : methodValue(r, r.lengthKm - r.takagiB) + "\n" + getString(R.string.val_fl_from_end, fmt(r.takagiB, 2), nameB), false);
    }

    /** A cross-check result, always expressed as km from end A so the column is comparable. */
    private String methodValue(Result r, double kmFromA) {
        double delta = kmFromA - mainKmA(r);
        return getString(R.string.val_fl_method, fmt(kmFromA, 2), (delta >= 0 ? "+" : "") + fmt(delta, 2));
    }

    private void renderDetails(Result r, String nameA, String nameB) {
        LinearLayout box = findViewById(R.id.containerFlDetails);
        box.removeAllViews();
        addRow(box, getString(R.string.lbl_fl_det_rf), Double.isNaN(r.rf) ? getString(R.string.val_fl_na) : fmt(r.rf, 2) + " Ω", false);
        addRow(box, getString(R.string.lbl_fl_det_dir, nameA), dirText(r.dirA), false);
        addRow(box, getString(R.string.lbl_fl_det_dir, nameB), dirText(r.dirB), false);
        addRow(box, getString(R.string.lbl_fl_det_delta), fmt(r.syncAngleDeg, 1) + "°", false);
        if (!Double.isNaN(r.inceptionDiffMs)) {
            addRow(box, getString(R.string.lbl_fl_det_inc_diff), fmt(r.inceptionDiffMs, 2) + " ms", false);
        }
        addRow(box, getString(R.string.lbl_fl_det_window), getString(R.string.val_fl_window, fmt(r.offsetCycles, 3), r.windowsUsed), false);
        addRow(box, getString(R.string.lbl_fl_det_model), getString(r.distributedModel ? R.string.val_fl_model_dist : R.string.val_fl_model_lumped), false);
        addEndDetails(box, r.endA, nameA);
        addEndDetails(box, r.endB, nameB);
    }

    private void addEndDetails(LinearLayout box, EndSummary e, String name) {
        String src = getString("I".equals(e.inceptionSource) ? R.string.val_fl_src_i
                : "V".equals(e.inceptionSource) ? R.string.val_fl_src_v : R.string.val_fl_src_t);
        addRow(box, getString(R.string.lbl_fl_det_record, name),
                getString(R.string.val_fl_record, fmt(e.fs, 0), e.samplesPerCycle, e.fileType, fmt(e.inceptionTime * 1000, 1), src), false);
    }

    private String dirText(int dir) {
        return getString(dir > 0 ? R.string.val_fl_dir_fwd : dir < 0 ? R.string.val_fl_dir_rev : R.string.val_fl_dir_unknown);
    }

    private void addRow(LinearLayout box, String label, String value, boolean highlight) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        l.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_size_body_small));
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setGravity(android.view.Gravity.END);
        v.setTextColor(highlight ? resolveColor(com.google.android.material.R.attr.colorPrimary) : ContextCompat.getColor(this, R.color.text_primary));
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_size_body_small));
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        View div = new View(this);
        div.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
        box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void renderChart(Result r, String nameA, String nameB) {
        LineChart chart = findViewById(R.id.chartFlProfile);
        // The curves are the sequence the main method solved with (see DoubleEndedFaultLocator).
        String quantity = r.sequenceUsed == 2 ? "|V2|" : r.superimposed ? "|ΔV1|" : "|V1|";
        ((TextView) findViewById(R.id.tvFlProfileYLabel)).setText(getString(R.string.lbl_fl_axis_y, quantity));
        ((TextView) findViewById(R.id.tvFlProfileXLabel)).setText(getString(R.string.lbl_fl_axis_x, nameA));
        if (r.profileKm == null) {
            chart.clear();
            return;
        }
        List<Entry> ea = new ArrayList<>(), eb = new ArrayList<>();
        for (int k = 0; k < r.profileKm.length; k++) {
            ea.add(new Entry((float) r.profileKm[k], (float) r.profileA[k]));
            eb.add(new Entry((float) r.profileKm[k], (float) r.profileB[k]));
        }
        int textColor = ContextCompat.getColor(this, R.color.text_secondary);
        LineDataSet da = new LineDataSet(ea, getString(R.string.lbl_fl_curve_from, nameA));
        LineDataSet db = new LineDataSet(eb, getString(R.string.lbl_fl_curve_from, nameB));
        int colorA = resolveColor(com.google.android.material.R.attr.colorPrimary);
        int colorB = ContextCompat.getColor(this, R.color.unit_accent_2);
        for (LineDataSet ds : new LineDataSet[]{da, db}) {
            ds.setDrawCircles(false);
            ds.setDrawValues(false);
            ds.setLineWidth(2f);
            ds.setHighlightEnabled(false);
        }
        da.setColor(colorA);
        db.setColor(colorB);
        chart.setData(new com.github.mikephil.charting.data.LineData(da, db));
        chart.getDescription().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getAxisLeft().setTextColor(textColor);
        chart.getLegend().setTextColor(textColor);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setTextColor(textColor);
        x.removeAllLimitLines();
        LimitLine ll = new LimitLine((float) mainKmA(r), fmt(mainKmA(r), 2) + " km");
        ll.setLineColor(ContextCompat.getColor(this, R.color.status_danger));
        ll.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
        ll.setLineWidth(1.5f);
        ll.enableDashedLine(10f, 6f, 0f);
        x.addLimitLine(ll);
        chart.setTouchEnabled(true);
        chart.setPinchZoom(true);
        chart.setNoDataTextColor(textColor);
        chart.setBackgroundColor(Color.TRANSPARENT);
        chart.invalidate();
    }

    private String phasorTable(Result r, String nameA, String nameB) {
        StringBuilder sb = new StringBuilder();
        appendPhasors(sb, r.endA, nameA);
        sb.append('\n');
        appendPhasors(sb, r.endB, nameB);
        return sb.toString().trim();
    }

    private void appendPhasors(StringBuilder sb, EndSummary e, String name) {
        sb.append(name).append('\n');
        sb.append(String.format(Locale.US, "%-4s %22s %22s%n", "", getString(R.string.lbl_fl_prefault), getString(R.string.lbl_fl_fault)));
        String[] names = {"Va", "Vb", "Vc", "Ia", "Ib", "Ic", "V1", "V2", "V0", "I1", "I2", "I0"};
        for (int k = 0; k < names.length; k++) {
            boolean isV = names[k].startsWith("V");
            sb.append(String.format(Locale.US, "%-4s %22s %22s%n", names[k],
                    e.hasPrefault ? phasorText(pick(e.pre, k), isV, e.displayRefAngle) : "-",
                    phasorText(pick(e.flt, k), isV, e.displayRefAngle)));
        }
    }

    private static Cx pick(Phasors p, int k) {
        switch (k) {
            case 0: case 1: case 2: return p.v[k];
            case 3: case 4: case 5: return p.i[k - 3];
            case 6: return p.v1;
            case 7: return p.v2;
            case 8: return p.v0;
            case 9: return p.i1;
            case 10: return p.i2;
            default: return p.i0;
        }
    }

    private static String phasorText(Cx x, boolean isV, double refAngle) {
        double ang = Math.toDegrees(x.arg() - refAngle);
        while (ang > 180) ang -= 360;
        while (ang <= -180) ang += 360;
        return isV ? String.format(Locale.US, "%9.2f kV %7.1f°", x.abs() / 1000, ang)
                : String.format(Locale.US, "%9.1f A  %7.1f°", x.abs(), ang);
    }

    /** PLN-style R/S/T naming, matching the DFR Viewer's assessment phase options. */
    private static String rstName(String type) {
        switch (type) {
            case "AG": return "R-N";
            case "BG": return "S-N";
            case "CG": return "T-N";
            case "AB": return "R-S";
            case "BC": return "S-T";
            case "CA": return "R-T";
            case "ABG": return "RS-N";
            case "BCG": return "ST-N";
            case "CAG": return "RT-N";
            case "ABC": return "R-S-T";
            default: return "?";
        }
    }

    // ------------------------------------------------------------------
    // Report sharing
    // ------------------------------------------------------------------

    private void shareReport() {
        Result r = lastResult;
        if (r == null) return;
        String nameA = resultNameA, nameB = resultNameB;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.ttl_fl_report)).append('\n');
        sb.append(nameA).append(" – ").append(nameB).append(" (").append(fmt(r.lengthKm, 2)).append(" km)\n\n");
        if (viewingSaved != null) {
            if (!viewingSaved.lineConfig.isEmpty()) {
                sb.append(getString(R.string.lbl_fl_line_config)).append(": ").append(viewingSaved.lineConfig).append('\n');
            }
        } else if (selectedLine != null) {
            sb.append(getString(R.string.lbl_fl_line_config)).append(": ").append(selectedLine.routeLabel())
                    .append(" (").append(selectedLine.bay).append(')');
            if (selectedLineEdited) sb.append(' ').append(getString(R.string.lbl_fl_line_config_edited));
            sb.append('\n');
        }
        sb.append(getString(R.string.lbl_fl_fault_type, rstName(r.faultType), r.faultType)).append('\n');
        sb.append(getString(R.string.lbl_fl_from, nameA)).append(": ").append(fmt(mainKmA(r), 2)).append(" km\n");
        sb.append(getString(R.string.lbl_fl_from, nameB)).append(": ").append(fmt(r.lengthKm - mainKmA(r), 2)).append(" km\n");
        if (syncAsMain) sb.append("(").append(getString(R.string.lbl_fl_method_sync_v)).append(")\n");
        if (!Double.isNaN(r.spreadKm)) sb.append(getString(R.string.msg_fl_spread, fmt(r.spreadKm, 2), r.windowsUsed)).append('\n');
        sb.append('\n').append(getString(R.string.lbl_fl_methods_header)).append('\n');
        appendRows(sb, findViewById(R.id.containerFlMethods));
        sb.append('\n').append(getString(R.string.lbl_fl_details_header)).append('\n');
        appendRows(sb, findViewById(R.id.containerFlDetails));
        LinearLayout warn = findViewById(R.id.containerFlWarnings);
        if (warn.getChildCount() > 0) {
            sb.append('\n');
            for (int i = 0; i < warn.getChildCount(); i++) sb.append(((TextView) warn.getChildAt(i)).getText()).append('\n');
        }
        sb.append('\n').append(getString(R.string.lbl_fl_sync_header)).append('\n')
                .append(((TextView) findViewById(R.id.tvFlSyncInfo)).getText()).append('\n')
                .append(currentAlignment != null && currentAlignment.valid ? angleTable(currentAlignment) : "").append('\n');
        String recA = viewingSaved != null ? viewingSaved.recordA : endA.cfg != null ? endA.cfg.getName() : "-";
        String recB = viewingSaved != null ? viewingSaved.recordB : endB.cfg != null ? endB.cfg.getName() : "-";
        sb.append('\n').append(getString(R.string.lbl_fl_record_files)).append('\n');
        sb.append("A: ").append(recA).append(" (").append(r.endA.triggerText).append(")\n");
        sb.append("B: ").append(recB).append(" (").append(r.endB.triggerText).append(")\n");
        sb.append('\n').append(phasorTable(r, nameA, nameB)).append('\n');

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ttl_fl_report) + " " + nameA + " – " + nameB);
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(intent, getString(R.string.btn_fl_share)));
    }

    private static void appendRows(StringBuilder sb, LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View row = box.getChildAt(i);
            if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() < 2) continue;
            CharSequence label = ((TextView) ((LinearLayout) row).getChildAt(0)).getText();
            CharSequence value = ((TextView) ((LinearLayout) row).getChildAt(1)).getText();
            sb.append("• ").append(label).append(": ").append(value.toString().replace('\n', ' ')).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void disconnectLater(Iec61850DfrClient client) {
        Runnable task = () -> {
            client.disconnect();
            if (openClient == client) openClient = null;
        };
        // onDestroy shuts the executor down, but a relay scan still running then finishes later
        // and lands here (as does the picker's dismiss listener) - queueing onto the dead
        // executor crashed the app. disconnect() is synchronized and idempotent, so a throwaway
        // thread racing onDestroy's own disconnect is fine.
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            new Thread(task).start();
        }
    }

    /** Closing or already gone - a background relay task may still be finishing after that. */
    private boolean isGone() {
        return isFinishing() || isDestroyed();
    }

    private void showInfoDialog(String title, String body) {
        if (isGone()) return; // background work finishing after the screen closed
        View v = getLayoutInflater().inflate(R.layout.dialog_fl_info, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvFlInfoTitle)).setText(title);
        ((TextView) v.findViewById(R.id.tvFlInfoBody)).setText(body);
        v.findViewById(R.id.btnFlInfoClose).setOnClickListener(x -> dialog.dismiss());
        dialog.show();
    }

    private AlertDialog cardDialog(View v) {
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog).setView(v).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }
        return dialog;
    }

    private void showProgress(String label) {
        runOnUiThread(() -> {
            tvProgress.setText(label);
            overlay.setVisibility(View.VISIBLE);
        });
    }

    private void hideProgress() {
        runOnUiThread(() -> overlay.setVisibility(View.GONE));
    }

    private ArrayAdapter<String> styledAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                ((TextView) v).setTextSize(13);
                ((TextView) v).setSingleLine(true);
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                return v;
            }
        };
    }

    private static class Selection implements AdapterView.OnItemSelectedListener {
        private final Runnable action;

        Selection(Runnable action) { this.action = action; }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            action.run();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) { }
    }

    private int resolveColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    /** Accepts both "0.07" and "0,07" (Indonesian keyboards); NaN when empty/invalid. */
    private static double parseNumber(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double orZero(double v) { return Double.isNaN(v) ? 0 : v; }

    private static double parsePositive(String s, double def) {
        double v = parseNumber(s);
        return v > 0 ? v : def;
    }

    private static String fmt(double v, int decimals) {
        if (Double.isNaN(v)) return "-";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    private static String fmtPlain(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
