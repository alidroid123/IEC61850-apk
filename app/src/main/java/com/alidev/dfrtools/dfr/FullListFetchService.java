package com.alidev.dfrtools.dfr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Walks EVERY leaf value on a connected device (every LD -> LN -> DO -> DA, no filtering) for
 * MMS Explorer's "Full List" - runs as its own foreground service so the fetch survives the user
 * leaving MmsExplorerActivity, same pattern as MonitoringRefreshService. Opens its own short-lived
 * Iec61850DfrClient connection, fully independent of the Activity's own client.
 *
 * Results live only in this process's memory (static list) - Full List is a live, one-off dump
 * rather than durable data like Node Definitions, so nothing is written to SharedPreferences.
 */
public class FullListFetchService extends Service {

    private static final String CHANNEL_ID = "mms_full_list_fetch";
    private static final int NOTIF_ID = 5401;

    public interface ProgressListener {
        void onProgress(String currentPath, int count);
        void onComplete(List<FullListEntry> results);
    }

    private static volatile ProgressListener listener;
    private static volatile boolean running = false;
    private static volatile String lastPath = "";
    private static volatile String currentIp = null;
    private static final List<FullListEntry> results = Collections.synchronizedList(new ArrayList<>());

    public static void setListener(ProgressListener l) { listener = l; }
    public static boolean isRunning() { return running; }
    public static String getLastPath() { return lastPath; }
    public static String getCurrentIp() { return currentIp; }
    public static List<FullListEntry> getResults() { return new ArrayList<>(results); }

    public static void start(Context context, String ip) {
        Intent intent = new Intent(context, FullListFetchService.class);
        intent.putExtra("ip", ip);
        ContextCompat.startForegroundService(context, intent);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private NotificationManager notificationManager;
    private Iec61850DfrClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY; // a fetch is already in flight - don't overlap it
        String ip = intent != null ? intent.getStringExtra("ip") : null;
        if (ip == null || ip.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        currentIp = ip;
        lastPath = "";
        results.clear();
        startForeground(NOTIF_ID, buildNotification(getString(R.string.msg_mms_fulllist_starting), true));
        executor.execute(() -> runFullList(ip));
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.lbl_mms_fulllist_notif_channel), NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean ongoing) {
        Intent openIntent = new Intent(this, MmsExplorerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle(getString(R.string.ttl_mms_fulllist_notif))
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        notificationManager.notify(NOTIF_ID, buildNotification(text, true));
    }

    private void updateNotificationFinal(String text) {
        notificationManager.notify(NOTIF_ID, buildNotification(text, false));
    }

    private void runFullList(String ip) {
        client = new Iec61850DfrClient();
        int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
        int port = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);
        boolean ok = client.connect(ip, port, timeout);

        if (ok) {
            try {
                List<String> lds = client.getLogicalDevices();
                for (String ld : lds) {
                    List<String> lns = client.getLogicalDeviceDirectory(ld);
                    for (String ln : lns) {
                        String lnPath = ld + "/" + ln;
                        List<String> dos = client.getLogicalNodeDirectory(lnPath);
                        for (String doName : dos) {
                            collectFullListUnderDO(lnPath + "." + doName);
                        }
                    }
                }
            } catch (Exception ignored) {}
            client.disconnect();
        }

        String finalText = getString(R.string.msg_mms_fulllist_done, results.size());
        updateNotificationFinal(finalText);

        List<FullListEntry> finalResults = new ArrayList<>(results);
        ProgressListener l = listener;
        if (l != null) l.onComplete(finalResults);

        running = false;
        stopForeground(STOP_FOREGROUND_DETACH); // leave the completion notification visible
        stopSelf();
    }

    /** Runs on the background executor - recurses into path's sub-structure, reading every leaf. */
    private void collectFullListUnderDO(String path) {
        List<String> subItems;
        try {
            subItems = client.getDataDirectory(path);
        } catch (Exception e) {
            return;
        }

        if (subItems == null || subItems.isEmpty()) {
            Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(path, null);
            String value = result != null ? result.value : "";
            results.add(new FullListEntry(path, value));
            lastPath = path;
            updateNotification(getString(R.string.msg_mms_fulllist_progress, path));
            ProgressListener l = listener;
            if (l != null) l.onProgress(path, results.size());
            return;
        }

        for (String sub : subItems) {
            collectFullListUnderDO(path + "." + sub);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // started-only; MmsExplorerActivity observes via the static listener, not binding
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
