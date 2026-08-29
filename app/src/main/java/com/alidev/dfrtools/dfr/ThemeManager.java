package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import androidx.appcompat.app.AppCompatDelegate;
import com.alidev.dfrtools.R;

public class ThemeManager {
    private static final String PREF_NAME = "dfr_theme_prefs";
    private static final String KEY_THEME_INDEX = "selected_theme_index";
    private static final String KEY_DARK_MODE = "dark_mode";

    public static final String[] THEME_NAMES = {
        "MODERN BLUE",
        "EMERALD",
        "PURPLE PREMIUM",
        "RED ENERGY"
    };

    /**
     * [ThemeIndex][Light(0)/Dark(1)][Primary(0), Secondary(1), Background(2), Surface(3), TextPrimary(4), Accent(5)]
     */
    private static final String[][][] THEME_COLORS = {
        { // THEME 1 - MODERN BLUE
            {"#1D4ED8", "#1E3A8A", "#F8FAFC", "#FFFFFF", "#0F172A", "#D97706"}, // Light
            {"#3B82F6", "#1D4ED8", "#0F172A", "#1E293B", "#F8FAFC", "#F59E0B"}  // Dark
        },
        { // THEME 2 - EMERALD
            {"#047857", "#064E3B", "#F9FAFB", "#FFFFFF", "#111827", "#EA580C"}, // Light
            {"#10B981", "#047857", "#111827", "#1F2937", "#F9FAFB", "#F97316"}  // Dark
        },
        { // THEME 3 - PURPLE PREMIUM
            {"#6D28D9", "#4C1D95", "#FAFAFA", "#FFFFFF", "#111827", "#16A34A"}, // Light
            {"#8B5CF6", "#6D28D9", "#18181B", "#27272A", "#FAFAFA", "#22C55E"}  // Dark
        },
        { // THEME 4 - RED ENERGY
            {"#B91C1C", "#7F1D1D", "#FAFAFA", "#FFFFFF", "#111827", "#0284C7"}, // Light
            {"#EF4444", "#B91C1C", "#111827", "#1F2937", "#F9FAFB", "#0EA5E9"}  // Dark
        }
    };

    public static void applyTheme(Context context) {
        SharedPreferences pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        // If first run, set default to dark mode
        if (!pref.contains(KEY_DARK_MODE)) {
            pref.edit().putBoolean(KEY_DARK_MODE, true).apply();
        }

        boolean isDark = pref.getBoolean(KEY_DARK_MODE, true);
        
        int currentMode = AppCompatDelegate.getDefaultNightMode();
        int targetMode = isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        
        // Only call setDefaultNightMode if it's different to avoid potential recreate loops
        if (currentMode != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode);
        }
        
        int themeIdx = pref.getInt(KEY_THEME_INDEX, 0);
        int themeResId = R.style.Theme_DFRtools_Blue; // Default
        switch (themeIdx) {
            case 1: themeResId = R.style.Theme_DFRtools_Emerald; break;
            case 2: themeResId = R.style.Theme_DFRtools_Purple; break;
            case 3: themeResId = R.style.Theme_DFRtools_Red; break;
        }

        // Only set theme if it's not already applied to the context to prevent flickering
        context.setTheme(themeResId);
    }

    public static int getSelectedThemeIndex(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_THEME_INDEX, 0);
    }

    public static void setSelectedThemeIndex(Context context, int index) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_THEME_INDEX, index).apply();
    }

    public static boolean isDarkMode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_MODE, true);
    }

    public static void setDarkMode(Context context, boolean isDark) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK_MODE, isDark).apply();
    }

    public static int getThemeColorPrimary(Context context, int index, boolean isDark) {
        return Color.parseColor(THEME_COLORS[index][isDark ? 1 : 0][0]);
    }

    public static int getThemeColorSecondary(Context context, int index, boolean isDark) {
        return Color.parseColor(THEME_COLORS[index][isDark ? 1 : 0][1]);
    }

    public static int getThemeColorPrimary(Context context) {
        return getThemeColorPrimary(context, getSelectedThemeIndex(context), isDarkMode(context));
    }

    public static int getThemeColorSecondary(Context context) {
        return getThemeColorSecondary(context, getSelectedThemeIndex(context), isDarkMode(context));
    }
}
