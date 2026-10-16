package com.dosse.airpods.aacp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;

import com.dosse.airpods.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Creates an <b>insecure</b> L2CAP connection-oriented channel socket to an
 * accessory (no authentication, no encryption).
 * <p>
 * On Android 10 (API 29) and later this is public API. Below that the only
 * way is to go through hidden API: either
 * {@code BluetoothDevice.createInsecureL2capSocket(int)} (present alongside
 * the public {@code createInsecureL2capChannel} from API 23 on) or, failing
 * that, the hidden {@link BluetoothSocket} constructors. The Galaxy S6 runs
 * Android 7, so the hidden paths are the ones that matter there.
 * <p>
 * The important detail, and the reason the connection used to hang, is that
 * the channel has to be created <b>insecure</b>. The platform's own insecure
 * path is
 * <pre>new BluetoothSocket(SOCKET_CHANNEL, -1, false, false, device, psm, null)</pre>
 * Passing {@code auth = true, encrypt = true} instead asks the Bluetooth
 * stack for an authenticated and encrypted link, which makes it raise
 * {@code btm_sec_l2cap_access_req} for the PSM. The AirPods never complete
 * that request, so {@code connect()} blocked until our own timeout closed the
 * socket. A non-null UUID is also avoided: the platform passes {@code null}
 * when an explicit PSM is given.
 */
public class AacpSocketFactory {

    private static final int SOCKET_TYPE_L2CAP = 3;

    private AacpSocketFactory () {
    }

    @SuppressLint("MissingPermission")
    public static BluetoothSocket createInsecureL2capSocket (BluetoothDevice device, int psm) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Logger.debug("AACP: creating L2CAP socket via public API (psm " + psm + ")");
            return device.createInsecureL2capChannel(psm);
        }

        Logger.debug("AACP: creating insecure L2CAP socket via hidden API (psm " + psm + ")");
        return createViaHiddenApi(device, psm);
    }

    private static BluetoothSocket createViaHiddenApi (BluetoothDevice device, int psm) throws Exception {
        Exception lastException = null;

        // 1. BluetoothDevice.createInsecureL2capSocket(int) - API 23..28.
        //    This is the exact counterpart of the public
        //    createInsecureL2capChannel(int) that API 29 exposes, so it is
        //    preferred over guessing a constructor.
        try {
            Method method = BluetoothDevice.class.getDeclaredMethod("createInsecureL2capSocket", int.class);
            method.setAccessible(true);
            BluetoothSocket socket = (BluetoothSocket)method.invoke(device, psm);
            Logger.debug("AACP: BluetoothDevice.createInsecureL2capSocket(" + psm + ") succeeded");
            return socket;
        } catch (Exception e) {
            lastException = e;
            Logger.debug("AACP: createInsecureL2capSocket(" + psm + ") unavailable: " + e);
        }

        // 2. Hidden constructors, insecure first, secure only as a last
        //    resort (it is the combination that used to hang, so it is worth
        //    still trying if the accessory turns out to want a secure link).
        //    Each entry is {parameterTypes, argumentValues}.
        Object[][] candidates = {
                // API 23..28: BluetoothSocket(type, fd, auth, encrypt, device, port, uuid)
                {new Class[] {int.class, int.class, boolean.class, boolean.class, BluetoothDevice.class, int.class, ParcelUuid.class},
                        new Object[] {SOCKET_TYPE_L2CAP, -1, false, false, device, psm, null}},
                // API 17..22: BluetoothSocket(device, type, auth, encrypt, port, uuid)
                {new Class[] {BluetoothDevice.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class},
                        new Object[] {device, SOCKET_TYPE_L2CAP, false, false, psm, null}},
                // Secure variants, last resort.
                {new Class[] {int.class, int.class, boolean.class, boolean.class, BluetoothDevice.class, int.class, ParcelUuid.class},
                        new Object[] {SOCKET_TYPE_L2CAP, -1, true, true, device, psm, ParcelUuid.fromString(AacpConstants.AACP_UUID)}},
                {new Class[] {BluetoothDevice.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class},
                        new Object[] {device, SOCKET_TYPE_L2CAP, true, true, psm, ParcelUuid.fromString(AacpConstants.AACP_UUID)}}
        };

        for (int i = 0; i < candidates.length; i++) {
            try {
                Class<?>[] parameterTypes = (Class<?>[])candidates[i][0];
                Object[] args = (Object[])candidates[i][1];

                Constructor<BluetoothSocket> constructor = BluetoothSocket.class.getDeclaredConstructor(parameterTypes);
                constructor.setAccessible(true);

                Logger.debug("AACP: socket constructor #" + (i + 1) + " succeeded");
                return constructor.newInstance(args);
            } catch (Exception e) {
                lastException = e;
                Logger.debug("AACP: socket constructor #" + (i + 1) + " unavailable: " + e.getMessage());
            }
        }

        throw lastException != null ? lastException : new IllegalStateException("No usable BluetoothSocket constructor found");
    }

}
