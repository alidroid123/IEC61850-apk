package com.alidev.dfrtools.dfr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alidev.dfrtools.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Line Database (Database Saluran): line length + positive/zero-sequence impedance per relay
 * bay, imported from the utility's conductor-data spreadsheet (CSV) or entered by hand. Also
 * opened by FL Double Ended in pick mode (EXTRA_PICK) to choose the line configuration; the
 * relays chosen there are passed in so the matching rows are listed first.
 */
public class LineDatabaseActivity extends BaseActivity {

    public static final String EXTRA_PICK = "pick_mode";
    public static final String EXTRA_RESULT_ID = "line_id";
    /** Relay A/B as {gi, bay, merk, type}, only used to rank rows in pick mode. */
    public static final String EXTRA_RELAY_A = "relay_a", EXTRA_RELAY_B = "relay_b";

    private static final int REQ_IMPORT_CSV = 501, REQ_SAVE_CSV = 502;
    private static final int MATCH_SCORE = 8;

    /** Editable fields of the add/edit dialog, in display order: {labelRes, isNumber}. */
    private static final int F_GI = 0, F_BAY = 1, F_LAWAN = 2, F_KV = 3, F_LENGTH = 4, F_R1 = 5, F_X1 = 6, F_R0 = 7,
            F_X0 = 8, F_B1 = 9, F_MERK = 10, F_TIPE = 11, F_KONDUKTOR = 12, F_CCC = 13, F_UPT = 14, F_ULTG = 15;
    private static final int[][] FIELDS = {
            {R.string.hint_line_gi, 0}, {R.string.hint_line_bay, 0}, {R.string.hint_line_lawan, 0},
            {R.string.hint_line_kv, 1}, {R.string.hint_fl_length, 1},
            {R.string.hint_fl_r1, 1}, {R.string.hint_fl_x1, 1}, {R.string.hint_fl_r0, 1}, {R.string.hint_fl_x0, 1},
            {R.string.hint_fl_b1, 1}, {R.string.hint_line_merk, 0}, {R.string.hint_line_tipe, 0},
            {R.string.hint_line_konduktor, 0}, {R.string.hint_line_ccc, 1}, {R.string.hint_line_upt, 0},
            {R.string.hint_line_ultg, 0},
    };

