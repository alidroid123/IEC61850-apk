package com.alidev.dfrtools.dfr;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.alidev.dfrtools.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expandable, searchable usage guide covering every screen in the app. */
public class HelpActivity extends BaseActivity {

    /** One icon-labeled button referenced by a section's steps, shown as a chip. */
    private static class HelpChip {
        final int iconRes, labelRes;
        HelpChip(int iconRes, int labelRes) {
            this.iconRes = iconRes;
            this.labelRes = labelRes;
        }
    }

    private static class HelpSection {
        final int iconRes, titleRes, summaryRes, funcRes, stepsRes;
        final HelpChip[] chips;
        HelpSection(int iconRes, int titleRes, int summaryRes, int funcRes, int stepsRes, HelpChip... chips) {
            this.iconRes = iconRes;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.funcRes = funcRes;
            this.stepsRes = stepsRes;
            this.chips = chips;
        }
    }

    private static final Pattern STEP_SPLIT_PATTERN = Pattern.compile("(?:^|\\n)\\d+\\.\\s*");

    private static final HelpSection[] SECTIONS = {
            new HelpSection(R.drawable.ic_menu, R.string.help_ttl_home, R.string.help_sum_home,
                    R.string.help_func_home, R.string.help_steps_home,
                    new HelpChip(R.drawable.ic_menu, R.string.lbl_help_chip_menu)),
            new HelpSection(R.drawable.ic_download, R.string.help_ttl_download, R.string.help_sum_download,
                    R.string.help_func_download, R.string.help_steps_download,
                    new HelpChip(R.drawable.ic_list, R.string.btn_dl_list),
                    new HelpChip(R.drawable.ic_download, R.string.btn_dl_start_download)),
            new HelpSection(R.drawable.ic_history, R.string.help_ttl_history, R.string.help_sum_history,
                    R.string.help_func_history, R.string.help_steps_history,
                    new HelpChip(R.drawable.ic_delete, R.string.btn_all_delete),
                    new HelpChip(R.drawable.ic_share, R.string.btn_all_share)),
            new HelpSection(R.drawable.ic_list, R.string.help_ttl_devices, R.string.help_sum_devices,
                    R.string.help_func_devices, R.string.help_steps_devices,
                    new HelpChip(R.drawable.ic_bulk_ping, R.string.btn_dev_ping),
                    new HelpChip(R.drawable.ic_export, R.string.btn_dev_export),
                    new HelpChip(R.drawable.ic_import, R.string.btn_dev_import)),
            new HelpSection(R.drawable.ic_mms, R.string.help_ttl_explorer, R.string.help_sum_explorer,
                    R.string.help_func_explorer, R.string.help_steps_explorer,
                    new HelpChip(R.drawable.ic_add, R.string.lbl_help_chip_add_point),
                    new HelpChip(R.drawable.ic_sync, R.string.btn_mms_refresh)),
            new HelpSection(R.drawable.ic_dfr_chart, R.string.help_ttl_viewer, R.string.help_sum_viewer,
                    R.string.help_func_viewer, R.string.help_steps_viewer,
                    new HelpChip(R.drawable.ic_zoom, R.string.lbl_view_icon_zoom),
                    new HelpChip(R.drawable.ic_cursor, R.string.lbl_view_icon_cursor),
                    new HelpChip(R.drawable.ic_settings, R.string.lbl_view_icon_settings)),
            new HelpSection(R.drawable.ic_ied_monitor, R.string.help_ttl_monitoring, R.string.help_sum_monitoring,
                    R.string.help_func_monitoring, R.string.help_steps_monitoring,
                    new HelpChip(R.drawable.ic_template, R.string.btn_mon_add_template),
                    new HelpChip(R.drawable.ic_edit_small, R.string.lbl_help_chip_edit),
                    new HelpChip(R.drawable.ic_sync, R.string.btn_mon_refresh_confirm)),
            new HelpSection(R.drawable.ic_template, R.string.help_ttl_template, R.string.help_sum_template,
                    R.string.help_func_template, R.string.help_steps_template,
                    new HelpChip(R.drawable.ic_add, R.string.btn_tmpl_add_template),
                    new HelpChip(R.drawable.ic_copy, R.string.btn_tmpl_duplicate_template),
                    new HelpChip(R.drawable.ic_delete, R.string.btn_all_delete),
                    new HelpChip(R.drawable.ic_save, R.string.btn_all_save_small)),
            new HelpSection(R.drawable.ic_settings, R.string.help_ttl_settings, R.string.help_sum_settings,
                    R.string.help_func_settings, R.string.help_steps_settings,
                    new HelpChip(R.drawable.ic_export, R.string.btn_dev_export),
                    new HelpChip(R.drawable.ic_import, R.string.btn_dev_import)),
            new HelpSection(R.drawable.ic_info, R.string.help_ttl_about, R.string.help_sum_about,
                    R.string.help_func_about, R.string.help_steps_about),
            new HelpSection(R.drawable.ic_check, R.string.help_ttl_tips, R.string.help_sum_tips,
                    R.string.help_func_tips, R.string.help_steps_tips,
                    new HelpChip(R.drawable.ic_vpn, R.string.lbl_help_chip_vpn),
                    new HelpChip(R.drawable.ic_theme, R.string.lbl_help_chip_theme)),
    };

