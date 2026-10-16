package com.dosse.airpods.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.os.PowerManager;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Theme helpers.
 * <p>
 * The app supports four theme modes: follow system, light, dark, and
 * dark-when-battery-saver-is-on. AppCompatDelegate applies the choice to
 * every activity on any API level, which matters because the S6 (Android 7)
 * has no system dark mode at all.
 */
public class ThemeUtils {

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_BATTERY = "battery";

    /** Applies the theme chosen in the settings to all AppCompat activities. */
    public static void applyTheme (Context context) {
        applyThemeMode(SharedPreferencesUtils.getThemeMode(context));
    }

    /** Applies a specific theme mode value ("system", "light", "dark", "battery"). */
    public static void applyThemeMode (String mode) {
        switch (mode) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case THEME_BATTERY:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /**
     * Whether the app should currently render in dark mode. This is the
     * resolved choice (follow-system already evaluated), and it is what
     * should drive things that don't go through AppCompat, like RemoteViews.
     */
    public static boolean isDark (Context context) {
        String mode = SharedPreferencesUtils.getThemeMode(context);

        if (THEME_DARK.equals(mode))
            return true;
        if (THEME_LIGHT.equals(mode))
            return false;
        if (THEME_BATTERY.equals(mode)) {
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        }

        return isSystemDark(context);
    }

    /** Whether the SYSTEM is currently in dark mode (only ever true on API 29+). */
    public static boolean isSystemDark (Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * A context whose resources resolve -night values according to the given
     * flag instead of the system setting. Used to resolve color resources
     * for the notification at runtime.
     */
    public static Context nightConfigured (Context context, boolean dark) {
        if (dark == isSystemDark(context))
            return context;

        Configuration cfg = new Configuration(context.getResources().getConfiguration());
        cfg.uiMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(cfg);
    }

}
