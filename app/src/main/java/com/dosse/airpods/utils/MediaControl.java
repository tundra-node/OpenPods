package com.dosse.airpods.utils;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

/**
 * Sends media key events to the active media session.
 * Works without any permission from API 21 onwards, so it's fine on the
 * old devices this fork targets.
 */
public class MediaControl {

    public static void pause (Context context) {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    public static void play (Context context) {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    private static void sendMediaKey (Context context, int keycode) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keycode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keycode));
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

}
