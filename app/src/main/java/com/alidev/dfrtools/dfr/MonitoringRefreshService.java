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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a full "refresh every monitored node" pass on its own, as a foreground service, so a bulk
 * refresh survives the user leaving IEDMonitoringActivity (backing out, switching apps, screen
 * off) instead of dying with it. It never touches the Activity's state - it loads/saves nodes
 * straight through MonitoringManager and opens its own short-lived Iec61850DfrClient connections,
 * fully independent of the Activity's own `clients` map used for per-group/foreground operations.
 *
 * IEDMonitoringActivity attaches a ProgressListener (static - single in-process observer, this
 * app never needs more than one) while it's alive to mirror progress in its own UI; the service
 * runs identically with no listener attached, only surfacing progress via the notification.
 */
public class MonitoringRefreshService extends Service {

    private static final String CHANNEL_ID = "monitoring_refresh";
    private static final int NOTIF_ID = 5101;

    public interface ProgressListener {
        void onProgress(String groupTitle, int doneInGroup, int totalInGroup, int doneOverall, int totalOverall);
        void onComplete(int successCount, int failCount);
    }

    private static volatile ProgressListener listener;
    private static volatile boolean running = false;
    // Snapshot of the most recent progress line, so an Activity that (re)attaches mid-run - e.g.
    // the user left and came back - can show the current state immediately instead of a blank
    // row until the next tick arrives.
    private static volatile String lastProgressText = "";

    public static void setListener(ProgressListener l) { listener = l; }
    public static boolean isRunning() { return running; }
    public static String getLastProgressText() { return lastProgressText; }

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitoringRefreshService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY; // a refresh is already in flight - don't overlap it
        running = true;
        lastProgressText = "";
        startForeground(NOTIF_ID, buildNotification(getString(R.string.msg_mon_bulk_notif_starting), 0, 0, true));
        executor.execute(this::runRefresh);
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.lbl_mon_bulk_notif_channel), NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, int progress, int max, boolean ongoing) {
        Intent openIntent = new Intent(this, IEDMonitoringActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle(getString(R.string.ttl_mon_bulk_notif))
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (ongoing && max > 0) builder.setProgress(max, progress, false);
        return builder.build();
    }

    private void updateNotification(String text, int progress, int max) {
        notificationManager.notify(NOTIF_ID, buildNotification(text, progress, max, true));
    }

    private void updateNotificationFinal(String text) {
        notificationManager.notify(NOTIF_ID, buildNotification(text, 0, 0, false));
    }

    /** Sequential connect+read across every group, same order the list itself sorts groups in. */
    private void runRefresh() {
        MonitoringManager manager = new MonitoringManager(this);
        List<MonitoredNode> nodes = manager.getNodes();

        Map<String, List<MonitoredNode>> grouped = new HashMap<>();
        for (MonitoredNode n : nodes) {
            List<MonitoredNode> group = grouped.get(n.ipAddress);
            if (group == null) {
                group = new ArrayList<>();
                grouped.put(n.ipAddress, group);
            }
            group.add(n);
        }
        List<String> sortedIps = new ArrayList<>(grouped.keySet());
        Collections.sort(sortedIps, (a, b) -> {
            MonitoringManager.DeviceHeaderData da = MonitoringManager.getDeviceHeaderData(this, a);
            MonitoringManager.DeviceHeaderData db = MonitoringManager.getDeviceHeaderData(this, b);
            String ta = da != null ? da.title : a;
            String tb = db != null ? db.title : b;
            return ta.compareToIgnoreCase(tb);
        });

        int totalAll = nodes.size();
        int doneOverall = 0;
        int successCount = 0, failCount = 0;
        int timeout = getResources().getInteger(R.integer.config_mms_connect_timeout_ms);
        int port = com.alidev.dfrtools.utils.ConfigHelper.getMmsPort(this);

        for (String ip : sortedIps) {
            List<MonitoredNode> groupNodes = grouped.get(ip);
            MonitoringManager.DeviceHeaderData data = MonitoringManager.getDeviceHeaderData(this, ip);
            String groupTitle = data != null ? data.title : ip;
            int totalInGroup = groupNodes.size();

            Iec61850DfrClient client = new Iec61850DfrClient();
            client.connect(ip, port, timeout);

            int doneInGroup = 0;
            for (MonitoredNode node : groupNodes) {
                if (client.isConnected()) {
                    Iec61850DfrClient.FcReadResult result = client.readWithFcFallback(node.fullPath, node.cachedFc);
                    if (result != null) {
                        node.cachedFc = result.fc;
                        node.lastValue = node.processRawValue(result.value);
                        if ("float".equals(node.type)) {
                            try { node.pushHistory(Float.parseFloat(node.lastValue)); } catch (NumberFormatException ignored) {}
                        }
                        node.lastUpdateMillis = System.currentTimeMillis();
                        successCount++;
                    } else {
                        failCount++;
                    }
                } else {
                    failCount++;
                }
                doneInGroup++;
                doneOverall++;

                String progressText = getString(R.string.msg_mon_bulk_progress, groupTitle, doneInGroup, totalInGroup, doneOverall, totalAll);
                lastProgressText = progressText;
                updateNotification(progressText, doneOverall, totalAll);
                ProgressListener l = listener;
                if (l != null) {
                    final int fDoneInGroup = doneInGroup, fDoneOverall = doneOverall;
                    l.onProgress(groupTitle, fDoneInGroup, totalInGroup, fDoneOverall, totalAll);
                }
            }
            client.disconnect();
        }

        manager.saveNodes(nodes);

        int finalSuccess = successCount, finalFail = failCount;
        String resultText = finalFail == 0
                ? getString(R.string.msg_mon_bulk_result_all_ok, totalAll)
                : getString(R.string.msg_mon_bulk_result, finalSuccess, finalFail);
        updateNotificationFinal(resultText);

        ProgressListener l = listener;
        if (l != null) l.onComplete(finalSuccess, finalFail);

        running = false;
        stopForeground(STOP_FOREGROUND_DETACH); // leave the completion notification visible for the user to see
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // started-only; IEDMonitoringActivity observes via the static listener, not binding
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
