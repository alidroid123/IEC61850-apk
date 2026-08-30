package com.alidev.dfrtools.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.dfr.HomeActivity;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives the push sent to the "app_updates" topic when a new release is published (see
 * publishReleaseToGit in app/build.gradle). Deliberately dumb: it only ever tells the user "an
 * update might be available, open the app" and taps through to HomeActivity - HomeActivity's own
 * background UpdateChecker (already the single source of truth for "is this actually newer" and
 * the dismiss/remind-later logic) takes it from there, so no version info needs to round-trip
 * through the push payload itself.
 */
public class AppFcmService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "app_update_push";
    private static final int NOTIF_ID = 5201;

    public static final String TOPIC_APP_UPDATES = "app_updates";

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);

        String title = getString(R.string.ttl_all_update_available);
        String body = getString(R.string.msg_push_update_available);
        boolean titleOverridden = false;

        // The release-publish send is data-only (see sendFcmTopicPush in app/build.gradle), so
        // prefer the data payload; fall back to a "notification" block for anyone sending a push
        // manually from the Firebase Console instead.
        if (message.getData().containsKey("title")) { title = message.getData().get("title"); titleOverridden = true; }
        if (message.getData().containsKey("body")) body = message.getData().get("body");
        if (message.getNotification() != null) {
            if (message.getNotification().getTitle() != null) { title = message.getNotification().getTitle(); titleOverridden = true; }
            if (message.getNotification().getBody() != null) body = message.getNotification().getBody();
        }

        // De-duped by version below (same id as the in-app checker uses), so a release the user
        // already saw via push and then re-confirms by opening the app doesn't show up twice.
        String version = message.getData().get("version");
        String notifId = version != null ? "update_" + version : "push_" + System.currentTimeMillis();

        // Matches the "vInstalled > vNew" title HomeActivity/AboutActivity use for the same
        // notification when found via the in-app checker instead of this push - only when the
        // sender didn't already set its own title (a manual Firebase Console push, say).
        if (version != null && !titleOverridden) {
            title = getString(R.string.msg_all_update_available_title, UpdateChecker.getCurrentVersionName(this), version);
        }

        AppNotifications.add(this, notifId, title, body);

        showNotification(title, body);
    }

    private void showNotification(String title, String body) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel(notificationManager);

        Intent openIntent = new Intent(this, HomeActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        notificationManager.notify(NOTIF_ID, notification);
    }

    private void createChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.lbl_push_update_channel), NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
