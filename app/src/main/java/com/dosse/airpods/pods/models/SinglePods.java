package com.dosse.airpods.pods.models;

import com.dosse.airpods.pods.Pod;

import java.util.Locale;

public abstract class SinglePods implements IPods {

    public abstract int getDrawable ();

    private final Pod pod;

    public SinglePods (Pod pod) {
        this.pod = pod;
    }

    public Pod getPod () {
        return pod;
    }

    public String getParsedStatus () {
        return pod.parseStatus();
    }

    public int getBatImgVisibility () {
        return pod.batImgVisibility();
    }

    public int getBatImgSrcId () {
        return pod.batImgSrcId();
    }

    @Override
    public boolean isSingle () {
        return true;
    }

    @Override
    public boolean isDisconnected () {
        return pod.isDisconnected();
    }

    @Override
    public void applyExactBattery (int leftPercent, boolean leftCharging, int rightPercent, boolean rightCharging, int casePercent, boolean caseCharging) {
        // Single-pod models report one battery; take whichever component is present
        int percent = Integer.MIN_VALUE;
        boolean charging = false;

        if (leftPercent != Integer.MIN_VALUE) {
            percent = leftPercent;
            charging = leftCharging;
        } else if (rightPercent != Integer.MIN_VALUE) {
            percent = rightPercent;
            charging = rightCharging;
        } else if (casePercent != Integer.MIN_VALUE) {
            percent = casePercent;
            charging = caseCharging;
        }

        if (percent == Integer.MIN_VALUE)
            return;

        if (percent < 0) {
            pod.setBattery(Pod.DISCONNECTED_STATUS, false, -1);
            return;
        }

        int step = Math.min(percent / 10, Pod.MAX_CONNECTED_STATUS);
        pod.setBattery(step, charging, percent);
    }

    @Override
    public String parseStatusForLogger () {
        return String.format(Locale.getDefault(), "Battery: %d%s Model: %s", pod.getStatus(), pod.isCharging() ? "+" : "", getModel());
    }

}