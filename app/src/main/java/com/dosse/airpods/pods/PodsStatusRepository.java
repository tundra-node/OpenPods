package com.dosse.airpods.pods;

import android.os.Handler;
import android.os.Looper;

import com.dosse.airpods.utils.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Single source of truth for the current AirPods status and connection state.
 * <p>
 * The BLE scanner and the AACP connection update it; the notification
 * thread, the UI dashboard, the connect card and the low battery alerts
 * read from it. Everything runs in one process.
 */
public class PodsStatusRepository {

    public interface Listener {
        /** Called on the main thread when the status changes. */
        default void onStatusChanged (PodsStatus status) {
        }

        /** Called on the main thread when the AirPods connect or disconnect. */
        default void onConnectionChanged (boolean connected) {
        }
    }

    private static final PodsStatusRepository instance = new PodsStatusRepository();

    public static PodsStatusRepository getInstance () {
        return instance;
    }

    private final Object lock = new Object();
    private final Set<Listener> listeners = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PodsStatus status = PodsStatus.DISCONNECTED;
    private boolean maybeConnected = false;

    public PodsStatus getStatus () {
        synchronized (lock) {
            return status;
        }
    }

    public boolean isMaybeConnected () {
        synchronized (lock) {
            return maybeConnected;
        }
    }

    public void setStatus (PodsStatus newStatus) {
        if (newStatus == null)
            return;

        PodsStatus old;
        synchronized (lock) {
            if (status == newStatus)
                return;
            old = status;
            status = newStatus;
        }

        Logger.debug("REPOSITORY: status updated");
        notifyStatusChanged(newStatus, old);
    }

    public void setMaybeConnected (boolean connected) {
        boolean changed;
        synchronized (lock) {
            changed = maybeConnected != connected;
            maybeConnected = connected;
        }

        if (changed) {
            Logger.debug("REPOSITORY: connected=" + connected);
            notifyConnectionChanged(connected);
        }
    }

    public void addListener (Listener listener) {
        if (listener == null)
            return;
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    public void removeListener (Listener listener) {
        if (listener == null)
            return;
        synchronized (lock) {
            listeners.remove(listener);
        }
    }

    private void notifyStatusChanged (final PodsStatus status, final PodsStatus old) {
        Set<Listener> snapshot;
        synchronized (lock) {
            snapshot = new HashSet<>(listeners);
        }

        mainHandler.post(() -> {
            for (Listener l : snapshot) {
                try {
                    l.onStatusChanged(status);
                } catch (Throwable t) {
                    Logger.error(t);
                }
            }
        });
    }

    private void notifyConnectionChanged (final boolean connected) {
        Set<Listener> snapshot;
        synchronized (lock) {
            snapshot = new HashSet<>(listeners);
        }

        mainHandler.post(() -> {
            for (Listener l : snapshot) {
                try {
                    l.onConnectionChanged(connected);
                } catch (Throwable t) {
                    Logger.error(t);
                }
            }
        });
    }

}