    private final List<View> sectionViews = new ArrayList<>();
    private TextView tvNoResults;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tvNoResults = findViewById(R.id.tvHelpNoResults);

        LinearLayout container = findViewById(R.id.containerHelpSections);
        LayoutInflater inflater = getLayoutInflater();
        for (HelpSection section : SECTIONS) {
            View view = buildSectionView(inflater, container, section);
            sectionViews.add(view);
            container.addView(view);
        }

        EditText etSearch = findViewById(R.id.etSearchHelp);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterSections(s.toString()); }
        });
    }

    /**
     * Search-as-you-type across every section's title/summary/function/steps text. A non-empty
     * query auto-expands every match (so the relevant text is visible immediately without an
     * extra tap) and collapses everything again once the query is cleared.
     */
    private void filterSections(String query) {
        String q = query.trim().toLowerCase(Locale.getDefault());
        boolean anyMatch = false;

        for (int i = 0; i < SECTIONS.length; i++) {
            HelpSection section = SECTIONS[i];
            View view = sectionViews.get(i);
            boolean matches = q.isEmpty() || sectionMatches(section, q);
            view.setVisibility(matches ? View.VISIBLE : View.GONE);
            if (matches) anyMatch = true;

            View body = view.findViewById(R.id.bodyContainer);
            ImageView chevron = view.findViewById(R.id.imgChevron);
            boolean expand = matches && !q.isEmpty();
            body.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.setRotation(expand ? 90f : 0f);
        }

        tvNoResults.setVisibility(anyMatch ? View.GONE : View.VISIBLE);
    }

    private boolean sectionMatches(HelpSection section, String lowercaseQuery) {
        return getString(section.titleRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                || getString(section.summaryRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                || getString(section.funcRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                || getString(section.stepsRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery);
    }

    private View buildSectionView(LayoutInflater inflater, ViewGroup parent, HelpSection section) {
        View item = inflater.inflate(R.layout.item_help_section, parent, false);

        ((ImageView) item.findViewById(R.id.imgSectionIcon)).setImageResource(section.iconRes);
        ((TextView) item.findViewById(R.id.txtSectionTitle)).setText(section.titleRes);
        ((TextView) item.findViewById(R.id.txtSectionSummary)).setText(section.summaryRes);
        ((TextView) item.findViewById(R.id.txtSectionFunction)).setText(section.funcRes);

        View chipsSection = item.findViewById(R.id.chipsSection);
        LinearLayout chipsContainer = item.findViewById(R.id.chipsContainer);
        if (section.chips.length == 0) {
            chipsSection.setVisibility(View.GONE);
        } else {
            for (HelpChip chip : section.chips) {
                View chipView = inflater.inflate(R.layout.item_help_chip, chipsContainer, false);
                ((ImageView) chipView.findViewById(R.id.imgChipIcon)).setImageResource(chip.iconRes);
                ((TextView) chipView.findViewById(R.id.txtChipLabel)).setText(chip.labelRes);
                chipsContainer.addView(chipView);
            }
        }

        LinearLayout stepsContainer = item.findViewById(R.id.stepsContainer);
        String[] steps = STEP_SPLIT_PATTERN.split(getString(section.stepsRes));
        int stepNumber = 1;
        for (String step : steps) {
            String stepText = step.trim();
            if (stepText.isEmpty()) continue;
            View stepRow = inflater.inflate(R.layout.item_help_step_row, stepsContainer, false);
            ((TextView) stepRow.findViewById(R.id.txtStepNumber)).setText(String.valueOf(stepNumber));
            ((TextView) stepRow.findViewById(R.id.txtStepText)).setText(stepText);
            stepsContainer.addView(stepRow);
            stepNumber++;
        }

        View header = item.findViewById(R.id.sectionHeader);
        View body = item.findViewById(R.id.bodyContainer);
        ImageView chevron = item.findViewById(R.id.imgChevron);

        header.setOnClickListener(v -> {
            boolean expanding = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expanding ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(expanding ? 90f : 0f).setDuration(150).start();
        });

        return item;
    }
}
