package com.dosse.airpods;

import android.app.Application;

import com.dosse.airpods.utils.ThemeUtils;

/**
 * Applies the user-selected theme at process start.
 * <p>
 * Android 7 (the S6) has no system-wide dark mode, so on that device the
 * values-night resources can only ever be used if the app opts into dark
 * mode itself. AppCompatDelegate applies the choice to all activities.
 */
public class OpenPods extends Application {

    @Override
    public void onCreate () {
        super.onCreate();
        ThemeUtils.applyTheme(this);
    }

}
