package com.dosse.airpods.pods;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.utils.Logger;
import com.dosse.airpods.utils.MediaControl;
import com.dosse.airpods.utils.SharedPreferencesUtils;

/**
 * Watches the in-ear state of both pods as it changes between beacons, and
 * controls media playback accordingly:
 * - when a pod that was in an ear gets taken out, send a media pause
 * - optionally, when the pods that were in ears are back in, send a media play
 * - optionally, while a removed pod is still out of an ear, keep media paused:
 *   if playback is started anyway, pause it again until the pod is back in
 * Only a pause sent by us is ever undone by the resume, so manually pausing
 * is never affected. In-ear data only exists for dual-pod models, single-pod
 * models are ignored.
 */
public class EarDetectionController {

    private static final long ENFORCE_POLL_MS = 1000L;

    /** Which source currently drives the in-ear state. */
    private enum Source {NONE, BEACON, AACP}

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Source source = Source.NONE;

    // Beacon path: per-pod attribution is available (with the usual caveat
    // that the beacon's left/right order can be flipped).
    private Boolean leftInEar = null, rightInEar = null;
    private boolean pausedWithLeft = false, pausedWithRight = false;
    private boolean removedLeftOut = false, removedRightOut = false;

    // AACP path: only a count of pods in the ears is reliable, so the state
    // machine works on counts instead of per-pod attribution.
    private Integer lastEarCount = null;
    private int expectedEarCount = 0;
    private boolean countRemovedOut = false;

    private boolean pausedByUs = false;
    private boolean enforcing = false;
    private Context appContext = null;

    private final Runnable enforceRunnable = new Runnable () {
        @Override
        public void run () {
            if (!enforcing || appContext == null) return;

            try {
                AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am != null && am.isMusicActive()) {
                    Logger.debug("EAR DETECTION: music started while a pod is out, pausing");
                    MediaControl.pause(appContext);
                }
            } catch (Throwable t) {
                Logger.error(t);
            }

            handler.postDelayed(this, ENFORCE_POLL_MS);
        }
    };

    public void onStatus (PodsStatus newStatus, Context context) {
        try {
            appContext = context.getApplicationContext();

            if (newStatus == null || newStatus == PodsStatus.DISCONNECTED ||
                    newStatus.getAirpods() == null || newStatus.getAirpods().isSingle()) {
                // No valid status: connection is gone, forget everything
                reset();
                return;
            }

            // AACP (if it is connected) provides faster and more accurate ear
            // data, so when the beacon comes back after an AACP session the
            // state must not be carried over.
            if (source == Source.AACP) {
                reset();
            }
            source = Source.BEACON;

            RegularPods pods = (RegularPods) newStatus.getAirpods();
            boolean nowLeft = pods.getPod(RegularPods.LEFT).isInEar();
            boolean nowRight = pods.getPod(RegularPods.RIGHT).isInEar();
            boolean leftConnected = pods.getPod(RegularPods.LEFT).isConnected();
            boolean rightConnected = pods.getPod(RegularPods.RIGHT).isConnected();

            Boolean prevLeft = leftInEar, prevRight = rightInEar;
            leftInEar = nowLeft;
            rightInEar = nowRight;

            // A removed pod stops counting once it's back in an ear, or once
            // it's disconnected entirely (put away in the case)
            if (removedLeftOut && (nowLeft || !leftConnected)) removedLeftOut = false;
            if (removedRightOut && (nowRight || !rightConnected)) removedRightOut = false;

            if (prevLeft == null) return; // first beacon after connecting, no transition to detect

            boolean leftRemoved = prevLeft && !nowLeft;
            boolean rightRemoved = prevRight && !nowRight;

            if (leftRemoved || rightRemoved) {
                // A pod that was in an ear is no longer in it
                Logger.debug("EAR DETECTION: pod removed from ear");

                if (SharedPreferencesUtils.isPausingOnRemoval(context)) {
                    Logger.debug("EAR DETECTION: pausing media");
                    MediaControl.pause(context);
                }

                pausedByUs = true;
                pausedWithLeft = prevLeft;
                pausedWithRight = prevRight;
                removedLeftOut = leftRemoved;
                removedRightOut = rightRemoved;
            } else if (pausedByUs && (!pausedWithLeft || nowLeft) && (!pausedWithRight || nowRight)) {
                // Every pod that was in an ear at pause time is back in an ear
                pausedByUs = false;
                removedLeftOut = false;
                removedRightOut = false;

                if (SharedPreferencesUtils.isResumingOnInsertion(context)) {
                    Logger.debug("EAR DETECTION: resuming media");
                    MediaControl.play(context);
                }
            }

            updateEnforcement();
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    /**
     * Count-based ear detection, fed by AACP. AACP's ear detection packet does
     * not reliably identify which pod is which (the primary/secondary order
     * swaps), so instead of tracking a pod we track how many pods are in an
     * ear. The pause/resume/enforce behavior is the same as the beacon path:
     * a drop in the count pauses, a return to the previous count resumes, and
     * while the count is still low playback is kept paused.
     *
     * @param inEarCount number of pods currently in an ear (0-2)
     */
    public void onEarCount (int inEarCount, Context context) {
        try {
            appContext = context.getApplicationContext();

            if (source != Source.AACP) {
                // Switching from the beacon path: do not carry its state over
                reset();
                source = Source.AACP;
            }

            Integer prev = lastEarCount;
            lastEarCount = inEarCount;

            if (prev == null)
                return; // first reading after connecting, no transition yet

            if (inEarCount < prev) {
                Logger.debug("EAR DETECTION: pod removed from ear (AACP)");

                if (SharedPreferencesUtils.isPausingOnRemoval(context)) {
                    Logger.debug("EAR DETECTION: pausing media");
                    MediaControl.pause(context);
                }

                pausedByUs = true;
                expectedEarCount = prev;
                countRemovedOut = true;
            } else if (pausedByUs && inEarCount >= expectedEarCount) {
                pausedByUs = false;
                countRemovedOut = false;
                expectedEarCount = 0;

                if (SharedPreferencesUtils.isResumingOnInsertion(context)) {
                    Logger.debug("EAR DETECTION: resuming media");
                    MediaControl.play(context);
                }
            }

            updateEnforcement();
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    /**
     * While a pod that was removed from an ear is still out of it (and still
     * connected), keep media paused: if playback is started by the user or
     * another app, send another pause. This is the closest an Android app can
     * get to "the AirPod out of your ear doesn't play music until it's back in".
     */
    private void updateEnforcement () {
        boolean shouldEnforce = pausedByUs &&
                (removedLeftOut || removedRightOut || countRemovedOut) &&
                SharedPreferencesUtils.isEnforcingPause(appContext);

        if (shouldEnforce && !enforcing) {
            Logger.debug("EAR DETECTION: enforcing pause while a pod is out");
            enforcing = true;
            handler.removeCallbacks(enforceRunnable);
            handler.postDelayed(enforceRunnable, ENFORCE_POLL_MS);
        } else if (!shouldEnforce && enforcing) {
            enforcing = false;
            handler.removeCallbacks(enforceRunnable);
        }
    }

    private void reset () {
        leftInEar = null;
        rightInEar = null;
        lastEarCount = null;
        expectedEarCount = 0;
        source = Source.NONE;
        pausedByUs = false;
        removedLeftOut = false;
        removedRightOut = false;
        countRemovedOut = false;
        enforcing = false;
        handler.removeCallbacks(enforceRunnable);
    }

}
