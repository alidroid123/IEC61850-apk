package com.alidev.dfrtools.dfr;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.alidev.dfrtools.R;

public abstract class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.alidev.dfrtools.utils.LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
    }

    protected void showThemeSelectionDialog() {
        boolean isDark = ThemeManager.isDarkMode(this);
        String modeName = isDark ? "Gelap" : "Terang";
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_DFRtools);
        builder.setTitle(getString(R.string.ttl_base_theme_selection, modeName));
        
        ThemeAdapter adapter = new ThemeAdapter(isDark);
        builder.setAdapter(adapter, (dialog, which) -> {
            if (which < ThemeManager.THEME_NAMES.length) {
                ThemeManager.setSelectedThemeIndex(this, which);
                recreate();
            }
        });
        
        String toggleTo = isDark ? "Light" : "Dark";
        builder.setNeutralButton(getString(R.string.btn_base_toggle_mode, toggleTo), (dialog, which) -> toggleTheme());
        builder.show();
    }

    private class ThemeAdapter extends BaseAdapter {
        private final boolean isDark;
        ThemeAdapter(boolean isDark) { this.isDark = isDark; }
        @Override public int getCount() { return ThemeManager.THEME_NAMES.length; }
        @Override public Object getItem(int i) { return ThemeManager.THEME_NAMES[i]; }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View view, ViewGroup viewGroup) {
            if (view == null) view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.item_theme_selection, viewGroup, false);
            TextView tv = view.findViewById(R.id.tvThemeName);
            View colorPreview = view.findViewById(R.id.viewThemeColor);
            ImageView ivCheck = view.findViewById(R.id.ivCheck);
            
            tv.setText(ThemeManager.THEME_NAMES[i]);
            
            int color1 = ThemeManager.getThemeColorPrimary(viewGroup.getContext(), i, isDark);
            
            GradientDrawable gd = (GradientDrawable) colorPreview.getBackground();
            if (gd != null) gd.setColor(color1);
            
            boolean isSelected = ThemeManager.getSelectedThemeIndex(viewGroup.getContext()) == i;
            ivCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            
            return view;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    protected void toggleTheme() {
        boolean isDark = !ThemeManager.isDarkMode(this);
        ThemeManager.setDarkMode(this, isDark);
        AppCompatDelegate.setDefaultNightMode(isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        recreate();
    }
}
