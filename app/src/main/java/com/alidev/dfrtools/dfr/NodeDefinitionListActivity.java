package com.alidev.dfrtools.dfr;

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
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Read-only table of "d" (description) attributes fetched via MMS Explorer's "Get Definition" - see NodeDefinitionManager. */
public class NodeDefinitionListActivity extends BaseActivity {

    private List<NodeDefinition> items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_definitions);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnExportDefinitions).setOnClickListener(v -> exportToCsv());

        items = new NodeDefinitionManager(this).getAll();
        Collections.sort(items, (a, b) -> {
            int byName = a.deviceName.compareToIgnoreCase(b.deviceName);
            return byName != 0 ? byName : a.nodeAddress.compareToIgnoreCase(b.nodeAddress);
        });

        View layoutEmpty = findViewById(R.id.layoutDefinitionsEmpty);
        layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);

        RecyclerView rv = findViewById(R.id.rvDefinitions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RecyclerView.Adapter<DefVH>() {
            @NonNull @Override
            public DefVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new DefVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_node_definition_row, parent, false));
            }

            @Override public void onBindViewHolder(@NonNull DefVH holder, int position) {
                holder.bind(items.get(position));
            }

            @Override public int getItemCount() { return items.size(); }
        });
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
            fos.write("NAMA,ALAMAT NODE,VALUE\n".getBytes(StandardCharsets.UTF_8));
            for (NodeDefinition d : items) {
                String line = String.format("\"%s\",\"%s\",\"%s\"\n", d.deviceName, d.nodeAddress, d.value);
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

    static class DefVH extends RecyclerView.ViewHolder {
        final TextView txtName, txtAddress, txtValue;

        DefVH(@NonNull View v) {
            super(v);
            txtName = v.findViewById(R.id.txtDefName);
            txtAddress = v.findViewById(R.id.txtDefAddress);
            txtValue = v.findViewById(R.id.txtDefValue);
        }

        void bind(NodeDefinition d) {
            txtName.setText(d.deviceName);
            txtAddress.setText(d.nodeAddress);
            txtValue.setText(d.value);
        }
    }
}
