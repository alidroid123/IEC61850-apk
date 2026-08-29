package com.alidev.dfrtools.update;

import android.content.Context;
import android.content.SharedPreferences;

public class UpdatePrefs {

    private static final long REMIND_AFTER_MS = 3L * 24 * 60 * 60 * 1000; // 3 days
    private static final int REMIND_AFTER_OPENS = 5;

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
    }

    public static void recordOpen(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs.getLong("dismissed_at", 0) != 0) {
            prefs.edit().putInt("opens_since_dismiss", prefs.getInt("opens_since_dismiss", 0) + 1).apply();
        }
    }

    public static boolean shouldShowPrompt(Context context) {
        SharedPreferences prefs = getPrefs(context);
        long dismissedAt = prefs.getLong("dismissed_at", 0);
        if (dismissedAt == 0) return true;

        long elapsed = System.currentTimeMillis() - dismissedAt;
        int opens = prefs.getInt("opens_since_dismiss", 0);
        return elapsed >= REMIND_AFTER_MS || opens >= REMIND_AFTER_OPENS;
    }

    public static void onDismissed(Context context) {
        getPrefs(context).edit()
                .putLong("dismissed_at", System.currentTimeMillis())
                .putInt("opens_since_dismiss", 0)
                .apply();
    }
}
