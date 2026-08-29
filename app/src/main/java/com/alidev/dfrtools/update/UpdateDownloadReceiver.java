package com.alidev.dfrtools.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Manifest-registered (not tied to any Activity's lifecycle) so the system installer still opens
 * automatically as soon as the update APK finishes downloading, even if the user has since left
 * the app or the screen is off - previously this only happened via a receiver registered on the
 * triggering Activity, which got torn down the moment that Activity was destroyed, leaving only
 * the user manually tapping the system download notification to trigger the install.
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long pendingId = UpdatePrefs.getPendingDownloadId(context);
        if (completedId == -1 || completedId != pendingId) return;

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(completedId);
        boolean successful = false;
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                successful = statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL;
            }
        }
        UpdatePrefs.clearPendingDownloadId(context);
        if (!successful) return;

        File apkFile = new File(context.getExternalFilesDir(null), "update.apk");
        if (!apkFile.exists()) return;

        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(installIntent);
    }
}
