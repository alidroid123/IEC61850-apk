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

/** Expandable, searchable usage guide covering every screen in the app. */
public class HelpActivity extends BaseActivity {

    private static class HelpSection {
        final int iconRes, titleRes, summaryRes, funcRes, stepsRes;
        HelpSection(int iconRes, int titleRes, int summaryRes, int funcRes, int stepsRes) {
            this.iconRes = iconRes;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.funcRes = funcRes;
            this.stepsRes = stepsRes;
        }
    }

    private static final HelpSection[] SECTIONS = {
            new HelpSection(R.drawable.ic_menu, R.string.help_ttl_home, R.string.help_sum_home,
                    R.string.help_func_home, R.string.help_steps_home),
            new HelpSection(R.drawable.ic_download, R.string.help_ttl_download, R.string.help_sum_download,
                    R.string.help_func_download, R.string.help_steps_download),
            new HelpSection(R.drawable.ic_history, R.string.help_ttl_history, R.string.help_sum_history,
                    R.string.help_func_history, R.string.help_steps_history),
            new HelpSection(R.drawable.ic_list, R.string.help_ttl_devices, R.string.help_sum_devices,
                    R.string.help_func_devices, R.string.help_steps_devices),
            new HelpSection(R.drawable.ic_mms, R.string.help_ttl_explorer, R.string.help_sum_explorer,
                    R.string.help_func_explorer, R.string.help_steps_explorer),
            new HelpSection(R.drawable.ic_dfr_chart, R.string.help_ttl_viewer, R.string.help_sum_viewer,
                    R.string.help_func_viewer, R.string.help_steps_viewer),
            new HelpSection(R.drawable.ic_ied_monitor, R.string.help_ttl_monitoring, R.string.help_sum_monitoring,
                    R.string.help_func_monitoring, R.string.help_steps_monitoring),
            new HelpSection(R.drawable.ic_template, R.string.help_ttl_template, R.string.help_sum_template,
                    R.string.help_func_template, R.string.help_steps_template),
            new HelpSection(R.drawable.ic_settings, R.string.help_ttl_settings, R.string.help_sum_settings,
                    R.string.help_func_settings, R.string.help_steps_settings),
            new HelpSection(R.drawable.ic_info, R.string.help_ttl_about, R.string.help_sum_about,
                    R.string.help_func_about, R.string.help_steps_about),
            new HelpSection(R.drawable.ic_check, R.string.help_ttl_tips, R.string.help_sum_tips,
                    R.string.help_func_tips, R.string.help_steps_tips),
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
        ((TextView) item.findViewById(R.id.txtSectionSteps)).setText(section.stepsRes);

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
