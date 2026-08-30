package com.alidev.dfrtools.dfr;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.alidev.dfrtools.R;
import com.alidev.dfrtools.utils.LocaleHelper;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class SettingsActivity extends BaseActivity {

    private EditText etMmsPort, etIntranetIp, etPingSingle, etPingBulk, etThreadPool, etDfrPoints, etMonInterval;
    private Spinner spLanguage;
    private com.google.android.material.button.MaterialButton btnClearCache;
    private String currentLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        loadSettings();

        if (getIntent().getBooleanExtra("open_import", false)) pickConfigFile();
    }

    private void initViews() {
        etMmsPort = findViewById(R.id.etMmsPort);
        etIntranetIp = findViewById(R.id.etIntranetIp);
        etPingSingle = findViewById(R.id.etPingSingle);
        etPingBulk = findViewById(R.id.etPingBulk);
        etThreadPool = findViewById(R.id.etThreadPool);
        etDfrPoints = findViewById(R.id.etDfrPoints);
        etMonInterval = findViewById(R.id.etMonInterval);
        spLanguage = findViewById(R.id.spLanguage);
        btnClearCache = findViewById(R.id.btnClearCache);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        btnClearCache.setOnClickListener(v -> clearCache());
        findViewById(R.id.btnExportConfig).setOnClickListener(v -> exportConfig());
        findViewById(R.id.btnImportConfig).setOnClickListener(v -> pickConfigFile());

        setupLanguageSpinner();
        updateCacheButtonLabel();
    }

    private void updateCacheButtonLabel() {
        safeExecute(() -> {
            long size = getDirSize(getCacheDir()) + getDirSize(getExternalCacheDir());
            String sizeStr = formatSize(size);
            runOnUiThread(() -> {
                btnClearCache.setText(getString(R.string.lbl_set_clear_cache) + " (" + sizeStr + ")");
            });
        });
    }

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private void safeExecute(Runnable r) {
        if (!executor.isShutdown()) executor.execute(r);
    }

    private void setupLanguageSpinner() {
        String[] languages = {"English", "Indonesia"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, languages) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                return v;
            }
            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                return v;
            }
        };
        spLanguage.setAdapter(adapter);
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);

        etMmsPort.setText(String.valueOf(prefs.getInt("mms_port", getResources().getInteger(R.integer.config_mms_default_port))));
        etIntranetIp.setText(prefs.getString("intranet_ip", "172.20.89.1"));
        etPingSingle.setText(String.valueOf(prefs.getInt("ping_count_single", getResources().getInteger(R.integer.config_ping_packet_count_single))));
        etPingBulk.setText(String.valueOf(prefs.getInt("ping_count_bulk", getResources().getInteger(R.integer.config_ping_packet_count_bulk))));
        etThreadPool.setText(String.valueOf(prefs.getInt("thread_pool_size", getResources().getInteger(R.integer.config_ping_thread_pool_size))));
        etDfrPoints.setText(String.valueOf(prefs.getInt("dfr_target_points", getResources().getInteger(R.integer.config_dfr_target_point_count))));
        etMonInterval.setText(String.valueOf(prefs.getInt("mon_update_interval_seconds", getResources().getInteger(R.integer.config_mon_default_interval_seconds))));

        currentLang = LocaleHelper.getLanguage(this);
        if (currentLang.equals("in")) {
            spLanguage.setSelection(1);
        } else {
            spLanguage.setSelection(0);
        }
    }

    private void saveSettings() {
        try {
            int mmsPort = Integer.parseInt(etMmsPort.getText().toString().trim());
            String intranetIp = etIntranetIp.getText().toString().trim();
            int pingSingle = Integer.parseInt(etPingSingle.getText().toString().trim());
            int pingBulk = Integer.parseInt(etPingBulk.getText().toString().trim());
            int threadPool = Integer.parseInt(etThreadPool.getText().toString().trim());
            int dfrPoints = Integer.parseInt(etDfrPoints.getText().toString().trim());
            int monInterval = Integer.parseInt(etMonInterval.getText().toString().trim());

            if (threadPool > 5 || threadPool < 1) {
                Toast.makeText(this, R.string.msg_set_invalid_range, Toast.LENGTH_SHORT).show();
                return;
            }

            String newLang = spLanguage.getSelectedItemPosition() == 1 ? "in" : "en";
            boolean langChanged = !newLang.equals(currentLang);

            getSharedPreferences("app_settings", MODE_PRIVATE).edit()
                    .putInt("mms_port", mmsPort)
                    .putString("intranet_ip", intranetIp)
                    .putInt("ping_count_single", pingSingle)
                    .putInt("ping_count_bulk", pingBulk)
                    .putInt("thread_pool_size", threadPool)
                    .putInt("dfr_target_points", dfrPoints)
                    .putInt("mon_update_interval_seconds", monInterval)
                    .apply();

            if (langChanged) {
                LocaleHelper.setLocale(this, newLang);
            }

            Toast.makeText(this, R.string.msg_set_saved, Toast.LENGTH_SHORT).show();
            
            if (langChanged) {
                // Restart app to apply language change to all activities in backstack
                Intent intent = new Intent(this, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            } else {
                finish();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.msg_set_input_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearCache() {
        safeExecute(() -> {
            long sizeBefore = getDirSize(getCacheDir()) + getDirSize(getExternalCacheDir());
            boolean successInternal = deleteDir(getCacheDir());
            boolean successExternal = deleteDir(getExternalCacheDir());
            
            runOnUiThread(() -> {
                if (successInternal || successExternal) {
                    Toast.makeText(this, String.format(getString(R.string.msg_set_cache_cleared), formatSize(sizeBefore)), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.msg_set_cache_fail, Toast.LENGTH_SHORT).show();
                }
                updateCacheButtonLabel();
            });
        });
    }

    private static final int REQUEST_IMPORT_CONFIG = 1001;
    private static final int REQUEST_SAVE_CONFIG = 1002;
    private File pendingExportSourceFile;

    private void exportConfig() {
        try {
            JSONObject root = BackupManager.exportConfig(this);

            File exportDir = new File(getExternalFilesDir(null), "Exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
            File file = new File(exportDir, "comtradedownloader_backup_" + timestamp + ".json");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }

            showConfigExportSuccessDialog(file);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_backup_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showConfigExportSuccessDialog(File file) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_export_success, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        ((TextView) dialogView.findViewById(R.id.tvExportMessage)).setText(R.string.msg_backup_export_ok);

        dialogView.findViewById(R.id.btnOpenFolder).setOnClickListener(v -> {
            startActivity(new Intent(this, InternalFileManagerActivity.class));
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnShare).setOnClickListener(v -> {
            shareConfigFile(file);
            dialog.dismiss();
        });

        View btnSaveAs = dialogView.findViewById(R.id.btnSaveAs);
        btnSaveAs.setVisibility(View.VISIBLE);
        btnSaveAs.setOnClickListener(v -> {
            dialog.dismiss();
            launchSaveConfigPicker(file);
        });

        dialog.show();
    }

    private void launchSaveConfigPicker(File file) {
        pendingExportSourceFile = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        try {
            startActivityForResult(intent, REQUEST_SAVE_CONFIG);
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void writeExportToUri(Uri destUri) {
        if (pendingExportSourceFile == null) return;
        try (InputStream in = new java.io.FileInputStream(pendingExportSourceFile);
             java.io.OutputStream out = getContentResolver().openOutputStream(destUri)) {
            if (out == null) throw new Exception("openOutputStream returned null");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            Toast.makeText(this, R.string.msg_backup_save_as_ok, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_backup_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            pendingExportSourceFile = null;
        }
    }

    private void shareConfigFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_backup_share_chooser)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_all_share_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void pickConfigFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimetypes = {"application/json", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        startActivityForResult(intent, REQUEST_IMPORT_CONFIG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_CONFIG && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importConfigFromUri(data.getData());
        } else if (requestCode == REQUEST_SAVE_CONFIG && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            writeExportToUri(data.getData());
        }
    }

    private void importConfigFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject root = new JSONObject(sb.toString());
            BackupManager.ImportResult result = BackupManager.importConfig(this, root);
            Toast.makeText(this, getString(R.string.msg_backup_import_ok,
                    result.devicesAdded, result.nodesAdded, result.templatesAdded, result.definitionsAdded), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_backup_import_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) size += file.length();
                    else if (file.isDirectory()) size += getDirSize(file);
                }
            }
        } else if (dir != null && dir.isFile()) {
            size += dir.length();
        }
        return size;
    }

    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String aChildren : children) {
                    deleteDir(new File(dir, aChildren));
                }
            }
            return dir.delete();
        } else if (dir != null && dir.isFile()) {
            return dir.delete();
        }
        return false;
    }

    private String formatSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log(size) / Math.log(1024));
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
