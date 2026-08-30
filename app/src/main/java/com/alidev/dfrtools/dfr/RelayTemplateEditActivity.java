package com.alidev.dfrtools.dfr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.alidev.dfrtools.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lets the user author/edit relay monitoring templates (see RelayTemplates) instead of them
 * being hardcoded: pick a template from the top spinner, edit its rows in the table (each row =
 * one RelayTemplates.Point), add/remove templates and rows. Row/field edits (including add/delete
 * row) only live in currentPoints until btnSaveTemplate is pressed, which is the only thing that
 * calls RelayTemplates.savePoints() - so switching templates or leaving without saving discards
 * an in-progress edit. Adding/deleting a whole template is a separate, immediately-persisted
 * action since it doesn't belong to any row edit session.
 */
public class RelayTemplateEditActivity extends BaseActivity {

    private static final List<String> TYPE_OPTIONS = java.util.Arrays.asList("string", "float", "boolean");

    private Spinner spTemplateSelector;
    private View layoutTemplateSelector;
    private View layoutTableArea;
    private View hsvTemplateTable;
    private TextView tvEmptyTemplates;
    private TextView tvEmptyRows;
    private LinearLayout llTemplateRows;

    private List<String> templateNames = new ArrayList<>();
    private String currentTemplateName;
    private List<RelayTemplates.Point> currentPoints = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_relay_template_edit);

        spTemplateSelector = findViewById(R.id.spTemplateSelector);
        layoutTemplateSelector = findViewById(R.id.layoutTemplateSelector);
        layoutTableArea = findViewById(R.id.layoutTableArea);
        hsvTemplateTable = findViewById(R.id.hsvTemplateTable);
        tvEmptyTemplates = findViewById(R.id.tvEmptyTemplates);
        tvEmptyRows = findViewById(R.id.tvEmptyRows);
        llTemplateRows = findViewById(R.id.llTemplateRows);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddTemplateHeader).setOnClickListener(v -> showAddTemplateChoiceDialog());
        findViewById(R.id.btnDuplicateTemplateHeader).setOnClickListener(v -> showDuplicateTemplateDialog());
        findViewById(R.id.btnDeleteTemplate).setOnClickListener(v -> confirmDeleteTemplate());
        findViewById(R.id.btnAddRow).setOnClickListener(v -> addRow());
        findViewById(R.id.btnSaveTemplate).setOnClickListener(v -> saveTemplate());
        findViewById(R.id.btnImportCsvHeader).setOnClickListener(v -> showImportCsvDialog());
        findViewById(R.id.btnExportCsvHeader).setOnClickListener(v -> exportCurrentTemplateToCsv());

        spTemplateSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < templateNames.size()) {
                    selectTemplate(templateNames.get(position));
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        reloadTemplateNames(null);
    }

    private ArrayAdapter<String> createFuturisticSpinnerAdapter(List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item_futuristic, items);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_futuristic);
        return adapter;
    }

    /** Reloads the template name list into the spinner, keeping/selecting preferredSelection if present. */
    private void reloadTemplateNames(@Nullable String preferredSelection) {
        templateNames = RelayTemplates.getTemplateNames(this);

        boolean hasTemplates = !templateNames.isEmpty();
        tvEmptyTemplates.setVisibility(hasTemplates ? View.GONE : View.VISIBLE);
        layoutTemplateSelector.setVisibility(hasTemplates ? View.VISIBLE : View.GONE);
        layoutTableArea.setVisibility(hasTemplates ? View.VISIBLE : View.GONE);

        if (!hasTemplates) {
            currentTemplateName = null;
            currentPoints = new ArrayList<>();
            return;
        }

        spTemplateSelector.setAdapter(createFuturisticSpinnerAdapter(templateNames));
        int selectIndex = 0;
        if (preferredSelection != null) {
            int idx = templateNames.indexOf(preferredSelection);
            if (idx >= 0) selectIndex = idx;
        }
        spTemplateSelector.setSelection(selectIndex);
        // setSelection doesn't always fire the listener when the index is unchanged from before.
        selectTemplate(templateNames.get(selectIndex));
    }

    private void selectTemplate(String name) {
        currentTemplateName = name;
        List<RelayTemplates.Point> points = RelayTemplates.get(this, name);
        currentPoints = points != null ? points : new ArrayList<>();
        renderRows();
    }

    private void saveTemplate() {
        if (currentTemplateName == null) return;
        RelayTemplates.savePoints(this, currentTemplateName, currentPoints);
        Toast.makeText(this, getString(R.string.msg_tmpl_saved, currentTemplateName), Toast.LENGTH_SHORT).show();
    }

    private void renderRows() {
        llTemplateRows.removeAllViews();

        boolean hasRows = !currentPoints.isEmpty();
        hsvTemplateTable.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        tvEmptyRows.setVisibility(hasRows ? View.GONE : View.VISIBLE);

        for (int i = 0; i < currentPoints.size(); i++) {
            llTemplateRows.addView(buildRowView(currentPoints.get(i)));
        }
    }

    private View buildRowView(RelayTemplates.Point point) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_relay_template_row, llTemplateRows, false);

        EditText etName = row.findViewById(R.id.etRowName);
        EditText etPath = row.findViewById(R.id.etRowPath);
        Spinner spType = row.findViewById(R.id.spRowType);
        EditText etUnit = row.findViewById(R.id.etRowUnit);
        EditText etMultiplier = row.findViewById(R.id.etRowMultiplier);

        etName.setText(point.customName);
        etPath.setText(point.path);
        etUnit.setText(point.unit);
        etMultiplier.setText(String.valueOf(point.multiplier));

        spType.setAdapter(createFuturisticSpinnerAdapter(TYPE_OPTIONS));
        int typeIndex = TYPE_OPTIONS.indexOf(point.type);
        spType.setSelection(typeIndex >= 0 ? typeIndex : 1); // default to "float" if unset/unrecognized

        etName.addTextChangedListener(new SimpleTextWatcher(s -> point.customName = s));
        etPath.addTextChangedListener(new SimpleTextWatcher(s -> point.path = s));
        etUnit.addTextChangedListener(new SimpleTextWatcher(s -> point.unit = s));
        etMultiplier.addTextChangedListener(new SimpleTextWatcher(s -> {
            try {
                point.multiplier = Float.parseFloat(s);
            } catch (NumberFormatException ignored) {
                // Leave the last valid multiplier in place while the user is mid-edit (e.g. "-" or "1.").
            }
        }));

        spType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                point.type = TYPE_OPTIONS.get(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        row.findViewById(R.id.btnRowDelete).setOnClickListener(v -> confirmDeleteRow(point));

        return row;
    }

    private void addRow() {
        currentPoints.add(new RelayTemplates.Point("", "", "float", "", 1f));
        renderRows();
    }

    private void showAddTemplateChoiceDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_add_template_choice, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        v.findViewById(R.id.btnChoiceBlank).setOnClickListener(view -> {
            dialog.dismiss();
            showAddTemplateDialog();
        });
        v.findViewById(R.id.btnChoiceFromMonitoring).setOnClickListener(view -> {
            dialog.dismiss();
            showPickMonitoringGroupDialog();
        });
        v.findViewById(R.id.btnCancelChoice).setOnClickListener(view -> dialog.dismiss());

        dialog.show();
    }

    private void showPickMonitoringGroupDialog() {
        List<MonitoredNode> allNodes = new MonitoringManager(this).getNodes();
        java.util.LinkedHashMap<String, List<MonitoredNode>> grouped = new java.util.LinkedHashMap<>();
        for (MonitoredNode n : allNodes) {
            List<MonitoredNode> list = grouped.get(n.ipAddress);
            if (list == null) { list = new ArrayList<>(); grouped.put(n.ipAddress, list); }
            list.add(n);
        }
        if (grouped.isEmpty()) {
            Toast.makeText(this, R.string.msg_tmpl_no_monitoring_groups, Toast.LENGTH_SHORT).show();
            return;
        }

        View v = getLayoutInflater().inflate(R.layout.dialog_pick_list, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        ((TextView) v.findViewById(R.id.tvPickListTitle)).setText(R.string.ttl_tmpl_pick_group);
        LinearLayout container = v.findViewById(R.id.llPickListItems);

        for (java.util.Map.Entry<String, List<MonitoredNode>> entry : grouped.entrySet()) {
            String ip = entry.getKey();
            List<MonitoredNode> groupNodes = entry.getValue();
            MonitoringManager.DeviceHeaderData header = MonitoringManager.getDeviceHeaderData(this, ip);
            String title = header != null ? (header.title + " (" + header.device + ")") : ip;
            String merkType = header != null && !header.merk.isEmpty() ? (header.merk + " " + header.type + " • ") : "";
            String subtitle = merkType + ip + " • " + groupNodes.size() + " point";

            View row = getLayoutInflater().inflate(R.layout.item_dialog_pick_row, container, false);
            ((TextView) row.findViewById(R.id.tvPickRowTitle)).setText(title);
            ((TextView) row.findViewById(R.id.tvPickRowSubtitle)).setText(subtitle);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                showAddTemplateFromGroupDialog(groupNodes);
            });
            container.addView(row);
        }

        v.findViewById(R.id.btnPickListCancel).setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    private void showAddTemplateFromGroupDialog(List<MonitoredNode> groupNodes) {
        View v = getLayoutInflater().inflate(R.layout.dialog_add_template, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        ((TextView) v.findViewById(R.id.tvAddTemplateDialogTitle)).setText(R.string.ttl_tmpl_add_from_monitoring_dialog);
        EditText etName = v.findViewById(R.id.etTemplateName);

        v.findViewById(R.id.btnCancelAddTemplate).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnConfirmAddTemplate).setOnClickListener(view -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError(getString(R.string.msg_tmpl_name_required));
                return;
            }
            boolean added = RelayTemplates.addTemplate(this, name);
            if (!added) {
                Toast.makeText(this, getString(R.string.msg_tmpl_name_exists, name), Toast.LENGTH_SHORT).show();
                return;
            }
            List<RelayTemplates.Point> points = buildPointsFromMonitoringGroup(groupNodes);
            RelayTemplates.savePoints(this, name, points);
            Toast.makeText(this, getString(R.string.msg_tmpl_added_from_monitoring, name, points.size()), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            reloadTemplateNames(name);
        });

        dialog.show();
    }

    /**
     * Converts one IED Monitoring group's live points into portable template Points. A template
     * Point's path must be LDInst/LN.DO.DA only (no IEDName - see RelayTemplates), but a
     * MonitoredNode.fullPath's domain segment is "IEDName+LDInst" concatenated with no separator
     * (standard IEC 61850 MMS domain-id), so IEDName can't be split off from a single path alone.
     * Since one physical device's IEDName is constant across all its logical devices while LDInst
     * varies, the longest common prefix across every distinct domain in this group IS the IEDName
     * - as long as the group spans 2+ logical devices. If it only spans one, there's no reliable
     * way to tell the two apart from the data alone, so the whole domain is kept as-is (the point
     * will still work if the template is re-applied to this same device, just not portably).
     */
    private List<RelayTemplates.Point> buildPointsFromMonitoringGroup(List<MonitoredNode> nodes) {
        java.util.LinkedHashSet<String> domains = new java.util.LinkedHashSet<>();
        for (MonitoredNode n : nodes) {
            int slash = n.fullPath.indexOf('/');
            if (slash > 0) domains.add(n.fullPath.substring(0, slash));
        }
        String iedNamePrefix = domains.size() > 1 ? longestCommonPrefix(domains) : "";

        List<RelayTemplates.Point> points = new ArrayList<>();
        for (MonitoredNode n : nodes) {
            int slash = n.fullPath.indexOf('/');
            if (slash <= 0) continue;
            String domain = n.fullPath.substring(0, slash);
            String suffix = n.fullPath.substring(slash); // includes leading '/'
            String ldInst = (!iedNamePrefix.isEmpty() && domain.length() > iedNamePrefix.length())
                    ? domain.substring(iedNamePrefix.length()) : domain;
            points.add(new RelayTemplates.Point(ldInst + suffix, n.customName, n.type, n.unit, n.multiplier));
        }
        return points;
    }

    private String longestCommonPrefix(java.util.Set<String> strings) {
        String prefix = null;
        for (String s : strings) {
            if (prefix == null) { prefix = s; continue; }
            int i = 0;
            while (i < prefix.length() && i < s.length() && prefix.charAt(i) == s.charAt(i)) i++;
            prefix = prefix.substring(0, i);
            if (prefix.isEmpty()) break;
        }
        return prefix != null ? prefix : "";
    }

    private void showAddTemplateDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_add_template, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        EditText etName = v.findViewById(R.id.etTemplateName);

        v.findViewById(R.id.btnCancelAddTemplate).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnConfirmAddTemplate).setOnClickListener(view -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError(getString(R.string.msg_tmpl_name_required));
                return;
            }
            boolean added = RelayTemplates.addTemplate(this, name);
            if (!added) {
                Toast.makeText(this, getString(R.string.msg_tmpl_name_exists, name), Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, getString(R.string.msg_tmpl_added, name), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            reloadTemplateNames(name);
        });

        dialog.show();
    }

    private void showDuplicateTemplateDialog() {
        if (currentTemplateName == null) {
            Toast.makeText(this, R.string.msg_tmpl_no_template_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        String sourceName = currentTemplateName;
        List<RelayTemplates.Point> sourcePoints = currentPoints;

        View v = getLayoutInflater().inflate(R.layout.dialog_add_template, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(v)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        ((TextView) v.findViewById(R.id.tvAddTemplateDialogTitle)).setText(R.string.ttl_tmpl_duplicate_dialog);
        EditText etName = v.findViewById(R.id.etTemplateName);
        etName.setText(sourceName + " (copy)");
        etName.selectAll();

        v.findViewById(R.id.btnCancelAddTemplate).setOnClickListener(view -> dialog.dismiss());
        v.findViewById(R.id.btnConfirmAddTemplate).setOnClickListener(view -> {
            String newName = etName.getText().toString().trim();
            if (newName.isEmpty()) {
                etName.setError(getString(R.string.msg_tmpl_name_required));
                return;
            }
            boolean added = RelayTemplates.addTemplate(this, newName);
            if (!added) {
                Toast.makeText(this, getString(R.string.msg_tmpl_name_exists, newName), Toast.LENGTH_SHORT).show();
                return;
            }
            RelayTemplates.savePoints(this, newName, deepCopyPoints(sourcePoints));
            Toast.makeText(this, getString(R.string.msg_tmpl_duplicated, sourceName, newName), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            reloadTemplateNames(newName);
        });

        dialog.show();
    }

    private List<RelayTemplates.Point> deepCopyPoints(List<RelayTemplates.Point> source) {
        List<RelayTemplates.Point> copy = new ArrayList<>();
        for (RelayTemplates.Point p : source) {
            copy.add(new RelayTemplates.Point(p.path, p.customName, p.type, p.unit, p.multiplier));
        }
        return copy;
    }

    private void confirmDeleteTemplate() {
        if (currentTemplateName == null) return;
        String name = currentTemplateName;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmTitle)).setText(R.string.ttl_tmpl_delete_template_confirm);
        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage)).setText(getString(R.string.msg_tmpl_delete_template_confirm, name));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            RelayTemplates.removeTemplate(this, name);
            Toast.makeText(this, getString(R.string.msg_tmpl_template_deleted, name), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            reloadTemplateNames(null);
        });

        dialog.show();
    }

    private void confirmDeleteRow(RelayTemplates.Point point) {
        String label = !point.customName.isEmpty() ? point.customName
                : !point.path.isEmpty() ? point.path : "";

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ((TextView) dialogView.findViewById(R.id.tvConfirmTitle)).setText(R.string.ttl_tmpl_delete_row_confirm);
        ((TextView) dialogView.findViewById(R.id.tvConfirmMessage)).setText(getString(R.string.msg_tmpl_delete_row_confirm, label));

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            currentPoints.remove(point);
            dialog.dismiss();
            renderRows();
        });

        dialog.show();
    }

    // --- CSV import/export ------------------------------------------------------------------
    // Column order: Name, Address (LD/LN.DO.DA), Type (string/float/boolean), Unit, Multiplier -
    // i.e. the same five fields as a RelayTemplates.Point / the on-screen table's columns, so a
    // file exported here re-imports byte-for-byte into any template (this one or another).

    private static final int REQUEST_TEMPLATE_CSV = 3001;

    private void showImportCsvDialog() {
        if (currentTemplateName == null) {
            Toast.makeText(this, R.string.msg_tmpl_no_template_for_import, Toast.LENGTH_SHORT).show();
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_import_template_csv, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialogView.findViewById(R.id.btnDownloadTemplate).setOnClickListener(v -> downloadTemplateCsvExample());
        dialogView.findViewById(R.id.btnContinueImport).setOnClickListener(v -> {
            dialog.dismiss();
            pickTemplateCsv();
        });
        dialogView.findViewById(R.id.btnCancelImport).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void pickTemplateCsv() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("text/comma-separated-values");
        String[] mimetypes = {"text/comma-separated-values", "text/csv", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        startActivityForResult(intent, REQUEST_TEMPLATE_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TEMPLATE_CSV && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importTemplateCsv(data.getData());
        }
    }

    private void importTemplateCsv(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            int added = 0;
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (firstLine) {
                    firstLine = false;
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("nama") || lower.contains("name") || lower.contains("alamat") || lower.contains("address")) continue;
                }
                String[] p = line.split(",", -1);
                if (p.length < 2) continue;

                String customName = p[0].replace("\"", "").trim();
                String path = p[1].replace("\"", "").trim();
                if (customName.isEmpty() || path.isEmpty()) continue;

                String type = p.length > 2 ? p[2].replace("\"", "").trim().toLowerCase(Locale.ROOT) : "float";
                if (!TYPE_OPTIONS.contains(type)) type = "float";
                String unit = p.length > 3 ? p[3].replace("\"", "").trim() : "";
                float multiplier = 1f;
                if (p.length > 4) {
                    try {
                        multiplier = Float.parseFloat(p[4].replace("\"", "").trim());
                    } catch (NumberFormatException ignored) {}
                }

                boolean duplicate = false;
                for (RelayTemplates.Point existing : currentPoints) {
                    if (existing.path.equals(path)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                currentPoints.add(new RelayTemplates.Point(path, customName, type, unit, multiplier));
                added++;
            }
            if (added > 0) renderRows();
            Toast.makeText(this, getString(R.string.msg_tmpl_import_ok, added), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_import_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void downloadTemplateCsvExample() {
        try {
            File file = new File(getExternalFilesDir(null), "template_relay_contoh.csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("Nama,Alamat,Tipe,Satuan,Pengali\n".getBytes(StandardCharsets.UTF_8));
            fos.write("Arus IR,Measurements/MMXU1.A.phsA.cVal.mag.f,float,A,1.0\n".getBytes(StandardCharsets.UTF_8));
            fos.write("CB Open,Control/XCBR1.Pos.stVal,boolean,,1.0\n".getBytes(StandardCharsets.UTF_8));
            fos.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_dev_share_template)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_import_fail, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /** Exports the in-memory (possibly unsaved) rows currently shown in the table, not whatever
     *  is on disk - what you see on screen is what gets shared, tap Save first if that matters. */
    private void exportCurrentTemplateToCsv() {
        if (currentTemplateName == null || currentPoints.isEmpty()) {
            Toast.makeText(this, R.string.msg_tmpl_nothing_to_export, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String safeName = currentTemplateName.replaceAll("[^A-Za-z0-9_-]", "_");
            File file = new File(getExternalFilesDir(null), "template_" + safeName + ".csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write("Nama,Alamat,Tipe,Satuan,Pengali\n".getBytes(StandardCharsets.UTF_8));
            for (RelayTemplates.Point point : currentPoints) {
                String row = String.format(Locale.US, "%s,%s,%s,%s,%s\n",
                        csvEscape(point.customName), csvEscape(point.path), csvEscape(point.type),
                        csvEscape(point.unit), String.valueOf(point.multiplier));
                fos.write(row.getBytes(StandardCharsets.UTF_8));
            }
            fos.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.ttl_tmpl_share_csv)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_import_fail, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /** Commas in a field would otherwise shift every later column when this file is read back in
     *  (both this app's own importTemplateCsv above and a spreadsheet) - wrapped in quotes instead. */
    private static String csvEscape(String value) {
        if (value == null) return "";
        return value.contains(",") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    /** TextWatcher that only cares about the final text, to keep row bindings above terse. */
    private interface OnTextChanged { void onChanged(String text); }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final OnTextChanged callback;
        SimpleTextWatcher(OnTextChanged callback) { this.callback = callback; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(android.text.Editable s) { callback.onChanged(s.toString()); }
    }
}
