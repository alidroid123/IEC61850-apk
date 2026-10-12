package com.alidev.dfrtools.dfr;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.update.AppNotifications;
import com.alidev.dfrtools.update.UpdateChecker;
import com.alidev.dfrtools.update.UpdateFlow;

public class AboutActivity extends BaseActivity {

    /** One headline capability listed on the About screen. */
    private static class AboutFeature {
        final int iconRes, nameRes, descRes;
        AboutFeature(int iconRes, int nameRes, int descRes) {
            this.iconRes = iconRes;
            this.nameRes = nameRes;
            this.descRes = descRes;
        }
    }

    /**
     * Kept in the same order the features appear in the Help guide, so the two screens tell a
     * consistent story. Add new capabilities here rather than as extra XML blocks in the layout.
     */
    private static final AboutFeature[] FEATURES = {
            new AboutFeature(R.drawable.ic_download,    R.string.abt_feat_1_name,  R.string.abt_feat_1_desc),
            new AboutFeature(R.drawable.ic_dfr_chart,   R.string.abt_feat_2_name,  R.string.abt_feat_2_desc),
            new AboutFeature(R.drawable.ic_fault_locator, R.string.abt_feat_11_name, R.string.abt_feat_11_desc),
            new AboutFeature(R.drawable.ic_history,     R.string.abt_feat_3_name,  R.string.abt_feat_3_desc),
            new AboutFeature(R.drawable.ic_list,        R.string.abt_feat_4_name,  R.string.abt_feat_4_desc),
            new AboutFeature(R.drawable.ic_line_data,   R.string.abt_feat_12_name, R.string.abt_feat_12_desc),
            new AboutFeature(R.drawable.ic_mms,         R.string.abt_feat_5_name,  R.string.abt_feat_5_desc),
            new AboutFeature(R.drawable.ic_ied_monitor, R.string.abt_feat_6_name,  R.string.abt_feat_6_desc),
            new AboutFeature(R.drawable.ic_template,    R.string.abt_feat_7_name,  R.string.abt_feat_7_desc),
            new AboutFeature(R.drawable.ic_export,      R.string.abt_feat_8_name,  R.string.abt_feat_8_desc),
            new AboutFeature(R.drawable.ic_sync,        R.string.abt_feat_9_name,  R.string.abt_feat_9_desc),
            new AboutFeature(R.drawable.ic_palette,     R.string.abt_feat_10_name, R.string.abt_feat_10_desc),
    };

    /** A screen a tutorial chapter links to ("Buka …" button). */
    private static class TutorialLink {
        final int labelRes;
        final Class<?> target;
        /** Boolean extra set to true on the Intent (e.g. Settings' "open_import"), or null. */
        final String flagExtra;
        TutorialLink(int labelRes, Class<?> target, String flagExtra) {
            this.labelRes = labelRes;
            this.target = target;
            this.flagExtra = flagExtra;
        }
        TutorialLink(int labelRes, Class<?> target) {
            this(labelRes, target, null);
        }
    }

    /** One chapter of the usage tutorial; stepsRes holds "1. …\n2. …" like the Help screen. */
    private static class TutorialChapter {
        final int titleRes, goalRes, stepsRes, noteRes;
        final TutorialLink[] links;
        TutorialChapter(int titleRes, int goalRes, int stepsRes, int noteRes, TutorialLink... links) {
            this.titleRes = titleRes;
            this.goalRes = goalRes;
            this.stepsRes = stepsRes;
            this.noteRes = noteRes;
            this.links = links;
        }
    }

    /**
     * The tutorial, in the order a new user works through the app: set-up, relay database,
     * downloading, local files, viewing, line data, fault location, monitoring, backup/update and
     * troubleshooting. HelpActivity stays the per-screen reference; this is the guided path.
     */
    private static final TutorialChapter[] TUTORIAL = {
            new TutorialChapter(R.string.tut_1_title, R.string.tut_1_goal, R.string.tut_1_steps, 0,
                    new TutorialLink(R.string.tut_link_settings, SettingsActivity.class),
                    new TutorialLink(R.string.tut_link_import_config, SettingsActivity.class, "open_import")),
            new TutorialChapter(R.string.tut_2_title, R.string.tut_2_goal, R.string.tut_2_steps, 0,
                    new TutorialLink(R.string.tut_link_devices, DeviceListActivity.class),
                    new TutorialLink(R.string.tut_link_overview, SubstationOverviewActivity.class)),
            new TutorialChapter(R.string.tut_3_title, R.string.tut_3_goal, R.string.tut_3_steps, R.string.tut_3_note,
                    new TutorialLink(R.string.tut_link_download, DfrDownloadActivity.class),
                    new TutorialLink(R.string.tut_link_devices, DeviceListActivity.class)),
            new TutorialChapter(R.string.tut_4_title, R.string.tut_4_goal, R.string.tut_4_steps, 0,
                    new TutorialLink(R.string.tut_link_history, InternalFileManagerActivity.class)),
            new TutorialChapter(R.string.tut_5_title, R.string.tut_5_goal, R.string.tut_5_steps, R.string.tut_5_note,
                    new TutorialLink(R.string.tut_link_viewer, DfrViewerActivity.class),
                    new TutorialLink(R.string.tut_link_history, InternalFileManagerActivity.class)),
            new TutorialChapter(R.string.tut_6_title, R.string.tut_6_goal, R.string.tut_6_steps, 0,
                    new TutorialLink(R.string.tut_link_line_db, LineDatabaseActivity.class)),
            new TutorialChapter(R.string.tut_7_title, R.string.tut_7_goal, R.string.tut_7_steps, R.string.tut_7_note,
                    new TutorialLink(R.string.tut_link_fl, FaultLocatorActivity.class),
                    new TutorialLink(R.string.tut_link_line_db, LineDatabaseActivity.class)),
            new TutorialChapter(R.string.tut_8_title, R.string.tut_8_goal, R.string.tut_8_steps, 0,
                    new TutorialLink(R.string.tut_link_explorer, MmsExplorerActivity.class),
                    new TutorialLink(R.string.tut_link_monitoring, IEDMonitoringActivity.class),
                    new TutorialLink(R.string.tut_link_template, RelayTemplateEditActivity.class),
                    new TutorialLink(R.string.tut_link_overview, SubstationOverviewActivity.class)),
            new TutorialChapter(R.string.tut_9_title, R.string.tut_9_goal, R.string.tut_9_steps, 0,
                    new TutorialLink(R.string.tut_link_settings, SettingsActivity.class),
                    new TutorialLink(R.string.tut_link_notifications, com.alidev.dfrtools.update.NotificationActivity.class)),
            new TutorialChapter(R.string.tut_10_title, R.string.tut_10_goal, R.string.tut_10_steps, 0,
                    new TutorialLink(R.string.tut_link_help, HelpActivity.class),
                    new TutorialLink(R.string.tut_link_devices, DeviceListActivity.class)),
    };

