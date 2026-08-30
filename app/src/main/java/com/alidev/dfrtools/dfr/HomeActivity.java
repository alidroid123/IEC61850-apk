package com.alidev.dfrtools.dfr;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.alidev.dfrtools.R;
import com.alidev.dfrtools.update.AppFcmService;
import com.alidev.dfrtools.update.AppNotifications;
import com.alidev.dfrtools.update.NotificationActivity;
import com.alidev.dfrtools.update.UpdateChecker;
import com.alidev.dfrtools.update.UpdateFlow;
import com.alidev.dfrtools.update.UpdatePrefs;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeActivity extends BaseActivity {

    private DrawerLayout drawerLayout;
    private TextView tvStatGiCount, tvStatDeviceCount, tvStatAlarmCount;
    private ImageView imgStatAlarm;
    private final UpdateFlow updateFlow = new UpdateFlow(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        drawerLayout = findViewById(R.id.drawerLayout);
        NavigationView navigationView = findViewById(R.id.navigationView);
        findViewById(R.id.btnMenu).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.btnNotifications).setOnClickListener(v -> startActivity(new Intent(this, NotificationActivity.class)));

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

        findViewById(R.id.fabAbout).setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        bindBottomBanner();

        checkForAppUpdate();
        requestNotificationPermissionIfNeeded();
        FirebaseMessaging.getInstance().subscribeToTopic(AppFcmService.TOPIC_APP_UPDATES);
    }

    private void bindBottomBanner() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.tvBottomBanner)).setText(
                    getString(R.string.lbl_home_bottom_banner, pInfo.versionName));
        } catch (PackageManager.NameNotFoundException ignored) {}
    }

    private static final int REQUEST_NOTIFICATION_PERMISSION = 2001;

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    /**
     * Silent background check against GitHub Releases; never surfaces errors to the user
     * since this is a non-critical background check (see UpdateChecker).
     */
    private void checkForAppUpdate() {
        UpdatePrefs.recordOpen(this);
        UpdateChecker.checkForUpdate(this, info -> {
            if (info == null || isFinishing()) return;
            // Logged to the in-app notification feed regardless of the dialog's own throttling
            // below, so a user who dismissed the popup can still find the update (and what
            // changed) later from the bell icon instead of it only ever appearing once.
            AppNotifications.add(this, "update_" + info.versionName,
                    getString(R.string.msg_all_update_available_title, info.versionName),
                    info.releaseNotes);
            refreshNotifBadge();
            if (!UpdatePrefs.shouldShowPrompt(this)) return;
            updateFlow.showUpdateDialog(info);
        });
    }

    private void refreshNotifBadge() {
        View dot = findViewById(R.id.dotNotifUnread);
        if (dot != null) dot.setVisibility(AppNotifications.hasUnread(this) ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboardStats();
        refreshNotifBadge();
        updateFlow.onResume();
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
        int[] btnIds = {R.id.theme_btn_0, R.id.theme_btn_1, R.id.theme_btn_2, R.id.theme_btn_3, R.id.theme_btn_4};
        int[] checkIds = {R.id.check_0, R.id.check_1, R.id.check_2, R.id.check_3, R.id.check_4};
        
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
