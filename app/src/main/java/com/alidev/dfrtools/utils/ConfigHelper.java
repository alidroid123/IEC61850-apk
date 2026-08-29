package com.alidev.dfrtools.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.alidev.dfrtools.R;

public class ConfigHelper {

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }

    public static int getMmsPort(Context context) {
        return getPrefs(context).getInt("mms_port", context.getResources().getInteger(R.integer.config_mms_default_port));
    }

    public static String getIntranetIp(Context context) {
        return getPrefs(context).getString("intranet_ip", "172.20.89.1");
    }

    public static int getPingCountSingle(Context context) {
        return getPrefs(context).getInt("ping_count_single", context.getResources().getInteger(R.integer.config_ping_packet_count_single));
    }

    public static int getPingCountBulk(Context context) {
        return getPrefs(context).getInt("ping_count_bulk", context.getResources().getInteger(R.integer.config_ping_packet_count_bulk));
    }

    public static int getThreadPoolSize(Context context) {
        return getPrefs(context).getInt("thread_pool_size", context.getResources().getInteger(R.integer.config_ping_thread_pool_size));
    }

    public static int getDfrTargetPoints(Context context) {
        return getPrefs(context).getInt("dfr_target_points", context.getResources().getInteger(R.integer.config_dfr_target_point_count));
    }

    /** Monitoring auto-refresh interval, in seconds (renamed from the old ms-based key on unit change). */
    public static int getMonUpdateIntervalSeconds(Context context) {
        return getPrefs(context).getInt("mon_update_interval_seconds", context.getResources().getInteger(R.integer.config_mon_default_interval_seconds));
    }
}
