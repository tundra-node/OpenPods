package com.dosse.airpods.pods;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.provider.Settings;
import androidx.annotation.RequiresApi;

import com.dosse.airpods.R;
import com.dosse.airpods.aacp.AacpManager;
import com.dosse.airpods.aacp.AacpPackets;
import com.dosse.airpods.notification.AlertManager;
import com.dosse.airpods.notification.ConnectCard;
import com.dosse.airpods.notification.NotificationThread;
import com.dosse.airpods.receivers.BluetoothListener;
import com.dosse.airpods.receivers.BluetoothReceiver;
import com.dosse.airpods.receivers.ScreenReceiver;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.utils.ConnectChime;
import com.dosse.airpods.utils.Logger;

import java.util.Objects;

import static com.dosse.airpods.pods.PodsStatusScanCallback.getScanFilters;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isSavingBattery;

/**
 * This is the class that does most of the work. It has 3 functions:
 * - Detect when AirPods are detected
 * - Receive beacons from AirPods and decode them (easier said than done thanks to google's autism)
 * - Display the notification with the status
 */
public class PodsService extends Service {

    /** Notification action: set the noise control mode (extra {@link #EXTRA_NOISE_MODE}). */
    public static final String ACTION_SET_NOISE_CONTROL = "com.dosse.airpods.action.SET_NOISE_CONTROL";
    public static final String EXTRA_NOISE_MODE = "noiseMode";

    /** Settings action: toggle conversational awareness (extra {@link #EXTRA_ENABLED}). */
    public static final String ACTION_SET_CONVERSATION_AWARENESS = "com.dosse.airpods.action.SET_CONVERSATION_AWARENESS";

    /** Settings action: set the adaptive noise control strength (extra {@link #EXTRA_STRENGTH}). */
    public static final String ACTION_SET_ADAPTIVE_STRENGTH = "com.dosse.airpods.action.SET_ADAPTIVE_STRENGTH";

    /** Settings action: rename the AirPods (extra {@link #EXTRA_NAME}). */
    public static final String ACTION_RENAME = "com.dosse.airpods.action.RENAME";

    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_STRENGTH = "strength";
    public static final String EXTRA_NAME = "name";

    private BluetoothLeScanner btScanner;
    private final PodsStatusRepository repository = PodsStatusRepository.getInstance();

    private static NotificationThread n = null;

    private BroadcastReceiver btReceiver = null;
    private BroadcastReceiver screenReceiver = null;
    private PodsStatusScanCallback scanCallback = null;
    private final EarDetectionController earDetection = new EarDetectionController();

    private AlertManager alertManager = null;
    private ConnectChime connectChime = null;
    private ConnectCard connectCard = null;

    private AacpManager aacpManager = null;
    private volatile AacpPackets.BatteryInfo lastAacpBattery = null;
    private volatile int lastNoiseControlMode = -1;
    private volatile boolean lastConversationAwareness = false;

