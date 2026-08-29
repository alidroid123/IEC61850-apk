package com.alidev.dfrtools.dfr;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.update.UpdateChecker;
import com.alidev.dfrtools.update.UpdateFlow;

public class AboutActivity extends BaseActivity {

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

        findViewById(R.id.btnCheckUpdate).setOnClickListener(v -> checkForUpdateManually());
    }

    private void checkForUpdateManually() {
        Toast.makeText(this, R.string.msg_all_update_checking, Toast.LENGTH_SHORT).show();
        UpdateChecker.checkForUpdate(this, info -> {
            if (isFinishing()) return;
            if (info != null) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        updateFlow.onDestroy();
    }
}
