package com.alidev.dfrtools.update;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.alidev.dfrtools.R;

import java.io.File;

/**
 * Shared "update available" dialog + download-kickoff flow, reused by every Activity that can
 * trigger an update check (HomeActivity's silent background check on app open, AboutActivity's
 * manual "Cek Update" button). The actual download and "install once downloaded" step are owned
 * by UpdateDownloadService (a foreground service, not this class) so they keep running - and the
 * system installer still opens automatically - even if the user leaves the app or this Activity
 * is destroyed while the download is in progress.
 */
public class UpdateFlow {

    private final Activity activity;
    private UpdateChecker.UpdateInfo pendingInfo;
    private boolean waitingForInstallPermission = false;

    public UpdateFlow(Activity activity) {
        this.activity = activity;
    }

    /**
     * Call from the host Activity's onResume(). If the user was sent to
     * Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES to grant the "install unknown apps" permission,
     * this resumes the update automatically as soon as they come back with it granted - either
     * installing the already-downloaded APK directly, or (re)starting the download if it isn't
     * there yet - instead of silently doing nothing until the user taps "Update Sekarang" again.
     */
    public void onResume() {
        if (!waitingForInstallPermission) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            return; // still not granted - keep waiting
        }
        waitingForInstallPermission = false;
        File apkFile = new File(activity.getExternalFilesDir(null), "update.apk");
        if (apkFile.exists()) {
            installDownloadedApk(apkFile);
        } else if (pendingInfo != null) {
            startDownload(pendingInfo);
        }
    }

    public void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        if (activity.isFinishing()) return;
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_update_available, null);
        TextView tvMessage = dialogView.findViewById(R.id.tvUpdateMessage);
        tvMessage.setText(activity.getString(R.string.msg_all_update_available, info.versionName));

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        dialogView.findViewById(R.id.btnUpdateLater).setOnClickListener(v -> {
            UpdatePrefs.onDismissed(activity);
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnUpdateNow).setOnClickListener(v -> {
            dialog.dismiss();
            startDownload(info);
        });

        dialog.show();
    }

    private void startDownload(UpdateChecker.UpdateInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInfo = info;
            waitingForInstallPermission = true;
            Toast.makeText(activity, R.string.msg_all_update_grant_install, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }

        Intent serviceIntent = new Intent(activity, UpdateDownloadService.class);
        serviceIntent.putExtra(UpdateDownloadService.EXTRA_DOWNLOAD_URL, info.downloadUrl);
        ContextCompat.startForegroundService(activity, serviceIntent);
        Toast.makeText(activity, R.string.msg_all_update_downloading, Toast.LENGTH_SHORT).show();
    }

    /** Used only for the onResume() "already downloaded, permission just granted" case above. */
    private void installDownloadedApk(File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(installIntent);
    }
}
