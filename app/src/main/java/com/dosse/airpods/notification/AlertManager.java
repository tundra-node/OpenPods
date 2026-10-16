package com.dosse.airpods.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.Pod;
import com.dosse.airpods.pods.PodsStatus;
import com.dosse.airpods.pods.PodsStatusRepository;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.pods.models.SinglePods;
import com.dosse.airpods.ui.MainActivity;
import com.dosse.airpods.utils.Logger;
import com.dosse.airpods.utils.SharedPreferencesUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a notification when a battery drops to 20% or 10% (like iOS does),
 * and plays a short chime when the AirPods connect.
 * <p>
 * An alert is shown once per threshold per session; the flags reset when the
 * component starts charging or the AirPods disconnect.
 */
public class AlertManager implements PodsStatusRepository.Listener {

    private static final String ALERT_CHANNEL_ID = "AirPodsAlerts";
    private static final int ALERT_NOTIFICATION_ID = 2;

    private static final int THRESHOLD_HIGH = 20;
    private static final int THRESHOLD_LOW = 10;

    /** Per-component alert bookkeeping. */
    private static class ComponentState {
        boolean connected;
        boolean charging;
        boolean firedHigh, firedLow;
    }

    private final ComponentState left = new ComponentState();
    private final ComponentState right = new ComponentState();
    private final ComponentState single = new ComponentState();
    private final ComponentState caseState = new ComponentState();

    private final Context context;

    public AlertManager (Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onConnectionChanged (boolean connected) {
        if (!connected) {
            // New session: allow alerts again when the pods reconnect
            resetAll();
        }
    }

    @Override
    public void onStatusChanged (PodsStatus status) {
        try {
            if (!SharedPreferencesUtils.isShowingLowBatteryAlerts(context)) {
                return;
            }

            IPods pods = status.getAirpods();
            if (pods == null) {
                return;
            }

            List<String> alerts = new ArrayList<>();

            if (pods instanceof RegularPods) {
                RegularPods r = (RegularPods)pods;
                process(left, r.getPod(RegularPods.LEFT), context.getString(R.string.alert_left), alerts);
                process(right, r.getPod(RegularPods.RIGHT), context.getString(R.string.alert_right), alerts);
                process(caseState, r.getPod(RegularPods.CASE), context.getString(R.string.alert_case), alerts);
            } else if (pods instanceof SinglePods) {
                process(single, ((SinglePods)pods).getPod(), context.getString(R.string.alert_single), alerts);
            }

            if (!alerts.isEmpty()) {
                notifyAlert(alerts);
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    /**
     * Updates the state of a component, firing an alert if a threshold was
     * just crossed (or already below on first reading of the session).
     */
    private void process (ComponentState state, Pod pod, String label, List<String> alerts) {
        if (pod == null) {
            return;
        }

        int percent = pod.getBatteryPercent();
        boolean charging = !pod.isDisconnected() && pod.isCharging();

        if (pod.isDisconnected()) {
            // Battery unreadable right now (case closed); keep the fired
            // flags so we don't re-alert on every read of the same level
            state.connected = false;
            return;
        }

        state.connected = true;

        if (charging) {
            // Charging resets the thresholds so a later drain alerts again
            state.charging = true;
            state.firedHigh = false;
            state.firedLow = false;
            return;
        }

        state.charging = false;

        if (percent <= THRESHOLD_LOW && !state.firedLow) {
            state.firedLow = true;
            state.firedHigh = true;
            alerts.add(label + ": " + percent + "%");
        } else if (percent <= THRESHOLD_HIGH && !state.firedHigh) {
            state.firedHigh = true;
            alerts.add(label + ": " + percent + "%");
        }
    }

    private void resetAll () {
        for (ComponentState s : new ComponentState[] {left, right, single, caseState}) {
            s.connected = false;
            s.charging = false;
            s.firedHigh = false;
            s.firedLow = false;
        }

        try {
            NotificationManager nm = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(ALERT_NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
        }
    }

    private void notifyAlert (List<String> lines) {
        try {
            NotificationManager nm = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }

            NotificationCompat.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(ALERT_CHANNEL_ID, context.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
                builder = new NotificationCompat.Builder(context, ALERT_CHANNEL_ID);
            } else {
                builder = new NotificationCompat.Builder(context);
                builder.setDefaults(Notification.DEFAULT_SOUND);
            }

            Intent intent = new Intent(context, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

            builder.setSmallIcon(R.mipmap.notification_icon)
                    .setContentTitle(context.getString(R.string.alert_title))
                    .setContentText(join(lines))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(join(lines)))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            nm.notify(ALERT_NOTIFICATION_ID, builder.build());
            Logger.debug("ALERT: low battery: " + join(lines));
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    private static String join (List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

}
