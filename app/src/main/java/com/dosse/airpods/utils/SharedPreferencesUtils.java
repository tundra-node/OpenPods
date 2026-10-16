package com.dosse.airpods.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public class SharedPreferencesUtils {

    public static boolean isSavingBattery (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("batterySaver", false);
    }

    public static boolean isPausingOnRemoval (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("pauseOnRemoval", true);
    }

    public static boolean isResumingOnInsertion (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("resumeOnInsertion", false);
    }

    public static boolean isEnforcingPause (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("enforcePause", true);
    }

    public static String getThemeMode (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString("theme", "system");
    }

    public static boolean isShowingConnectCard (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("showConnectCard", true);
    }

    public static boolean isPlayingSoundOnConnect (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("connectSound", false);
    }

    public static boolean isShowingLowBatteryAlerts (Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("lowBatteryAlerts", true);
    }

}
