package com.alidev.dfrtools.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/alidroid123/IEC61850-apk/releases/latest";
    private static final int TIMEOUT_MS = 8000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static class UpdateInfo {
        public final String versionName;
        public final String downloadUrl;
        /** GitHub release notes body (may be empty if the release was published without one). */
        public final String releaseNotes;

        UpdateInfo(String versionName, String downloadUrl, String releaseNotes) {
            this.versionName = versionName;
            this.downloadUrl = downloadUrl;
            this.releaseNotes = releaseNotes;
        }
    }

    public interface Callback {
        void onResult(UpdateInfo info); // null if no update available or check failed
    }

    public static void checkForUpdate(Context context, Callback callback) {
        String currentVersion = getCurrentVersionName(context);
        Context appContext = context.getApplicationContext();

        EXECUTOR.execute(() -> {
            UpdateInfo result = null;
            try {
                result = fetchLatestRelease(currentVersion);
            } catch (Exception e) {
                Log.w(TAG, "Update check failed: " + e.getMessage());
            }
            UpdateInfo finalResult = result;
            MAIN_HANDLER.post(() -> callback.onResult(finalResult));
        });
    }

    private static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private static UpdateInfo fetchLatestRelease(String currentVersion) throws Exception {
        URL url = new URL(LATEST_RELEASE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;

            String body = readStream(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            String tagName = json.optString("tag_name", "");
            String remoteVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (remoteVersion.isEmpty() || !isNewer(remoteVersion, currentVersion)) return null;

            JSONArray assets = json.optJSONArray("assets");
            String apkUrl = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            if (apkUrl == null) return null;

            String releaseNotes = json.optString("body", "").trim();
            return new UpdateInfo(remoteVersion, apkUrl, releaseNotes);
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** Numeric major.minor.patch comparison; returns true if `remote` is newer than `current`. */
    static boolean isNewer(String remote, String current) {
        int[] r = parseVersion(remote);
        int[] c = parseVersion(current);
        for (int i = 0; i < 3; i++) {
            if (r[i] != c[i]) return r[i] > c[i];
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        int[] parts = new int[]{0, 0, 0};
        if (version == null) return parts;
        String[] split = version.trim().split("\\.");
        for (int i = 0; i < parts.length && i < split.length; i++) {
            try {
                parts[i] = Integer.parseInt(split[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return parts;
    }
}
