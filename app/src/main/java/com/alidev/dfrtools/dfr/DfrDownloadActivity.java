package com.alidev.dfrtools.dfr;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputEditText;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.utils.IpAddressHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DfrDownloadActivity extends BaseActivity {

    private final Iec61850DfrClient client = new Iec61850DfrClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService pingExecutor = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> pingFuture = null;

    private EditText etIp1, etIp2, etIp3, etIp4;
    private TextInputEditText etPort, etN;
    private Button btnConnect, btnPing, btnDownloadByMode;
    private ImageButton btnSaveDevice, btnOpenList, btnMenu;
    private TextView tvConnectionStatus, tvVendorInfo, tvEmptyState, tvFileListLabel, tvPingResults, tvPingStatus, tvDeviceInfo, tvPingResultsPort;
    private LinearLayout containerFiles;
    private View overlayProgress, layoutPostDownload;
    private androidx.appcompat.widget.SwitchCompat swIntranetCheck;
    private Spinner spProfile;
    private com.google.android.material.card.MaterialCardView cardDownloadMode;
    private TextView tvProgressLabel;
    private View layoutProgressOnly;
    private Button btnOpenDownloaded, btnCloseOverlay, btnOpenFolder, btnShareDownloaded;
    private RadioGroup rgDownloadMode;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private String currentGi = "", currentBay = "";

    private ComtradeSmartSearch.ScanResult lastScan = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dfr_download);

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        btnMenu = findViewById(R.id.btnMenu);

        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(androidx.core.view.GravityCompat.START));

        setupDrawerThemes(navigationView);

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_mode) {
                drawerLayout.closeDrawers();
                toggleTheme();
                return true;
            } else if (id == R.id.nav_settings) {
                drawerLayout.closeDrawers();
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            } else if (id == R.id.nav_monitoring) {
                drawerLayout.closeDrawers();
                startActivity(new Intent(this, IEDMonitoringActivity.class));
                return true;
            } else if (id == R.id.nav_about) {
                drawerLayout.closeDrawers();
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            } else if (id == R.id.nav_vpn) {
                drawerLayout.closeDrawers();
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
                return true;
            }
            return false;
        });

        findViewById(R.id.fabGuide).setOnClickListener(v -> {
            startActivity(new Intent(this, AboutActivity.class));
        });

        etIp1 = findViewById(R.id.etIp1);
        etIp2 = findViewById(R.id.etIp2);
        etIp3 = findViewById(R.id.etIp3);
        etIp4 = findViewById(R.id.etIp4);
        IpAddressHelper.setupIpInputs(etIp1, etIp2, etIp3, etIp4);

        etPort = findViewById(R.id.etPort);
        etN = findViewById(R.id.etN);
        btnConnect = findViewById(R.id.btnConnect);
        btnPing = findViewById(R.id.btnPing);
        btnDownloadByMode = findViewById(R.id.btnDownloadByMode);
        btnSaveDevice = findViewById(R.id.btnSaveDevice);
        btnOpenList = findViewById(R.id.btnOpenList);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvPingResults = findViewById(R.id.tvPingResults);
        tvPingResultsPort = findViewById(R.id.tvPingResultsPort);
        tvPingStatus = findViewById(R.id.tvPingStatus);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        tvVendorInfo = findViewById(R.id.tvVendorInfo);
        tvEmptyState = findViewById(R.id.tvEmptyState);
        tvFileListLabel = findViewById(R.id.tvFileListLabel);
        containerFiles = findViewById(R.id.containerFiles);
        cardDownloadMode = findViewById(R.id.cardDownloadMode);
        swIntranetCheck = findViewById(R.id.swIntranetCheck);
        spProfile = findViewById(R.id.spProfile);
        overlayProgress = findViewById(R.id.overlayProgress);
        tvProgressLabel = findViewById(R.id.tvProgressLabel);
        layoutProgressOnly = findViewById(R.id.layoutProgressOnly);
        rgDownloadMode = findViewById(R.id.rgDownloadMode);
        layoutPostDownload = findViewById(R.id.layoutPostDownload);
        btnOpenDownloaded = findViewById(R.id.btnOpenDownloaded);
        btnOpenFolder = findViewById(R.id.btnOpenFolder);
        btnShareDownloaded = findViewById(R.id.btnShareDownloaded);
        btnCloseOverlay = findViewById(R.id.btnCloseOverlay);

        setupIpWatcher();
        setupProfileSpinner();

        btnConnect.setOnClickListener(v -> checkIntranetAndExecute(this::onConnectClicked));
        btnPing.setOnClickListener(v -> checkIntranetAndExecute(this::onPingClicked));
        btnDownloadByMode.setOnClickListener(v -> onDownloadByModeClicked());
        btnSaveDevice.setOnClickListener(v -> onSaveDeviceClicked());
        btnOpenList.setOnClickListener(v -> onOpenListClicked());
        btnCloseOverlay.setOnClickListener(v -> hideProgress());
        btnOpenFolder.setOnClickListener(v -> {
            hideProgress();
            startActivity(new Intent(this, InternalFileManagerActivity.class));
        });

        loadLastIp();
        tvEmptyState.setVisibility(View.VISIBLE);

        handleDeviceIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeviceIntent(intent);
    }

    private void handleDeviceIntent(Intent intent) {
        if (intent == null) return;
        String ip = intent.getStringExtra("ip");
        String gi = intent.getStringExtra("gi");
        String bay = intent.getStringExtra("bay");
        String device = intent.getStringExtra("device");

        if (ip != null) {
            ip = ip.replace("\"", "").trim();
            IpAddressHelper.setIpToInputs(ip, etIp1, etIp2, etIp3, etIp4);

            if (gi != null && bay != null && device != null) {
                this.currentGi = gi;
                this.currentBay = bay;
                tvDeviceInfo.setText(String.format("%s \u2022 %s \u2022 %s", gi, bay, device));
                tvDeviceInfo.setVisibility(View.VISIBLE);
            } else {
                this.currentGi = "";
                this.currentBay = "";
                tvDeviceInfo.setVisibility(View.GONE);
            }

            checkIntranetAndExecute(this::onConnectClicked);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.execute(client::disconnect);
        executor.shutdown();
        pingExecutor.shutdownNow();
    }

    private void onConnectClicked() {
            if (btnConnect.getText().toString().equalsIgnoreCase(getString(R.string.btn_dl_disconnect))) {
                showProgress(getString(R.string.msg_dl_disconnecting));
            executor.execute(() -> {
                client.disconnect();
                runOnUiThread(this::onDisconnected);
            });
            return;
        }

        String host = IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        String portStr = textOf(etPort);
        if (TextUtils.isEmpty(host) || host.equals("0.0.0.0")) {
            Toast.makeText(this, R.string.err_dl_required_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        try {
            int defaultPort = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
            port = TextUtils.isEmpty(portStr) ? defaultPort : Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            etPort.setError(getString(R.string.err_dl_invalid_port));
            return;
        }

        showProgress(getString(R.string.msg_dl_connecting, host, port));
        saveLastIp(host);
        int profile = spProfile.getSelectedItemPosition();
        int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
        executor.execute(() -> {
            boolean success = client.connect(host, port, timeout, profile);
            if (!success) {
                String reason = client.getLastError();
                runOnUiThread(() -> {
                    hideProgress();
                    Toast.makeText(this, getString(R.string.msg_dl_connect_fail, reason), Toast.LENGTH_LONG).show();
                });
                return;
            }
            runOnUiThread(() -> onConnected(host));
            runSmartScan();
        });
    }

    private void onConnected(String host) {
        tvConnectionStatus.setText(getString(R.string.msg_dl_connected_to, host));
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_safe));
        btnConnect.setText(R.string.btn_dl_disconnect);
    }

    private void onDisconnected() {
        hideProgress();
        tvConnectionStatus.setText(R.string.msg_dl_status_off_short);
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvDeviceInfo.setVisibility(View.GONE);
        btnConnect.setText(R.string.btn_dl_connect);
        tvVendorInfo.setVisibility(View.GONE);
        cardDownloadMode.setVisibility(View.GONE);
        tvFileListLabel.setVisibility(View.GONE);
        containerFiles.removeAllViews();
        lastScan = null;
        tvEmptyState.setText(R.string.msg_dl_connect_to_search);
        tvEmptyState.setVisibility(View.VISIBLE);
        this.currentGi = "";
        this.currentBay = "";
    }

    private void runSmartScan() {
        runOnUiThread(() -> showProgress(getString(R.string.msg_dl_detecting_vendor)));

        ComtradeSmartSearch.Logger uiLogger = message ->
                runOnUiThread(() -> showProgress(message.replaceFirst("^[!iv]\\s*", "")));

        ComtradeSmartSearch.VendorInfo vendor = ComtradeSmartSearch.detectVendor(this, client, uiLogger);

        runOnUiThread(() -> showProgress(getString(R.string.msg_dl_scanning)));

        ComtradeSmartSearch.ScanResult scan = ComtradeSmartSearch.scan(this, client, vendor.deepScan, uiLogger);

        runOnUiThread(() -> {
            hideProgress();
            lastScan = scan;
            renderVendorInfo(vendor);
            renderScanResult(scan);
        });
    }

    private void renderVendorInfo(ComtradeSmartSearch.VendorInfo vendor) {
        if (vendor.vendorOrModel == null || vendor.vendorOrModel.isEmpty()) {
            tvVendorInfo.setText(R.string.msg_dl_vendor_unknown);
        } else {
            String mode = vendor.deepScan ? getString(R.string.msg_dl_mode_deep_label) : getString(R.string.msg_dl_mode_normal_label);
            tvVendorInfo.setText(getString(R.string.msg_dl_vendor_detected, vendor.vendorOrModel, mode));
        }
    }

    private void renderScanResult(ComtradeSmartSearch.ScanResult scan) {
        containerFiles.removeAllViews();

        if (scan.targetFiles.isEmpty()) {
            cardDownloadMode.setVisibility(View.GONE);
            tvFileListLabel.setVisibility(View.GONE);
            tvEmptyState.setText(R.string.msg_dl_no_files_found);
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }

        tvEmptyState.setVisibility(View.GONE);
        cardDownloadMode.setVisibility(View.VISIBLE);
        tvFileListLabel.setVisibility(View.VISIBLE);
        tvFileListLabel.setText(getString(R.string.msg_dl_files_found_count, scan.targetFiles.size()));

        int number = 1;
        for (DfrFileEntry entry : scan.targetFiles) {
            containerFiles.addView(buildTargetRow(entry, number, scan.allFiles));
            number++;
        }
    }

    private View buildTargetRow(DfrFileEntry entry, int number, List<DfrFileEntry> allFiles) {
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        card.setRadius(dp(8));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.divider));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(4);
        card.setLayoutParams(cardLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));

        TextView tvNumber = new TextView(this);
        tvNumber.setText("#" + number);
        tvNumber.setTextColor(ContextCompat.getColor(this, R.color.brand_primary)); 
        tvNumber.setTypeface(tvNumber.getTypeface(), android.graphics.Typeface.BOLD);
        tvNumber.setTextSize(11f);
        tvNumber.setMinWidth(dp(25));
        row.addView(tvNumber);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textColLp);

        TextView tvName = new TextView(this);
        tvName.setText(entry.cleanName());
        tvName.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tvName.setTextSize(12.5f);
        tvName.setTypeface(tvName.getTypeface(), android.graphics.Typeface.BOLD);
        textCol.addView(tvName);

        TextView tvMeta = new TextView(this);
        tvMeta.setText(entry.formattedSize() + "  \u2022  " + entry.formattedDate());
        tvMeta.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvMeta.setTextSize(10.5f);
        textCol.addView(tvMeta);

        row.addView(textCol);

        File localFile = getLocalFile(entry.cleanName());
        boolean exists = localFile.exists();

        if (exists) {
            Button btnOpen = new Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnOpen.setText(R.string.btn_all_open);
            btnOpen.setTextSize(10f);
            btnOpen.setPadding(dp(8), 0, dp(8), 0);
            btnOpen.setMinWidth(0);
            btnOpen.setMinimumWidth(0);
            btnOpen.setTextColor(ContextCompat.getColor(this, R.color.status_safe));
            btnOpen.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
            btnOpen.setOnClickListener(v -> {
                Intent intent = new Intent(this, DfrViewerActivity.class);
                intent.setData(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", localFile));
                
                String baseName = entry.cleanName().substring(0, entry.cleanName().lastIndexOf('.'));
                File datFile = getLocalFile(baseName + ".dat");
                if (datFile.exists()) {
                    intent.putExtra("paired_dat_uri", FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", datFile).toString());
                }
                
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            });
            row.addView(btnOpen);
            
            View space = new View(this);
            space.setLayoutParams(new LinearLayout.LayoutParams(dp(4), dp(1)));
            row.addView(space);
        }

        Button btnDownload = new Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDownload.setText(exists ? getString(R.string.msg_dl_btn_redl) : getString(R.string.msg_dl_btn_unduh));
        btnDownload.setTextSize(10f);
        btnDownload.setPadding(dp(8), 0, dp(8), 0);
        btnDownload.setMinWidth(0);
        btnDownload.setMinimumWidth(0);
        btnDownload.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        btnDownload.setOnClickListener(v -> downloadFileSet(entry, allFiles));
        row.addView(btnDownload);

        card.addView(row);
        return card;
    }

    private File getLocalFile(String fileName) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File baseDir = new File(downloadDir, "DFR");
        String subFolderName = "General";
        if (!TextUtils.isEmpty(currentGi) && !TextUtils.isEmpty(currentBay)) {
            subFolderName = (currentGi + "_" + currentBay).replaceAll("[^a-zA-Z0-9_]", "_");
        }
        
        File targetDir = new File(baseDir, subFolderName);
        File file = new File(targetDir, fileName);
        if (file.exists()) return file;

        File fallbackDir = new File(new File(getExternalFilesDir(null), "DFR"), subFolderName);
        File fallbackFile = new File(fallbackDir, fileName);
        if (fallbackFile.exists()) return fallbackFile;
        
        return file;
    }

    private void onDownloadByModeClicked() {
        if (lastScan == null || lastScan.targetFiles.isEmpty()) {
            Toast.makeText(this, R.string.msg_dl_no_scan_results, Toast.LENGTH_SHORT).show();
            return;
        }
        String nStr = etN.getText() == null ? "" : etN.getText().toString().trim();
        int n;
        try {
            n = Integer.parseInt(nStr);
        } catch (NumberFormatException e) {
            etN.setError(getString(R.string.err_dl_required_n));
            return;
        }
        if (n < 1) {
            etN.setError(getString(R.string.err_dl_min_n));
            return;
        }

        boolean bulkMode = rgDownloadMode.getCheckedRadioButtonId() == R.id.rbBulk;
        List<DfrFileEntry> queue;
        if (bulkMode) {
            queue = ComtradeSmartSearch.selectBulk(lastScan.targetFiles, n);
        } else {
            DfrFileEntry single = ComtradeSmartSearch.selectSingle(lastScan.targetFiles, n);
            queue = single != null ? java.util.Collections.singletonList(single) : java.util.Collections.emptyList();
        }

        if (queue.isEmpty()) {
            Toast.makeText(this, R.string.msg_dl_index_out_of_range, Toast.LENGTH_SHORT).show();
            return;
        }

        downloadQueueSequentially(queue, lastScan.allFiles);
    }

    private void downloadFileSet(DfrFileEntry target, List<DfrFileEntry> allFiles) {
        downloadQueueSequentially(java.util.Collections.singletonList(target), allFiles);
    }

    private void downloadQueueSequentially(List<DfrFileEntry> queue, List<DfrFileEntry> allFiles) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File baseDir = new File(downloadDir, "DFR");
        String subFolderName = "General";
        if (!TextUtils.isEmpty(currentGi) && !TextUtils.isEmpty(currentBay)) {
            subFolderName = (currentGi + "_" + currentBay).replaceAll("[^a-zA-Z0-9_]", "_");
        }
        File finalOutDir;
        File targetDir = new File(baseDir, subFolderName);

        if (targetDir.exists() || targetDir.mkdirs()) {
            finalOutDir = targetDir;
        } else {
            finalOutDir = new File(new File(getExternalFilesDir(null), "DFR"), subFolderName);
            if (!finalOutDir.exists()) finalOutDir.mkdirs();
        }

        executor.execute(() -> {
            int totalOk = 0, totalFail = 0;
            List<File> savedFiles = new ArrayList<>();

            for (DfrFileEntry target : queue) {
                List<DfrFileEntry> fileSet = ComtradeSmartSearch.resolveFileSet(target, allFiles);
                for (DfrFileEntry f : fileSet) {
                    String localName = f.cleanName();
                    File outFile = new File(finalOutDir, localName);
                    runOnUiThread(() -> showProgress(getString(R.string.msg_dl_downloading_file, localName)));

                    boolean success = client.downloadFile(f.fullPath(), outFile.getAbsolutePath());
                    if (success) {
                        totalOk++;
                        savedFiles.add(outFile);
                    } else {
                        totalFail++;
                    }
                }
            }

            int finalOk = totalOk, finalFail = totalFail;
            runOnUiThread(() -> {
                if (finalOk > 0) {
                    tvProgressLabel.setText(getString(R.string.msg_dl_download_ok, savedFiles.size()));
                    layoutProgressOnly.setVisibility(View.GONE);
                    layoutPostDownload.setVisibility(View.VISIBLE);
                    
                    File cfgFile = null;
                    File datFile = null;
                    for (File f : savedFiles) {
                        if (f.getName().toLowerCase().endsWith(".cfg")) cfgFile = f;
                        if (f.getName().toLowerCase().endsWith(".dat")) datFile = f;
                    }

                    if (cfgFile != null) {
                        final File finalCfg = cfgFile;
                        final File finalDat = datFile;
                        btnOpenDownloaded.setVisibility(View.VISIBLE);
                        btnOpenDownloaded.setOnClickListener(v -> {
                            hideProgress();
                            Intent intent = new Intent(this, DfrViewerActivity.class);
                            intent.setData(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", finalCfg));
                            if (finalDat != null) {
                                intent.putExtra("paired_dat_uri", FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", finalDat).toString());
                            }
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(intent);
                        });
                        
                        btnShareDownloaded.setOnClickListener(v -> {
                            shareFiles(savedFiles);
                        });
                    } else {
                        btnOpenDownloaded.setVisibility(View.GONE);
                    }
                    
                    renderScanResult(lastScan);
                } else {
                    hideProgress();
                    String msg = (finalFail > 0) ? getString(R.string.msg_dl_download_failed_with_errors, finalFail) : getString(R.string.msg_dl_download_fail);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }


    private void shareFiles(List<File> files) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
            }

            Intent intent = new Intent();
            if (uris.size() == 1) {
                intent.setAction(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_dl_share_chooser)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupProfileSpinner() {
        String[] profiles = {
                getString(R.string.profile_default),
                getString(R.string.profile_abb_ge),
                getString(R.string.profile_schneider)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, profiles) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                ((TextView) v).setTextSize(13);
                return v;
            }
            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                return v;
            }
        };
        spProfile.setAdapter(adapter);
    }

    private void setupIpWatcher() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                lookupDeviceByIp(IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4));
            }
        };
        etIp1.addTextChangedListener(watcher);
        etIp2.addTextChangedListener(watcher);
        etIp3.addTextChangedListener(watcher);
        etIp4.addTextChangedListener(watcher);
    }

    private void lookupDeviceByIp(String ip) {
        if (TextUtils.isEmpty(ip) || ip.equals("0.0.0.0")) {
            tvDeviceInfo.setVisibility(View.GONE);
            this.currentGi = "";
            this.currentBay = "";
            return;
        }

        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String json = pref.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String savedIp = obj.getString("ip").replace("\"", "").trim();
                if (savedIp.equalsIgnoreCase(ip)) {
                    String gi = obj.getString("gi").replace("\"", "").trim();
                    String bay = obj.getString("bay").replace("\"", "").trim();
                    String device = obj.getString("device").replace("\"", "").trim();
                    
                    this.currentGi = gi;
                    this.currentBay = bay;
                    
                    runOnUiThread(() -> {
                        tvDeviceInfo.setText(String.format("%s \u2022 %s \u2022 %s", gi, bay, device));
                        tvDeviceInfo.setVisibility(View.VISIBLE);
                    });
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        tvDeviceInfo.setVisibility(View.GONE);
        this.currentGi = "";
        this.currentBay = "";
    }

    private void saveLastIp(String ip) {
        getSharedPreferences("dfr_prefs", MODE_PRIVATE).edit().putString("last_ip", ip).apply();
    }

    private void loadLastIp() {
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String lastIp = pref.getString("last_ip", "");
        if (!lastIp.isEmpty()) {
            IpAddressHelper.setIpToInputs(lastIp, etIp1, etIp2, etIp3, etIp4);
        }
        
        int defaultPort = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
        etPort.setText(String.valueOf(defaultPort));
    }

    private void onSaveDeviceClicked() {
        String host = IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        if (TextUtils.isEmpty(host) || host.split("\\.").length < 4) {
            Toast.makeText(this, R.string.msg_dl_invalid_ip_prompt, Toast.LENGTH_SHORT).show();
            return;
        }

        String existingGi = "", existingBay = "", existingDevice = "", existingMerk = "", existingType = "";
        boolean isUpdate = false;
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String json = pref.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getString("ip").equalsIgnoreCase(host)) {
                    existingGi = obj.getString("gi").replace("\"", "").trim();
                    existingBay = obj.getString("bay").replace("\"", "").trim();
                    existingDevice = obj.getString("device").replace("\"", "").trim();
                    existingMerk = obj.optString("merk", "").replace("\"", "").trim();
                    existingType = obj.optString("type", "").replace("\"", "").trim();
                    isUpdate = true;
                    break;
                }
            }
        } catch (Exception ignored) {}

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_save_device, null);
        EditText etGi = dialogView.findViewById(R.id.etDialogGi);
        EditText etBay = dialogView.findViewById(R.id.etDialogBay);
        EditText etDevice = dialogView.findViewById(R.id.etDialogDevice);
        EditText etMerk = dialogView.findViewById(R.id.etDialogMerk);
        EditText etType = dialogView.findViewById(R.id.etDialogType);

        if (isUpdate) {
            etGi.setText(existingGi);
            etBay.setText(existingBay);
            etDevice.setText(existingDevice);
            etMerk.setText(existingMerk);
            etType.setText(existingType);
        }

        new AlertDialog.Builder(this, R.style.Theme_DFRtools)
                .setTitle(isUpdate ? R.string.ttl_dev_update : R.string.ttl_dev_save)
                .setView(dialogView)
                .setPositiveButton(isUpdate ? R.string.btn_all_update : R.string.btn_all_save_small, (dialog, which) -> {
                    String gi = etGi.getText().toString().trim();
                    String bay = etBay.getText().toString().trim();
                    String device = etDevice.getText().toString().trim();
                    String merk = etMerk.getText().toString().trim();
                    String type = etType.getText().toString().trim();

                    if (gi.isEmpty() || bay.isEmpty() || device.isEmpty()) {
                        Toast.makeText(this, R.string.msg_all_fields_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveDeviceToList(gi, bay, device, host, merk, type);
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }

    private void saveDeviceToList(String gi, String bay, String device, String ip, String merk, String type) {
        SharedPreferences pref = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        String json = pref.getString("device_list", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            boolean updated = false;
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getString("ip").equalsIgnoreCase(ip)) {
                    obj.put("gi", gi);
                    obj.put("bay", bay);
                    obj.put("device", device);
                    obj.put("merk", merk);
                    obj.put("type", type);
                    updated = true;
                    break;
                }
            }

            if (!updated) {
                JSONObject obj = new JSONObject();
                obj.put("gi", gi);
                obj.put("bay", bay);
                obj.put("device", device);
                obj.put("ip", ip);
                obj.put("merk", merk);
                obj.put("type", type);
                arr.put(obj);
            }

            pref.edit().putString("device_list", arr.toString()).apply();
            Toast.makeText(this, updated ? R.string.msg_dl_update_device_ok : R.string.msg_dl_save_device_ok, Toast.LENGTH_SHORT).show();
            
            if (IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4).equalsIgnoreCase(ip)) {
                lookupDeviceByIp(ip);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onOpenListClicked() {
        Intent intent = new Intent(this, DeviceListActivity.class);
        intent.putExtra("is_pick_mode", true);
        startActivityForResult(intent, 200);
    }

    private void checkIntranetAndExecute(Runnable onSuccess) {
        if (swIntranetCheck != null && !swIntranetCheck.isChecked()) {
            onSuccess.run();
            return;
        }
        Toast.makeText(this, R.string.msg_dev_ping_precheck, Toast.LENGTH_SHORT).show();
        String intranetIp = com.alidev.dfrtools.utils.ConfigHelper.getIntranetIp(this);
        pingExecutor.execute(() -> {
            boolean intranetOk = false;
            try {
                java.lang.Process process = Runtime.getRuntime().exec("ping -c 1 -W 2 " + intranetIp);
                int exitCode = process.waitFor();
                intranetOk = (exitCode == 0);
            } catch (Exception e) {
                e.printStackTrace();
            }

            final boolean finalIntranetOk = intranetOk;
            runOnUiThread(() -> {
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

    private void onPingClicked() {
        String host = IpAddressHelper.getIpFromInputs(etIp1, etIp2, etIp3, etIp4);
        if (TextUtils.isEmpty(host) || host.equals("0.0.0.0")) {
            Toast.makeText(this, R.string.err_mms_required_ip, Toast.LENGTH_SHORT).show();
            return;
        }

        if (pingFuture != null && !pingFuture.isDone()) {
            pingFuture.cancel(true);
        }

        tvPingResults.setText(getString(R.string.msg_dl_pinging, host));
        tvPingResults.setVisibility(View.VISIBLE);
        tvPingResultsPort.setVisibility(View.GONE);
        tvPingStatus.setVisibility(View.GONE);

        pingFuture = pingExecutor.submit(() -> {
            StringBuilder result = new StringBuilder();
            boolean success = false;
            try {
                int count = com.alidev.dfrtools.utils.ConfigHelper.getPingCountBulk(this);
                int timeout = getResources().getInteger(R.integer.config_ping_timeout_seconds);
                try {
                    java.net.InetAddress addr = java.net.InetAddress.getByName(host);
                    if (addr.isReachable(timeout * 1000)) {
                        result.append(getString(R.string.msg_mms_host_reachable));
                    }
                } catch (Exception ignored) {}

                String cmd = String.format(java.util.Locale.US, "ping -c %d -W %d %s", count, timeout, host);
                java.lang.Process process = Runtime.getRuntime().exec(cmd);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.interrupted()) {
                        process.destroy();
                        return;
                    }
                    result.append(line).append("\n");
                    String currentOutput = result.toString();
                    runOnUiThread(() -> tvPingResults.setText(currentOutput));
                }
                int exitCode = process.waitFor();
                success = (exitCode == 0);
            } catch (Exception e) {
                result.append("Error: ").append(e.getMessage());
            }

            final boolean finalSuccess = success;

            boolean portOpen = false;
            try {
                java.net.Socket socket = new java.net.Socket();
                int socketTimeout = getResources().getInteger(R.integer.config_ping_socket_timeout_ms);
                socket.connect(new java.net.InetSocketAddress(host, 102), socketTimeout);
                socket.close();
                portOpen = true;
            } catch (Exception ignored) {}

            final boolean finalPortOpen = portOpen;

            runOnUiThread(() -> {
                tvPingResults.setText(result.toString());
                tvPingResultsPort.setVisibility(View.VISIBLE);
                if (finalPortOpen) {
                    tvPingResultsPort.setText(R.string.lbl_dl_port_open);
                    tvPingResultsPort.setTextColor(0xFF00FF00); 
                } else {
                    tvPingResultsPort.setText(R.string.lbl_dl_port_closed);
                    tvPingResultsPort.setTextColor(0xFFFF0000); 
                }

                tvPingResults.postDelayed(() -> {
                    tvPingResults.setVisibility(View.GONE);
                    tvPingResultsPort.setVisibility(View.GONE);
                }, 4000);

                tvPingStatus.setVisibility(View.VISIBLE);
                
                StringBuilder statusText = new StringBuilder();
                statusText.append(finalSuccess ? "ONLINE" : "OFFLINE");
                statusText.append(" \u2022 PORT 102: ");
                statusText.append(finalPortOpen ? "OPEN" : "CLOSED");
                
                tvPingStatus.setText(statusText.toString());
                
                if (finalSuccess && finalPortOpen) {
                    tvPingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_safe));
                } else if (finalSuccess) {
                    tvPingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning));
                } else {
                    tvPingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
                }
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200 && resultCode == RESULT_OK && data != null) {
            String ip = data.getStringExtra("ip");
            String gi = data.getStringExtra("gi");
            String bay = data.getStringExtra("bay");
            String device = data.getStringExtra("device");

            if (ip != null) {
                ip = ip.replace("\"", "").trim();
                IpAddressHelper.setIpToInputs(ip, etIp1, etIp2, etIp3, etIp4);

                if (gi != null && bay != null && device != null) {
                    this.currentGi = gi;
                    this.currentBay = bay;
                    tvDeviceInfo.setText(String.format("%s \u2022 %s \u2022 %s", gi, bay, device));
                    tvDeviceInfo.setVisibility(View.VISIBLE);
                } else {
                    this.currentGi = "";
                    this.currentBay = "";
                    tvDeviceInfo.setVisibility(View.GONE);
                }

                checkIntranetAndExecute(this::onConnectClicked);
            }
        }
    }

    private void showProgress(String label) {
        runOnUiThread(() -> {
            tvProgressLabel.setText(label);
            layoutPostDownload.setVisibility(View.GONE);
            layoutProgressOnly.setVisibility(View.VISIBLE);
            overlayProgress.setVisibility(View.VISIBLE);
        });
    }

    private void hideProgress() {
        runOnUiThread(() -> {
            overlayProgress.setVisibility(View.GONE);
            layoutProgressOnly.setVisibility(View.GONE);
            layoutPostDownload.setVisibility(View.GONE);
        });
    }

    private String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private void setupDrawerThemes(NavigationView navigationView) {
        View drawerRoot = findViewById(R.id.containerThemeGrid);
        if (drawerRoot == null) return;
        
        int current = ThemeManager.getSelectedThemeIndex(this);
        
        int[] btnIds = {R.id.theme_btn_0, R.id.theme_btn_1, R.id.theme_btn_2, R.id.theme_btn_3};
        int[] checkIds = {R.id.check_0, R.id.check_1, R.id.check_2, R.id.check_3};
        
        for (int i = 0; i < btnIds.length; i++) {
            final int index = i;
            drawerRoot.findViewById(btnIds[i]).setOnClickListener(v -> applyThemeChange(index));
            drawerRoot.findViewById(checkIds[i]).setVisibility(current == i ? View.VISIBLE : View.GONE);
        }
    }

    private void applyThemeChange(int index) {
        ThemeManager.setSelectedThemeIndex(this, index);
        drawerLayout.closeDrawers();
        recreate();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
