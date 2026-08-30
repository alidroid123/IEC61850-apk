package com.alidev.dfrtools.update;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.alidev.dfrtools.R;

import java.io.File;

/**
 * Foreground service that owns the update-APK download start-to-finish, so the system installer
 * still opens automatically once the download completes even if the user leaves the app.
 *
 * A manifest (static) BroadcastReceiver can't do this: Android 8+ blocks implicit-broadcast
 * delivery to statically-declared receivers for apps targeting API 26+, and
 * DownloadManager.ACTION_DOWNLOAD_COMPLETE is not one of the exempted system broadcasts - only a
 * receiver registered at *runtime* still receives it, which is why this is a running service
 * (mirroring MonitoringRefreshService's existing foreground-service pattern) rather than a
 * receiver in AndroidManifest.xml. The runtime receiver also must be registered EXPORTED (see
 * below) since the broadcast comes from the system's DownloadManager process, not our own app.
 */
public class UpdateDownloadService extends Service {

    private static final String CHANNEL_ID = "update_download";
    private static final int NOTIF_ID = 5301;
    public static final String EXTRA_DOWNLOAD_URL = "download_url";

    private long downloadId = -1;
    private BroadcastReceiver downloadCompleteReceiver;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent != null ? intent.getStringExtra(EXTRA_DOWNLOAD_URL) : null;
        if (url == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification());

        // Clear out any leftover APK from a previous update attempt first: it keeps the extra
        // storage this feature uses capped at one APK at a time (never accumulating across
        // updates), and avoids handing the installer a stale/partial file left behind by an
        // earlier failed or abandoned download at the same destination path.
        apkFile().delete();

        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(getString(R.string.app_name));
        request.setDescription(getString(R.string.ttl_all_update_available));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, null, "update.apk");
        request.setMimeType("application/vnd.android.package-archive");
        downloadId = downloadManager.enqueue(request);

        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) installAndStop();
            }
        };
        // EXPORTED (not NOT_EXPORTED) because this broadcast is sent by the system's
        // DownloadManager provider - a different UID/process than our own app - and
        // NOT_EXPORTED blocks delivery from anything but our own package, silently dropping it.
        ContextCompat.registerReceiver(this, downloadCompleteReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);

        return START_NOT_STICKY;
    }

    private File apkFile() {
        return new File(getExternalFilesDir(null), "update.apk");
    }

    /**
     * ACTION_DOWNLOAD_COMPLETE fires for both a successful AND a failed download - it only means
     * the download reached a terminal state, not that the file is valid. Installing without this
     * status check was the cause of the "package appears to be invalid" installer error some
     * users hit: on a failed/incomplete download the broadcast still arrived and the code below
     * used to try installing the partial file anyway, while tapping the system's own "download
     * complete" notification later worked because by then the file was actually finished.
     */
    private void installAndStop() {
        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        boolean successful = false;
        try (Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                successful = status == DownloadManager.STATUS_SUCCESSFUL;
            }
        } catch (Exception ignored) {}

        File apkFile = apkFile();
        if (successful && apkFile.exists() && apkFile.length() > 0) {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(installIntent);
        } else {
            apkFile.delete();
            Toast.makeText(this, R.string.msg_update_download_failed, Toast.LENGTH_LONG).show();
        }
        stopSelf();
    }

    private Notification buildNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.lbl_update_download_channel), NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.ttl_all_update_available))
                .setContentText(getString(R.string.msg_all_update_downloading))
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (downloadCompleteReceiver != null) {
            try { unregisterReceiver(downloadCompleteReceiver); } catch (Exception ignored) {}
            downloadCompleteReceiver = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
