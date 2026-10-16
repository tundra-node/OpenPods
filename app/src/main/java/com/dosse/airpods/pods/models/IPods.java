package com.dosse.airpods.pods.models;

public interface IPods {

    String getModel ();

    boolean isSingle ();

    boolean isDisconnected ();

    String parseStatusForLogger ();

    /**
     * Overrides the battery information with exact values received over AACP.
     * Components not reported keep their previous values.
     *
     * @param leftPercent  exact charge 0-100, or -1 if disconnected,
     *                     or {@link Integer#MIN_VALUE} if not reported
     * @param leftCharging whether the left pod is charging
     * @param rightPercent exact charge 0-100, or -1 if disconnected,
     *                     or {@link Integer#MIN_VALUE} if not reported
     * @param rightCharging whether the right pod is charging
     * @param casePercent  exact charge 0-100, or -1 if disconnected,
     *                     or {@link Integer#MIN_VALUE} if not reported
     * @param caseCharging whether the case is charging
     */
    default void applyExactBattery (int leftPercent, boolean leftCharging, int rightPercent, boolean rightCharging, int casePercent, boolean caseCharging) {
    }

}