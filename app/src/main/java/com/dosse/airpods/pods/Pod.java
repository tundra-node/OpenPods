package com.dosse.airpods.pods;

import android.view.View;

import com.dosse.airpods.R;

public class Pod {

    public static final int DISCONNECTED_STATUS = 15;
    public static final int MAX_CONNECTED_STATUS = 10;
    public static final int LOW_BATTERY_STATUS = 1;

    private int status;
    private boolean charging;
    private final boolean inEar;
    /** Exact charge in percent from AACP (0-100), or -1 when unknown. */
    private int exactPercent = -1;

    public Pod (int status, boolean charging, boolean inEar) {
        this.status = status;
        this.charging = charging;
        this.inEar = inEar;
    }

    public int getStatus () {
        return status;
    }

    /**
     * Replaces the battery information with exact values from AACP.
     *
     * @param status       0-10 (5% steps) or {@link #DISCONNECTED_STATUS}
     * @param charging     whether the component is charging
     * @param exactPercent exact charge 0-100, or -1 to fall back to the 5% steps
     */
    public void setBattery (int status, boolean charging, int exactPercent) {
        this.status = status;
        this.charging = charging;
        this.exactPercent = (exactPercent >= 0 && exactPercent <= 100) ? exactPercent : -1;
    }

    public String parseStatus () {
        if (exactPercent >= 0)
            return exactPercent + "%";
        return (status == MAX_CONNECTED_STATUS) ? "100%" : ((status < MAX_CONNECTED_STATUS) ? ((status * 10 + 5) + "%") : "");
    }

    /**
     * Battery charge in percent (0-100). Uses the exact AACP value when
     * available, otherwise the 5% step from the beacon. Returns -1 if the
     * battery level is unknown/disconnected.
     */
    public int getBatteryPercent () {
        if (isDisconnected())
            return -1;
        if (exactPercent >= 0)
            return exactPercent;
        return (status == MAX_CONNECTED_STATUS) ? 100 : (status * 10 + 5);
    }

    public boolean isCharging () {
        return charging;
    }

    public boolean isInEar () {
        return inEar;
    }

    public boolean isConnected () {
        return status <= MAX_CONNECTED_STATUS;
    }

    public boolean isDisconnected () {
        return status == DISCONNECTED_STATUS;
    }

    public boolean isLowBattery () {
        return isConnected() && getBatteryPercent() <= 15;
    }

    public int inEarVisibility () {
        return inEar ? View.VISIBLE : View.INVISIBLE;
    }

    public int batImgVisibility () {
        return (charging && isConnected() || isLowBattery()) ? View.VISIBLE : View.GONE;
    }

    public int batImgSrcId () {
        return charging ? R.drawable.ic_battery_charging_full_green_24dp : R.drawable.ic_battery_alert_red_24dp;
    }

}