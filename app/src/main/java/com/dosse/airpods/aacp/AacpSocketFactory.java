package com.dosse.airpods.aacp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;

import com.dosse.airpods.utils.Logger;

import java.lang.reflect.Constructor;

/**
 * Creates an insecure L2CAP connection-oriented channel socket to an
 * accessory.
 * <p>
 * On Android 10 (API 29) and later this is public API. Below that the only
 * way is to reflect into the hidden {@link BluetoothSocket} constructors,
 * which is unrestricted before Android 9 (P). The Galaxy S6 runs Android 7,
 * so the reflection path is the one that matters there.
 * <p>
 * The list of constructor signatures is the same approach used by LibrePods.
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

        Logger.debug("AACP: creating L2CAP socket via reflection (psm " + psm + ")");
        return createViaReflection(device, psm);
    }

    private static BluetoothSocket createViaReflection (BluetoothDevice device, int psm) throws Exception {
        ParcelUuid uuid = ParcelUuid.fromString(AacpConstants.AACP_UUID);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        // Signature candidates, from newest to oldest. Each entry is
        // {parameterTypes, argumentValues} with a placeholder resolved to the
        // actual device/adapter.
        Object[][] candidates = {
                {new Class[] {BluetoothAdapter.class, BluetoothDevice.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class},
                        new Object[] {adapter, device, SOCKET_TYPE_L2CAP, true, true, psm, uuid}},
                {new Class[] {BluetoothDevice.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class},
                        new Object[] {device, SOCKET_TYPE_L2CAP, true, true, psm, uuid}},
                {new Class[] {BluetoothDevice.class, int.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class},
                        new Object[] {device, SOCKET_TYPE_L2CAP, -1, true, true, psm, uuid}},
                {new Class[] {int.class, int.class, boolean.class, boolean.class, BluetoothDevice.class, int.class, ParcelUuid.class},
                        new Object[] {SOCKET_TYPE_L2CAP, -1, true, true, device, psm, uuid}},
                {new Class[] {int.class, boolean.class, boolean.class, BluetoothDevice.class, int.class, ParcelUuid.class},
                        new Object[] {SOCKET_TYPE_L2CAP, true, true, device, psm, uuid}}
        };

        Exception lastException = null;

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
