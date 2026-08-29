package com.alidev.dfrtools.dfr;

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

import com.alidev.dfrtools.R;

import java.util.ArrayList;
import java.util.List;

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
        findViewById(R.id.btnAddTemplateHeader).setOnClickListener(v -> showAddTemplateDialog());
        findViewById(R.id.btnDuplicateTemplateHeader).setOnClickListener(v -> showDuplicateTemplateDialog());
        findViewById(R.id.btnDeleteTemplate).setOnClickListener(v -> confirmDeleteTemplate());
        findViewById(R.id.btnAddRow).setOnClickListener(v -> addRow());
        findViewById(R.id.btnSaveTemplate).setOnClickListener(v -> saveTemplate());

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
