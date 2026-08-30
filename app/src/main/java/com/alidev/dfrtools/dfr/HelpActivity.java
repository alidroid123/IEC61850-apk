package com.alidev.dfrtools.dfr;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.alidev.dfrtools.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expandable, searchable usage guide covering every screen in the app. */
public class HelpActivity extends BaseActivity {

    /** One button/icon/menu explained in a section's glossary. */
    private static class HelpComponent {
        final int iconRes, nameRes, descRes;
        HelpComponent(int iconRes, int nameRes, int descRes) {
            this.iconRes = iconRes;
            this.nameRes = nameRes;
            this.descRes = descRes;
        }
    }

    /** One distinct workflow within a section (a section can have several). */
    private static class HelpFeature {
        final int titleRes, stepsRes;
        HelpFeature(int titleRes, int stepsRes) {
            this.titleRes = titleRes;
            this.stepsRes = stepsRes;
        }
    }

    private static class HelpSection {
        final int iconRes, titleRes, summaryRes, funcRes;
        final HelpComponent[] components;
        final HelpFeature[] features;
        HelpSection(int iconRes, int titleRes, int summaryRes, int funcRes,
                    HelpComponent[] components, HelpFeature[] features) {
            this.iconRes = iconRes;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.funcRes = funcRes;
            this.components = components;
            this.features = features;
        }
    }

    private static final Pattern STEP_SPLIT_PATTERN = Pattern.compile("(?:^|\\n)\\d+\\.\\s*");
    private static final Pattern INLINE_ICON_PATTERN = Pattern.compile("\\{ic:([a-z0-9_]+)\\}");

    /** Token name (used as {ic:xxx} inside step strings) -> drawable resource. */
    private static final Map<String, Integer> ICON_NAME_MAP = new HashMap<>();
    static {
        ICON_NAME_MAP.put("list", R.drawable.ic_list);
        ICON_NAME_MAP.put("add", R.drawable.ic_add);
        ICON_NAME_MAP.put("sync", R.drawable.ic_sync);
        ICON_NAME_MAP.put("delete", R.drawable.ic_delete);
        ICON_NAME_MAP.put("edit", R.drawable.ic_edit_small);
        ICON_NAME_MAP.put("export", R.drawable.ic_export);
        ICON_NAME_MAP.put("import", R.drawable.ic_import);
        ICON_NAME_MAP.put("save", R.drawable.ic_save);
        ICON_NAME_MAP.put("copy", R.drawable.ic_copy);
        ICON_NAME_MAP.put("template", R.drawable.ic_template);
        ICON_NAME_MAP.put("ping", R.drawable.ic_bulk_ping);
        ICON_NAME_MAP.put("zoom", R.drawable.ic_zoom);
        ICON_NAME_MAP.put("cursor", R.drawable.ic_cursor);
        ICON_NAME_MAP.put("settings", R.drawable.ic_settings);
        ICON_NAME_MAP.put("vpn", R.drawable.ic_vpn);
        ICON_NAME_MAP.put("theme", R.drawable.ic_theme);
        ICON_NAME_MAP.put("download", R.drawable.ic_download);
        ICON_NAME_MAP.put("menu", R.drawable.ic_menu);
        ICON_NAME_MAP.put("history", R.drawable.ic_history);
        ICON_NAME_MAP.put("mms", R.drawable.ic_mms);
        ICON_NAME_MAP.put("chart", R.drawable.ic_dfr_chart);
        ICON_NAME_MAP.put("monitor", R.drawable.ic_ied_monitor);
        ICON_NAME_MAP.put("info", R.drawable.ic_info);
        ICON_NAME_MAP.put("check", R.drawable.ic_check);
        ICON_NAME_MAP.put("share", R.drawable.ic_share);
        ICON_NAME_MAP.put("search", R.drawable.ic_search);
        ICON_NAME_MAP.put("folder", R.drawable.ic_folder);
        ICON_NAME_MAP.put("openfolder", R.drawable.ic_open_folder);
        ICON_NAME_MAP.put("warning", R.drawable.ic_warning);
        ICON_NAME_MAP.put("location", R.drawable.ic_location);
        ICON_NAME_MAP.put("toggle", R.drawable.ic_toggle_off);
        ICON_NAME_MAP.put("arrowright", R.drawable.ic_arrow_right);
        ICON_NAME_MAP.put("back", R.drawable.ic_arrow_back);
    }

