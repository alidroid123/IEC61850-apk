package com.alidev.dfrtools.dfr;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DfrViewerActivity extends BaseActivity {

    private static final int PICK_CFG_FILE = 1;
    private static final int PICK_DAT_FILE = 2;

    private static final String[] PHASA_OPTIONS_DEFAULT = {"R-N","S-N","T-N","RS-N","RT-N","ST-N","R-S","R-T","S-T","R-S-T","RST-N"};
    private static final String[] PHASA_OPTIONS_T_DIFF = {"R_HV","S_HV","T_HV","RS_HV","RT_HV","ST_HV","N_HV","R_LV","S_LV","T_LV","RS_LV","RT_LV","ST_LV","N_LV"};
    private static final String[] PHASA_OPTIONS_OCR = {"R-S","S-T","R-T","RST","R-N","S-N","T-N"};

    private ProgressBar progressBar;
    private View controlsLayout, layoutIncidentInfo, layoutFloatingZoom;
    private Spinner spValueType, spScaleType;
    private RadioGroup rgCursorSelection;
    private TextView txtCursorDelta, txtFloatingZoom, txtFileName, txtTopDate, txtTopTrigger, txtResolutionInfo, txtFolderHeader;
    private EditText etTopTitle;
    private ImageView btnLockZoom, btnLockCursor;
    private RecyclerView rvCharts;
    private DfrChartAdapter adapter;

    private boolean isZoomLocked = false, isCursorLocked = false;
    private int resolutionPercent;

    private int getInitialResolution() {
        return getResources().getInteger(R.integer.config_dfr_default_resolution_percent);
    }
    private final ComtradeParser parser = new ComtradeParser();
    private List<float[]> rawDataList = new ArrayList<>();
    private final List<int[]> digitalDataList = new ArrayList<>();
    private final List<Float> timeDataList = new ArrayList<>();
    private final List<String> cfgLines = new ArrayList<>();
    private final List<String> datLines = new ArrayList<>();

    // Dynamic mapping structure
    private static class MappingItem {
        String label;
        int analogIdx = -1;
        boolean isCurrent = true; // For unit A vs V
        MappingItem(String l, boolean c) { this.label = l; this.isCurrent = c; }
    }
    
    private String currentTemplate = "DISTANCE";
    private final List<MappingItem> mainMappings = new ArrayList<>();
    private final List<Integer> extraAnalogIndices = new ArrayList<>();

    private Highlight currentHighlight1 = null;
    private Highlight currentHighlight2 = null;
    private Highlight currentCtim1 = null;
    private Highlight currentCtim2 = null;
    private final Matrix sharedMatrix = new Matrix();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private void safeExecute(Runnable task) {
        if (!executor.isShutdown()) {
            try {
                executor.execute(task);
            } catch (Exception ignored) {}
        }
    }

    private Highlight[] getActiveCursors() {
        List<Highlight> hs = new ArrayList<>();
        if (currentHighlight1 != null) { Highlight h = new Highlight(currentHighlight1.getX(), 0, 0); h.setDataIndex(0); hs.add(h); }
        if (currentHighlight2 != null) { Highlight h = new Highlight(currentHighlight2.getX(), 0, 0); h.setDataIndex(1); hs.add(h); }
        if (currentCtim1 != null) { Highlight h = new Highlight(currentCtim1.getX(), 0, 0); h.setDataIndex(2); hs.add(h); }
        if (currentCtim2 != null) { Highlight h = new Highlight(currentCtim2.getX(), 0, 0); h.setDataIndex(3); hs.add(h); }
        return hs.toArray(new Highlight[0]);
    }

    private void updateAllCursors() {
        Highlight[] hArray = getActiveCursors();
        for (int i = 0; i < rvCharts.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = rvCharts.getChildViewHolder(rvCharts.getChildAt(i));
            if (holder instanceof DfrChartAdapter.ChartViewHolder) {
                DfrChartAdapter.ChartViewHolder vh = (DfrChartAdapter.ChartViewHolder) holder;
                int pos = vh.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= adapter.models.size()) continue;
                
                ChartModel m = adapter.models.get(pos);
                if (m.isDigital) {
                    if (vh.digitalView != null) vh.digitalView.setSyncState(sharedMatrix, hArray);
                } else {
                    vh.chart.highlightValues(hArray);
                }
                vh.updateCursorText(m);
            }
        }
        updateCursorDeltaDisplay();
    }

    private void updateResultAssessment() {
        for (int i = 0; i < rvCharts.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = rvCharts.getChildViewHolder(rvCharts.getChildAt(i));
            if (holder instanceof DfrChartAdapter.ResultViewHolder) {
                ((DfrChartAdapter.ResultViewHolder) holder).refreshLiveValues();
            }
        }
    }

    private void updateCursorDeltaDisplay() {
        if (currentCtim1 != null && currentCtim2 != null) {
            float x1 = currentCtim1.getX(), x2 = currentCtim2.getX(), deltaMs = Math.abs(x2 - x1) * 1000f;
            txtCursorDelta.setText(String.format(java.util.Locale.US, "%s = %.1f ms", (x2 >= x1) ? "T2 - T1" : "T1 - T2", deltaMs)); txtCursorDelta.setVisibility(View.VISIBLE);
        } else txtCursorDelta.setVisibility(View.GONE);
    }

    private AlertDialog activeDialog;

    private boolean isAutoLoading = false;
    private Uri pendingDatUri = null;
    private static final String[] COMPANION_EXTENSIONS = {".hdr", ".inf", ".xml"};
    private final java.util.LinkedHashMap<String, Uri> companionFileUris = new java.util.LinkedHashMap<>();

    // State persistence for assessment section
    private static class AssessmentState {
        String title = "", note = "";
        int phasaPos = 0;
        int cursorIMode = 0; // 0:C1, 1:C2, 2:C1&C2
        int cursorVMode = 1; // 0:C1, 1:C2, 2:C1&C2
        final java.util.Map<String, String> lockedValues = new java.util.HashMap<>();
        final java.util.Map<String, RowState> rowStates = new java.util.HashMap<>();
        
        static class RowState {
            boolean checked = false; // Default to unchecked
            int mapPos = 0, valPos = 0;
            String customParam = "", manualValue = "";
        }
        RowState getRow(String key) {
            if (!rowStates.containsKey(key)) rowStates.put(key, new RowState());
            return rowStates.get(key);
        }
    }
    private final AssessmentState assessmentState = new AssessmentState();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dfr_viewer);
        initViews();
        
        Intent intent = getIntent();
        if (intent != null) {
            String folderName = intent.getStringExtra("folder_name");
            if (folderName != null && !folderName.isEmpty()) {
                txtFolderHeader.setText(folderName);
                txtFolderHeader.setVisibility(View.VISIBLE);
                // IF from history (folder name present), disable auto dialog
                isAutoLoading = true; 
            }

            String pairedDat = intent.getStringExtra("paired_dat_uri");
            if (pairedDat != null) {
                this.pendingDatUri = Uri.parse(pairedDat);
                this.isAutoLoading = true;
            }

            if (intent.getData() != null) {
                Uri uri = intent.getData();
                String lowUri = uri.toString().toLowerCase();
                if (lowUri.endsWith(".cfg")) {
                    loadCfg(uri);
                } else {
                    // Opened via an external VIEW intent on a .dat/.hdr/.inf/.xml companion file
                    // rather than the .cfg itself - locate the sibling .cfg and load that instead.
                    Uri cfgUri = null;
                    for (String ext : new String[]{".dat", ".hdr", ".inf", ".xml"}) {
                        if (lowUri.endsWith(ext)) { cfgUri = findSiblingFile(uri, ext, ".cfg"); break; }
                    }
                    if (cfgUri != null) {
                        loadCfg(cfgUri);
                    } else {
                        Toast.makeText(this, R.string.msg_view_needs_cfg_pair, Toast.LENGTH_LONG).show();
                        pickCfgFile();
                    }
                }
            } else if (!isAutoLoading) {
                checkLastOpened();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void checkLastOpened() {
        android.content.SharedPreferences prefs = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String lastCfg = prefs.getString("last_cfg_uri", null);
        String lastDat = prefs.getString("last_dat_uri", null);
        
        if (lastCfg != null && lastDat != null) {
            try {
                Uri cfgUri = Uri.parse(lastCfg);
                Uri datUri = Uri.parse(lastDat);
                isAutoLoading = true;
                this.pendingDatUri = datUri;
                loadCfg(cfgUri);
            } catch (Exception ignored) {}
        }
    }

    private void initViews() {
        progressBar = findViewById(R.id.progressBar);
        controlsLayout = findViewById(R.id.controlsLayout);
        spValueType = findViewById(R.id.spinnerValueType);
        spScaleType = findViewById(R.id.spinnerScaleType);
        rgCursorSelection = findViewById(R.id.rgCursorSelection);
        txtCursorDelta = findViewById(R.id.txtCursorDelta);
        layoutFloatingZoom = findViewById(R.id.layoutFloatingZoom);
        txtFloatingZoom = findViewById(R.id.txtFloatingZoom);
        txtFileName = findViewById(R.id.txtFileName);
        rvCharts = findViewById(R.id.rvCharts);
        btnLockZoom = findViewById(R.id.btnLockZoom);
        btnLockCursor = findViewById(R.id.btnLockCursor);

        layoutIncidentInfo = findViewById(R.id.layoutIncidentInfo);
        etTopTitle = findViewById(R.id.etTopTitle);
        txtTopDate = findViewById(R.id.txtTopDate);
        txtTopTrigger = findViewById(R.id.txtTopTrigger);
        txtResolutionInfo = findViewById(R.id.txtResolutionInfo);
        txtFolderHeader = findViewById(R.id.txtFolderHeader);

        resolutionPercent = getInitialResolution();
        txtResolutionInfo.setOnClickListener(v -> showResolutionDialog());
        updateResolutionDisplay();

        etTopTitle.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void afterTextChanged(android.text.Editable s) { assessmentState.title = s.toString(); }
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
        });

        btnLockZoom.setOnClickListener(v -> toggleZoomLock());
        btnLockCursor.setOnClickListener(v -> toggleCursorLock());

        rvCharts.setLayoutManager(new LinearLayoutManager(this));
        int cacheSize = getResources().getInteger(R.integer.config_dfr_rv_cache_size);
        rvCharts.setItemViewCacheSize(cacheSize);
        rvCharts.getRecycledViewPool().setMaxRecycledViews(0, cacheSize); 
        rvCharts.getRecycledViewPool().setMaxRecycledViews(1, cacheSize);
        
        adapter = new DfrChartAdapter();
        rvCharts.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnOpenCfg).setOnClickListener(v -> pickCfgFile());
        findViewById(R.id.btnSettings).setOnClickListener(this::showSettingsMenu);
    }

    private void toggleZoomLock() {
        isZoomLocked = !isZoomLocked;
        int colorGold = ContextCompat.getColor(this, R.color.dfr_chart_cursor_time);
        btnLockZoom.setImageTintList(android.content.res.ColorStateList.valueOf(isZoomLocked ? colorGold : Color.WHITE));
        Toast.makeText(this, isZoomLocked ? R.string.msg_view_zoom_locked : R.string.msg_view_zoom_unlocked, Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void toggleCursorLock() {
        isCursorLocked = !isCursorLocked;
        int colorGold = ContextCompat.getColor(this, R.color.dfr_chart_cursor_time);
        btnLockCursor.setImageTintList(android.content.res.ColorStateList.valueOf(isCursorLocked ? colorGold : Color.WHITE));
        Toast.makeText(this, isCursorLocked ? R.string.msg_view_cursor_locked : R.string.msg_view_cursor_unlocked, Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void showSettingsMenu(View v) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_viewer_settings, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnMenuConfig).setOnClickListener(v1 -> {
            dialog.dismiss();
            if (parser.channelNames != null) showChannelMappingDialog(parser.channelNames);
        });

        dialogView.findViewById(R.id.btnMenuRes).setOnClickListener(v1 -> {
            dialog.dismiss();
            showResolutionDialog();
        });

        dialogView.findViewById(R.id.btnMenuReset).setOnClickListener(v1 -> {
            dialog.dismiss();
            resetZoom();
        });

        dialogView.findViewById(R.id.btnMenuCompanion).setOnClickListener(v1 -> {
            dialog.dismiss();
            showCompanionFilesDialog();
        });

        dialogView.findViewById(R.id.btnMenuCancel).setOnClickListener(v1 -> dialog.dismiss());

        dialog.show();
    }

    private void updateResolutionDisplay() {
        if (txtResolutionInfo != null) {
            txtResolutionInfo.setText(getString(R.string.lbl_view_res_info_prefix, resolutionPercent));
        }
    }

    private void showResolutionDialog() {
        if (isFinishing()) return;
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_resolution, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        android.widget.SeekBar seekBar = dialogView.findViewById(R.id.seekBarResolution);
        TextView txtVal = dialogView.findViewById(R.id.txtResolutionValue);
        
        int totalData = rawDataList.size();
        seekBar.setMax(99);
        seekBar.setProgress(resolutionPercent - 1);
        
        int currentData = (int) (totalData * (resolutionPercent / 100.0f));
        if (currentData < 100 && totalData > 100) currentData = 100;
        txtVal.setText(getString(R.string.val_view_res_detail_label, resolutionPercent, currentData));
        
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                resolutionPercent = progress + 1;
                int dataCount = (int) (totalData * (resolutionPercent / 100.0f));
                if (dataCount < 100 && totalData > 100) dataCount = 100;
                txtVal.setText(getString(R.string.val_view_res_detail_label, resolutionPercent, dataCount));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        
        dialogView.findViewById(R.id.btnApplyRes).setOnClickListener(view -> {
            dialog.dismiss();
            updateResolutionDisplay();
            if (!rawDataList.isEmpty()) plotData();
        });
        dialogView.findViewById(R.id.btnCancelRes).setOnClickListener(view -> dialog.dismiss());
        
        dialog.show();
    }

    private void setupToggles() {
        spValueType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"spontaneous", "RMS"}));
        spScaleType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{getString(R.string.lbl_view_scale_secondary), getString(R.string.lbl_view_scale_primary)}));
        
        // Default selection: spontaneous (index 0)
        spValueType.setSelection(0);
        
        // Set default Chart View to Primary
        spScaleType.setSelection(1);
        
        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (!rawDataList.isEmpty()) plotData(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        spValueType.setOnItemSelectedListener(l);
        spScaleType.setOnItemSelectedListener(l);
        
        rgCursorSelection.setOnCheckedChangeListener((g, id) -> {
            String msg = getString(R.string.msg_view_chart_swipe_prefix);
            if (id == R.id.rbCursor1) msg += getString(R.string.lbl_view_label_c1) + " (" + getString(R.string.lbl_view_color_cyan) + ")";
            else if (id == R.id.rbCursor2) msg += getString(R.string.lbl_view_label_c2) + " (" + getString(R.string.lbl_view_color_magenta) + ")";
            else msg += "T" + (id == R.id.rbCtim1 ? "1" : "2") + " (" + getString(R.string.lbl_view_color_yellow) + ")";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void resetZoom() {
        sharedMatrix.reset();
        adapter.notifyDataSetChanged();
        layoutFloatingZoom.setVisibility(View.GONE);
    }

    private void pickCfgFile() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_pick_source, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnSourceSystem).setOnClickListener(v -> {
            dialog.dismiss();
            // ACTION_OPEN_DOCUMENT (rather than ACTION_GET_CONTENT) so the returned URI is a real,
            // persistable document URI - needed for the sibling .dat lookup in loadCfg() to have a
            // chance of working, and so takePersistableUriPermission() actually sticks.
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(intent, PICK_CFG_FILE);
            } catch (Exception e) {
                Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
            }
        });

        dialogView.findViewById(R.id.btnSourceHistory).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, InternalFileManagerActivity.class));
        });

        dialogView.findViewById(R.id.btnCancelSource).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void pickDatFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK_DAT_FILE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String fileName = getFileName(uri);
            String lowName = fileName != null ? fileName.toLowerCase() : "";
            
            if (requestCode == PICK_CFG_FILE) {
                if (lowName.endsWith(".cfg")) {
                    loadCfg(uri);
                } else {
                    Toast.makeText(this, getString(R.string.msg_view_wrong_file_cfg, fileName), Toast.LENGTH_LONG).show();
                    pickCfgFile();
                }
            } else if (requestCode == PICK_DAT_FILE) {
                if (lowName.endsWith(".dat")) {
                    loadDat(uri);
                } else {
                    Toast.makeText(this, getString(R.string.msg_view_wrong_file_cfg).replace(".cfg", ".dat").replace("%s", fileName), Toast.LENGTH_LONG).show();
                    pickDatFile();
                }
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) result = cursor.getString(nameIndex);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = (result != null) ? result.lastIndexOf('/') : -1;
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    /**
     * Looks for a sibling document next to `sourceUri` (which must end in `sourceExtLower`),
     * with the same base name but `targetExtLower` instead. Tries, in order: a DocumentsContract
     * document-id swap (works when the provider's doc id encodes a real relative path, e.g. the
     * system Files picker), then raw URI string substitution, then (file:// only) a direct File
     * lookup - checking both the given case and the upper-cased extension, since DFRs are often
     * saved with all-caps extensions. Returns null if no readable sibling is found.
     */
    private Uri findSiblingFile(Uri sourceUri, String sourceExtLower, String targetExtLower) {
        if (sourceUri == null) return null;
        if ("content".equals(sourceUri.getScheme())) {
            try {
                String docId = android.provider.DocumentsContract.getDocumentId(sourceUri);
                if (docId != null && docId.length() > sourceExtLower.length()
                        && docId.regionMatches(true, docId.length() - sourceExtLower.length(), sourceExtLower, 0, sourceExtLower.length())) {
                    String siblingDocId = docId.substring(0, docId.length() - sourceExtLower.length()) + targetExtLower;
                    Uri candidate = android.provider.DocumentsContract.buildDocumentUri(sourceUri.getAuthority(), siblingDocId);
                    try (InputStream test = getContentResolver().openInputStream(candidate)) {
                        if (test != null) return candidate;
                    } catch (Exception ignored) {
                        // Provider doesn't expose a path-based document ID (e.g. Drive, Downloads)
                        // - fall through to the naive string-substitution below.
                    }
                }
            } catch (Exception ignored) {}
        }

        String sourceUriString = sourceUri.toString();
        if (!sourceUriString.toLowerCase().endsWith(sourceExtLower)) return null;
        String targetUriString = sourceUriString.substring(0, sourceUriString.length() - sourceExtLower.length()) + targetExtLower;
        try {
            Uri candidate = Uri.parse(targetUriString);
            try (InputStream test = getContentResolver().openInputStream(candidate)) {
                if (test != null) return candidate;
            } catch (Exception ignored) {
                if ("file".equals(sourceUri.getScheme())) {
                    File sourceFile = new File(sourceUri.getPath());
                    String base = sourceFile.getName();
                    int dot = base.lastIndexOf('.');
                    if (dot != -1) base = base.substring(0, dot);
                    File targetFile = new File(sourceFile.getParent(), base + targetExtLower);
                    if (targetFile.exists()) return Uri.fromFile(targetFile);
                    File targetFileUpper = new File(sourceFile.getParent(), base + targetExtLower.toUpperCase());
                    if (targetFileUpper.exists()) return Uri.fromFile(targetFileUpper);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void showCompanionFilesDialog() {
        if (companionFileUris.isEmpty()) {
            Toast.makeText(this, R.string.msg_view_no_companion_files, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[companionFileUris.size()];
        Uri[] uris = new Uri[companionFileUris.size()];
        int i = 0;
        for (java.util.Map.Entry<String, Uri> entry : companionFileUris.entrySet()) {
            String name = getFileName(entry.getValue());
            labels[i] = name != null ? name : entry.getKey();
            uris[i] = entry.getValue();
            i++;
        }
        new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setTitle(R.string.ttl_view_companion_files)
                .setItems(labels, (d, which) -> openCompanionFile(uris[which]))
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    private void openCompanionFile(Uri uri) {
        try {
            Uri viewUri = uri;
            String mime = null;
            if ("file".equals(uri.getScheme())) {
                viewUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(uri.getPath()));
            } else {
                mime = getContentResolver().getType(uri);
            }
            if (mime == null) mime = "*/*";
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(viewUri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCfg(Uri uri) {
        if (isFinishing()) return;
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
        safeExecute(() -> {
            try {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception(getString(R.string.msg_view_file_not_found));
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                cfgLines.clear();
                String line;
                while ((line = reader.readLine()) != null) cfgLines.add(line);
                reader.close();
                parser.parseCfg(cfgLines);
                getSharedPreferences("dfr_prefs", MODE_PRIVATE).edit().putString("last_cfg_uri", uri.toString()).apply();
                
                // Try to find matching .dat if not already pending, and pick up any companion
                // files (.hdr/.inf/.xml - vendor/standard auxiliary files that sometimes ship
                // alongside a COMTRADE record) sitting next to the .cfg, via findSiblingFile().
                if (pendingDatUri == null) pendingDatUri = findSiblingFile(uri, ".cfg", ".dat");
                companionFileUris.clear();
                for (String ext : COMPANION_EXTENSIONS) {
                    Uri companion = findSiblingFile(uri, ".cfg", ext);
                    if (companion != null) companionFileUris.put(ext, companion);
                }

                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    updateFileNameDisplay(uri);
                    updateIncidentInfoDisplay();
                    if (pendingDatUri != null) {
                        loadDat(pendingDatUri);
                        pendingDatUri = null;
                    } else {
                        Toast.makeText(this, R.string.msg_view_dat_not_found, Toast.LENGTH_SHORT).show();
                        pickDatFile();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.msg_view_file_load_fail, e.getMessage()), Toast.LENGTH_LONG).show();
                    isAutoLoading = false;
                    pendingDatUri = null;
                });
            }
        });
    }

    private void updateIncidentInfoDisplay() {
        if (parser.cfgLines != null && !parser.cfgLines.isEmpty()) {
            try {
                String[] tl = parser.cfgLines.get(parser.cfgLines.size() - 3).split(",");
                String rawDate = tl[0].trim(); // Format: DD/MM/YYYY
                String triggerTime = tl[1].trim();

                // Format Date to: Hari, DD-MMMM-YYYY
                java.text.SimpleDateFormat sdfIn = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US);
                java.util.Date date = sdfIn.parse(rawDate);
                if (date != null) {
                    java.text.SimpleDateFormat sdfOut = new java.text.SimpleDateFormat("EEEE, dd-MMMM-yyyy", java.util.Locale.getDefault());
                    txtTopDate.setText(sdfOut.format(date));
                }
                txtTopTrigger.setText(triggerTime);
                layoutIncidentInfo.setVisibility(View.VISIBLE);
            } catch (Exception ignored) {
                layoutIncidentInfo.setVisibility(View.GONE);
            }
        }
    }

    private void updateFileNameDisplay(Uri uri) {
        String name = "Unknown File";
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    String found = cursor.getString(nameIndex);
                    if (found != null) name = found;
                }
            }
        } catch (Exception e) { 
            String segment = uri.getLastPathSegment();
            if (segment != null) name = segment;
        }
        
        // Show Station Name if available, otherwise filename
        String headerTitle = (parser.stationName != null && !parser.stationName.isEmpty()) ? parser.stationName : name;
        txtFileName.setText(headerTitle);

        // Fill Incident Title without extension
        String title = name;
        int dot = title.lastIndexOf('.');
        if (dot != -1) title = title.substring(0, dot);
        etTopTitle.setText(title);
    }

    private void loadDat(Uri uri) {
        if (isFinishing()) return;
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
        safeExecute(() -> {
            try {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception(getString(R.string.msg_view_file_not_found));
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                datLines.clear();
                String line;
                while ((line = reader.readLine()) != null) datLines.add(line);
                reader.close();
                getSharedPreferences("dfr_prefs", MODE_PRIVATE).edit().putString("last_dat_uri", uri.toString()).apply();
                
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    if (loadSavedMapping()) {
                        processAndPlot();
                    } else if (isAutoLoading) {
                        processAndPlot();
                    } else {
                        showChannelMappingDialog(parser.channelNames);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.msg_view_dat_load_fail, e.getMessage()), Toast.LENGTH_LONG).show();
                    pickDatFile();
                });
            }
        });
    }

    private void showSingleChannelMappingDialog(ChartModel m) {
        if (isFinishing() || parser.channelNames == null) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_device_actions, null); // Reuse action style
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        TextView tvTitle = dialogView.findViewById(R.id.tvActionTitle);
        tvTitle.setText(getString(R.string.ttl_view_channel_config) + " (" + m.label + ")");
        tvTitle.setTextColor(m.color);

        // Replace content with a simple spinner and apply button
        LinearLayout container = (LinearLayout) tvTitle.getParent();
        // Remove existing action buttons
        for (int i = container.getChildCount() - 1; i >= 2; i--) container.removeViewAt(i);

        Spinner spinner = new Spinner(this);
        spinner.setBackgroundResource(R.drawable.bg_spinner);
        ArrayAdapter<String> mapAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, parser.channelNames) {
            @NonNull @Override public View getView(int pos, @Nullable View conv, @NonNull ViewGroup par) {
                TextView tv = (TextView) super.getView(pos, conv, par);
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                return tv;
            }
            @Override public View getDropDownView(int pos, @Nullable View conv, @NonNull ViewGroup par) {
                TextView tv = (TextView) super.getDropDownView(pos, conv, par);
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                return tv;
            }
        };
        spinner.setAdapter(mapAdapter);
        spinner.setSelection(m.analogIdx);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(44 * getResources().getDisplayMetrics().density));
        lp.setMargins(0, (int)(16 * getResources().getDisplayMetrics().density), 0, (int)(24 * getResources().getDisplayMetrics().density));
        container.addView(spinner, lp);

        com.google.android.material.button.MaterialButton btnApply = new com.google.android.material.button.MaterialButton(this);
        btnApply.setText(R.string.btn_view_update_channel);
        btnApply.setBackgroundTintList(android.content.res.ColorStateList.valueOf(m.color));
        btnApply.setOnClickListener(v -> {
            int newIdx = spinner.getSelectedItemPosition();
            if (m.slotIdx >= 0 && m.slotIdx < mainMappings.size()) {
                mainMappings.get(m.slotIdx).analogIdx = newIdx;
                saveMapping();
                dialog.dismiss();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.VISIBLE);
                    plotData();
                });
            }
        });
        container.addView(btnApply);

        com.google.android.material.button.MaterialButton btnCancel = new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCancel.setText(R.string.btn_all_cancel);
        btnCancel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        btnCancel.setStrokeColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider)));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int)(8 * getResources().getDisplayMetrics().density);
        container.addView(btnCancel, clp);

        dialog.show();
    }
    private void showChannelMappingDialog(String[] availableChannels) {
        if (isFinishing()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_channel_mapping, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }
        
        Spinner sTemplate = dialogView.findViewById(R.id.spinner_template);
        LinearLayout mappingContainer = dialogView.findViewById(R.id.containerMappingFields);
        LinearLayout extraContainer = dialogView.findViewById(R.id.containerExtraChannels);
        
        final List<MappingItem> tempMappings = new ArrayList<>();
        for (MappingItem m : mainMappings) {
            MappingItem copy = new MappingItem(m.label, m.isCurrent);
            copy.analogIdx = m.analogIdx;
            tempMappings.add(copy);
        }
        final List<Integer> currentExtras = new ArrayList<>(extraAnalogIndices);

        String[] templates = {"DISTANCE", "LINE DIFF", "T DIFF", "OCR"};
        ArrayAdapter<String> tAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, templates) {
            @NonNull @Override public View getView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                TextView tv = (TextView) super.getView(p, c, pa); tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary)); return tv;
            }
            @Override public View getDropDownView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                TextView tv = (TextView) super.getDropDownView(p, c, pa); tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary)); return tv;
            }
        };
        sTemplate.setAdapter(tAdapter);
        
        int startIdx = 0;
        for (int i = 0; i < templates.length; i++) if (templates[i].equals(currentTemplate)) startIdx = i;
        sTemplate.setSelection(startIdx);

        final boolean[] isInitializing = {true};

        Runnable refreshUI = new Runnable() {
            @Override public void run() {
                mappingContainer.removeAllViews();
                for (MappingItem item : tempMappings) {
                    View fieldRow = getLayoutInflater().inflate(R.layout.item_mapping_spinner_row, mappingContainer, false);
                    TextView tvLabel = fieldRow.findViewById(R.id.txtMappingLabel);
                    Spinner spinner = fieldRow.findViewById(R.id.spinnerMapping);
                    tvLabel.setText(item.label);
                    tvLabel.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, item.isCurrent ? R.color.brand_primary : R.color.status_warning));
                    
                    ArrayAdapter<String> mapAdp = new ArrayAdapter<String>(DfrViewerActivity.this, android.R.layout.simple_spinner_dropdown_item, availableChannels) {
                        @NonNull @Override public View getView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                            TextView tv = (TextView) super.getView(p, c, pa); tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary)); return tv;
                        }
                        @Override public View getDropDownView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                            TextView tv = (TextView) super.getDropDownView(p, c, pa); tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary)); return tv;
                        }
                    };
                    spinner.setAdapter(mapAdp);
                    spinner.setSelection(item.analogIdx >= 0 ? item.analogIdx : 0);
                    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { item.analogIdx = pos; }
                        @Override public void onNothingSelected(AdapterView<?> p) {}
                    });
                    mappingContainer.addView(fieldRow);
                }

                extraContainer.removeAllViews();
                java.util.Set<Integer> selectedMain = new java.util.HashSet<>();
                for (MappingItem m : tempMappings) selectedMain.add(m.analogIdx);
                for (int i = 0; i < availableChannels.length; i++) {
                    if (selectedMain.contains(i)) continue;
                    CheckBox cb = new CheckBox(DfrViewerActivity.this);
                    cb.setText(availableChannels[i]); cb.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary));
                    cb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_size_body_small));
                    cb.setMinHeight(0); cb.setMinimumHeight(0);
                    cb.setPadding(cb.getPaddingLeft(), (int)(2 * getResources().getDisplayMetrics().density), cb.getPaddingRight(), (int)(2 * getResources().getDisplayMetrics().density));
                    
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(0, 0, 0, 0);
                    cb.setLayoutParams(lp);

                    cb.setChecked(currentExtras.contains(i));
                    final int index = i;
                    cb.setOnCheckedChangeListener((b, checked) -> { if (checked) { if (!currentExtras.contains(index)) currentExtras.add(index); } else currentExtras.remove((Integer) index); });
                    extraContainer.addView(cb);
                }
            }
        };

        sTemplate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (isInitializing[0]) { isInitializing[0] = false; refreshUI.run(); return; }
                currentTemplate = templates[pos];
                initMappingsForTemplate(currentTemplate);
                tempMappings.clear();
                applyTemplateLogic(tempMappings, availableChannels);
                refreshUI.run();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        dialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.btn_view_process), (d, which) -> {
            mainMappings.clear(); mainMappings.addAll(tempMappings);
            extraAnalogIndices.clear(); extraAnalogIndices.addAll(currentExtras);
            saveMapping();
            runOnUiThread(() -> { progressBar.setVisibility(View.VISIBLE); plotData(); });
        });
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.btn_all_cancel), (d, which) -> {});
        dialog.show();

        // Style the buttons for better visibility
        com.google.android.material.button.MaterialButton btnPositive = (com.google.android.material.button.MaterialButton) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        com.google.android.material.button.MaterialButton btnNegative = (com.google.android.material.button.MaterialButton) dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        
        if (btnPositive != null) {
            btnPositive.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary));
            btnPositive.setTextColor(Color.WHITE);
            btnPositive.setTextSize(14f);
            btnPositive.setInsetTop(0); btnPositive.setInsetBottom(0); // Compact style
        }
        if (btnNegative != null) {
            btnNegative.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
            btnNegative.setTextSize(14f);
        }
    }

    private void applyTemplateLogic(List<MappingItem> targetList, String[] available) {
        targetList.clear();
        for (MappingItem item : mainMappings) {
            MappingItem copy = new MappingItem(item.label, item.isCurrent);
            String key = item.label.toUpperCase(); int bestIdx = -1;
            for (int i = 0; i < available.length; i++) {
                String name = available[i].toUpperCase();
                if (name.equals(key)) { bestIdx = i; break; }
                if (key.equals("IA") && (name.contains("IA") || name.contains("IL1") || name.contains("IR"))) { bestIdx = i; break; }
                if (key.equals("IB") && (name.contains("IB") || name.contains("IL2") || name.contains("IS"))) { bestIdx = i; break; }
                if (key.equals("IC") && (name.contains("IC") || name.contains("IL3") || name.contains("IT"))) { bestIdx = i; break; }
                if (key.equals("IN") && (name.contains("IN") || name.contains("IG") || name.contains("RES"))) { bestIdx = i; break; }
                if (key.startsWith("VA") && (name.contains("VA") || name.contains("UL1") || name.contains("UR"))) { bestIdx = i; break; }
                if (key.startsWith("VB") && (name.contains("VB") || name.contains("UL2") || name.contains("US"))) { bestIdx = i; break; }
                if (key.startsWith("VC") && (name.contains("VC") || name.contains("UL3") || name.contains("UT"))) { bestIdx = i; break; }
                if (key.equals("VN") && (name.contains("VN") || name.contains("VG"))) { bestIdx = i; break; }
                if (name.contains(key)) { bestIdx = i; break; }
            }
            copy.analogIdx = (bestIdx != -1) ? bestIdx : 0; targetList.add(copy);
        }
    }

    private void processAndPlot() {
        if (datLines.isEmpty() && !rawDataList.isEmpty()) { plotData(); return; }
        if (isFinishing()) return;
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
        safeExecute(() -> {
            try {
                rawDataList = parser.parseDat(datLines, parser.analogCount, parser.digitalCount, digitalDataList, timeDataList); datLines.clear(); 
                if (!rawDataList.isEmpty()) {
                    int targetCount = com.alidev.dfrtools.utils.ConfigHelper.getDfrTargetPoints(this);
                    float targetPercent = ((float)targetCount / rawDataList.size()) * 100.0f;
                    resolutionPercent = Math.min(100, Math.max(1, Math.round(targetPercent)));
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    if (rawDataList.isEmpty()) Toast.makeText(this, R.string.msg_view_empty_data, Toast.LENGTH_LONG).show();
                    else {
                        updateResolutionDisplay(); controlsLayout.setVisibility(View.VISIBLE); setupToggles();
                        if (mainMappings.isEmpty()) {
                            // Default: Show all analog channels from CFG, grouping Current then Voltage
                            List<MappingItem> currents = new ArrayList<>();
                            List<MappingItem> voltages = new ArrayList<>();
                            List<MappingItem> others = new ArrayList<>();
                            for (int i = 0; i < parser.analogCount; i++) {
                                String label = parser.channelNames[i];
                                String lu = label.toUpperCase();
                                boolean isI = lu.contains("I") || lu.contains("A") || lu.contains("CURR");
                                boolean isV = lu.contains("V") || lu.contains("U") || lu.contains("VOLT");
                                MappingItem item = new MappingItem(label, isI);
                                item.analogIdx = i;
                                if (isI) currents.add(item);
                                else if (isV) voltages.add(item);
                                else others.add(item);
                            }
                            mainMappings.addAll(currents);
                            mainMappings.addAll(voltages);
                            mainMappings.addAll(others);
                        }
                        plotData();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.msg_view_general_error, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void plotData() {
        if (isFinishing()) return;
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
        safeExecute(() -> {
            if (timeDataList.isEmpty() || rawDataList.isEmpty()) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                });
                return;
            }
            List<ChartModel> models = new ArrayList<>();
            float freq = Math.max(1, parser.lineFrequency);
            int samples = Math.round((float) parser.sampleRate / freq);
            if (samples < 1) samples = 1;
            float timeStep = 1.0f / Math.max(1, parser.sampleRate);
            boolean isRms = spValueType.getSelectedItemPosition() == 1;
            boolean isPrimary = spScaleType.getSelectedItemPosition() == 1;

            models.add(new ChartModel(getString(R.string.lbl_view_analog_channel_header), true));

            for (int i = 0; i < mainMappings.size(); i++) {
                MappingItem m = mainMappings.get(i);
                if (m.analogIdx < 0 || m.analogIdx >= parser.analogCount) continue;
                models.add(buildAnalogModel(m.analogIdx, m.label, getChannelColor(i, m.isCurrent), isRms, isPrimary, samples, timeStep, i));
            }
            
            for (int extraIdx : extraAnalogIndices) {
                if (extraIdx < 0 || extraIdx >= parser.analogCount) continue;
                String label = (parser.channelNames != null) ? parser.channelNames[extraIdx] : "Extra " + extraIdx;
                models.add(buildAnalogModel(extraIdx, label, Color.GRAY, isRms, isPrimary, samples, timeStep, -1));
            }

            models.add(new ChartModel(getString(R.string.lbl_view_digital_channel_header), true));

            for (int d = 0; d < parser.digitalCount; d++) {
                List<Entry> dEntries = new ArrayList<>(); 
                int totalPoints = digitalDataList.size();
                int minPoints = getResources().getInteger(R.integer.config_dfr_min_visible_points);
                int maxVisiblePoints = (int) (totalPoints * (resolutionPercent / 100.0f));
                if (maxVisiblePoints < minPoints) maxVisiblePoints = minPoints;
                int step = (totalPoints > maxVisiblePoints) ? totalPoints / maxVisiblePoints : 1;
                if (step < 1) step = 1;
                for (int i = 0; i < digitalDataList.size(); i += step) {
                    float val = (float) digitalDataList.get(i)[d];
                    float t = (i < timeDataList.size()) ? timeDataList.get(i) : (i * timeStep) - parser.triggerOffset;
                    dEntries.add(new Entry(t, val));
                }
                float startTime = timeDataList.isEmpty() ? 0 : timeDataList.get(0);
                float endTime = timeDataList.isEmpty() ? 0 : timeDataList.get(timeDataList.size() - 1);
                String dName = (parser.digitalNames != null && d < parser.digitalNames.length) ? parser.digitalNames[d] : "D" + (d + 1);
                ChartModel cm = new ChartModel(dName, ContextCompat.getColor(this, R.color.dfr_digital_block), dEntries, 1.1f, true, startTime, endTime, -1, -1);
                cm.prepareData();
                if (cm.hasTransition) models.add(cm);
            }
            for (ChartModel m : models) if (!m.isDigital && !m.isHeader) m.prepareData();
            runOnUiThread(() -> { if (isFinishing()) return; adapter.setModels(models); progressBar.setVisibility(View.GONE); });
        });
    }

    private int getChannelColor(int index, boolean isCurrent) {
        int[] colors = {
                R.color.dfr_analog_r, R.color.dfr_analog_s, R.color.dfr_analog_t, R.color.dfr_analog_n,
                R.color.brand_primary, R.color.status_warning, R.color.status_safe, R.color.status_info
        };
        return ContextCompat.getColor(this, colors[index % colors.length]);
    }

    private ChartModel buildAnalogModel(int idx, String label, int color, boolean isRms, boolean isPrimary, int samples, float timeStep, int slotIdx) {
        float[] data;
        if (isRms) data = parser.calculateRms(rawDataList, idx, samples);
        else { data = new float[rawDataList.size()]; for (int j = 0; j < data.length; j++) data[j] = (rawDataList.get(j).length > idx) ? rawDataList.get(j)[idx] : 0; }
        // IEEE C37.111 PS flag: 'S' means the file already stores secondary-side values (the
        // common case - multiply by primary/secondary ratio to get primary), 'P' means the file
        // already stores primary-side values (divide by the ratio to get secondary instead, and
        // leave primary mode untouched). Ignoring this flag double-scales PS='P' files.
        boolean storedAsPrimary = parser.unitModes != null && idx < parser.unitModes.length && parser.unitModes[idx] == 'P';
        if (parser.primaryRatios != null && parser.secondaryRatios != null && idx < parser.primaryRatios.length && idx < parser.secondaryRatios.length) {
            float ratio = parser.primaryRatios[idx] / Math.max(0.001f, parser.secondaryRatios[idx]);
            if (isPrimary && !storedAsPrimary) {
                for (int j = 0; j < data.length; j++) data[j] *= ratio;
            } else if (!isPrimary && storedAsPrimary) {
                for (int j = 0; j < data.length; j++) data[j] /= ratio;
            }
        }
        List<Entry> entries = new ArrayList<>();
        int totalPoints = data.length;
        int minPoints = getResources().getInteger(R.integer.config_dfr_min_visible_points);
        int maxVisiblePoints = (int) (totalPoints * (resolutionPercent / 100.0f));
        if (maxVisiblePoints < minPoints) maxVisiblePoints = minPoints;
        int step = (totalPoints > maxVisiblePoints) ? totalPoints / maxVisiblePoints : 1;
        if (step < 1) step = 1;
        float maxVal = 0.1f;
        for (int j = 0; j < data.length; j += step) {
            float t = (j < timeDataList.size()) ? timeDataList.get(j) : j * timeStep;
            entries.add(new Entry(t, data[j]));
            if (Math.abs(data[j]) > maxVal) maxVal = Math.abs(data[j]);
        }
        float startTime = timeDataList.isEmpty() ? 0 : timeDataList.get(0);
        float endTime = timeDataList.isEmpty() ? 0 : timeDataList.get(timeDataList.size() - 1);
        return new ChartModel(label, color, entries, maxVal, false, startTime, endTime, idx, slotIdx);
    }

    private String getMappingKey() {
        if (parser.cfgLines == null || parser.cfgLines.isEmpty()) return null;
        // Identify GI based on first line (Station) and total analog channels
        String stationInfo = parser.cfgLines.get(0);
        return "dfr_map_" + (stationInfo + "_" + parser.analogCount).hashCode();
    }

    private void saveMapping() {
        String key = getMappingKey();
        if (key == null) return;
        
        StringBuilder main = new StringBuilder();
        for (int i = 0; i < mainMappings.size(); i++) {
            main.append(mainMappings.get(i).analogIdx);
            if (i < mainMappings.size() - 1) main.append(",");
        }

        StringBuilder extra = new StringBuilder();
        for (int i = 0; i < extraAnalogIndices.size(); i++) {
            extra.append(extraAnalogIndices.get(i));
            if (i < extraAnalogIndices.size() - 1) extra.append("|");
        }
        
        String mappingData = currentTemplate + ";" + main + ";" + extra;
        getSharedPreferences("dfr_prefs", MODE_PRIVATE).edit()
                .putString(key, mappingData)
                .apply();
    }

    private boolean loadSavedMapping() {
        String key = getMappingKey();
        if (key == null) return false;
        String saved = getSharedPreferences("dfr_prefs", MODE_PRIVATE).getString(key, null);
        if (saved == null) return false;
        try {
            String[] parts = saved.split(";");
            if (parts.length < 2) return false;
            
            currentTemplate = parts[0];
            initMappingsForTemplate(currentTemplate);
            
            String[] indices = parts[1].split(",");
            for (int i = 0; i < Math.min(indices.length, mainMappings.size()); i++) {
                mainMappings.get(i).analogIdx = Integer.parseInt(indices[i]);
            }
            
            extraAnalogIndices.clear();
            if (parts.length > 2 && !parts[2].isEmpty()) {
                for (String s : parts[2].split("\\|")) {
                    extraAnalogIndices.add(Integer.parseInt(s));
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private void initMappingsForTemplate(String template) {
        mainMappings.clear();
        if (template.equals("DISTANCE")) {
            mainMappings.add(new MappingItem("IA", true)); mainMappings.add(new MappingItem("IB", true));
            mainMappings.add(new MappingItem("IC", true)); mainMappings.add(new MappingItem("IN", true));
            mainMappings.add(new MappingItem("VA", false)); mainMappings.add(new MappingItem("VB", false));
            mainMappings.add(new MappingItem("VC", false)); mainMappings.add(new MappingItem("VN", false));
        } else if (template.equals("LINE DIFF")) {
            mainMappings.add(new MappingItem("I DIFF A", true)); mainMappings.add(new MappingItem("I DIFF B", true)); mainMappings.add(new MappingItem("I DIFF C", true));
            mainMappings.add(new MappingItem("IA", true)); mainMappings.add(new MappingItem("IB", true)); mainMappings.add(new MappingItem("IC", true)); mainMappings.add(new MappingItem("IN", true));
            mainMappings.add(new MappingItem("VA", false)); mainMappings.add(new MappingItem("VB", false)); mainMappings.add(new MappingItem("VC", false)); mainMappings.add(new MappingItem("VN", false));
        } else if (template.equals("T DIFF")) {
            mainMappings.add(new MappingItem("I DIFF A", true)); mainMappings.add(new MappingItem("I DIFF B", true)); mainMappings.add(new MappingItem("I DIFF C", true));
            mainMappings.add(new MappingItem("IREF HV", true)); mainMappings.add(new MappingItem("IREF LV", true));
            mainMappings.add(new MappingItem("3I0HV", true)); mainMappings.add(new MappingItem("3I0LV", true));
        } else if (template.equals("OCR")) {
            mainMappings.add(new MappingItem("IA", true)); mainMappings.add(new MappingItem("IB", true));
            mainMappings.add(new MappingItem("IC", true)); mainMappings.add(new MappingItem("IN", true));
        }
    }

    private String getFormattedUnitValue(float value, String typeLabel, int analogIdx) {
        float absVal = Math.abs(value);
        String labelUpper = typeLabel.toUpperCase();
        
        // Frequency check first
        if (labelUpper.contains("FREQ")) {
            return String.format(java.util.Locale.US, "%.1f Hz", value);
        }

        String unit = "";
        if (analogIdx >= 0 && parser.analogUnits != null && analogIdx < parser.analogUnits.length) {
            unit = parser.analogUnits[analogIdx].trim();
        }

        if (unit.isEmpty()) {
            boolean isI = labelUpper.contains("I") || labelUpper.contains("A") || labelUpper.contains("REF") || labelUpper.contains("DIFF");
            unit = isI ? "A" : "V";
        }

        if (absVal == 0) return String.format(java.util.Locale.US, "0 %s", unit);

        // Standard COMTRADE practice: 
        // If unit is base 'A' or 'V', we can auto-scale to mA/kA or mV/kV.
        // If unit is already 'kA' or 'kV', we stay with it (value is already in that scale).
        String unitUpper = unit.toUpperCase();
        if (unitUpper.equals("A") || unitUpper.equals("V")) {
            if (absVal < 1.0f) {
                return String.format(java.util.Locale.US, "%.1f m%s", value * 1000f, unit);
            } else if (absVal >= 1000f) {
                return String.format(java.util.Locale.US, "%.1f k%s", value / 1000f, unit);
            }
        }

        return String.format(java.util.Locale.US, "%.1f %s", value, unit);
    }

    private String getValueString(ChartModel m, float x) {
        float y = findYValue(m.entries, x);
        if (m.isDigital) return y > 0.5 ? "ON" : "OFF";
        return getFormattedUnitValue(y, m.label, m.analogIdx);
    }

    private float findYValue(List<Entry> entries, float x) {
        if (entries == null || entries.isEmpty()) return 0;
        int low = 0, high = entries.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1; float midX = entries.get(mid).getX();
            if (Math.abs(midX - x) < 0.0001) return entries.get(mid).getY();
            else if (midX < x) low = mid + 1; else high = mid - 1;
        }
        if (low >= entries.size()) return entries.get(entries.size() - 1).getY();
        if (high < 0) return entries.get(0).getY();
        return (Math.abs(entries.get(low).getX() - x) < Math.abs(entries.get(high).getX() - x)) ? entries.get(low).getY() : entries.get(high).getY();
    }

    public static class ChartModel {
        String label; int color; List<Entry> entries; float maxY; boolean isDigital; float startTime, totalTime; String digitalSummary = "";
        boolean hasTransition = false;
        LineData cachedData;
        int analogIdx = -1; // To access original rawDataList
        int slotIdx = -1;   // Mapping slot (0-7 for IR-VN)
        boolean isHeader = false;

        ChartModel(String label, int color, List<Entry> entries, float maxY, boolean isDigital, float startTime, float totalTime, int analogIdx, int slotIdx) {
            this.label = label; this.color = color; this.entries = entries; this.maxY = maxY; this.isDigital = isDigital; this.startTime = startTime; this.totalTime = totalTime;
            this.analogIdx = analogIdx;
            this.slotIdx = slotIdx;
        }

        ChartModel(String label, boolean isHeader) {
            this.label = label;
            this.isHeader = isHeader;
        }

        void prepareData() {
            if (isHeader) return;
            calculateDigitalSummary();
            LineDataSet set = new LineDataSet(entries, label);
            set.setDrawCircles(false);
            set.setColor(color);
            set.setDrawValues(false);
            set.setHighlightEnabled(true);
            set.setHighlightLineWidth(1.5f);
            if (isDigital) {
                set.setMode(LineDataSet.Mode.STEPPED);
                set.setDrawFilled(true);
                set.setFillColor(color);
                set.setFillAlpha(100);
            }
            this.cachedData = new LineData(set);
        }

        void calculateDigitalSummary() {
            if (!isDigital || entries.isEmpty()) return;
            
            // Find first entry >= 0.0s (Trigger)
            int startIndex = 0;
            while (startIndex < entries.size() && entries.get(startIndex).getX() < 0) startIndex++;
            if (startIndex >= entries.size()) return;

            StringBuilder sb = new StringBuilder();
            float lastX = 0.0f; // Force start at 0.0ms (trigger)
            float lastY = entries.get(startIndex).getY();
            hasTransition = false;
            
            for (int i = startIndex; i < entries.size(); i++) {
                float currX = entries.get(i).getX();
                float currY = entries.get(i).getY();
                
                if (Math.abs(currY - lastY) > 0.1f) {
                    float durationMs = (currX - lastX) * 1000f;
                    // Aturan: Perubahan 1-0 atau 0-1 di atas 1ms
                    if (durationMs >= 1.0f) {
                        sb.append(String.format(java.util.Locale.US, "[%.1fms-%.1fms][%d-%d]{%.1fms}, ",
                                lastX * 1000, currX * 1000, (int)lastY, (int)currY, durationMs));
                        lastX = currX;
                        lastY = currY;
                        hasTransition = true;
                    }
                }
            }
            
            float finalX = entries.get(entries.size() - 1).getX();
            float finalDur = (finalX - lastX) * 1000f;
            if (finalDur >= 1.0f) {
                sb.append(String.format(java.util.Locale.US, "[%.1fms-%.1fms][%d]{%.1fms}",
                        lastX * 1000, finalX * 1000, (int)lastY, finalDur));
            }
            
            digitalSummary = sb.toString().trim();
            if (digitalSummary.endsWith(",")) digitalSummary = digitalSummary.substring(0, digitalSummary.length() - 1);
        }
    }

    private class DfrChartAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_CHART = 0, TYPE_RESULT = 1, TYPE_HEADER = 2;
        private static final String PAYLOAD_Y_SCALE = "PAYLOAD_Y_SCALE";
        private List<ChartModel> models = new ArrayList<>();
        void setModels(List<ChartModel> models) { 
            this.models = new ArrayList<>(models); 
            notifyDataSetChanged(); 
        }

        public void triggerGlobalYCalibration(DfrLineChart sourceChart) {
            if (models.isEmpty() || sourceChart == null) return;

            final float xMin = sourceChart.getLowestVisibleX();
            final float xMax = sourceChart.getHighestVisibleX();

            // 1. Hitung ulang maxY untuk semua model Analog
            for (ChartModel m : models) {
                if (m.isHeader || m.isDigital || m.entries == null || m.entries.isEmpty()) continue;

                float peak = 0.01f;
                for (Entry e : m.entries) {
                    if (e.getX() >= xMin && e.getX() <= xMax) {
                        float val = Math.abs(e.getY());
                        if (val > peak) peak = val;
                    }
                }
                m.maxY = peak * 1.15f;
            }

            // 2. UPDATE LANGSUNG: Paksa update ke ViewHolder yang sedang terlihat di layar.
            for (int i = 0; i < rvCharts.getChildCount(); i++) {
                RecyclerView.ViewHolder holder = rvCharts.getChildViewHolder(rvCharts.getChildAt(i));
                if (holder instanceof ChartViewHolder) {
                    ChartViewHolder vh = (ChartViewHolder) holder;
                    int pos = vh.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && pos < models.size()) {
                        vh.updateYScaleOnly(models.get(pos));
                    }
                }
            }

            // 3. Notify standar untuk memastikan konsistensi cache di luar layar
            notifyItemRangeChanged(0, models.size(), PAYLOAD_Y_SCALE);
        }

        @Override public int getItemViewType(int position) { 
            if (position >= models.size()) return TYPE_RESULT;
            if (models.get(position).isHeader) return TYPE_HEADER;
            return TYPE_CHART;
        }
        @Override public int getItemCount() { return models.isEmpty() ? 0 : models.size() + 1; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_RESULT) return new ResultViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dfr_result, parent, false));
            if (viewType == TYPE_HEADER) return new HeaderViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dfr_header_group, parent, false));
            return new ChartViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dfr_chart_row, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof ChartViewHolder) ((ChartViewHolder) holder).bind(models.get(position)); 
            else if (holder instanceof ResultViewHolder) ((ResultViewHolder) holder).bind();
            else if (holder instanceof HeaderViewHolder) ((HeaderViewHolder) holder).bind(models.get(position));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_Y_SCALE)) {
                if (holder instanceof ChartViewHolder) {
                    ((ChartViewHolder) holder).updateYScaleOnly(models.get(position));
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle;
            HeaderViewHolder(View v) { super(v); tvTitle = v.findViewById(R.id.txtHeaderTitle); }
            void bind(ChartModel m) { tvTitle.setText(m.label); }
        }

        class ChartViewHolder extends RecyclerView.ViewHolder {
            TextView tvInfo, tvSummary; DfrLineChart chart; DfrDigitalView digitalView;
            ImageView ivEditIcon; View layoutHeader;
            ChartViewHolder(View v) {
                super(v); tvInfo = v.findViewById(R.id.txtChannelInfo); tvSummary = v.findViewById(R.id.txtDigitalSummary); 
                chart = v.findViewById(R.id.rowChart);
                digitalView = v.findViewById(R.id.rowDigitalView);
                ivEditIcon = v.findViewById(R.id.ivEditIcon);
                layoutHeader = v.findViewById(R.id.layoutChannelHeader);
            }
            private void setupChart(boolean isDigital) {
                chart.setDragEnabled(!isZoomLocked); 
                chart.setScaleXEnabled(!isZoomLocked); 
                chart.setScaleYEnabled(false); // CRITICAL: Disable vertical zoom to use our Auto-Fit logic
                chart.setPinchZoom(false); 
                chart.setHighlightPerTapEnabled(!isCursorLocked);
                chart.getDescription().setEnabled(false); chart.getLegend().setEnabled(false);
                chart.getAxisLeft().setEnabled(!isDigital); chart.getAxisRight().setEnabled(false);
                
                // Disable internal auto-scale to use our synchronized manual scaling
                chart.setAutoScaleMinMaxEnabled(false); 
                chart.setMinOffset(0f);
                
                XAxis x = chart.getXAxis(); x.setPosition(XAxis.XAxisPosition.BOTTOM); 
                x.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary)); 
                x.setGridColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_chart_grid)); 
                x.setAvoidFirstLastClipping(false); x.setLabelCount(8, false); x.setTextSize(7f); x.setDrawLabels(!isDigital);
                
                x.setValueFormatter(new ValueFormatter() { @Override public String getFormattedValue(float v) { return String.format(java.util.Locale.US, "%.1f", v); } });
                
                YAxis y = chart.getAxisLeft(); 
                y.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary)); 
                y.setGridColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_chart_grid)); 
                y.setTextSize(7f); y.setDrawLabels(!isDigital);
                y.setValueFormatter(new ValueFormatter() {
                    @Override public String getFormattedValue(float v) {
                        int pos = getAdapterPosition();
                        if (pos == RecyclerView.NO_POSITION || pos >= models.size()) return String.format(java.util.Locale.US, "%.1f", v);
                        ChartModel m = models.get(pos);
                        return getFormattedUnitValue(v, m.label, m.analogIdx);
                    }
                });
                chart.setOnChartGestureListener(new OnChartGestureListener() {
                    @Override public void onChartGestureStart(android.view.MotionEvent me, ChartTouchListener.ChartGesture lastGesture) {}
                    @Override public void onChartGestureEnd(android.view.MotionEvent me, ChartTouchListener.ChartGesture lastGesture) { 
                        triggerGlobalYCalibration(chart); 
                        updateResultAssessment();
                    }
                    @Override public void onChartLongPressed(android.view.MotionEvent me) {}
                    @Override public void onChartDoubleTapped(android.view.MotionEvent me) {}
                    @Override public void onChartSingleTapped(android.view.MotionEvent me) {}
                    @Override public void onChartFling(android.view.MotionEvent me1, android.view.MotionEvent me2, float vx, float vy) {}
                    @Override public void onChartScale(android.view.MotionEvent me, float sx, float sy) { sync(); }
                    @Override public void onChartTranslate(android.view.MotionEvent me, float dx, float dy) { sync(); }
                    private void sync() {
                        sharedMatrix.set(chart.getViewPortHandler().getMatrixTouch()); float[] sharedVals = new float[9]; sharedMatrix.getValues(sharedVals);
                        float scaleX = sharedVals[Matrix.MSCALE_X];
                        txtFloatingZoom.setText(String.format(java.util.Locale.US, "%.2f X", scaleX));
                        layoutFloatingZoom.setVisibility(scaleX > 1.01f ? View.VISIBLE : View.GONE);

                        for (int i = 0; i < rvCharts.getChildCount(); i++) {
                            RecyclerView.ViewHolder holder = rvCharts.getChildViewHolder(rvCharts.getChildAt(i));
                            if (holder instanceof ChartViewHolder && holder != ChartViewHolder.this) {
                                ChartViewHolder vh = (ChartViewHolder) holder; Matrix m = vh.chart.getViewPortHandler().getMatrixTouch(); float[] target = new float[9]; m.getValues(target);
                                target[Matrix.MSCALE_X] = sharedVals[Matrix.MSCALE_X]; target[Matrix.MTRANS_X] = sharedVals[Matrix.MTRANS_X]; m.setValues(target); vh.chart.getViewPortHandler().refresh(m, vh.chart, true);

                                // Digital (boolean) rows are drawn by a separate DfrDigitalView, not
                                // this hidden vh.chart, so the matrix update above has no visible
                                // effect for them - without this they only catch up once the gesture
                                // ends (via triggerGlobalYCalibration), lagging behind during the drag.
                                int pos = vh.getAdapterPosition();
                                if (pos != RecyclerView.NO_POSITION && pos < models.size()
                                        && models.get(pos).isDigital && vh.digitalView != null) {
                                    vh.digitalView.setSyncState(sharedMatrix, getActiveCursors());
                                }
                            }
                        }
                    }
                });
                chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
                    @Override public void onValueSelected(Entry e, Highlight h) {
                        int checkedId = rgCursorSelection.getCheckedRadioButtonId(); if (checkedId == R.id.rbCursor1) currentHighlight1 = h; else if (checkedId == R.id.rbCursor2) currentHighlight2 = h; else if (checkedId == R.id.rbCtim1) currentCtim1 = h; else if (checkedId == R.id.rbCtim2) currentCtim2 = h; updateAllCursors();
                    }
                    @Override public void onNothingSelected() {
                        int checkedId = rgCursorSelection.getCheckedRadioButtonId(); if (checkedId == R.id.rbCursor1) currentHighlight1 = null; else if (checkedId == R.id.rbCursor2) currentHighlight2 = null; else if (checkedId == R.id.rbCtim1) currentCtim1 = null; else if (checkedId == R.id.rbCtim2) currentCtim2 = null; updateAllCursors();
                    }
                });
            }
            void updateYScaleOnly(ChartModel m) {
                if (m.isDigital) {
                    if (digitalView != null) digitalView.setSyncState(sharedMatrix, getActiveCursors());
                    return;
                }
                if (chart == null) return;
                YAxis y = chart.getAxisLeft();
                float limit = Math.max(0.01f, m.maxY);
                y.setAxisMinimum(-limit);
                y.setAxisMaximum(limit);

                chart.calculateOffsets();
                chart.invalidate();
            }
            void bind(ChartModel m) {
                tvInfo.setTextColor(m.color); 
                
                if (m.isDigital) {
                    chart.setVisibility(View.GONE);
                    digitalView.setVisibility(View.VISIBLE);
                    digitalView.setData(m.entries, m.startTime, m.totalTime);
                    digitalView.setSyncState(sharedMatrix, getActiveCursors());
                    
                    tvSummary.setVisibility(View.VISIBLE); 
                    String summary = m.digitalSummary.replace("{", "(").replace("}", ")");
                    android.text.SpannableStringBuilder span = new android.text.SpannableStringBuilder(summary);
                    int start = summary.indexOf("(");
                    int colorGold = ContextCompat.getColor(itemView.getContext(), R.color.dfr_chart_cursor_time);
                    while (start != -1) {
                        int end = summary.indexOf(")", start);
                        if (end != -1) {
                            span.setSpan(new android.text.style.ForegroundColorSpan(colorGold),
                                    start, end + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            start = summary.indexOf("(", end);
                        } else break;
                    }
                    tvSummary.setText(span);
                } else {
                    chart.setVisibility(View.VISIBLE);
                    digitalView.setVisibility(View.GONE);
                    tvSummary.setVisibility(View.GONE);
                    
                    chart.setTag(m.label); setupChart(m.isDigital);
                    float density = getResources().getDisplayMetrics().density;
                    // LOCK Left Offset to 40dp for perfect vertical alignment across all channels
                    float sharedLeftOffset = 40f * density;
                    chart.setViewPortOffsets(sharedLeftOffset, 5f * density, 4f * density, 12f * density);

                    float finalMax = m.maxY * 1.1f; 
                    chart.getAxisLeft().setAxisMinimum(-finalMax); 
                    chart.getAxisLeft().setAxisMaximum(finalMax); 
                    chart.getAxisLeft().setDrawLabels(true); 
                    chart.getXAxis().setDrawLabels(true); 
                    chart.getXAxis().setAxisMinimum(m.startTime); 
                    chart.getXAxis().setAxisMaximum(m.totalTime);
                    
                    float[] mValues = new float[9]; sharedMatrix.getValues(mValues); 
                    float scaleX = mValues[Matrix.MSCALE_X]; 
                    chart.getXAxis().setLabelCount(scaleX > 10 ? 12 : (scaleX > 5 ? 8 : 5), false);
                    
                    ViewGroup.LayoutParams lp = chart.getLayoutParams(); 
                    lp.height = (int) (130 * density); 
                    chart.setLayoutParams(lp);
                    
                    chart.setData(m.cachedData); 
                    
                    // Inject Zoom/Pan state
                    Matrix current = chart.getViewPortHandler().getMatrixTouch();
                    float[] sVals = new float[9], cVals = new float[9];
                    sharedMatrix.getValues(sVals);
                    current.getValues(cVals);
                    cVals[Matrix.MSCALE_X] = sVals[Matrix.MSCALE_X];
                    cVals[Matrix.MTRANS_X] = sVals[Matrix.MTRANS_X];
                    cVals[Matrix.MSCALE_Y] = 1.0f; // Force no vertical zoom
                    cVals[Matrix.MTRANS_Y] = 0f;
                    current.setValues(cVals);
                    chart.getViewPortHandler().refresh(current, chart, false);

                    // Inject Cursor state
                    chart.highlightValues(getActiveCursors());
                    chart.invalidate();
                }
                
                if (ivEditIcon != null) {
                    ivEditIcon.setVisibility((!m.isDigital && !m.isHeader && m.slotIdx != -1) ? View.VISIBLE : View.GONE);
                }

                if (layoutHeader != null) {
                    layoutHeader.setOnClickListener(v -> {
                        if (m.slotIdx != -1) {
                            showSingleChannelMappingDialog(m);
                        } else if (m.isDigital) {
                            Toast.makeText(DfrViewerActivity.this, m.label, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
                updateCursorText(m);
            }
            private void updateCursorText(ChartModel m) {
                String c1Val = currentHighlight1 == null ? "---" : getValueString(m, currentHighlight1.getX());
                String c2Val = currentHighlight2 == null ? "---" : getValueString(m, currentHighlight2.getX());
                
                String extraInfo = "";
                if (!m.isDigital && !m.isHeader && m.analogIdx >= 0 && parser.channelNames != null && m.analogIdx < parser.channelNames.length) {
                    extraInfo = " (" + parser.channelNames[m.analogIdx] + ")";
                }

                String raw = String.format(java.util.Locale.US, "%s | C1 = %s | C2 = %s%s", m.label, c1Val, c2Val, extraInfo);
                android.text.SpannableString span = new android.text.SpannableString(raw);
                
                int c1Start = raw.indexOf("C1 = ");
                if (c1Start != -1) {
                    int c1End = raw.indexOf(" |", c1Start);
                    if (c1End == -1 || c1End < c1Start) c1End = raw.length();
                    span.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_chart_cursor_c1)), c1Start, c1End, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                
                int c2Start = raw.indexOf("C2 = ");
                if (c2Start != -1) {
                    int extraStart = raw.indexOf(" (", c2Start);
                    int c2End = (extraStart != -1) ? extraStart : raw.length();
                    span.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_chart_cursor_c2)), c2Start, c2End, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    if (extraStart != -1) {
                        span.setSpan(new android.text.style.ForegroundColorSpan(m.color), extraStart, raw.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                
                tvInfo.setText(span);
            }
        }

        class ResultViewHolder extends RecyclerView.ViewHolder {
            LinearLayout containerAnalog, containerDigital; EditText etNote;

            ResultViewHolder(View v) {
                super(v); containerAnalog = v.findViewById(R.id.containerAnalogAssessment); containerDigital = v.findViewById(R.id.containerDigitalAssessment); 
                etNote = v.findViewById(R.id.etNote);

                v.findViewById(R.id.btnExportPdf).setOnClickListener(v1 -> exportPdf()); v.findViewById(R.id.btnDownloadImg).setOnClickListener(v1 -> downloadImage());

                etNote.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void afterTextChanged(android.text.Editable s) { assessmentState.note = s.toString(); }
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                });
            }

            void bind() {
                containerAnalog.removeAllViews(); containerDigital.removeAllViews();
                etTopTitle.setText(assessmentState.title); etNote.setText(assessmentState.note);
                
                final String[] phasaOptions;
                if (currentTemplate.equals("T DIFF")) phasaOptions = PHASA_OPTIONS_T_DIFF;
                else if (currentTemplate.equals("OCR")) phasaOptions = PHASA_OPTIONS_OCR;
                else phasaOptions = PHASA_OPTIONS_DEFAULT;

                Spinner spPhasa = addPhasaRow("phasa_terganggu", 1, getString(R.string.lbl_view_param_fault_phase), phasaOptions);
                if (assessmentState.phasaPos >= phasaOptions.length) assessmentState.phasaPos = 0;
                spPhasa.setSelection(assessmentState.phasaPos);
                spPhasa.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { 
                        assessmentState.phasaPos = pos; 
                        updateMultiPhaseRows(phasaOptions[pos]); 
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
                updateMultiPhaseRows(phasaOptions[assessmentState.phasaPos]);
                
                addDigitalRow("di_diff_oprt", "Diff_oprt :", false);
                addDigitalRow("di_dist_oprt", "Dist_oprt :", false);
                
                String[] params = {"Any_Trip", "A/R Close", "Dist_Send", "Dist_Recv", "Def_Send", "Def_Recv", "CB_Phase_R_Open", "CB_Phase_S_Open", "CB_Phase_T_Open", "CB_3P_Open", "CB_Open"};
                for (int i = 0; i < params.length; i++) addDigitalRow("di_" + params[i], params[i], false);
                
                for (int i = 0; i < 8; i++) addDigitalRow("custom_di_" + i, "", true);

                addManualTimeRow("man_op_time", getString(R.string.lbl_view_param_operating_time)); 
                addManualTimeRow("man_dead_time", getString(R.string.lbl_view_param_dead_time)); 
                addManualTimeRow("di_clearing_time", getString(R.string.lbl_view_param_clearing_time)); 

                updateNumbering();
            }

            private Spinner addPhasaRow(String key, int no, String param, String[] options) {
                View row = getLayoutInflater().inflate(R.layout.item_dfr_assessment_row, containerAnalog, false);
                CheckBox cb = row.findViewById(R.id.checkInclude); cb.setVisibility(View.VISIBLE);
                AssessmentState.RowState state = assessmentState.getRow(key); cb.setChecked(state.checked);
                cb.setOnCheckedChangeListener((b, checked) -> { state.checked = checked; updateNumbering(); });
                
                ((TextView)row.findViewById(R.id.txtRowNo)).setText(no + "."); ((TextView)row.findViewById(R.id.txtRowParameter)).setText(param);
                Spinner sp = row.findViewById(R.id.spRowValue); 
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(DfrViewerActivity.this, R.layout.item_spinner_small, options) {
                    @NonNull @Override public View getView(int pos, @Nullable View conv, @NonNull ViewGroup par) {
                        TextView tv = (TextView) super.getView(pos, conv, par);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        return tv;
                    }
                    @Override public View getDropDownView(int pos, @Nullable View conv, @NonNull ViewGroup par) {
                        TextView tv = (TextView) super.getDropDownView(pos, conv, par);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        return tv;
                    }
                };
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                sp.setAdapter(adapter);
                containerAnalog.addView(row); return sp;
            }

            private void updateMultiPhaseRows(String selection) {
                for (int i = containerAnalog.getChildCount() - 1; i >= 1; i--) containerAnalog.removeViewAt(i);
                
                List<String> phases = parsePhases(selection);
                int currentNo = 2;

                if (currentTemplate.equals("T DIFF") || currentTemplate.equals("OCR")) {
                    for (String p : phases) {
                        String label = getString(R.string.lbl_view_param_fault_current).replace(" :", "") + " " + p.replace("_", " ");
                        addLockedValueRow("val_i_" + p, currentNo++, label, "I" + p, p, true);
                    }
                } else {
                    // DISTANCE / LINE DIFF (Default)
                    for (String p : phases) {
                        addLockedValueRow("val_i_" + p, currentNo++, "I" + p + " " + getString(R.string.lbl_view_param_fault_current), "I" + p, p, true);
                    }
                    for (String p : phases) {
                        addLockedValueRow("val_v_" + p, currentNo++, "V" + p + " " + getString(R.string.lbl_view_param_voltage_drop), "V" + p, p, false);
                    }
                }
                updateNumbering();
            }

            private List<String> parsePhases(String selection) {
                List<String> res = new ArrayList<>();
                if (selection.equals("RST")) {
                    res.add("R"); res.add("S"); res.add("T");
                } else if (selection.contains("_")) {
                    String[] parts = selection.split("_");
                    String side = parts[1];
                    for (char c : parts[0].toCharArray()) {
                        if (c != 'N' || parts[0].length() == 1) res.add(c + "_" + side);
                    }
                } else {
                    String target = selection.replace("-", "");
                    for (char c : target.toCharArray()) {
                        if (c != 'N') res.add(String.valueOf(c));
                    }
                }
                return res;
            }

            private void addCursorModeRow(String key, int no, String param, boolean isCurrent, String refPhase) {
                View row = getLayoutInflater().inflate(R.layout.item_dfr_assessment_row, containerAnalog, false);
                row.setTag(key);
                CheckBox cb = row.findViewById(R.id.checkInclude); cb.setVisibility(View.VISIBLE);
                AssessmentState.RowState state = assessmentState.getRow(key); cb.setChecked(state.checked);
                cb.setOnCheckedChangeListener((b, checked) -> { state.checked = checked; updateNumbering(); });

                ((TextView)row.findViewById(R.id.txtRowNo)).setText(no + "."); 
                ((TextView)row.findViewById(R.id.txtRowParameter)).setText(param + " :");
                
                Spinner sp = row.findViewById(R.id.spRowValue);
                
                String v1 = getSingleValue(refPhase, currentHighlight1, isCurrent);
                String v2 = getSingleValue(refPhase, currentHighlight2, isCurrent);
                
                List<String> opts = new ArrayList<>();
                opts.add("C1 (" + v1 + ")");
                opts.add("C2 (" + v2 + ")");
                
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(DfrViewerActivity.this, R.layout.item_spinner_small, opts) {
                    @NonNull @Override public View getView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                        TextView tv = (TextView) super.getView(p, c, pa); 
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        String s = opts.get(p);
                        if (s.contains("(") && s.contains(")")) {
                            tv.setText(s.substring(s.indexOf("(") + 1, s.lastIndexOf(")")));
                        }
                        return tv;
                    }
                    @Override public View getDropDownView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                        TextView tv = (TextView) super.getDropDownView(p, c, pa); tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary)); return tv;
                    }
                };
                sp.setAdapter(adapter);
                sp.setSelection(isCurrent ? assessmentState.cursorIMode : assessmentState.cursorVMode);
                
                sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        if (isCurrent) assessmentState.cursorIMode = pos;
                        else assessmentState.cursorVMode = pos;
                        refreshLiveValues();
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
                containerAnalog.addView(row);
            }

            private void addLockedValueRow(String key, int no, String param, String stateKey, String phaseSuffix, boolean isCurrent) {
                View row = getLayoutInflater().inflate(R.layout.item_dfr_assessment_row, containerAnalog, false);
                row.setTag(key);
                CheckBox cb = row.findViewById(R.id.checkInclude); cb.setVisibility(View.VISIBLE);
                AssessmentState.RowState state = assessmentState.getRow(key); cb.setChecked(state.checked);
                cb.setOnCheckedChangeListener((b, checked) -> { state.checked = checked; updateNumbering(); });

                ((TextView)row.findViewById(R.id.txtRowNo)).setText(no + "."); ((TextView)row.findViewById(R.id.txtRowParameter)).setText(param);
                Spinner sp = row.findViewById(R.id.spRowValue);
                
                String v1 = getSingleValue(phaseSuffix, currentHighlight1, isCurrent);
                String v2 = getSingleValue(phaseSuffix, currentHighlight2, isCurrent);
                
                List<String> opts = new ArrayList<>();
                opts.add("C1 (" + v1 + ")");
                opts.add("C2 (" + v2 + ")");
                
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(DfrViewerActivity.this, R.layout.item_spinner_small, opts) {
                    @NonNull @Override public View getView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                        TextView tv = (TextView) super.getView(p, c, pa);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        String s = opts.get(p);
                        if (s.contains("(") && s.contains(")")) {
                            tv.setText(s.substring(s.indexOf("(") + 1, s.lastIndexOf(")")));
                        }
                        return tv;
                    }
                    @Override public View getDropDownView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                        TextView tv = (TextView) super.getDropDownView(p, c, pa); tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary)); return tv;
                    }
                };
                sp.setAdapter(adapter);
                sp.setSelection(state.valPos < opts.size() ? state.valPos : 0);
                
                sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        state.valPos = pos;
                        String sel = opts.get(pos);
                        if (sel.contains("(") && sel.contains(")")) {
                            sel = sel.substring(sel.indexOf("(") + 1, sel.lastIndexOf(")"));
                        }
                        assessmentState.lockedValues.put(stateKey, sel);
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
                containerAnalog.addView(row);
            }

            private void addDigitalRow(String key, String param, boolean isCustom) {
                View row = getLayoutInflater().inflate(R.layout.item_dfr_assessment_row, containerDigital, false);
                CheckBox cb = row.findViewById(R.id.checkInclude); cb.setVisibility(View.VISIBLE); 
                AssessmentState.RowState state = assessmentState.getRow(key); cb.setChecked(state.checked);
                row.findViewById(R.id.spChannelMap).setVisibility(View.VISIBLE);
                EditText etParam = row.findViewById(R.id.etRowParameter); TextView tvParam = row.findViewById(R.id.txtRowParameter);
                if (isCustom) { 
                    tvParam.setVisibility(View.GONE); etParam.setVisibility(View.VISIBLE); etParam.setText(state.customParam);
                    etParam.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void afterTextChanged(android.text.Editable s) { state.customParam = s.toString(); }
                        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                    });
                } else tvParam.setText(param);
                Spinner spMap = row.findViewById(R.id.spChannelMap), spVal = row.findViewById(R.id.spRowValue);
                List<String> diLabels = new ArrayList<>(); for (ChartModel m : models) if (m.isDigital) diLabels.add(m.label);
                
                ArrayAdapter<String> mapAdp = new ArrayAdapter<String>(DfrViewerActivity.this, R.layout.item_spinner_small, diLabels) {
                    @NonNull @Override public View getView(int pos, @Nullable View conv, @NonNull ViewGroup par) {
                        TextView tv = (TextView) super.getView(pos, conv, par);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        return tv;
                    }
                    @Override public View getDropDownView(int pos, @Nullable View conv, @NonNull ViewGroup par) {
                        TextView tv = (TextView) super.getDropDownView(pos, conv, par);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        return tv;
                    }
                };
                mapAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spMap.setAdapter(mapAdp);
                
                spMap.setSelection(state.mapPos);
                spMap.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        state.mapPos = pos; String sel = diLabels.get(pos); 
                        for (ChartModel m : models) if (m.label.equals(sel)) { 
                            String[] trans = m.digitalSummary.split(", ");
                            ArrayAdapter<String> valAdp = new ArrayAdapter<String>(DfrViewerActivity.this, R.layout.item_spinner_small, trans) {
                                @NonNull @Override public View getView(int pos2, @Nullable View conv, @NonNull ViewGroup par) {
                                    TextView tv = (TextView) super.getView(pos2, conv, par);
                                    tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                                    return tv;
                                }
                                @Override public View getDropDownView(int pos2, @Nullable View conv, @NonNull ViewGroup par) {
                                    TextView tv = (TextView) super.getDropDownView(pos2, conv, par);
                                    tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                                    return tv;
                                }
                            };
                            valAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spVal.setAdapter(valAdp);
                            spVal.setSelection(state.valPos); break; 
                        }
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
                spVal.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { state.valPos = pos; }
                    @Override public void onNothingSelected(AdapterView<?> p) {}
                });
                cb.setOnCheckedChangeListener((b, checked) -> { state.checked = checked; updateNumbering(); });
                containerDigital.addView(row);
            }

            private void addManualTimeRow(String key, String param) {
                View row = getLayoutInflater().inflate(R.layout.item_dfr_assessment_row, containerDigital, false);
                CheckBox cb = row.findViewById(R.id.checkInclude); cb.setVisibility(View.VISIBLE); 
                AssessmentState.RowState state = assessmentState.getRow(key); cb.setChecked(state.checked);
                ((TextView)row.findViewById(R.id.txtRowParameter)).setText(param); row.findViewById(R.id.spRowValue).setVisibility(View.GONE); 
                EditText etVal = row.findViewById(R.id.etRowValue); etVal.setVisibility(View.VISIBLE); etVal.setText(state.manualValue);
                etVal.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void afterTextChanged(android.text.Editable s) { state.manualValue = s.toString(); }
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                });
                cb.setOnCheckedChangeListener((b, checked) -> { state.checked = checked; updateNumbering(); });
                containerDigital.addView(row);
            }

            void updateAnalogSpinners() { 
                // Only refresh if we have visible analog rows that depend on cursors
                if (containerAnalog.getChildCount() > 1) {
                    adapter.notifyItemChanged(adapter.getItemCount() - 1); 
                }
            }
            
            void refreshLiveValues() {
                if (containerAnalog == null) return;
                
                for (int i = 0; i < containerAnalog.getChildCount(); i++) {
                    View row = containerAnalog.getChildAt(i);
                    Object tag = row.getTag();
                    if (!(tag instanceof String)) continue;
                    String key = (String) tag;
                    Spinner sp = row.findViewById(R.id.spRowValue);
                    if (sp == null || !key.startsWith("val_")) continue;

                    String suffix = key.substring(6);
                    boolean isI = key.startsWith("val_i_");
                    String stateKey = isI ? "I" + suffix.toUpperCase() : "V" + suffix.toUpperCase();
                    
                    String v1 = getSingleValue(suffix, currentHighlight1, isI);
                    String v2 = getSingleValue(suffix, currentHighlight2, isI);
                    
                    List<String> opts = new ArrayList<>();
                    opts.add("C1 (" + v1 + ")");
                    opts.add("C2 (" + v2 + ")");
                    
                    updateSpinnerSilently(sp, opts);
                    
                    // Update the locked value as well for report generation
                    AssessmentState.RowState state = assessmentState.getRow(key);
                    String selectedVal = (state.valPos == 1) ? v2 : v1;
                    assessmentState.lockedValues.put(stateKey, selectedVal);
                }
            }

            private void updateSpinnerSilently(Spinner sp, List<String> opts) {
                int pos = sp.getSelectedItemPosition();
                ArrayAdapter<String> adp = new ArrayAdapter<String>(DfrViewerActivity.this, R.layout.item_spinner_small, opts) {
                    @NonNull @Override public View getView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                        TextView tv = (TextView) super.getView(p, c, pa);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        String s = opts.get(p);
                        if (s.contains("(") && s.contains(")")) s = s.substring(s.indexOf("(") + 1, s.lastIndexOf(")"));
                        else if (s.contains(" = ")) s = s.split(" = ")[1].trim();
                        if (s.startsWith("Unlock: ")) s = s.replace("Unlock: ", "").trim();
                        tv.setText(s);
                        return tv;
                    }
                    @Override public View getDropDownView(int p, @Nullable View c, @NonNull ViewGroup pa) {
                        TextView tv = (TextView) super.getDropDownView(p, c, pa);
                        tv.setTextColor(ContextCompat.getColor(getContext(), R.color.dfr_text_primary));
                        return tv;
                    }
                };
                adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                sp.setAdapter(adp);
                if (pos >= 0 && pos < opts.size()) sp.setSelection(pos, false);
            }

            private String getRefAnalogValue(String phaseSuffix, boolean lookForCurrent) {
                int mode = lookForCurrent ? assessmentState.cursorIMode : assessmentState.cursorVMode;
                String v1 = getSingleValue(phaseSuffix, currentHighlight1, lookForCurrent);
                String v2 = getSingleValue(phaseSuffix, currentHighlight2, lookForCurrent);
                
                if (mode == 1) return v2;
                return v1;
            }

            private String getSingleValue(String phaseSuffix, Highlight h, boolean lookForCurrent) {
                if (h == null || models.isEmpty()) return "---";
                String ps = phaseSuffix.toUpperCase();
                
                // Extract base phase (R, S, T)
                String phaseChar = "";
                if (ps.contains("R")) phaseChar = "R";
                else if (ps.contains("S")) phaseChar = "S";
                else if (ps.contains("T")) phaseChar = "T";
                else if (ps.contains("A")) phaseChar = "A";
                else if (ps.contains("B")) phaseChar = "B";
                else if (ps.contains("C")) phaseChar = "C";
                else if (ps.contains("N") || ps.contains("G")) phaseChar = "N";

                boolean isHV = ps.contains("HV") || ps.contains("SIDE 1") || ps.contains("H_V");
                boolean isLV = ps.contains("LV") || ps.contains("SIDE 2") || ps.contains("L_V");

                for (ChartModel m : models) {
                    if (m.isDigital || m.isHeader) continue;
                    String label = m.label.toUpperCase();
                    
                    // Basic check: Is it Current or Voltage?
                    boolean isI = label.contains("I") || label.contains("A") || label.contains("REF") || label.contains("DIFF");
                    boolean isV = label.contains("V") || label.contains("U");
                    
                    if (lookForCurrent && !isI) continue;
                    if (!lookForCurrent && !isV) continue;

                    // Phase match logic
                    boolean phaseMatch = label.contains(phaseChar);
                    if (phaseChar.equals("A") || phaseChar.equals("R")) phaseMatch = label.contains("R") || label.contains("A") || label.contains("L1");
                    else if (phaseChar.equals("B") || phaseChar.equals("S")) phaseMatch = label.contains("S") || label.contains("B") || label.contains("L2");
                    else if (phaseChar.equals("C") || phaseChar.equals("T")) phaseMatch = label.contains("T") || label.contains("C") || label.contains("L3");
                    else if (phaseChar.equals("N")) phaseMatch = label.contains("N") || label.contains("G") || label.contains("RES");
                    
                    if (!phaseMatch && !phaseChar.isEmpty()) continue;

                    // Side match logic for T DIFF
                    if (isHV && !(label.contains("HV") || label.contains("H") || label.contains("1"))) continue;
                    if (isLV && !(label.contains("LV") || label.contains("L") || label.contains("2"))) continue;

                    return getValueString(m, h.getX());
                }
                return "---";
            }
            private void updateNumbering() {
                int no = 1;
                for (int i = 0; i < containerAnalog.getChildCount(); i++) {
                    View r = containerAnalog.getChildAt(i); CheckBox cb = r.findViewById(R.id.checkInclude);
                    if (cb.isChecked()) ((TextView)r.findViewById(R.id.txtRowNo)).setText(no++ + ".");
                    else ((TextView)r.findViewById(R.id.txtRowNo)).setText(R.string.val_all_hyphen);
                }
                for (int i = 0; i < containerDigital.getChildCount(); i++) {
                    View r = containerDigital.getChildAt(i); CheckBox cb = r.findViewById(R.id.checkInclude);
                    if (cb.isChecked()) ((TextView)r.findViewById(R.id.txtRowNo)).setText(no++ + ".");
                    else ((TextView)r.findViewById(R.id.txtRowNo)).setText(R.string.val_all_hyphen);
                }
            }
            private void exportPdf() { renderFullReport(true); }
            private void downloadImage() { renderFullReport(false); }

            private void renderFullReport(boolean isPdf) {
                if (isFinishing()) return;
                progressBar.setVisibility(View.VISIBLE);
                final int totalCharts = models.size();
                final List<android.graphics.Bitmap> bitmaps = new ArrayList<>();
                final int exportWidth = getResources().getInteger(R.integer.config_dfr_export_width_px);
                final int screenWidth = rvCharts.getWidth();
                
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable processTask = new Runnable() {
                    int currentIndex = 0;
                    final int batchSize = getResources().getInteger(R.integer.config_dfr_export_batch_size);
                    final int frameDelay = getResources().getInteger(R.integer.config_dfr_export_frame_delay_ms);
                    
                    @Override public void run() {
                        if (isFinishing()) return;
                        
                        // Add Report Header as first item
                        if (currentIndex == 0) {
                            bitmaps.add(createReportHeader(exportWidth));
                        }

                        for (int i = 0; i < batchSize && currentIndex < totalCharts; i++) {
                            ChartModel m = models.get(currentIndex);
                            android.graphics.Bitmap b = captureChartRow(m, exportWidth, screenWidth);
                            if (b != null) bitmaps.add(b);
                            currentIndex++;
                        }
                        if (currentIndex < totalCharts) handler.postDelayed(this, frameDelay);
                        else {
                            // structured report of assessments
                            bitmaps.add(createStructuredAssessmentReport(exportWidth));

                            safeExecute(() -> {
                                try {
                                    if (isPdf) saveAsPdf(bitmaps); else saveAsImage(bitmaps);
                                } catch (Exception e) {
                                    runOnUiThread(() -> Toast.makeText(DfrViewerActivity.this, "Export Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                                } finally {
                                    runOnUiThread(() -> { if (!isFinishing()) progressBar.setVisibility(View.GONE); });
                                }
                            });
                        }
                    }
                };
                handler.post(processTask);
            }

            private android.graphics.Bitmap captureChartRow(ChartModel m, int exportWidth, int screenWidth) {
                if (m.isHeader || m.entries == null || m.entries.isEmpty()) return null;

                // 1. Create View with DARK THEME Background (Matching Real UI)
                View row = getLayoutInflater().inflate(R.layout.item_dfr_chart_row, null, false);
                row.setBackgroundColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_background));
                
                ChartViewHolder vh = new ChartViewHolder(row);
                float density = getResources().getDisplayMetrics().density;
                float leftOffset = 45f * density; 

                // 2. Measure & Layout FIRST pass
                row.measure(View.MeasureSpec.makeMeasureSpec(exportWidth, View.MeasureSpec.EXACTLY), 
                          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

                // 3. Bind Data & Style for DARK THEME EXPORT (Exactly like UI)
                vh.tvInfo.setTextColor(m.color);
                if (m.isDigital) {
                    vh.chart.setVisibility(View.GONE);
                    vh.digitalView.setVisibility(View.VISIBLE);
                    vh.digitalView.setData(m.entries, m.startTime, m.totalTime);
                    
                    float ratio = (screenWidth > (45f*density)) ? (float)(exportWidth - leftOffset) / (screenWidth - (45f*density)) : 1f;
                    vh.digitalView.setSyncState(sharedMatrix, DfrViewerActivity.this.getActiveCursors());
                    vh.digitalView.setExportScaleRatio(ratio);
                    
                    vh.tvSummary.setVisibility(View.VISIBLE);
                    vh.tvSummary.setText(m.digitalSummary);
                    vh.tvSummary.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_secondary));
                } else {
                    vh.chart.setVisibility(View.VISIBLE);
                    vh.digitalView.setVisibility(View.GONE);
                    vh.tvSummary.setVisibility(View.GONE);
                    
                    vh.setupChart(false);
                    // MATCH REAL UI COLORS (Theme Background, Theme Text)
                    vh.chart.setBackgroundColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_background));
                    vh.chart.setViewPortOffsets(leftOffset, 5f * density, 4f * density, 12f * density);
                    
                    YAxis y = vh.chart.getAxisLeft();
                    y.setDrawLabels(true);
                    y.setDrawAxisLine(true);
                    y.setDrawGridLines(true);
                    y.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary));
                    y.setGridColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_chart_grid));
                    y.setTextSize(8f);
                    y.setAxisLineColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary));

                    XAxis x = vh.chart.getXAxis();
                    x.setDrawLabels(true);
                    x.setDrawAxisLine(true);
                    x.setDrawGridLines(true);
                    x.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary));
                    x.setGridColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_chart_grid));
                    x.setTextSize(8f);
                    x.setAxisLineColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary));
                    x.setPosition(XAxis.XAxisPosition.BOTTOM);

                    float finalMax = m.maxY * 1.1f;
                    y.setAxisMinimum(-finalMax);
                    y.setAxisMaximum(finalMax);
                    x.setAxisMinimum(m.startTime);
                    x.setAxisMaximum(m.totalTime);
                    
                    LineDataSet set = new LineDataSet(m.entries, m.label);
                    set.setDrawCircles(false); 
                    set.setColor(m.color); // USES THE THEME-BASED COLOR
                    set.setLineWidth(1.5f);
                    set.setDrawValues(false);
                    vh.chart.setData(new LineData(set));
                    
                    Matrix scaledMatrix = new Matrix(sharedMatrix);
                    float[] sVals = new float[9]; scaledMatrix.getValues(sVals);
                    float ratio = (screenWidth > (45f*density)) ? (float)(exportWidth - leftOffset) / (screenWidth - (45f*density)) : 1f;
                    sVals[Matrix.MTRANS_X] *= ratio;
                    scaledMatrix.setValues(sVals);
                    
                    vh.chart.getViewPortHandler().refresh(scaledMatrix, vh.chart, false);
                    vh.chart.highlightValues(DfrViewerActivity.this.getActiveCursors());
                    
                    vh.chart.notifyDataSetChanged();
                    vh.chart.calculateOffsets();
                }
                vh.updateCursorText(m);

                // 4. Measure & Layout FINAL pass
                row.measure(View.MeasureSpec.makeMeasureSpec(exportWidth, View.MeasureSpec.EXACTLY), 
                          View.MeasureSpec.makeMeasureSpec(row.getMeasuredHeight(), View.MeasureSpec.EXACTLY));
                row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

                // 5. Draw
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(row.getMeasuredWidth(), row.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                row.draw(new android.graphics.Canvas(b));
                return b;
            }

            private void setExportMode(boolean exportMode) {
                itemView.findViewById(R.id.btnExportPdf).setVisibility(exportMode ? View.GONE : View.VISIBLE);
                itemView.findViewById(R.id.btnDownloadImg).setVisibility(exportMode ? View.GONE : View.VISIBLE);
                for (int i = 0; i < containerAnalog.getChildCount(); i++) {
                    View r = containerAnalog.getChildAt(i); CheckBox cb = r.findViewById(R.id.checkInclude);
                    cb.setVisibility(exportMode ? View.GONE : View.VISIBLE);
                    r.setVisibility((exportMode && !cb.isChecked()) ? View.GONE : View.VISIBLE);
                }
                for (int i = 0; i < containerDigital.getChildCount(); i++) {
                    View r = containerDigital.getChildAt(i); CheckBox cb = r.findViewById(R.id.checkInclude);
                    cb.setVisibility(exportMode ? View.GONE : View.VISIBLE);
                    r.setVisibility((exportMode && !cb.isChecked()) ? View.GONE : View.VISIBLE);
                }
            }

            private android.graphics.Bitmap captureView(View v, int width) {
                v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(b);
                canvas.drawColor(androidx.core.content.ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_background));
                v.draw(canvas); return b;
            }

            /**
             * Lays each bitmap onto fixed A4-portrait pages (config_pdf_page_*), packing as many
             * consecutive items as fit within a page's content height before starting a new page,
             * instead of the old 1-bitmap-per-arbitrarily-sized-page behavior - this both gives a
             * real, consistent paper size and minimizes the page count / wasted whitespace.
             */
            private void saveAsPdf(List<android.graphics.Bitmap> bitmaps) {
                try {
                    int pageWidth = getResources().getInteger(R.integer.config_pdf_page_width_px);
                    int pageHeight = getResources().getInteger(R.integer.config_pdf_page_height_px);
                    int margin = getResources().getInteger(R.integer.config_pdf_page_margin_px);
                    int spacing = getResources().getInteger(R.integer.config_pdf_item_spacing_px);
                    int contentWidth = pageWidth - 2 * margin;
                    int contentHeight = pageHeight - 2 * margin;

                    android.graphics.pdf.PdfDocument document = new android.graphics.pdf.PdfDocument();
                    int pageNum = 1;
                    android.graphics.pdf.PdfDocument.Page page = null;
                    float cursorY = margin;

                    for (android.graphics.Bitmap b : bitmaps) {
                        float scale = Math.min(1f, (float) contentWidth / b.getWidth());
                        float drawW = b.getWidth() * scale;
                        float drawH = b.getHeight() * scale;
                        if (drawH > contentHeight) {
                            // Taller than a whole page on its own - shrink further to fit height instead.
                            scale = Math.min(scale, (float) contentHeight / b.getHeight());
                            drawW = b.getWidth() * scale;
                            drawH = b.getHeight() * scale;
                        }

                        if (page == null || cursorY + drawH > margin + contentHeight) {
                            if (page != null) document.finishPage(page);
                            android.graphics.pdf.PdfDocument.PageInfo pageInfo =
                                    new android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum++).create();
                            page = document.startPage(pageInfo);
                            cursorY = margin;
                        }

                        float left = margin + (contentWidth - drawW) / 2f;
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.postScale(scale, scale);
                        matrix.postTranslate(left, cursorY);
                        page.getCanvas().drawBitmap(b, matrix, null);
                        cursorY += drawH + spacing;
                    }
                    if (page != null) document.finishPage(page);

                    String fileName = "DFR_Report_" + System.currentTimeMillis() + ".pdf";
                    java.io.File file = new java.io.File(getExternalFilesDir(null), fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                    document.writeTo(fos); document.close(); fos.close();
                    runOnUiThread(() -> openFile(file, "application/pdf"));
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(DfrViewerActivity.this, "PDF Error: " + e.getMessage(), Toast.LENGTH_SHORT).show()); }
            }

            private void saveAsImage(List<android.graphics.Bitmap> bitmaps) {
                if (bitmaps == null || bitmaps.isEmpty()) return;
                try {
                    int totalHeight = 0; for (android.graphics.Bitmap b : bitmaps) totalHeight += b.getHeight();
                    if (totalHeight <= 0) return;
                    android.graphics.Bitmap combined = android.graphics.Bitmap.createBitmap(bitmaps.get(0).getWidth(), totalHeight, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(combined);
                    int y = 0; for (android.graphics.Bitmap b : bitmaps) { canvas.drawBitmap(b, 0, y, null); y += b.getHeight(); }
                    String fileName = "DFR_Report_" + System.currentTimeMillis() + ".png";
                    java.io.File file = new java.io.File(getExternalFilesDir(null), fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                    combined.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos); fos.close();
                    runOnUiThread(() -> openFile(file, "image/png"));
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(DfrViewerActivity.this, "Image Error: " + e.getMessage(), Toast.LENGTH_SHORT).show()); }
            }

            private android.graphics.Bitmap createStructuredAssessmentReport(int width) {
                LinearLayout root = new LinearLayout(DfrViewerActivity.this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setBackgroundColor(Color.WHITE);
                root.setPadding(40, 40, 40, 40);

                // Incident Details Section
                addReportSectionHeader(root, getString(R.string.lbl_view_report_section_details), ContextCompat.getColor(DfrViewerActivity.this, R.color.brand_primary));
                addReportRow(root, getString(R.string.lbl_view_incident_title_label).replace(" :", ""), etTopTitle.getText().toString());
                addReportRow(root, getString(R.string.lbl_view_date_label).replace(" :", ""), txtTopDate.getText().toString());
                addReportRow(root, getString(R.string.lbl_view_trigger_time_label).replace(" :", ""), txtTopTrigger.getText().toString());

                // Analog Assessment (Checked only)
                boolean hasAnalog = false;
                for (String key : assessmentState.rowStates.keySet()) {
                    if (key.startsWith("val_") || key.equals("phasa_terganggu") || key.startsWith("mode_")) {
                        AssessmentState.RowState rs = assessmentState.rowStates.get(key);
                        if (rs != null && rs.checked) {
                            if (!hasAnalog) {
                                addReportSectionHeader(root, getString(R.string.lbl_view_report_section_analog), ContextCompat.getColor(DfrViewerActivity.this, R.color.status_warning));
                                hasAnalog = true;
                            }
                            String param = getParamNameFromKey(key);
                            String value = getDisplayValueFromState(key, rs);
                            addReportRow(root, param, value);
                        }
                    }
                }

                // Digital Assessment (Checked only)
                boolean hasDigital = false;
                for (String key : assessmentState.rowStates.keySet()) {
                    if (key.startsWith("di_") || key.startsWith("custom_di_") || key.startsWith("man_")) {
                        AssessmentState.RowState rs = assessmentState.rowStates.get(key);
                        if (rs != null && rs.checked) {
                            if (!hasDigital) {
                                addReportSectionHeader(root, getString(R.string.lbl_view_report_section_digital), ContextCompat.getColor(DfrViewerActivity.this, R.color.status_warning));
                                hasDigital = true;
                            }
                            String param = getParamNameFromKey(key);
                            if (key.startsWith("custom_di_")) param = rs.customParam;
                            String value = getDisplayValueFromState(key, rs);
                            addReportRow(root, param, value);
                        }
                    }
                }

                // Note Section
                if (!assessmentState.note.isEmpty()) {
                    addReportSectionHeader(root, getString(R.string.lbl_view_report_section_notes), ContextCompat.getColor(DfrViewerActivity.this, R.color.text_secondary));
                    TextView tvNote = new TextView(DfrViewerActivity.this);
                    tvNote.setText(assessmentState.note);
                    tvNote.setTextColor(Color.BLACK);
                    tvNote.setTextSize(14);
                    tvNote.setPadding(0, 10, 0, 10);
                    root.addView(tvNote);
                }

                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
                
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(root.getMeasuredWidth(), Math.max(1, root.getMeasuredHeight()), android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(b));
                return b;
            }

            private void addReportSectionHeader(LinearLayout root, String title, int color) {
                TextView tv = new TextView(DfrViewerActivity.this);
                tv.setText(title);
                tv.setTextColor(color);
                tv.setTextSize(16);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setPadding(0, 30, 0, 10);
                root.addView(tv);
                
                View line = new View(DfrViewerActivity.this);
                line.setBackgroundColor(color);
                root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3));
            }

            private void addReportRow(LinearLayout root, String param, String value) {
                LinearLayout row = new LinearLayout(DfrViewerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 8, 0, 8);
                
                TextView tvP = new TextView(DfrViewerActivity.this);
                tvP.setText(param);
                tvP.setTextColor(Color.DKGRAY);
                tvP.setTextSize(14);
                row.addView(tvP, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                
                TextView tvV = new TextView(DfrViewerActivity.this);
                tvV.setTextColor(Color.BLACK);
                tvV.setTextSize(14);
                tvV.setTypeface(null, android.graphics.Typeface.BOLD);
                tvV.setGravity(android.view.Gravity.END);
                
                // Clean display for structured report values
                String cleanVal = value;
                if (cleanVal.contains(" = ")) cleanVal = cleanVal.split(" = ")[1].trim();
                if (cleanVal.startsWith("Unlock: ")) cleanVal = cleanVal.replace("Unlock: ", "").trim();
                tvV.setText(cleanVal);

                row.addView(tvV, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
                
                root.addView(row);
                View divider = new View(DfrViewerActivity.this);
                divider.setBackgroundColor(Color.LTGRAY);
                root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }

            private String getParamNameFromKey(String key) {
                if (key.equals("phasa_terganggu")) return getString(R.string.lbl_view_param_fault_phase).replace(" :", "");
                if (key.equals("mode_arus")) return getString(R.string.lbl_view_param_fault_current).replace(" :", "") + " Mode";
                if (key.equals("mode_teg")) return getString(R.string.lbl_view_tegangan_prefix).replace(" :", "") + " Mode";
                if (key.startsWith("val_i_")) {
                    String p = key.substring(6).replace("_", " ");
                    return getString(R.string.lbl_view_param_fault_current).replace(" :", "") + " " + p;
                }
                if (key.startsWith("val_v_")) {
                    String p = key.substring(6).replace("_", " ");
                    return "Drop " + getString(R.string.lbl_view_tegangan_prefix).replace(" :", "") + " " + p;
                }
                if (key.equals("man_op_time")) return getString(R.string.lbl_view_param_operating_time).replace(" :", "");
                if (key.equals("man_dead_time")) return getString(R.string.lbl_view_param_dead_time).replace(" :", "");
                if (key.equals("di_clearing_time")) return getString(R.string.lbl_view_param_clearing_time).replace(" :", "");
                return key.replace("di_", "").replace("_", " ");
            }

            private String getDisplayValueFromState(String key, AssessmentState.RowState rs) {
                if (key.equals("phasa_terganggu")) {
                    final String[] ph;
                    if (currentTemplate.equals("T DIFF")) ph = PHASA_OPTIONS_T_DIFF;
                    else if (currentTemplate.equals("OCR")) ph = PHASA_OPTIONS_OCR;
                    else ph = PHASA_OPTIONS_DEFAULT;
                    return (rs.mapPos >= 0 && rs.mapPos < ph.length) ? ph[rs.mapPos] : "-";
                }
                if (key.startsWith("mode_")) {
                    int mode = key.equals("mode_arus") ? assessmentState.cursorIMode : assessmentState.cursorVMode;
                    String[] modes = {"C1", "C2", "C1 & C2"};
                    return (mode >= 0 && mode < modes.length) ? modes[mode] : "-";
                }
                if (key.startsWith("val_")) {
                    String phase = key.substring(6).toUpperCase();
                    String stateKey = key.startsWith("val_i_") ? "I" + phase : "V" + phase;
                    String locked = assessmentState.lockedValues.get(stateKey);
                    
                    if (locked != null) {
                        // Extract numeric value from "IA = 100.5 A" format
                        if (locked.contains(" = ")) return locked.split(" = ")[1];
                        return locked;
                    }
                    return "-";
                }
                if (key.startsWith("man_") || key.equals("di_clearing_time")) return rs.manualValue;
                
                // Digital value from mapping
                List<String> diLabels = new ArrayList<>(); for (ChartModel m : models) if (m.isDigital) diLabels.add(m.label);
                if (rs.mapPos >= 0 && rs.mapPos < diLabels.size()) {
                    String label = diLabels.get(rs.mapPos);
                    for (ChartModel m : models) if (m.label.equals(label)) {
                        String[] trans = m.digitalSummary.split(", ");
                        return (rs.valPos >= 0 && rs.valPos < trans.length) ? trans[rs.valPos] : "-";
                    }
                }
                return "-";
            }

            private android.graphics.Bitmap createReportHeader(int width) {
                LinearLayout header = new LinearLayout(DfrViewerActivity.this);
                header.setOrientation(LinearLayout.VERTICAL);
                header.setBackgroundColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_background));
                header.setPadding(40, 40, 40, 20);

                TextView tv = new TextView(DfrViewerActivity.this);
                String title = etTopTitle.getText().toString().toUpperCase();
                tv.setText(getString(R.string.lbl_view_report_title) + " " + title);
                tv.setTextSize(24);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setTextColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.dfr_text_primary));
                tv.setGravity(android.view.Gravity.CENTER);
                
                header.addView(tv);
                
                View divider = new View(DfrViewerActivity.this);
                divider.setBackgroundColor(ContextCompat.getColor(DfrViewerActivity.this, R.color.brand_primary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6);
                lp.topMargin = 16;
                header.addView(divider, lp);

                header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());
                
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(header.getMeasuredWidth(), header.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                header.draw(new android.graphics.Canvas(b));
                return b;
            }

            private void openFile(java.io.File file, String mime) {
                Toast.makeText(DfrViewerActivity.this, getString(R.string.msg_file_saved_notif, file.getName()), Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = androidx.core.content.FileProvider.getUriForFile(DfrViewerActivity.this, getPackageName() + ".fileprovider", file);
                intent.setDataAndType(uri, mime); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        }
    }

    public static class ComtradeParser {
        public float[] multipliers, offsets, primaryRatios, secondaryRatios;
        public char[] unitModes; public String[] channelNames, digitalNames, analogUnits; public int analogCount, digitalCount, sampleRate; public float lineFrequency; public float triggerOffset = 0; public List<String> cfgLines;
        public String stationName = "";

        public void parseCfg(List<String> raw) {
            this.cfgLines = raw; List<String> cfg = new ArrayList<>(); for (String s : raw) if (!s.trim().isEmpty()) cfg.add(s);
            if (cfg.size() < 4) return;

            // Baris 1: Station Name
            String[] firstLine = cfg.get(0).split(",");
            if (firstLine.length > 0) stationName = firstLine[0].trim();

            String[] counts = cfg.get(1).split(","); 
            analogCount = Integer.parseInt(counts[1].replaceAll("[^0-9]", "").trim()); 
            digitalCount = Integer.parseInt(counts[2].replaceAll("[^0-9]", "").trim());
            multipliers = new float[analogCount]; offsets = new float[analogCount]; 
            primaryRatios = new float[analogCount]; secondaryRatios = new float[analogCount];
            unitModes = new char[analogCount]; channelNames = new String[analogCount]; 
            analogUnits = new String[analogCount]; digitalNames = new String[digitalCount];
            
            for (int i = 0; i < analogCount; i++) {
                if (2 + i >= cfg.size()) break;
                String[] p = cfg.get(2 + i).split(",");
                if (p.length >= 13) {
                    channelNames[i] = p[1].trim(); 
                    analogUnits[i] = p[4].trim(); // uu: Satuan fisik
                    multipliers[i] = safe(p[5]); // a: multiplier
                    offsets[i] = safe(p[6]); // b: offset
                    primaryRatios[i] = safe(p[10]); 
                    secondaryRatios[i] = safe(p[11]);
                    String ps = p[12].trim().toUpperCase(); // PS: P atau S
                    unitModes[i] = ps.isEmpty() ? 'S' : ps.charAt(0);
                } else if (p.length >= 7) {
                    channelNames[i] = p[1].trim(); 
                    analogUnits[i] = p.length >= 5 ? p[4].trim() : "";
                    multipliers[i] = safe(p[5]); 
                    offsets[i] = safe(p[6]); 
                    primaryRatios[i] = 1; 
                    secondaryRatios[i] = 1; 
                    unitModes[i] = 'S';
                }
            }
            for (int i = 0; i < digitalCount; i++) { 
                if (2 + analogCount + i >= cfg.size()) break;
                String[] p = cfg.get(2 + analogCount + i).split(","); 
                digitalNames[i] = p.length >= 2 ? p[1].trim() : "D" + (i + 1); 
            }

            // Standard COMTRADE: line freq is after digital channels
            int freqIdx = 2 + analogCount + digitalCount;
            if (cfg.size() > freqIdx) {
                lineFrequency = safe(cfg.get(freqIdx));
            } else {
                lineFrequency = 50f;
            }
            if (lineFrequency <= 0) lineFrequency = 50f;

            // Sample rate info: skip nRates (freqIdx + 1)
            int rateIdx = freqIdx + 2;
            if (cfg.size() > rateIdx) {
                String[] rateLine = cfg.get(rateIdx).split(",");
                sampleRate = (int) safe(rateLine[0]);
            } else {
                sampleRate = 8000;
            }
            if (sampleRate <= 0) sampleRate = 8000;

            try { 
                triggerOffset = calculateTimeDiff(cfg.get(cfg.size() - 4), cfg.get(cfg.size() - 3)); 
            } catch (Exception e) { 
                triggerOffset = 0; 
            }
        }
        private float calculateTimeDiff(String s, String t) { try { return toSeconds(t.split(",")[1].trim()) - toSeconds(s.split(",")[1].trim()); } catch (Exception e) { return 0; } }
        private float toSeconds(String s) { String[] p = s.split(":"); return safe(p[0]) * 3600 + safe(p[1]) * 60 + safe(p[2]); }
        private float safe(String s) { try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0f; } }
        public List<float[]> parseDat(List<String> lines, int aCount, int dCount, List<int[]> dOut, List<Float> tOut) {
            List<float[]> data = new ArrayList<>(lines.size()); dOut.clear(); tOut.clear();
            int expectedCols = 2 + aCount + dCount;
            
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().isEmpty()) continue; 
                String[] p = line.contains(",") ? line.split(",") : line.trim().split("\\s+");
                if (p.length < 2) continue; 
                
                // Mismatch validation: check if columns are enough
                if (p.length < expectedCols) {
                    throw new RuntimeException("FILE STRUCTURE MISMATCH!\n" +
                            "The .cfg file expects " + expectedCols + " data columns, " +
                            "but the .dat file line " + (i+1) + " only has " + p.length + " columns.\n\n" +
                            "Please ensure both .cfg and .dat files originate from the same record.");
                }

                tOut.add((safe(p[1]) / 1000000f) - triggerOffset);
                float[] s = new float[aCount]; 
                for (int j = 0; j < aCount; j++) s[j] = (safe(p[2 + j]) * multipliers[j]) + offsets[j]; 
                data.add(s);

                if (dCount > 0) { 
                    int[] d = new int[dCount]; 
                    for (int j = 0; j < dCount; j++) {
                        try { d[j] = Integer.parseInt(p[2 + aCount + j].trim()); } catch (Exception e) { d[j] = 0; }
                    }
                    dOut.add(d); 
                }
            }
            return data;
        }
        public float[] calculateRms(List<float[]> raw, int idx, int samples) {
            if (samples < 1) samples = 1;
            float[] rms = new float[raw.size()]; double sumSq = 0;
            for (int i = 0; i < Math.min(samples, raw.size()); i++) { float v = raw.get(i)[idx]; sumSq += (v * v); rms[i] = (float) Math.sqrt(sumSq / (i + 1)); }
            for (int i = samples; i < raw.size(); i++) { float n = raw.get(i)[idx], o = raw.get(i - samples)[idx]; sumSq += (n * n) - (o * o); rms[i] = (float) Math.sqrt(Math.max(0, sumSq) / samples); }
            return rms;
        }
    }
}
