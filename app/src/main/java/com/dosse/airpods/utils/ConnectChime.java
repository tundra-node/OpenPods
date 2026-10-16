package com.dosse.airpods.utils;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

import com.dosse.airpods.pods.PodsStatusRepository;

/**
 * Plays a short chime when the AirPods connect, if enabled in the settings.
 * <p>
 * Connection events during the first seconds after startup are ignored,
 * because the service is often started while the pods are already connected
 * and that should not chime.
 */
public class ConnectChime implements PodsStatusRepository.Listener {

    private static final long GRACE_PERIOD_MS = 5000;

    private final Context context;
    private final long createdAt = System.currentTimeMillis();

    public ConnectChime (Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onConnectionChanged (boolean connected) {
        if (!connected)
            return;
        if (System.currentTimeMillis() - createdAt < GRACE_PERIOD_MS)
            return;
        if (!SharedPreferencesUtils.isPlayingSoundOnConnect(context))
            return;

        play();
    }

    private void play () {
        try {
            final ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 200);
            new Handler(Looper.getMainLooper()).postDelayed(toneGenerator::release, 500);
            Logger.debug("CONNECT CHIME");
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

}
