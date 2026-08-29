package com.alidev.dfrtools.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * Shared "update available" dialog + download + sideload-install flow, reused by every Activity
 * that can trigger an update check (HomeActivity's silent background check on app open,
 * AboutActivity's manual "Cek Update" button).
 */
public class UpdateFlow {

    private final Activity activity;
    private BroadcastReceiver downloadReceiver;
    private long downloadId = -1;

    public UpdateFlow(Activity activity) {
        this.activity = activity;
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
            Toast.makeText(activity, R.string.msg_all_update_grant_install, Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }

        DownloadManager downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl));
        request.setTitle(activity.getString(R.string.app_name));
        request.setDescription(activity.getString(R.string.ttl_all_update_available));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(activity, null, "update.apk");
        request.setMimeType("application/vnd.android.package-archive");

        downloadId = downloadManager.enqueue(request);

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) installDownloadedApk();
            }
        };
        ContextCompat.registerReceiver(activity, downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void installDownloadedApk() {
        File apkFile = new File(activity.getExternalFilesDir(null), "update.apk");
        if (!apkFile.exists()) return;
        Uri apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(installIntent);
    }

    public void onDestroy() {
        if (downloadReceiver != null) {
            try { activity.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
            downloadReceiver = null;
        }
    }
}
