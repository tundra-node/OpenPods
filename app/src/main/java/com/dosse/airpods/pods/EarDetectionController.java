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
 * <p>
 * A new state is not acted upon the instant a beacon reports it: it has to
 * hold for the user-configured confirmation delay first. Beacons (and AACP ear
 * packets) are noisy, and a pod that is only bumped or briefly adjusted
 * reports an ear transition that is over by the next packet. Waiting makes
 * sure that the pod genuinely is in or out before the music is interrupted.
 */
public class EarDetectionController {

    private static final long ENFORCE_POLL_MS = 1000L;

    /** Which source currently drives the in-ear state. */
    private enum Source {NONE, BEACON, AACP}

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Source source = Source.NONE;

    // Beacon path: per-pod attribution is available (with the usual caveat
    // that the beacon's left/right order can be flipped). lastLeft/lastRight
    // are the most recent readings, leftInEar/rightInEar the confirmed ones.
    private Boolean lastLeft = null, lastRight = null;
    private Boolean leftInEar = null, rightInEar = null;
    private boolean lastLeftConnected = false, lastRightConnected = false;
    private boolean pausedWithLeft = false, pausedWithRight = false;
    private boolean removedLeftOut = false, removedRightOut = false;

    // AACP path: only a count of pods in the ears is reliable, so the state
    // machine works on counts instead of per-pod attribution.
    private Integer lastEarCount = null;
    private Integer earCount = null;
    private int expectedEarCount = 0;
    private boolean countRemovedOut = false;

    // A change that is waiting to be confirmed, and whether that change takes
    // a pod out of an ear. The direction is remembered so that a removal can
    // still be applied if the connection drops before it is confirmed.
    private boolean settling = false;
    private boolean settlingRemoval = false;

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

    /** Fires once a pending in-ear change has held long enough to be trusted. */
    private final Runnable settleRunnable = new Runnable () {
        @Override
        public void run () {
            settling = false;
            settlingRemoval = false;

            try {
                if (source == Source.BEACON) applyBeaconState(appContext);
                else if (source == Source.AACP) applyEarCount(appContext);
            } catch (Throwable t) {
                Logger.error(t);
            }
        }
    };

