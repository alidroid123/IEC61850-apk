package com.alidev.dfrtools.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;

/**
 * Deletes the downloaded update APK once this app has actually finished being reinstalled.
 * MY_PACKAGE_REPLACED is sent directly to this package (not a general implicit broadcast), so
 * unlike DownloadManager.ACTION_DOWNLOAD_COMPLETE it is exempt from the API 26+ restriction on
 * delivering implicit broadcasts to manifest-declared receivers and can stay static here.
 */
public class UpdateInstalledReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new File(context.getExternalFilesDir(null), "update.apk").delete();
    }
}
