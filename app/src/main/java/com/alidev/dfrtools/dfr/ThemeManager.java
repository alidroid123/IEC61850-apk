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

    /** Index of the ABSTRACT theme in THEME_NAMES / THEME_COLORS - the only multi-slot theme. */
    private static final int THEME_INDEX_ABSTRACT = 4;

    public static final String[] THEME_NAMES = {
        "MODERN BLUE",
        "EMERALD",
        "PURPLE PREMIUM",
        "RED ENERGY",
        "ABSTRACT"
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
        },
        { // THEME 5 - ABSTRACT. Has no single colour of its own (see ABSTRACT_STYLES); slot 0
          // stands in here so this array stays index-aligned for getThemeColorPrimary/Secondary.
            {"#0369A1", "#6D28D9", "#FAFAFA", "#FFFFFF", "#111827", "#BE185D"}, // Light
            {"#0EA5E9", "#8B5CF6", "#131318", "#1E1E26", "#FAFAFA", "#EC4899"}  // Dark
        }
    };

    /**
     * The six ABSTRACT slots, in palette order (Azure, Violet, Magenta, Coral, Teal, Green).
     * Each rotates which hue plays primary/secondary/accent - see the comment in values/themes.xml.
     */
    private static final int[] ABSTRACT_STYLES = {
        R.style.Theme_DFRtools_Abstract_S0,
        R.style.Theme_DFRtools_Abstract_S1,
        R.style.Theme_DFRtools_Abstract_S2,
        R.style.Theme_DFRtools_Abstract_S3,
        R.style.Theme_DFRtools_Abstract_S4,
        R.style.Theme_DFRtools_Abstract_S5
    };

    /**
     * Which ABSTRACT slot each screen gets. Grouped by function (acquisition, device DB, live
     * monitoring, config) and ordered so the jumps users make most often - Home to DFR Download,
     * Home to IED Monitoring - land on strongly contrasting hues rather than neighbouring ones.
     *
     * Keyed on Class objects, not class-name strings: the app builds with minifyEnabled false
     * today, but if R8 is ever switched on, name-based matching would silently fall through to
     * slot 0 on every screen (a theme that quietly stops rotating, with no crash to point at it).
     * Class identity survives obfuscation.
     *
     * Anything not listed - and any non-Activity Context - falls back to slot 0.
     */
    private static final java.util.Map<Class<?>, Integer> ABSTRACT_SLOT_BY_ACTIVITY;
    static {
        java.util.Map<Class<?>, Integer> m = new java.util.HashMap<>();
        m.put(HomeActivity.class,                0); // Azure
        m.put(DeviceListActivity.class,          1); // Violet
        m.put(RelayTemplateEditActivity.class,   2); // Magenta
        m.put(DfrDownloadActivity.class,         3); // Coral
        m.put(InternalFileManagerActivity.class, 3);
        // The two live-IED screens sit on the cool Teal slot on purpose. Both colour-code their
        // own data by status - red for an alarm or an offline device, green for healthy - so a
        // warm magenta/coral chrome around them reads as an alarm state that isn't there.
        m.put(IEDMonitoringActivity.class,       4); // Teal
        m.put(MmsExplorerActivity.class,         4);
        m.put(SettingsActivity.class,            5); // Green
        m.put(AboutActivity.class,               5);
        m.put(HelpActivity.class,                5);
        ABSTRACT_SLOT_BY_ACTIVITY = m;
    }

    private static int abstractSlotFor(Context context) {
        Integer slot = ABSTRACT_SLOT_BY_ACTIVITY.get(context.getClass());
        return slot != null ? slot : 0;
    }

    public static void applyTheme(Context context) {
        SharedPreferences pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        // If first run, default to light mode.
        if (!pref.contains(KEY_DARK_MODE)) {
            pref.edit().putBoolean(KEY_DARK_MODE, false).apply();
        }

        boolean isDark = pref.getBoolean(KEY_DARK_MODE, false);

        int currentMode = AppCompatDelegate.getDefaultNightMode();
        int targetMode = isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;

        // Only call setDefaultNightMode if it's different to avoid potential recreate loops
        if (currentMode != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode);
        }

        // Default theme is ABSTRACT (index 4): it's the one built to look intentional on first
        // run without the user picking anything, since it rotates a real palette per screen
        // rather than repeating one flat hue everywhere.
        int themeIdx = pref.getInt(KEY_THEME_INDEX, THEME_INDEX_ABSTRACT);
        int themeResId = R.style.Theme_DFRtools_Blue; // Default
        switch (themeIdx) {
            case 1: themeResId = R.style.Theme_DFRtools_Emerald; break;
            case 2: themeResId = R.style.Theme_DFRtools_Purple; break;
            case 3: themeResId = R.style.Theme_DFRtools_Red; break;
            // ABSTRACT is the one theme that resolves to a different style per screen, so which
            // activity is being themed decides the palette slot. Works without touching any
            // layout because they all already read ?attr/colorPrimary / colorSecondary / accent.
            case THEME_INDEX_ABSTRACT: themeResId = ABSTRACT_STYLES[abstractSlotFor(context)]; break;
        }

        // Only set theme if it's not already applied to the context to prevent flickering
        context.setTheme(themeResId);
    }

    public static int getSelectedThemeIndex(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_THEME_INDEX, THEME_INDEX_ABSTRACT);
    }

    public static void setSelectedThemeIndex(Context context, int index) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_THEME_INDEX, index).apply();
    }

    public static boolean isDarkMode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_MODE, false);
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