    public void onStatus (PodsStatus newStatus, Context context) {
        try {
            appContext = context.getApplicationContext();

            if (newStatus == null || newStatus == PodsStatus.DISCONNECTED ||
                    newStatus.getAirpods() == null || newStatus.getAirpods().isSingle()) {
                // No valid status: connection is gone. A removal that was still
                // waiting to be confirmed was real though, so apply it before
                // forgetting everything, otherwise the music keeps playing with
                // a pod out of an ear.
                reset(settling && settlingRemoval);
                return;
            }

            // AACP (if it is connected) provides faster and more accurate ear
            // data, so when the beacon comes back after an AACP session the
            // state must not be carried over.
            if (source == Source.AACP) {
                reset(false);
            }
            source = Source.BEACON;

            RegularPods pods = (RegularPods) newStatus.getAirpods();
            boolean nowLeft = pods.getPod(RegularPods.LEFT).isInEar();
            boolean nowRight = pods.getPod(RegularPods.RIGHT).isInEar();
            lastLeftConnected = pods.getPod(RegularPods.LEFT).isConnected();
            lastRightConnected = pods.getPod(RegularPods.RIGHT).isConnected();

            if (leftInEar == null) {
                // First beacon after connecting: adopt the state silently,
                // there is no transition to detect yet
                leftInEar = lastLeft = nowLeft;
                rightInEar = lastRight = nowRight;
                return;
            }

            if (lastLeft == nowLeft && lastRight == nowRight) return; // nothing new

            lastLeft = nowLeft;
            lastRight = nowRight;

            // Does the new reading take a pod out of an ear?
            scheduleSettle(context, (leftInEar && !nowLeft) || (rightInEar && !nowRight));
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
                reset(false);
                source = Source.AACP;
            }

            if (earCount == null) {
                // First reading after connecting, no transition to detect yet
                earCount = lastEarCount = inEarCount;
                return;
            }

            if (lastEarCount != null && lastEarCount == inEarCount) return; // nothing new

            lastEarCount = inEarCount;

            // Does the new reading take a pod out of an ear?
            scheduleSettle(context, inEarCount < earCount);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    /**
     * Arms the confirmation delay for the change that was just observed. The
     * change is only applied once it has held for the whole delay, so a state
     * that flips back and forth never reaches the media controls.
     */
    private void scheduleSettle (Context context, boolean removal) {
        settling = true;
        settlingRemoval = removal;
        handler.removeCallbacks(settleRunnable);

        long delay = SharedPreferencesUtils.getEarConfirmationDelay(context) * 1000L;
        if (delay <= 0) {
            settleRunnable.run();
        } else {
            Logger.debug("EAR DETECTION: confirming ear change in " + delay + "ms");
            handler.postDelayed(settleRunnable, delay);
        }
    }

    /** Confirmed beacon state changed: pause or resume if needed. */
    private void applyBeaconState (Context context) {
        if (lastLeft == null || lastRight == null) return;

        Boolean prevLeft = leftInEar, prevRight = rightInEar;
        leftInEar = lastLeft;
        rightInEar = lastRight;

        // A removed pod stops counting once it's back in an ear, or once
        // it's disconnected entirely (put away in the case)
        if (removedLeftOut && (lastLeft || !lastLeftConnected)) removedLeftOut = false;
        if (removedRightOut && (lastRight || !lastRightConnected)) removedRightOut = false;

        boolean leftRemoved = prevLeft != null && prevLeft && !lastLeft;
        boolean rightRemoved = prevRight != null && prevRight && !lastRight;

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
        } else if (pausedByUs && (!pausedWithLeft || lastLeft) && (!pausedWithRight || lastRight)) {
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
    }

    /** Confirmed in-ear count changed: pause or resume if needed. */
    private void applyEarCount (Context context) {
        if (earCount == null || lastEarCount == null) return;

        Integer prev = earCount;
        int now = lastEarCount;
        earCount = now;

        if (now < prev) {
            Logger.debug("EAR DETECTION: pod removed from ear (AACP)");

            if (SharedPreferencesUtils.isPausingOnRemoval(context)) {
                Logger.debug("EAR DETECTION: pausing media");
                MediaControl.pause(context);
            }

            pausedByUs = true;
            expectedEarCount = prev;
            countRemovedOut = true;
        } else if (pausedByUs && now >= expectedEarCount) {
            pausedByUs = false;
            countRemovedOut = false;
            expectedEarCount = 0;

            if (SharedPreferencesUtils.isResumingOnInsertion(context)) {
                Logger.debug("EAR DETECTION: resuming media");
                MediaControl.play(context);
            }
        }

        updateEnforcement();
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

    /**
     * @param applyPendingRemoval true to apply an unconfirmed removal first, so
     *                            a pod that was observed leaving the ear still
     *                            pauses playback even if the connection drops
     *                            before the confirmation delay has elapsed.
     */
    private void reset (boolean applyPendingRemoval) {
        if (applyPendingRemoval && settling) {
            settleRunnable.run();
        }

        settling = false;
        settlingRemoval = false;
        lastLeft = null;
        lastRight = null;
        leftInEar = null;
        rightInEar = null;
        lastLeftConnected = false;
        lastRightConnected = false;
        lastEarCount = null;
        earCount = null;
        expectedEarCount = 0;
        source = Source.NONE;
        pausedByUs = false;
        removedLeftOut = false;
        removedRightOut = false;
        countRemovedOut = false;
        enforcing = false;
        handler.removeCallbacks(enforceRunnable);
        handler.removeCallbacks(settleRunnable);
    }

}
