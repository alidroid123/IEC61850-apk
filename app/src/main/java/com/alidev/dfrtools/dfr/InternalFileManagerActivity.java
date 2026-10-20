package com.alidev.dfrtools.dfr;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.alidev.dfrtools.R;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class InternalFileManagerActivity extends BaseActivity {

    private RecyclerView rvFolders;
    private FolderAdapter adapter;
    private final List<File> folders = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_internal_file_manager);

        rvFolders = findViewById(R.id.rvFolders);
        rvFolders.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        View openFolderAction = findViewById(R.id.headerAction);
        if (openFolderAction != null) {
            openFolderAction.setOnClickListener(v -> {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File baseDir = new File(downloadDir, "DFR");
                if (!baseDir.exists()) baseDir.mkdirs();
                if (openDfrFolder(baseDir)) return;
                // Nothing on this device can browse to the folder - a dialog stays up until the
                // user dismisses it (a Toast got clipped before the full path could be read).
                new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                        .setTitle(R.string.ttl_file_open_chooser)
                        .setMessage(getString(R.string.msg_file_open_folder_unsupported, baseDir.getAbsolutePath()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }

        loadFolders();
        checkPermissions();
        adapter = new FolderAdapter(folders);
        rvFolders.setAdapter(adapter);

        ((TextView) findViewById(R.id.tvFolderSummary)).setText(getString(R.string.lbl_file_folder_count, folders.size()));
    }

    /**
     * Opens the Downloads/DFR folder in whatever can browse it, trying the most specific option
     * first: the exact folder in the system Documents UI (Files app), then the system Downloads
     * screen, then a FileProvider content:// folder Uri. Returns false only when none resolve.
     */
    private boolean openDfrFolder(File baseDir) {
        // 1) The exact folder in the system Documents UI (the "Files" app on most devices).
        try {
            Uri docUri = android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Download/DFR");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(docUri, "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return true; }
        } catch (Exception ignored) { }
        // 2) The system Downloads screen (DFR records live under Download/).
        try {
            Intent i = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
            if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return true; }
        } catch (Exception ignored) { }
        // 3) A FileProvider content:// folder Uri handed to any file manager that accepts it.
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", baseDir);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(i, getString(R.string.ttl_file_open_chooser)));
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showPermissionDialog();
            }
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_file_permission)
                .setMessage(R.string.msg_file_permission_body)
                .setPositiveButton(R.string.btn_all_grant_permission, (d, w) -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.btn_all_later, null)
                .show();
    }

    private void loadFolders() {
        folders.clear();
        
        // 1. Scan public Downloads/DFR
        File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DFR");
        if (publicDir.exists() && publicDir.isDirectory()) {
            File[] files = publicDir.listFiles(File::isDirectory);
            if (files != null) folders.addAll(Arrays.asList(files));
        }
        
        // 2. Scan internal fallback
        File internalDir = new File(getExternalFilesDir(null), "DFR");
        if (internalDir.exists() && internalDir.isDirectory()) {
            File[] files = internalDir.listFiles(File::isDirectory);
            if (files != null) {
                for (File f : files) {
                    boolean duplicate = false;
                    for (File existing : folders) {
                        if (existing.getName().equals(f.getName())) { duplicate = true; break; }
                    }
                    if (!duplicate) folders.add(f);
                }
            }
        }
        
        folders.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    }

    private void shareAllInFolder(File folder) {
        File[] files = folder.listFiles(f -> !f.isDirectory());
        if (files == null || files.length == 0) return;

        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) {
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
        }

        Intent intent = new Intent();
        if (uris.size() == 1) {
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.ttl_file_share_folder, folder.getName())));
    }

    private void showDeleteConfirm(String title, String message, Runnable onConfirm) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView)dialogView.findViewById(R.id.tvConfirmTitle)).setText(title);
        ((TextView)dialogView.findViewById(R.id.tvConfirmMessage)).setText(message);

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            onConfirm.run();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void deleteFolder(File folder) {
        showDeleteConfirm(getString(R.string.ttl_file_delete_folder), getString(R.string.msg_file_delete_folder_body, folder.getName()), () -> {
            recursiveDelete(folder);
            loadFolders();
            adapter.notifyDataSetChanged();
        });
    }

    private void showRenameFolder(File folder) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(folder.getName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.msg_file_rename_hint);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new AlertDialog.Builder(this, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_file_rename_folder)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
                        Toast.makeText(this, R.string.msg_file_rename_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.equals(folder.getName())) return;
                    File target = new File(folder.getParentFile(), name);
                    if (target.exists()) {
                        Toast.makeText(this, R.string.msg_file_rename_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (folder.renameTo(target)) {
                        loadFolders();
                        adapter.notifyDataSetChanged();
                    } else {
                        Toast.makeText(this, R.string.msg_file_rename_fail, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void recursiveDelete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) recursiveDelete(child);
        }
        file.delete();
    }

    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {
        private final List<File> items;

        public FolderAdapter(List<File> items) { this.items = items; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_folder, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            File folder = items.get(position);
            holder.tvName.setText(folder.getName());
            holder.btnShare.setOnClickListener(v -> shareAllInFolder(folder));
            holder.btnDelete.setOnClickListener(v -> deleteFolder(folder));
            holder.btnRename.setOnClickListener(v -> showRenameFolder(folder));

            holder.containerFiles.removeAllViews();
            File[] files = folder.listFiles(f -> !f.isDirectory());
            holder.tvFileCount.setText(getString(R.string.lbl_file_count, files != null ? files.length : 0));
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) {
                    View fileView = LayoutInflater.from(holder.itemView.getContext()).inflate(R.layout.item_file_history, holder.containerFiles, false);
                    ((TextView)fileView.findViewById(R.id.tvFileName)).setText(f.getName());
                    ((TextView)fileView.findViewById(R.id.tvFileSize)).setText(String.format(Locale.getDefault(), "%.1f KB", f.length() / 1024.0));

                    boolean isCfg = f.getName().toLowerCase().endsWith(".cfg");
                    ((ImageView) fileView.findViewById(R.id.ivFileIcon))
                            .setImageResource(isCfg ? R.drawable.ic_dfr_chart : R.drawable.ic_file_open);

                    View btnOpen = fileView.findViewById(R.id.btnOpenFile);
                    btnOpen.setVisibility(isCfg ? View.VISIBLE : View.GONE);

                    btnOpen.setOnClickListener(v -> {
                        Intent intent = new Intent(InternalFileManagerActivity.this, DfrViewerActivity.class);
                        intent.setData(FileProvider.getUriForFile(InternalFileManagerActivity.this, getPackageName() + ".fileprovider", f));
                        
                        // Pass folder name for header
                        if (f.getParentFile() != null) {
                            intent.putExtra("folder_name", f.getParentFile().getName());
                        }

                        // Find matching .dat file
                        String baseName = f.getName().substring(0, f.getName().lastIndexOf('.'));
                        File datFile = new File(f.getParent(), baseName + ".dat");
                        if (datFile.exists()) {
                            intent.putExtra("paired_dat_uri", FileProvider.getUriForFile(InternalFileManagerActivity.this, getPackageName() + ".fileprovider", datFile).toString());
                        }

                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                    });

                    fileView.findViewById(R.id.btnDeleteFile).setOnClickListener(v -> {
                        showDeleteConfirm(getString(R.string.ttl_file_delete_file), getString(R.string.msg_file_delete_file_body, f.getName()), () -> {
                            f.delete();
                            onBindViewHolder(holder, position);
                        });
                    });
                    holder.containerFiles.addView(fileView);
                }
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvFileCount;
            LinearLayout containerFiles;
            View btnShare, btnDelete, btnRename;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvFolderName);
                tvFileCount = v.findViewById(R.id.tvFileCount);
                containerFiles = v.findViewById(R.id.containerFiles);
                btnShare = v.findViewById(R.id.btnShareAll);
                btnDelete = v.findViewById(R.id.btnDeleteFolder);
                btnRename = v.findViewById(R.id.btnRenameFolder);
            }
        }
    }
}