    private static final HelpSection[] SECTIONS = {
            new HelpSection(R.drawable.ic_menu, R.string.help_ttl_home, R.string.help_sum_home, R.string.help_func_home,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_menu, R.string.help_cmp_home_1_name, R.string.help_cmp_home_1_desc),
                            new HelpComponent(R.drawable.ic_download, R.string.help_cmp_home_2_name, R.string.help_cmp_home_2_desc),
                            new HelpComponent(R.drawable.ic_warning, R.string.help_cmp_home_3_name, R.string.help_cmp_home_3_desc),
                            new HelpComponent(R.drawable.ic_info, R.string.help_cmp_home_4_name, R.string.help_cmp_home_4_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_home_1_title, R.string.help_feat_home_1_steps),
                            new HelpFeature(R.string.help_feat_home_2_title, R.string.help_feat_home_2_steps),
                    }),
            new HelpSection(R.drawable.ic_download, R.string.help_ttl_download, R.string.help_sum_download, R.string.help_func_download,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_list, R.string.help_cmp_dl_1_name, R.string.help_cmp_dl_1_desc),
                            new HelpComponent(R.drawable.ic_settings, R.string.help_cmp_dl_2_name, R.string.help_cmp_dl_2_desc),
                            new HelpComponent(R.drawable.ic_check, R.string.help_cmp_dl_3_name, R.string.help_cmp_dl_3_desc),
                            new HelpComponent(R.drawable.ic_download, R.string.help_cmp_dl_4_name, R.string.help_cmp_dl_4_desc),
                            new HelpComponent(R.drawable.ic_download, R.string.help_cmp_dl_5_name, R.string.help_cmp_dl_5_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_dl_1_title, R.string.help_feat_dl_1_steps),
                            new HelpFeature(R.string.help_feat_dl_2_title, R.string.help_feat_dl_2_steps),
                            new HelpFeature(R.string.help_feat_dl_3_title, R.string.help_feat_dl_3_steps),
                    }),
            new HelpSection(R.drawable.ic_history, R.string.help_ttl_history, R.string.help_sum_history, R.string.help_func_history,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_open_folder, R.string.help_cmp_hist_1_name, R.string.help_cmp_hist_1_desc),
                            new HelpComponent(R.drawable.ic_folder, R.string.help_cmp_hist_2_name, R.string.help_cmp_hist_2_desc),
                            new HelpComponent(R.drawable.ic_delete, R.string.help_cmp_hist_3_name, R.string.help_cmp_hist_3_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_hist_1_title, R.string.help_feat_hist_1_steps),
                            new HelpFeature(R.string.help_feat_hist_2_title, R.string.help_feat_hist_2_steps),
                            new HelpFeature(R.string.help_feat_hist_3_title, R.string.help_feat_hist_3_steps),
                    }),
            new HelpSection(R.drawable.ic_list, R.string.help_ttl_devices, R.string.help_sum_devices, R.string.help_func_devices,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_bulk_ping, R.string.help_cmp_dev_1_name, R.string.help_cmp_dev_1_desc),
                            new HelpComponent(R.drawable.ic_export, R.string.help_cmp_dev_2_name, R.string.help_cmp_dev_2_desc),
                            new HelpComponent(R.drawable.ic_import, R.string.help_cmp_dev_3_name, R.string.help_cmp_dev_3_desc),
                            new HelpComponent(R.drawable.ic_location, R.string.help_cmp_dev_4_name, R.string.help_cmp_dev_4_desc),
                            new HelpComponent(R.drawable.ic_warning, R.string.help_cmp_dev_5_name, R.string.help_cmp_dev_5_desc),
                            new HelpComponent(R.drawable.ic_list, R.string.help_cmp_dev_6_name, R.string.help_cmp_dev_6_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_dev_1_title, R.string.help_feat_dev_1_steps),
                            new HelpFeature(R.string.help_feat_dev_2_title, R.string.help_feat_dev_2_steps),
                            new HelpFeature(R.string.help_feat_dev_3_title, R.string.help_feat_dev_3_steps),
                            new HelpFeature(R.string.help_feat_dev_4_title, R.string.help_feat_dev_4_steps),
                    }),
            new HelpSection(R.drawable.ic_mms, R.string.help_ttl_explorer, R.string.help_sum_explorer, R.string.help_func_explorer,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_list, R.string.help_cmp_exp_1_name, R.string.help_cmp_exp_1_desc),
                            new HelpComponent(R.drawable.ic_vpn, R.string.help_cmp_exp_2_name, R.string.help_cmp_exp_2_desc),
                            new HelpComponent(R.drawable.ic_search, R.string.help_cmp_exp_3_name, R.string.help_cmp_exp_3_desc),
                            new HelpComponent(R.drawable.ic_add, R.string.help_cmp_exp_4_name, R.string.help_cmp_exp_4_desc),
                            new HelpComponent(R.drawable.ic_sync, R.string.help_cmp_exp_5_name, R.string.help_cmp_exp_5_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_exp_1_title, R.string.help_feat_exp_1_steps),
                            new HelpFeature(R.string.help_feat_exp_2_title, R.string.help_feat_exp_2_steps),
                            new HelpFeature(R.string.help_feat_exp_3_title, R.string.help_feat_exp_3_steps),
                            new HelpFeature(R.string.help_feat_exp_4_title, R.string.help_feat_exp_4_steps),
                    }),
            new HelpSection(R.drawable.ic_dfr_chart, R.string.help_ttl_viewer, R.string.help_sum_viewer, R.string.help_func_viewer,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_zoom, R.string.help_cmp_view_1_name, R.string.help_cmp_view_1_desc),
                            new HelpComponent(R.drawable.ic_cursor, R.string.help_cmp_view_2_name, R.string.help_cmp_view_2_desc),
                            new HelpComponent(R.drawable.ic_folder, R.string.help_cmp_view_3_name, R.string.help_cmp_view_3_desc),
                            new HelpComponent(R.drawable.ic_settings, R.string.help_cmp_view_4_name, R.string.help_cmp_view_4_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_view_1_title, R.string.help_feat_view_1_steps),
                            new HelpFeature(R.string.help_feat_view_2_title, R.string.help_feat_view_2_steps),
                            new HelpFeature(R.string.help_feat_view_3_title, R.string.help_feat_view_3_steps),
                            new HelpFeature(R.string.help_feat_view_4_title, R.string.help_feat_view_4_steps),
                    }),
            new HelpSection(R.drawable.ic_ied_monitor, R.string.help_ttl_monitoring, R.string.help_sum_monitoring, R.string.help_func_monitoring,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_sync, R.string.help_cmp_mon_1_name, R.string.help_cmp_mon_1_desc),
                            new HelpComponent(R.drawable.ic_add, R.string.help_cmp_mon_2_name, R.string.help_cmp_mon_2_desc),
                            new HelpComponent(R.drawable.ic_search, R.string.help_cmp_mon_3_name, R.string.help_cmp_mon_3_desc),
                            new HelpComponent(R.drawable.ic_arrow_right, R.string.help_cmp_mon_4_name, R.string.help_cmp_mon_4_desc),
                            new HelpComponent(R.drawable.ic_sync, R.string.help_cmp_mon_5_name, R.string.help_cmp_mon_5_desc),
                            new HelpComponent(R.drawable.ic_edit_small, R.string.help_cmp_mon_6_name, R.string.help_cmp_mon_6_desc),
                            new HelpComponent(R.drawable.ic_delete, R.string.help_cmp_mon_7_name, R.string.help_cmp_mon_7_desc),
                            new HelpComponent(R.drawable.ic_edit_small, R.string.help_cmp_mon_8_name, R.string.help_cmp_mon_8_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_mon_1_title, R.string.help_feat_mon_1_steps),
                            new HelpFeature(R.string.help_feat_mon_2_title, R.string.help_feat_mon_2_steps),
                            new HelpFeature(R.string.help_feat_mon_3_title, R.string.help_feat_mon_3_steps),
                            new HelpFeature(R.string.help_feat_mon_4_title, R.string.help_feat_mon_4_steps),
                            new HelpFeature(R.string.help_feat_mon_5_title, R.string.help_feat_mon_5_steps),
                    }),
            new HelpSection(R.drawable.ic_template, R.string.help_ttl_template, R.string.help_sum_template, R.string.help_func_template,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_add, R.string.help_cmp_tmpl_1_name, R.string.help_cmp_tmpl_1_desc),
                            new HelpComponent(R.drawable.ic_copy, R.string.help_cmp_tmpl_2_name, R.string.help_cmp_tmpl_2_desc),
                            new HelpComponent(R.drawable.ic_list, R.string.help_cmp_tmpl_3_name, R.string.help_cmp_tmpl_3_desc),
                            new HelpComponent(R.drawable.ic_delete, R.string.help_cmp_tmpl_4_name, R.string.help_cmp_tmpl_4_desc),
                            new HelpComponent(R.drawable.ic_delete, R.string.help_cmp_tmpl_5_name, R.string.help_cmp_tmpl_5_desc),
                            new HelpComponent(R.drawable.ic_save, R.string.help_cmp_tmpl_6_name, R.string.help_cmp_tmpl_6_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_tmpl_1_title, R.string.help_feat_tmpl_1_steps),
                            new HelpFeature(R.string.help_feat_tmpl_2_title, R.string.help_feat_tmpl_2_steps),
                            new HelpFeature(R.string.help_feat_tmpl_3_title, R.string.help_feat_tmpl_3_steps),
                            new HelpFeature(R.string.help_feat_tmpl_4_title, R.string.help_feat_tmpl_4_steps),
                    }),
            new HelpSection(R.drawable.ic_settings, R.string.help_ttl_settings, R.string.help_sum_settings, R.string.help_func_settings,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_settings, R.string.help_cmp_set_1_name, R.string.help_cmp_set_1_desc),
                            new HelpComponent(R.drawable.ic_delete, R.string.help_cmp_set_2_name, R.string.help_cmp_set_2_desc),
                            new HelpComponent(R.drawable.ic_check, R.string.help_cmp_set_3_name, R.string.help_cmp_set_3_desc),
                            new HelpComponent(R.drawable.ic_export, R.string.help_cmp_set_4_name, R.string.help_cmp_set_4_desc),
                            new HelpComponent(R.drawable.ic_import, R.string.help_cmp_set_5_name, R.string.help_cmp_set_5_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_set_1_title, R.string.help_feat_set_1_steps),
                            new HelpFeature(R.string.help_feat_set_2_title, R.string.help_feat_set_2_steps),
                            new HelpFeature(R.string.help_feat_set_3_title, R.string.help_feat_set_3_steps),
                    }),
            new HelpSection(R.drawable.ic_info, R.string.help_ttl_about, R.string.help_sum_about, R.string.help_func_about,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_info, R.string.help_cmp_abt_1_name, R.string.help_cmp_abt_1_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_abt_1_title, R.string.help_feat_abt_1_steps),
                    }),
            new HelpSection(R.drawable.ic_check, R.string.help_ttl_tips, R.string.help_sum_tips, R.string.help_func_tips,
                    new HelpComponent[]{
                            new HelpComponent(R.drawable.ic_vpn, R.string.help_cmp_tips_1_name, R.string.help_cmp_tips_1_desc),
                            new HelpComponent(R.drawable.ic_theme, R.string.help_cmp_tips_2_name, R.string.help_cmp_tips_2_desc),
                    },
                    new HelpFeature[]{
                            new HelpFeature(R.string.help_feat_tips_1_title, R.string.help_feat_tips_1_steps),
                            new HelpFeature(R.string.help_feat_tips_2_title, R.string.help_feat_tips_2_steps),
                            new HelpFeature(R.string.help_feat_tips_3_title, R.string.help_feat_tips_3_steps),
                    }),
    };

    private final List<View> sectionViews = new ArrayList<>();
    private TextView tvNoResults;
    private int inlineIconColor;
    private int inlineIconSizePx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
        inlineIconColor = tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
        inlineIconSizePx = (int) (getResources().getDisplayMetrics().density * 16);

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
     * Search-as-you-type across every section's title/summary/function/component/feature text. A
     * non-empty query auto-expands every match (so the relevant text is visible immediately
     * without an extra tap) and collapses everything again once the query is cleared.
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
        if (getString(section.titleRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                || getString(section.summaryRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                || getString(section.funcRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)) {
            return true;
        }
        for (HelpComponent c : section.components) {
            if (getString(c.nameRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                    || getString(c.descRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)) {
                return true;
            }
        }
        for (HelpFeature f : section.features) {
            if (getString(f.titleRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)
                    || getString(f.stepsRes).toLowerCase(Locale.getDefault()).contains(lowercaseQuery)) {
                return true;
            }
        }
        return false;
    }

    private View buildSectionView(LayoutInflater inflater, ViewGroup parent, HelpSection section) {
        View item = inflater.inflate(R.layout.item_help_section, parent, false);

        ((ImageView) item.findViewById(R.id.imgSectionIcon)).setImageResource(section.iconRes);
        ((TextView) item.findViewById(R.id.txtSectionTitle)).setText(section.titleRes);
        ((TextView) item.findViewById(R.id.txtSectionSummary)).setText(section.summaryRes);
        ((TextView) item.findViewById(R.id.txtSectionFunction)).setText(section.funcRes);

        LinearLayout componentsContainer = item.findViewById(R.id.componentsContainer);
        for (HelpComponent c : section.components) {
            View row = inflater.inflate(R.layout.item_help_component_row, componentsContainer, false);
            ((ImageView) row.findViewById(R.id.imgComponentIcon)).setImageResource(c.iconRes);
            ((TextView) row.findViewById(R.id.txtComponentName)).setText(c.nameRes);
            ((TextView) row.findViewById(R.id.txtComponentDesc)).setText(c.descRes);
            componentsContainer.addView(row);
        }

        LinearLayout featuresContainer = item.findViewById(R.id.featuresContainer);
        for (HelpFeature f : section.features) {
            View block = inflater.inflate(R.layout.item_help_feature_block, featuresContainer, false);
            ((TextView) block.findViewById(R.id.txtFeatureTitle)).setText(f.titleRes);

            LinearLayout stepsContainer = block.findViewById(R.id.featureStepsContainer);
            String[] steps = STEP_SPLIT_PATTERN.split(getString(f.stepsRes));
            int stepNumber = 1;
            for (String step : steps) {
                String stepText = step.trim();
                if (stepText.isEmpty()) continue;
                View stepRow = inflater.inflate(R.layout.item_help_step_row, stepsContainer, false);
                ((TextView) stepRow.findViewById(R.id.txtStepNumber)).setText(String.valueOf(stepNumber));
                ((TextView) stepRow.findViewById(R.id.txtStepText)).setText(renderInlineIcons(stepText));
                stepsContainer.addView(stepRow);
                stepNumber++;
            }
            featuresContainer.addView(block);
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

    /** Replaces every {ic:name} token in raw with a small inline icon matching the surrounding text. */
    private CharSequence renderInlineIcons(String raw) {
        Matcher m = INLINE_ICON_PATTERN.matcher(raw);
        if (!m.find()) return raw;
        m.reset();

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(raw, last, m.start());
            Integer iconRes = ICON_NAME_MAP.get(m.group(1));
            if (iconRes != null) {
                int start = sb.length();
                sb.append(" ");
                Drawable icon = ContextCompat.getDrawable(this, iconRes);
                if (icon != null) {
                    icon = icon.mutate();
                    DrawableCompat.setTint(icon, inlineIconColor);
                    icon.setBounds(0, 0, inlineIconSizePx, inlineIconSizePx);
                    sb.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE), start, sb.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            last = m.end();
        }
        sb.append(raw, last, raw.length());
        return sb;
    }
}