    private static final java.util.regex.Pattern STEP_SPLIT = java.util.regex.Pattern.compile("(?:^|\\n)\\d+\\.\\s*");

    private final UpdateFlow updateFlow = new UpdateFlow(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        TextView tvVersion = findViewById(R.id.tvVersion);
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVersion.setText(getString(R.string.val_abt_version_format, pInfo.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        buildTutorial();
        buildFeatureList();

        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> checkForUpdateManually());
        findViewById(R.id.btnTutorialHelp).setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
    }

    /** Collapsed chapter cards; tapping a header shows its steps, note and screen links. */
    private void buildTutorial() {
        LinearLayout container = findViewById(R.id.containerTutorial);
        LayoutInflater inflater = getLayoutInflater();
        for (int c = 0; c < TUTORIAL.length; c++) {
            TutorialChapter chapter = TUTORIAL[c];
            View card = inflater.inflate(R.layout.item_tutorial_chapter, container, false);
            ((TextView) card.findViewById(R.id.tutNumber)).setText(String.valueOf(c + 1));
            ((TextView) card.findViewById(R.id.tutTitle)).setText(chapter.titleRes);
            ((TextView) card.findViewById(R.id.tutGoal)).setText(chapter.goalRes);

            LinearLayout steps = card.findViewById(R.id.tutSteps);
            int n = 1;
            for (String step : STEP_SPLIT.split(getString(chapter.stepsRes))) {
                String text = step.trim();
                if (text.isEmpty()) continue;
                View row = inflater.inflate(R.layout.item_help_step_row, steps, false);
                ((TextView) row.findViewById(R.id.txtStepNumber)).setText(String.valueOf(n++));
                ((TextView) row.findViewById(R.id.txtStepText)).setText(text);
                steps.addView(row);
            }

            if (chapter.noteRes != 0) {
                TextView note = card.findViewById(R.id.tutNote);
                note.setText(chapter.noteRes);
                note.setVisibility(View.VISIBLE);
            }

            LinearLayout links = card.findViewById(R.id.tutLinks);
            for (TutorialLink link : chapter.links) {
                com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(
                        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                b.setText(getString(R.string.btn_tut_open, getString(link.labelRes)));
                b.setTextSize(13f);
                b.setAllCaps(false);
                b.setIconResource(R.drawable.ic_arrow_right);
                b.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_END);
                b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                b.setOnClickListener(v -> {
                    Intent intent = new Intent(this, link.target);
                    if (link.flagExtra != null) intent.putExtra(link.flagExtra, true);
                    startActivity(intent);
                });
                links.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            View body = card.findViewById(R.id.tutBody);
            ImageView chevron = card.findViewById(R.id.tutChevron);
            card.findViewById(R.id.tutHeader).setOnClickListener(v -> {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                chevron.animate().rotation(open ? 90f : 0f).setDuration(150).start();
            });
            container.addView(card);
        }
    }

    private void buildFeatureList() {
        LinearLayout container = findViewById(R.id.containerAboutFeatures);
        LayoutInflater inflater = getLayoutInflater();
        for (AboutFeature feature : FEATURES) {
            View row = inflater.inflate(R.layout.item_about_feature, container, false);
            ((ImageView) row.findViewById(R.id.imgFeatureIcon)).setImageResource(feature.iconRes);
            ((TextView) row.findViewById(R.id.txtFeatureName)).setText(feature.nameRes);
            ((TextView) row.findViewById(R.id.txtFeatureDesc)).setText(feature.descRes);
            container.addView(row);
        }
    }

    private void checkForUpdateManually() {
        Toast.makeText(this, R.string.msg_all_update_checking, Toast.LENGTH_SHORT).show();
        UpdateChecker.checkForUpdate(this, info -> {
            if (isFinishing()) return;
            if (info != null) {
                AppNotifications.add(this, "update_" + info.versionName,
                        getString(R.string.msg_all_update_available_title, UpdateChecker.getCurrentVersionName(this), info.versionName),
                        info.releaseNotes);
                updateFlow.showUpdateDialog(info);
            } else {
                Toast.makeText(this, R.string.msg_all_update_none, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFlow.onResume();
    }
}
