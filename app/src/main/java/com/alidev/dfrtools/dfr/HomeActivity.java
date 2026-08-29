package com.alidev.dfrtools.dfr;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.alidev.dfrtools.R;
import com.alidev.dfrtools.update.UpdateChecker;
import com.alidev.dfrtools.update.UpdatePrefs;
import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeActivity extends BaseActivity {

    private DrawerLayout drawerLayout;
    private TextView tvStatGiCount, tvStatDeviceCount, tvStatAlarmCount;
    private ImageView imgStatAlarm;
    private BroadcastReceiver updateDownloadReceiver;
    private long updateDownloadId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        NavigationView navigationView = findViewById(R.id.navigationView);
        findViewById(R.id.btnMenu).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

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
            } else if (id == R.id.nav_edit_template) {
                drawerLayout.closeDrawers();
                startActivity(new Intent(this, RelayTemplateEditActivity.class));
                return true;
            } else if (id == R.id.nav_about) {
                drawerLayout.closeDrawers();
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            } else if (id == R.id.nav_help) {
                drawerLayout.closeDrawers();
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            } else if (id == R.id.nav_vpn) {
                drawerLayout.closeDrawers();
                String packageName = "com.fortinet.forticlient_vpn";
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    // Fallback to VPN settings if app not installed
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

        findViewById(R.id.cardDownload).setOnClickListener(v -> {
            startActivity(new Intent(this, DfrDownloadActivity.class));
        });

        findViewById(R.id.cardFiles).setOnClickListener(v -> {
            startActivity(new Intent(this, InternalFileManagerActivity.class));
        });

        findViewById(R.id.cardViewer).setOnClickListener(v -> {
            startActivity(new Intent(this, DfrViewerActivity.class));
        });

        findViewById(R.id.cardDevices).setOnClickListener(v -> {
            startActivity(new Intent(this, DeviceListActivity.class));
        });

        findViewById(R.id.cardMms).setOnClickListener(v -> {
            startActivity(new Intent(this, MmsExplorerActivity.class));
        });

        findViewById(R.id.cardMonitoring).setOnClickListener(v -> {
            startActivity(new Intent(this, IEDMonitoringActivity.class));
        });

        tvStatGiCount = findViewById(R.id.tvStatGiCount);
        tvStatDeviceCount = findViewById(R.id.tvStatDeviceCount);
        tvStatAlarmCount = findViewById(R.id.tvStatAlarmCount);
        imgStatAlarm = findViewById(R.id.imgStatAlarm);

        checkForAppUpdate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updateDownloadReceiver != null) {
            unregisterReceiver(updateDownloadReceiver);
            updateDownloadReceiver = null;
        }
    }

    /**
     * Silent background check against GitHub Releases; never surfaces errors to the user
     * since this is a non-critical background check (see UpdateChecker).
     */
    private void checkForAppUpdate() {
        UpdatePrefs.recordOpen(this);
        UpdateChecker.checkForUpdate(this, info -> {
            if (info == null || isFinishing() || !UpdatePrefs.shouldShowPrompt(this)) return;
            showUpdateDialog(info);
        });
    }

    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_update_available, null);
        TextView tvMessage = dialogView.findViewById(R.id.tvUpdateMessage);
        tvMessage.setText(getString(R.string.msg_all_update_available, info.versionName));

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        dialogView.findViewById(R.id.btnUpdateLater).setOnClickListener(v -> {
            UpdatePrefs.onDismissed(this);
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnUpdateNow).setOnClickListener(v -> {
            dialog.dismiss();
            startUpdateDownload(info);
        });

        dialog.show();
    }

    private void startUpdateDownload(UpdateChecker.UpdateInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.msg_all_update_grant_install, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }

        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl));
        request.setTitle(getString(R.string.app_name));
        request.setDescription(getString(R.string.ttl_all_update_available));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, null, "update.apk");
        request.setMimeType("application/vnd.android.package-archive");

        updateDownloadId = downloadManager.enqueue(request);

        updateDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == updateDownloadId) installDownloadedApk();
            }
        };
        ContextCompat.registerReceiver(this, updateDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void installDownloadedApk() {
        File apkFile = new File(getExternalFilesDir(null), "update.apk");
        if (!apkFile.exists()) return;
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(installIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboardStats();
    }

    /**
     * Reads straight from the same persisted SharedPreferences the other activities write to
     * (dfr_prefs/device_list + last_ping_results, MonitoringManager) rather than caching in
     * memory, since ping results/alarms can change while this activity isn't on screen.
     */
    private void refreshDashboardStats() {
        SharedPreferences dfrPrefs = getSharedPreferences("dfr_prefs", MODE_PRIVATE);
        Set<String> giSet = new HashSet<>();
        int deviceTotal = 0;
        int deviceOnline = 0;
        try {
            JSONArray devices = new JSONArray(dfrPrefs.getString("device_list", "[]"));
            JSONObject pingResults = new JSONObject(dfrPrefs.getString("last_ping_results", "{}"));
            deviceTotal = devices.length();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject d = devices.getJSONObject(i);
                String gi = d.optString("gi", "").trim();
                if (!gi.isEmpty()) giSet.add(gi);

                String ip = d.optString("ip", "");
                JSONObject pingResult = pingResults.optJSONObject(ip);
                if (pingResult != null && "ONLINE".equals(pingResult.optString("status"))) {
                    deviceOnline++;
                }
            }
        } catch (JSONException ignored) {}

        int alarmActive = 0;
        List<MonitoredNode> nodes = new MonitoringManager(this).getNodes();
        for (MonitoredNode node : nodes) {
            if (node.isAlarming()) alarmActive++;
        }

        tvStatGiCount.setText(String.valueOf(giSet.size()));
        tvStatDeviceCount.setText(deviceOnline + "/" + deviceTotal);
        tvStatAlarmCount.setText(String.valueOf(alarmActive));

        // Neutral by default (matches the other two stats); only pop to red when there's
        // something to actually warn about.
        boolean hasAlarm = alarmActive > 0;
        int alarmTextColor = ContextCompat.getColor(this, hasAlarm ? R.color.status_danger : R.color.text_primary);
        int alarmIconColor;
        if (hasAlarm) {
            alarmIconColor = ContextCompat.getColor(this, R.color.status_danger);
        } else {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
            alarmIconColor = typedValue.data;
        }
        tvStatAlarmCount.setTextColor(alarmTextColor);
        imgStatAlarm.setImageTintList(android.content.res.ColorStateList.valueOf(alarmIconColor));
    }

    private void setupDrawerThemes(NavigationView navigationView) {
        View drawerRoot = findViewById(R.id.containerThemeGrid);
        if (drawerRoot == null) return;
        
        int current = ThemeManager.getSelectedThemeIndex(this);
        
        // Setup buttons and checkmarks
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
}
