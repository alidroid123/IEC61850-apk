package com.alidev.dfrtools.dfr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Read-only table of "d" (description) attributes fetched via MMS Explorer's "Get Definition"
 * for ONE device (picked beforehand in MmsExplorerActivity.showPickDefinitionDeviceDialog) -
 * see NodeDefinitionManager.
 */
public class NodeDefinitionListActivity extends BaseActivity {

    private String ip;
    private List<NodeDefinition> items;
    private RecyclerView.Adapter<DefVH> adapter;
    private View layoutEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_definitions);

        ip = getIntent().getStringExtra("ip");
        if (ip == null || ip.isEmpty()) {
            finish();
            return;
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnExportDefinitions).setOnClickListener(v -> exportToCsv());
        findViewById(R.id.btnDeleteDefinitionsTable).setOnClickListener(v -> confirmDeleteTable());

        layoutEmpty = findViewById(R.id.layoutDefinitionsEmpty);

        RecyclerView rv = findViewById(R.id.rvDefinitions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<DefVH>() {
            @NonNull @Override
            public DefVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new DefVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_node_definition_row, parent, false));
            }

            @Override public void onBindViewHolder(@NonNull DefVH holder, int position) {
                holder.bind(items.get(position));
            }

            @Override public int getItemCount() { return items.size(); }
        };
        rv.setAdapter(adapter);

        loadItems();
    }

    private void loadItems() {
        items = new NodeDefinitionManager(this).getForIp(ip);
        Collections.sort(items, (a, b) -> a.nodeAddress.compareToIgnoreCase(b.nodeAddress));

        String deviceName = items.isEmpty() ? ip : items.get(0).deviceName;
        ((TextView) findViewById(R.id.txtDefinitionsSubtitle)).setText(
                getString(R.string.lbl_mms_definitions_subtitle, deviceName, items.size()));

        layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void confirmDeleteTable() {
        String deviceName = items.isEmpty() ? ip : items.get(0).deviceName;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmTitle)).setText(R.string.ttl_mms_definitions_delete_confirm);
        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage)).setText(
                getString(R.string.msg_mms_definitions_delete_confirm, deviceName));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            new NodeDefinitionManager(this).removeForIp(ip);
            dialog.dismiss();
            Toast.makeText(this, R.string.msg_mms_definitions_deleted, Toast.LENGTH_SHORT).show();
            finish();
        });

        dialog.show();
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("node_definition", text));
        Toast.makeText(this, R.string.lbl_mms_def_copied, Toast.LENGTH_SHORT).show();
    }

    private void exportToCsv() {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.msg_mms_definitions_export_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File exportDir = new File(getExternalFilesDir(null), "Exports");
            if (!exportDir.exists()) exportDir.mkdirs();

            File file = new File(exportDir, "node_definitions.csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("NAMA,ALAMAT NODE,STATUS GENERAL,VALUE\n".getBytes(StandardCharsets.UTF_8));
            for (NodeDefinition d : items) {
                String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\"\n",
                        d.deviceName, d.nodeAddress, d.hasGeneralStatus ? d.generalStatusValue : "", d.value);
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
            fos.close();
            showExportSuccessDialog(file);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_mms_definitions_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showExportSuccessDialog(File file) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_export_success, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnOpenFolder).setOnClickListener(v -> {
            startActivity(new Intent(this, InternalFileManagerActivity.class));
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnShare).setOnClickListener(v -> {
            shareFile(file);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_mms_definitions_share_chooser)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_all_share_fail, Toast.LENGTH_SHORT).show();
        }
    }

    class DefVH extends RecyclerView.ViewHolder {
        final View layoutStatus, dotStatus;
        final TextView txtStatus, txtAddress, txtValue;

        DefVH(@NonNull View v) {
            super(v);
            layoutStatus = v.findViewById(R.id.layoutDefStatus);
            dotStatus = v.findViewById(R.id.dotDefStatus);
            txtStatus = v.findViewById(R.id.txtDefStatus);
            txtAddress = v.findViewById(R.id.txtDefAddress);
            txtValue = v.findViewById(R.id.txtDefValue);
        }

        void bind(NodeDefinition d) {
            if (d.hasGeneralStatus) {
                layoutStatus.setVisibility(View.VISIBLE);
                boolean on = d.generalStatusValue.equalsIgnoreCase("true");
                txtStatus.setText(on ? "TRUE" : "FALSE");
                int color = ContextCompat.getColor(NodeDefinitionListActivity.this,
                        on ? R.color.status_danger : R.color.status_safe);
                txtStatus.setTextColor(color);
                dotStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            } else {
                layoutStatus.setVisibility(View.GONE);
            }

            txtAddress.setText(d.nodeAddress);
            txtValue.setText(d.value);
            txtAddress.setOnClickListener(v -> copyToClipboard(d.nodeAddress));
            txtValue.setOnClickListener(v -> copyToClipboard(d.value));
        }
    }
}
