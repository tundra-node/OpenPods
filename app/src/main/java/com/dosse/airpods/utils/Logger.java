package com.dosse.airpods.utils;

import android.util.Log;

/**
 * S6 fork: logging stays on in release builds too, so ear detection can be
 * debugged on the phone directly (adb logcat -s AirPods)
 */
public class Logger {

    private static final boolean ENABLE_LOGGING = true;
    public static final String TAG = "AirPods";

    public static void debug (String msg) {
        if (ENABLE_LOGGING) Log.d(TAG, msg);
    }

    public static void error (Throwable t) {
        if (ENABLE_LOGGING) Log.e(TAG, "ERROR", t);
    }

}
