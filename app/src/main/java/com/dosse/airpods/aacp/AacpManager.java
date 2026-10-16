package com.dosse.airpods.aacp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import com.dosse.airpods.utils.Logger;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Talks AACP to a connected pair of AirPods over an L2CAP channel.
 * <p>
 * The connection is best-effort: if the socket cannot be created or the
 * accessory refuses the channel, the app keeps working with beacon-only data
 * (see {@link Listener}). All socket work happens on a dedicated thread, and
 * callbacks are posted to the main thread.
 */
public class AacpManager {

    private static final long CONNECT_TIMEOUT_MS = 5000;
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final int MAX_CONNECT_ATTEMPTS = 4;
    private static final long[] RETRY_DELAYS_MS = {0, 2000, 5000, 10000};

    public interface Listener {
        default void onAacpConnected () {
        }

        default void onAacpDisconnected () {
        }

        default void onBattery (AacpPackets.BatteryInfo battery) {
        }

        default void onEarDetection (int[] earDetection) {
        }

        default void onNoiseControl (int mode) {
        }

        default void onConversationAwareness (boolean enabled) {
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object socketLock = new Object();

    private Listener listener;
    private BluetoothSocket socket;
    private OutputStream output;
    private Thread connectThread;
    private Thread readerThread;

    private volatile boolean connecting = false;
    private volatile boolean connected = false;
    private volatile boolean stopped = true;
    private BluetoothDevice device;

    private volatile int lastNoiseControlMode = -1;

    public void setListener (Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected () {
        return connected;
    }

    public int getLastNoiseControlMode () {
        return lastNoiseControlMode;
    }

    // ------------------------------------------------------------ connection

    public synchronized void connect (BluetoothDevice device) {
        if (device == null || connected || connecting)
            return;

        this.device = device;
        stopped = false;
        connecting = true;

        connectThread = new Thread(this::connectionLoop, "AacpConnect");
        connectThread.start();
    }

    public synchronized void disconnect () {
        stopped = true;
        connecting = false;
        closeSocket();
    }

    private void connectionLoop () {
        for (int attempt = 0; attempt < MAX_CONNECT_ATTEMPTS && !stopped && !connected; attempt++) {
            try {
                if (RETRY_DELAYS_MS[attempt] > 0)
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
            } catch (InterruptedException e) {
                return;
            }

            if (stopped || connected)
                return;

            Logger.debug("AACP: connecting, attempt " + (attempt + 1));

            if (tryConnect()) {
                connecting = false;
                startReader();
                return;
            }
        }

        connecting = false;

        if (!stopped)
            Logger.debug("AACP: giving up (beacon-only mode)");
    }

    @SuppressLint("MissingPermission")
    private boolean tryConnect () {
        BluetoothSocket newSocket = null;

        try {
            newSocket = AacpSocketFactory.createInsecureL2capSocket(device, AacpConstants.PSM);

            // Bound the blocking connect() call: close the socket from
            // another thread after the timeout and connect() will throw.
            final BluetoothSocket socketToClose = newSocket;
            mainHandler.postDelayed(() -> {
                try {
                    if (!socketToClose.isConnected())
                        socketToClose.close();
                } catch (Throwable ignored) {
                }
            }, CONNECT_TIMEOUT_MS);

            newSocket.connect();

            synchronized (socketLock) {
                socket = newSocket;
                output = newSocket.getOutputStream();
            }

            connected = true;

            // Handshake and subscribe to notifications. The sequence is sent
            // more than once because the accessory can be busy right after
            // connecting and drop the first packets.
            sendHandshakeSequence();

            Logger.debug("AACP: connected");
            postConnected();
            return true;
        } catch (Throwable t) {
            Logger.debug("AACP: connect failed: " + t);

            try {
                if (newSocket != null)
                    newSocket.close();
            } catch (Throwable ignored) {
            }

            return false;
        }
    }

    private void sendHandshakeSequence () {
        try {
            send(AacpPackets.handshake());
            send(AacpPackets.setFeatureFlags());
            send(AacpPackets.requestNotifications(true));
            send(AacpPackets.requestProximityKeys((byte)(0x01 + 0x04)));

            Thread.sleep(200);
            send(AacpPackets.setFeatureFlags());
            send(AacpPackets.requestNotifications(true));
            Thread.sleep(200);
            send(AacpPackets.handshake());
            send(AacpPackets.requestNotifications(true));
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    private void startReader () {
        readerThread = new Thread(this::readLoop, "AacpReader");
        readerThread.start();
    }

    private void readLoop () {
        byte[] buffer = new byte[1024];

        while (!stopped && connected) {
            try {
                InputStream in;
                synchronized (socketLock) {
                    in = socket != null ? socket.getInputStream() : null;
                }

                if (in == null)
                    break;

                int read = in.read(buffer);

                if (read < 0) {
                    Logger.debug("AACP: stream closed");
                    break;
                }

                if (read > 0)
                    dispatch(buffer, read);
            } catch (Throwable t) {
                if (!stopped)
                    Logger.debug("AACP: read failed: " + t);
                break;
            }
        }

        boolean wasConnected = connected;
        handleDisconnect();

        if (wasConnected && !stopped) {
            // Accessory dropped us; back off a little before trying again so a
            // flapping link cannot spin in a tight reconnect loop.
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }

            if (stopped)
                return;

            connecting = false;
            connect(device);
        }
    }

    /** Walks the buffer and handles every complete packet it contains. */
    private void dispatch (byte[] data, int length) {
        int offset = 0;

        while (offset + 6 <= length) {
            if (!AacpPackets.hasHeader(data, offset, length)) {
                Logger.debug("AACP: unexpected data: " + AacpPackets.toHex(slice(data, offset, length - offset)));
                return;
            }

            int packetLength = AacpPackets.packetLength(data, offset, length);

            if (packetLength < 0 || offset + packetLength > length) {
                // Unknown opcode or truncated packet; nothing more we can do
                Logger.debug("AACP: unrecognized packet (opcode 0x" + String.format("%02X", data[offset + 4]) + ")");
                return;
            }

            byte[] packet = slice(data, offset, packetLength);
            handlePacket(packet);
            offset += packetLength;
        }
    }

    private void handlePacket (byte[] packet) {
        int opcode = packet[4] & 0xFF;

        try {
            switch (opcode) {
                case AacpConstants.OP_BATTERY_INFO: {
                    AacpPackets.BatteryInfo battery = AacpPackets.parseBattery(packet);
                    Logger.debug("AACP: battery " + describe(battery));
                    postBattery(battery);
                    break;
                }
                case AacpConstants.OP_EAR_DETECTION: {
                    int[] ear = AacpPackets.parseEarDetection(packet);
                    Logger.debug("AACP: ear detection " + (ear != null ? ear[0] + "," + ear[1] : "?"));
                    postEarDetection(ear);
                    break;
                }
                case AacpConstants.OP_CONTROL_COMMAND: {
                    AacpPackets.ControlCommand command = AacpPackets.parseControlCommand(packet);

                    if (command != null && command.identifier == AacpConstants.CC_LISTENING_MODE) {
                        int mode = AacpPackets.noiseControlMode(command);
                        lastNoiseControlMode = mode;
                        Logger.debug("AACP: noise control mode " + mode);
                        postNoiseControl(mode);
                    }
                    break;
                }
                case AacpConstants.OP_CONVERSATION_AWARENESS: {
                    boolean enabled = AacpPackets.parseConversationAwareness(packet);
                    Logger.debug("AACP: conversation awareness " + enabled);
                    postConversationAwareness(enabled);
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // ---------------------------------------------------------------- output

    /** Sets the noise control mode ({@link AacpConstants} NOISE_*). */
    public boolean setNoiseControl (int mode) {
        if (mode < AacpConstants.NOISE_OFF || mode > AacpConstants.NOISE_ADAPTIVE)
            return false;

        lastNoiseControlMode = mode;
        return send(AacpPackets.controlCommand(AacpConstants.CC_LISTENING_MODE, (byte)mode));
    }

    public boolean setConversationAwareness (boolean enabled) {
        return send(AacpPackets.controlCommand(AacpConstants.CC_CONVERSATION_DETECT, enabled));
    }

    public boolean setAdaptiveNoiseStrength (int strength) {
        // Documented range for the AutoANC Strength control command is 0-100.
        int value = Math.max(0, Math.min(100, strength));
        return send(AacpPackets.controlCommand(AacpConstants.CC_AUTO_ANC_STRENGTH, (byte)value));
    }

    public boolean setEarDetectionEnabled (boolean enabled) {
        return send(AacpPackets.controlCommand(AacpConstants.CC_EAR_DETECTION_CONFIG, enabled));
    }

    public boolean rename (String newName) {
        if (newName == null || newName.isEmpty())
            return false;

        return send(AacpPackets.rename(newName));
    }

    public boolean requestNotifications () {
        return send(AacpPackets.requestNotifications(true));
    }

    private boolean send (byte[] packet) {
        synchronized (socketLock) {
            if (!connected || output == null)
                return false;

            try {
                output.write(packet);
                output.flush();
                return true;
            } catch (Throwable t) {
                Logger.debug("AACP: write failed: " + t);
                return false;
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    private void handleDisconnect () {
        boolean wasConnected = connected;
        connected = false;

        synchronized (socketLock) {
            output = null;
        }

        closeSocket();

        if (wasConnected) {
            Logger.debug("AACP: disconnected");
            postDisconnected();
        }
    }

    private void closeSocket () {
        synchronized (socketLock) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
                socket = null;
            }
            output = null;
        }

        connected = false;
    }

    private void postConnected () {
        mainHandler.post(() -> {
            if (listener != null)
                listener.onAacpConnected();
        });
    }

    private void postDisconnected () {
        mainHandler.post(() -> {
            if (listener != null)
                listener.onAacpDisconnected();
        });
    }

    private void postBattery (final AacpPackets.BatteryInfo battery) {
        mainHandler.post(() -> {
            if (listener != null)
                listener.onBattery(battery);
        });
    }

    private void postEarDetection (final int[] ear) {
        mainHandler.post(() -> {
            if (listener != null && ear != null)
                listener.onEarDetection(ear);
        });
    }

    private void postNoiseControl (final int mode) {
        mainHandler.post(() -> {
            if (listener != null)
                listener.onNoiseControl(mode);
        });
    }

    private void postConversationAwareness (final boolean enabled) {
        mainHandler.post(() -> {
            if (listener != null)
                listener.onConversationAwareness(enabled);
        });
    }

    private static String describe (AacpPackets.BatteryInfo b) {
        return "L=" + (b.hasLeft ? b.leftPercent + "%" + (b.leftCharging ? "+" : "") : "-") +
                " R=" + (b.hasRight ? b.rightPercent + "%" + (b.rightCharging ? "+" : "") : "-") +
                " C=" + (b.hasCase ? b.casePercent + "%" + (b.caseCharging ? "+" : "") : "-");
    }

    private static byte[] slice (byte[] data, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }

}