    private LineDataManager manager;
    private final List<LineData> all = new ArrayList<>();
    private final List<LineData> shown = new ArrayList<>();
    private final List<String> giNames = new ArrayList<>();
    private RecyclerView.Adapter<RowVH> adapter;
    private Spinner spGi;
    private EditText etSearch;
    private View layoutEmpty;
    private TextView tvSubtitle;
    private boolean pickMode;
    private DeviceListActivity.DeviceItem relayA, relayB;
    private File pendingExportFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_line_database);
        manager = new LineDataManager(this);

        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        relayA = relayFromExtra(getIntent().getStringArrayExtra(EXTRA_RELAY_A));
        relayB = relayFromExtra(getIntent().getStringArrayExtra(EXTRA_RELAY_B));

        tvSubtitle = findViewById(R.id.tvLineDbSubtitle);
        layoutEmpty = findViewById(R.id.layoutLineEmpty);
        spGi = findViewById(R.id.spLineGi);
        etSearch = findViewById(R.id.etLineSearch);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnLineAdd).setOnClickListener(v -> showEditDialog(null));
        findViewById(R.id.btnLineExport).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnLineImport).setOnClickListener(v -> pickCsv());
        findViewById(R.id.btnLineDeleteAll).setOnClickListener(v -> confirmDeleteAll());
        findViewById(R.id.btnLineLoadDefault).setOnClickListener(v -> {
            int[] counts = manager.loadDefaults();
            reload();
            if (counts != null) Toast.makeText(this, getString(R.string.msg_line_load_default_ok, counts[0]), Toast.LENGTH_SHORT).show();
        });

        if (pickMode) {
            ((TextView) findViewById(R.id.tvLineDbTitle)).setText(R.string.ttl_line_pick);
            TextView hint = findViewById(R.id.tvLinePickHint);
            hint.setVisibility(View.VISIBLE);
            hint.setText(relayA != null && relayB != null
                    ? getString(R.string.msg_line_pick_hint_pair, relayA.gi, relayB.gi)
                    : getString(R.string.msg_line_pick_hint));
        }

        RecyclerView rv = findViewById(R.id.rvLines);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RowVH>() {
            @NonNull
            @Override
            public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RowVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_line_data, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RowVH holder, int position) { holder.bind(shown.get(position)); }

            @Override
            public int getItemCount() { return shown.size(); }
        };
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        spGi.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { applyFilter(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        reload();
    }

    @Nullable
    private static DeviceListActivity.DeviceItem relayFromExtra(@Nullable String[] v) {
        if (v == null || v.length < 4) return null;
        DeviceListActivity.DeviceItem d = new DeviceListActivity.DeviceItem();
        d.gi = v[0]; d.bay = v[1]; d.merk = v[2]; d.type = v[3];
        return d;
    }

    private void reload() {
        all.clear();
        all.addAll(manager.getAll());
        TreeSet<String> gis = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (LineData d : all) if (!d.gi.isEmpty()) gis.add(d.gi);
        String prevGi = spGi.getSelectedItemPosition() > 0 && spGi.getSelectedItemPosition() <= giNames.size()
                ? giNames.get(spGi.getSelectedItemPosition() - 1) : null;
        giNames.clear();
        giNames.addAll(gis);
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.lbl_line_all_gi));
        items.addAll(giNames);
        spGi.setAdapter(styledAdapter(items));
        if (prevGi != null && giNames.contains(prevGi)) spGi.setSelection(giNames.indexOf(prevGi) + 1, false);
        findViewById(R.id.layoutLineExport).setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.layoutLineDeleteAll).setVisibility(all.isEmpty() || pickMode ? View.GONE : View.VISIBLE);
        applyFilter();
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toUpperCase(Locale.ROOT);
        int giPos = spGi.getSelectedItemPosition();
        String gi = giPos > 0 && giPos <= giNames.size() ? giNames.get(giPos - 1) : null;
        shown.clear();
        for (LineData d : all) {
            if (gi != null && !gi.equalsIgnoreCase(d.gi)) continue;
            if (!q.isEmpty()) {
                boolean hit = true;
                String text = d.searchText();
                for (String word : q.split("\\s+")) if (!text.contains(word)) { hit = false; break; }
                if (!hit) continue;
            }
            shown.add(d);
        }
        Collections.sort(shown, (x, y) -> {
            if (pickMode && (relayA != null || relayB != null)) {
                int c = Integer.compare(LineDataManager.score(y, relayA, relayB), LineDataManager.score(x, relayA, relayB));
                if (c != 0) return c;
            }
            int c = x.gi.compareToIgnoreCase(y.gi);
            return c != 0 ? c : x.bay.compareToIgnoreCase(y.bay);
        });
        tvSubtitle.setText(getString(R.string.lbl_line_count, shown.size(), all.size()));
        layoutEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        // Restoring the bundled data only makes sense for an empty table, not an empty search.
        findViewById(R.id.btnLineLoadDefault).setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class RowVH extends RecyclerView.ViewHolder {
        final TextView tvMatch, tvRoute, tvDetail, tvImpedance, tvConductor;

        RowVH(View v) {
            super(v);
            tvMatch = v.findViewById(R.id.tvLineMatch);
            tvRoute = v.findViewById(R.id.tvLineRoute);
            tvDetail = v.findViewById(R.id.tvLineDetail);
            tvImpedance = v.findViewById(R.id.tvLineImpedance);
            tvConductor = v.findViewById(R.id.tvLineConductor);
        }

        void bind(LineData d) {
            tvRoute.setText(d.routeLabel());
            tvDetail.setText(d.detailLabel());
            tvImpedance.setText(d.impedanceLabel());
            StringBuilder c = new StringBuilder(d.konduktor);
            if (!Double.isNaN(d.ccc)) c.append(c.length() > 0 ? " • " : "").append("CCC ").append(LineData.fmt(d.ccc, 0)).append(" A");
            if (!d.ultg.isEmpty()) c.append(c.length() > 0 ? " • " : "").append("ULTG ").append(d.ultg);
            tvConductor.setText(c.toString());
            tvConductor.setVisibility(c.length() == 0 ? View.GONE : View.VISIBLE);
            boolean match = pickMode && LineDataManager.score(d, relayA, relayB) >= MATCH_SCORE;
            tvMatch.setVisibility(match ? View.VISIBLE : View.GONE);
            itemView.setOnClickListener(v -> {
                if (pickMode) {
                    setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_RESULT_ID, d.id));
                    finish();
                } else {
                    showEditDialog(d);
                }
            });
            itemView.setOnLongClickListener(v -> {
                showEditDialog(d);
                return true;
            });
        }
    }

    // ------------------------------------------------------------------
    // Add / edit
    // ------------------------------------------------------------------

    private void showEditDialog(@Nullable LineData existing) {
        LineData d = existing != null ? existing : new LineData();
        View v = getLayoutInflater().inflate(R.layout.dialog_line_data_edit, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvLineEditTitle)).setText(existing != null ? R.string.ttl_line_edit : R.string.ttl_line_add);
        LinearLayout container = v.findViewById(R.id.containerLineFields);
        TextView tvComputed = v.findViewById(R.id.tvLineComputed);

        EditText[] inputs = new EditText[FIELDS.length];
        String[] values = {
                d.gi, d.bay, d.giLawan, n(d.kv), n(d.lengthKm), n(d.r1), n(d.x1), n(d.r0), n(d.x0), n(d.b1),
                d.merk, d.tipe, d.konduktor, n(d.ccc), d.upt, d.ultg
        };
        TextWatcher recompute = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { tvComputed.setText(computedText(readForm(inputs, new LineData()))); }
        };
        for (int k = 0; k < FIELDS.length; k++) {
            TextInputLayout til = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
            til.setHint(getString(FIELDS[k][0]));
            TextInputEditText et = new TextInputEditText(til.getContext());
            et.setSingleLine(true);
            et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            et.setInputType(FIELDS[k][1] == 1 ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            et.setText(values[k]);
            et.addTextChangedListener(recompute);
            til.addView(et);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            container.addView(til, lp);
            inputs[k] = et;
        }
        tvComputed.setText(computedText(readForm(inputs, new LineData())));

        View btnDelete = v.findViewById(R.id.btnLineEditDelete);
        btnDelete.setVisibility(existing != null ? View.VISIBLE : View.GONE);
        btnDelete.setOnClickListener(x -> {
            manager.delete(d.id);
            dialog.dismiss();
            reload();
        });
        v.findViewById(R.id.btnLineEditCancel).setOnClickListener(x -> dialog.dismiss());
        v.findViewById(R.id.btnLineEditSave).setOnClickListener(x -> {
            LineData edited = readForm(inputs, new LineData());
            if (edited.gi.isEmpty() || !edited.hasZ1()) {
                Toast.makeText(this, R.string.msg_line_required, Toast.LENGTH_LONG).show();
                return;
            }
            // Keep the spreadsheet-only columns this form doesn't show.
            edited.id = d.id;
            edited.no = d.no;
            edited.giUip2b = d.giUip2b;
            edited.grade = d.grade;
            edited.standarKonduktor = d.standarKonduktor;
            edited.completeDerived();
            manager.upsert(edited);
            dialog.dismiss();
            reload();
        });
        dialog.show();
    }

    /** Builds a row from the form; Z/angle are always recomputed from R/X here. */
    private LineData readForm(EditText[] in, LineData d) {
        d.gi = text(in[F_GI]);
        d.bay = text(in[F_BAY]);
        d.giLawan = text(in[F_LAWAN]);
        d.kv = num(in[F_KV]);
        d.lengthKm = num(in[F_LENGTH]);
        d.r1 = num(in[F_R1]);
        d.x1 = num(in[F_X1]);
        d.r0 = num(in[F_R0]);
        d.x0 = num(in[F_X0]);
        d.b1 = num(in[F_B1]);
        d.merk = text(in[F_MERK]);
        d.tipe = text(in[F_TIPE]);
        d.konduktor = text(in[F_KONDUKTOR]);
        d.ccc = num(in[F_CCC]);
        d.upt = text(in[F_UPT]);
        d.ultg = text(in[F_ULTG]);
        d.completeDerived();
        return d;
    }

    private String computedText(LineData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Z1 = ").append(LineData.fmt(d.z1, 4)).append(" Ω ∠ ").append(LineData.fmt(d.ang1, 2)).append("°\n");
        sb.append("Z0 = ").append(LineData.fmt(d.z0, 4)).append(" Ω ∠ ").append(LineData.fmt(d.ang0, 2)).append("°\n");
        sb.append("Z2 = Z1");
        if (d.hasZ1() && d.hasZ0()) {
            double k0 = Math.hypot(d.r0 - d.r1, d.x0 - d.x1) / (3 * Math.hypot(d.r1, d.x1));
            sb.append("   |k0| = ").append(LineData.fmt(k0, 3));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // CSV import / export, delete all
    // ------------------------------------------------------------------

    private void pickCsv() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/comma-separated-values", "text/csv", "text/plain"});
        try {
            startActivityForResult(intent, REQ_IMPORT_CSV);
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void importCsv(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("null stream");
            LineDataCsv.ParseResult r = LineDataCsv.parse(new InputStreamReader(is, StandardCharsets.UTF_8));
            if (r.rows.isEmpty()) {
                showMessage(getString(R.string.ttl_line_import), getString(R.string.msg_line_import_none, r.recognisedColumns));
                return;
            }
            int[] counts = manager.importRows(r.rows);
            reload();
            showMessage(getString(R.string.ttl_line_import), getString(R.string.msg_line_import_ok, counts[0], counts[1], r.skipped));
        } catch (Exception e) {
            showMessage(getString(R.string.ttl_line_import), getString(R.string.msg_line_import_fail, String.valueOf(e.getMessage())));
        }
    }

    private void exportCsv() {
        if (all.isEmpty()) return;
        try {
            File dir = new File(getExternalFilesDir(null), "Exports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "line_database.csv");
            List<LineData> rows = new ArrayList<>(all);
            Collections.sort(rows, (x, y) -> {
                int c = x.gi.compareToIgnoreCase(y.gi);
                return c != 0 ? c : x.bay.compareToIgnoreCase(y.bay);
            });
            try (FileOutputStream fos = new FileOutputStream(file)) {
                StringBuilder sb = new StringBuilder(LineDataCsv.EXPORT_HEADER).append('\n');
                for (int i = 0; i < rows.size(); i++) sb.append(LineDataCsv.toCsvLine(rows.get(i), i + 1)).append('\n');
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            showExportDialog(file);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void showExportDialog(File file) {
        View v = getLayoutInflater().inflate(R.layout.dialog_export_success, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvExportMessage)).setText(getString(R.string.msg_line_export_ok, all.size()));
        v.findViewById(R.id.btnOpenFolder).setOnClickListener(x -> {
            startActivity(new Intent(this, InternalFileManagerActivity.class));
            dialog.dismiss();
        });
        v.findViewById(R.id.btnShare).setOnClickListener(x -> {
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, getString(R.string.ttl_line_db)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.msg_all_share_fail, Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
        View saveAs = v.findViewById(R.id.btnSaveAs);
        saveAs.setVisibility(View.VISIBLE);
        saveAs.setOnClickListener(x -> {
            dialog.dismiss();
            pendingExportFile = file;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_TITLE, file.getName());
            try {
                startActivityForResult(intent, REQ_SAVE_CSV);
            } catch (Exception e) {
                Toast.makeText(this, R.string.msg_view_picker_fail, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    private void writeExportTo(Uri dest) {
        if (pendingExportFile == null) return;
        try (InputStream in = new FileInputStream(pendingExportFile); OutputStream out = getContentResolver().openOutputStream(dest)) {
            if (out == null) throw new Exception("null stream");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Toast.makeText(this, R.string.msg_backup_save_as_ok, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.msg_dev_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            pendingExportFile = null;
        }
    }

    private void confirmDeleteAll() {
        View v = getLayoutInflater().inflate(R.layout.dialog_fl_info, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvFlInfoTitle)).setText(R.string.ttl_line_delete_all);
        ((TextView) v.findViewById(R.id.tvFlInfoBody)).setText(getString(R.string.msg_line_delete_all, all.size()));
        TextView btn = v.findViewById(R.id.btnFlInfoClose);
        btn.setText(R.string.btn_all_delete);
        btn.setTextColor(ContextCompat.getColor(this, R.color.status_danger));
        btn.setOnClickListener(x -> {
            manager.deleteAll();
            dialog.dismiss();
            reload();
        });
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_IMPORT_CSV) importCsv(data.getData());
        else if (requestCode == REQ_SAVE_CSV) writeExportTo(data.getData());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void showMessage(String title, String body) {
        View v = getLayoutInflater().inflate(R.layout.dialog_fl_info, null);
        AlertDialog dialog = cardDialog(v);
        ((TextView) v.findViewById(R.id.tvFlInfoTitle)).setText(title);
        ((TextView) v.findViewById(R.id.tvFlInfoBody)).setText(body);
        v.findViewById(R.id.btnFlInfoClose).setOnClickListener(x -> dialog.dismiss());
        dialog.show();
    }

    private AlertDialog cardDialog(View v) {
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_Comtrade_Dialog).setView(v).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }
        return dialog;
    }

    private ArrayAdapter<String> styledAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                ((TextView) v).setTextSize(13);
                ((TextView) v).setSingleLine(true);
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                ((TextView) v).setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
                return v;
            }
        };
    }

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static double num(EditText et) {
        return LineDataCsv.num(text(et), ',');
    }

    private static String n(double v) {
        if (Double.isNaN(v)) return "";
        String s = String.format(Locale.US, "%.6f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