    /**
     * The following method (startAirPodsScanner) creates a bluetooth LE scanner.
     * This scanner receives all beacons from nearby BLE devices (not just your devices!) so we need to do 3 things:
     * - Check that the beacon comes from something that looks like a pair of AirPods
     * - Make sure that it is YOUR pair of AirPods
     * - Decode the beacon to get the status
     *
     * After decoding a beacon, the status is written to PodsStatus so that the NotificationThread can use the information
     */
    @SuppressLint("MissingPermission")
    private void startAirPodsScanner () {
        try {
            Logger.debug("START SCANNER");

            BluetoothManager btManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter btAdapter = btManager.getAdapter();

            if (btAdapter == null) {
                Logger.debug("No BT");
                return;
            }

            if (btScanner != null && scanCallback != null) {
                btScanner.stopScan(scanCallback);
                scanCallback = null;
            }

            if (!btAdapter.isEnabled()) {
                Logger.debug("BT Off");
                return;
            }

            btScanner = btAdapter.getBluetoothLeScanner();

            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(isSavingBattery(getApplicationContext()) ? ScanSettings.SCAN_MODE_LOW_POWER : ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(1) // DON'T USE 0
                    .build();

            scanCallback = new PodsStatusScanCallback() {
                @Override
                public void onStatus (PodsStatus newStatus) {
                    // AACP provides faster and more accurate in-ear and
                    // battery data, so it takes precedence while connected.
                    boolean aacpActive = aacpManager != null && aacpManager.isConnected();

                    if (!aacpActive)
                        earDetection.onStatus(newStatus, getApplicationContext());

                    // A beacon carries only 5% steps: keep the exact battery
                    // that AACP already reported.
                    if (aacpActive && lastAacpBattery != null && newStatus != null && newStatus.getAirpods() != null)
                        applyBattery(newStatus.getAirpods(), lastAacpBattery);

                    repository.setStatus(newStatus);
                }
            };

            btScanner.startScan(getScanFilters(), scanSettings, scanCallback);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopAirPodsScanner () {
        try {
            if (btScanner != null && scanCallback != null) {
                Logger.debug("STOP SCANNER");

                btScanner.stopScan(scanCallback);
                scanCallback = null;
            }
            repository.setStatus(PodsStatus.DISCONNECTED);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    public PodsService () {
    }

    @Override
    public IBinder onBind (Intent intent) {
        return null;
    }

    /**
     * When the service is created, we register to get as many bluetooth and airpods related events as possible.
     * ACL_CONNECTED and ACL_DISCONNECTED should have been enough, but you never know with android these days.
     */
    @Override
    public void onCreate () {
        super.onCreate();

        // Low battery alerts and the optional connect chime observe the
        // repository; they are only active while the service is alive.
        alertManager = new AlertManager(this);
        connectChime = new ConnectChime(this);
        connectCard = new ConnectCard(this);
        repository.addListener(alertManager);
        repository.addListener(connectChime);
        repository.addListener(connectCard);

        // AACP gives exact battery and instant ear detection. It is entirely
        // optional: everything degrades to beacon-only data when it fails.
        aacpManager = new AacpManager();
        aacpManager.setListener(new AacpManager.Listener() {
            @Override
            public void onAacpConnected () {
                Logger.debug("AACP: connected, switching to exact data");
            }

            @Override
            public void onAacpDisconnected () {
                Logger.debug("AACP: disconnected, falling back to beacons");
            }

            @Override
            public void onBattery (AacpPackets.BatteryInfo battery) {
                lastAacpBattery = battery;

                PodsStatus current = repository.getStatus();
                if (current == null || current == PodsStatus.DISCONNECTED || current.getAirpods() == null)
                    return;

                applyBattery(current.getAirpods(), battery);

                // Re-publish around the same model so the notification, the
                // connect card and the low battery alerts see the new values.
                repository.setStatus(new PodsStatus(current.getAirpods()));
            }

            @Override
            public void onEarDetection (int[] ear) {
                if (isSinglePodModel())
                    return; // in-ear detection is meaningless for single pods

                earDetection.onEarCount(AacpPackets.inEarCount(ear), getApplicationContext());
            }

            @Override
            public void onNoiseControl (int mode) {
                lastNoiseControlMode = mode;
            }

            @Override
            public void onConversationAwareness (boolean enabled) {
                lastConversationAwareness = enabled;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForeground(101, createBackgroundNotification());

        unregisterBtReceiver();

        btReceiver = new BluetoothReceiver() {
            @Override
            public void onStart () {
                // Bluetooth turned on, start/restart scanner.
                Logger.debug("BT ON");
                startAirPodsScanner();
            }

            @Override
            public void onStop () {
                // Bluetooth turned off, stop scanner and remove notification.
                Logger.debug("BT OFF");
                if (aacpManager != null)
                    aacpManager.disconnect();
                repository.setMaybeConnected(false);
                stopAirPodsScanner();
            }

            @Override
            public void onConnect (BluetoothDevice bluetoothDevice) {
                // Airpods filter
                if (checkUUID(bluetoothDevice)) {
                    // Airpods connected, show notification.
                    Logger.debug("ACL CONNECTED");
                    repository.setMaybeConnected(true);

                    if (aacpManager != null)
                        aacpManager.connect(bluetoothDevice);
                }
            }

            @Override
            public void onDisconnect (BluetoothDevice bluetoothDevice) {
                // Airpods filter
                if (checkUUID(bluetoothDevice)) {
                    // Airpods disconnected, remove notification but leave the scanner going.
                    Logger.debug("ACL DISCONNECTED");
                    if (aacpManager != null)
                        aacpManager.disconnect();
                    lastAacpBattery = null;
                    repository.setMaybeConnected(false);
                }
            }
        };

        try {
            registerReceiver(btReceiver, BluetoothReceiver.buildFilter());
        } catch (Throwable t) {
            Logger.error(t);
        }

        // This BT Profile Proxy allows us to know if airpods are already connected when the app is started.
        // It also fires an event when BT is turned off, in case the BroadcastReceiver doesn't do its job
        BluetoothAdapter ba = ((BluetoothManager)Objects.requireNonNull(getSystemService(Context.BLUETOOTH_SERVICE))).getAdapter();
        ba.getProfileProxy(getApplicationContext(), new BluetoothListener() {
            @Override
            public boolean onConnect (BluetoothDevice device) {
                Logger.debug("BT PROXY SERVICE CONNECTED ");

                if (checkUUID(device)) {
                    Logger.debug("BT PROXY: AIRPODS ALREADY CONNECTED");
                    repository.setMaybeConnected(true);

                    if (aacpManager != null)
                        aacpManager.connect(device);
                    return true;
                }

                return false;
            }

            @Override
            public void onDisconnect () {
                Logger.debug("BT PROXY SERVICE DISCONNECTED ");
                if (aacpManager != null)
                    aacpManager.disconnect();
                lastAacpBattery = null;
                repository.setMaybeConnected(false);
            }
        }, BluetoothProfile.HEADSET);

        if (ba.isEnabled())
            startAirPodsScanner(); // If BT is already on when the app is started, start the scanner without waiting for an event to happen

        // Screen on/off listener to suspend scanning when the screen is off, to save battery
        unregisterScreenReceiver();

        if (isSavingBattery(getApplicationContext())) {
            screenReceiver = new ScreenReceiver() {
                @Override
                public void onStart () {
                    Logger.debug("SCREEN ON");
                    startAirPodsScanner();
                }

                @Override
                public void onStop () {
                    Logger.debug("SCREEN OFF");
                    stopAirPodsScanner();
                }
            };

            try {
                registerReceiver(screenReceiver, ScreenReceiver.buildFilter());
            } catch (Throwable t) {
                Logger.error(t);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean checkUUID (BluetoothDevice bluetoothDevice) {
        ParcelUuid[] AIRPODS_UUIDS = {
                ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"),
                ParcelUuid.fromString("2a72e02b-7b99-778f-014d-ad0b7221ec74")
        };
        ParcelUuid[] uuids = bluetoothDevice.getUuids();

        if (uuids == null)
            return false;

        for (ParcelUuid u : uuids)
            for (ParcelUuid v : AIRPODS_UUIDS)
                if (u.equals(v)) return true;

        return false;
    }

    /** Applies an AACP battery reading to a model, without republishing it. */
    private static void applyBattery (IPods pods, AacpPackets.BatteryInfo battery) {
        if (pods == null || battery == null)
            return;

        pods.applyExactBattery(
                battery.hasLeft ? battery.leftPercent : Integer.MIN_VALUE, battery.leftCharging,
                battery.hasRight ? battery.rightPercent : Integer.MIN_VALUE, battery.rightCharging,
                battery.hasCase ? battery.casePercent : Integer.MIN_VALUE, battery.caseCharging);
    }

    private boolean isSinglePodModel () {
        PodsStatus current = repository.getStatus();
        return current != null && current.getAirpods() != null && current.getAirpods().isSingle();
    }

    @Override
    public void onDestroy () {
        super.onDestroy();
        if (alertManager != null) {
            repository.removeListener(alertManager);
            alertManager = null;
        }
        if (connectChime != null) {
            repository.removeListener(connectChime);
            connectChime = null;
        }
        if (connectCard != null) {
            repository.removeListener(connectCard);
            connectCard = null;
        }
        if (aacpManager != null) {
            aacpManager.disconnect();
            aacpManager = null;
        }
        unregisterBtReceiver();
        unregisterScreenReceiver();
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId) {
        handleAacpAction(intent);

        if (n == null || !n.isAlive()) {
            n = new NotificationThread(this) {
                @Override
                public boolean isConnected () {
                    return repository.isMaybeConnected();
                }

                @Override
                public PodsStatus getStatus () {
                    return repository.getStatus();
                }

                @Override
                public boolean isNoiseControlAvailable () {
                    return aacpManager != null && aacpManager.isConnected();
                }

                @Override
                public int getNoiseControlMode () {
                    return lastNoiseControlMode;
                }
            };
            n.start();
        }
        return START_STICKY;
    }

    /** Handles the actions coming from the notification and the settings. */
    private void handleAacpAction (Intent intent) {
        if (intent == null || intent.getAction() == null)
            return;

        try {
            switch (intent.getAction()) {
                case ACTION_SET_NOISE_CONTROL: {
                    if (aacpManager != null && aacpManager.isConnected()) {
                        int mode = intent.getIntExtra(EXTRA_NOISE_MODE, -1);
                        Logger.debug("AACP: notification action, noise mode " + mode);
                        if (aacpManager.setNoiseControl(mode))
                            lastNoiseControlMode = mode;
                    }
                    break;
                }
                case ACTION_SET_CONVERSATION_AWARENESS: {
                    if (aacpManager != null && aacpManager.isConnected()) {
                        boolean enabled = intent.getBooleanExtra(EXTRA_ENABLED, false);
                        Logger.debug("AACP: setting conversation awareness " + enabled);
                        aacpManager.setConversationAwareness(enabled);
                    }
                    break;
                }
                case ACTION_SET_ADAPTIVE_STRENGTH: {
                    if (aacpManager != null && aacpManager.isConnected()) {
                        int strength = intent.getIntExtra(EXTRA_STRENGTH, -1);
                        Logger.debug("AACP: setting adaptive noise strength " + strength);
                        if (strength >= 0)
                            aacpManager.setAdaptiveNoiseStrength(strength);
                    }
                    break;
                }
                case ACTION_RENAME: {
                    if (aacpManager != null && aacpManager.isConnected()) {
                        String name = intent.getStringExtra(EXTRA_NAME);
                        Logger.debug("AACP: renaming to " + name);
                        if (name != null && !name.isEmpty())
                            aacpManager.rename(name);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    // Foreground service for background notification (confusing I know).
    // Only enabled for API30+
    @RequiresApi(api = Build.VERSION_CODES.O)
    private Notification createBackgroundNotification () {
        final String notChannelID = "FOREGROUND_ID";

        NotificationManager notManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel notChannel = new NotificationChannel(notChannelID, getString(R.string.bg_noti_channel), NotificationManager.IMPORTANCE_LOW);
        notChannel.setShowBadge(false);
        notManager.createNotificationChannel(notChannel);

        Intent notIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, notChannelID);

        PendingIntent notPendingIntent = PendingIntent.getActivity(this, 1110, notIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, notChannelID)
                .setSmallIcon(R.drawable.pod_case)
                .setContentTitle(getString(R.string.bg_noti_title))
                .setContentText(getString(R.string.bg_noti_text))
                .setContentIntent(notPendingIntent)
                .setOngoing(true);

        return builder.build();
    }

    private void unregisterBtReceiver () {
        try {
            if (btReceiver != null) {
                unregisterReceiver(btReceiver);
                btReceiver = null;
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    private void unregisterScreenReceiver () {
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver);
                screenReceiver = null;
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

}
