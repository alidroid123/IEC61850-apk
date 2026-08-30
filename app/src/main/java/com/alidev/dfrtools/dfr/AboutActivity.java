package com.alidev.dfrtools.dfr;

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
            new AboutFeature(R.drawable.ic_history,     R.string.abt_feat_3_name,  R.string.abt_feat_3_desc),
            new AboutFeature(R.drawable.ic_list,        R.string.abt_feat_4_name,  R.string.abt_feat_4_desc),
            new AboutFeature(R.drawable.ic_mms,         R.string.abt_feat_5_name,  R.string.abt_feat_5_desc),
            new AboutFeature(R.drawable.ic_ied_monitor, R.string.abt_feat_6_name,  R.string.abt_feat_6_desc),
            new AboutFeature(R.drawable.ic_template,    R.string.abt_feat_7_name,  R.string.abt_feat_7_desc),
            new AboutFeature(R.drawable.ic_export,      R.string.abt_feat_8_name,  R.string.abt_feat_8_desc),
            new AboutFeature(R.drawable.ic_sync,        R.string.abt_feat_9_name,  R.string.abt_feat_9_desc),
            new AboutFeature(R.drawable.ic_palette,     R.string.abt_feat_10_name, R.string.abt_feat_10_desc),
    };

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

        buildFeatureList();

        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> checkForUpdateManually());
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
                        getString(R.string.msg_all_update_available_title, info.versionName),
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
