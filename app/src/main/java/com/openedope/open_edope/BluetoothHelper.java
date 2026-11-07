package com.openedope.open_edope;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class BluetoothHelper {
    private static final String TAG = "OpenEDopeBLE";
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID LINK_SERVICE =
            UUID.fromString("85920000-0338-4b83-ae4a-ac1d217adb03");
    private static final UUID LINK_INDICATION =
            UUID.fromString("85920100-0338-4b83-ae4a-ac1d217adb03");
    private static final UUID LINK_WRITE =
            UUID.fromString("85920200-0338-4b83-ae4a-ac1d217adb03");
    private static final byte[] KESTREL_KEY =
            hexBytes("98e439ab2d999f58c1c58a7e66c7905d");
    private static final byte[] KESTREL_C0 =
            hexBytes("a7f1d92a82c8d8fe434d98558ce2b347");

    private final Context context;
    private final BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private final Map<String, String> scanResults = new LinkedHashMap<>();
    private boolean scanning;
    private volatile int connectionState = BluetoothProfile.STATE_DISCONNECTED;
    private volatile String status = "Idle";
    private volatile String lastError = "";
    private volatile String lastCharacteristicUuid = "";
    private volatile String lastCharacteristicValue = "";
    private BluetoothGattCharacteristic linkIndicationCharacteristic;
    private BluetoothGattCharacteristic linkWriteCharacteristic;
    // Kestrel 5700 accepts 20-byte ATT payloads (23-byte ATT MTU).
    // GATT writes are stop-and-wait: the next chunk is sent only after
    // onCharacteristicWrite() confirms the previous chunk.
    private static final int BLE_WRITE_CHUNK_SIZE = 20;
    private final Queue<byte[]> bleWriteQueue = new ArrayDeque<>();
    private final Object bleWriteLock = new Object();
    private boolean bleWriteInProgress;
    private final ByteArrayAccumulator indicationAccumulator = new ByteArrayAccumulator();
    private boolean kestrelAuthenticated;
    private boolean privacyResponseReceived;
    private boolean requestAuthSent;
    private boolean authenticationInProgress;
    private boolean kestrelNonceSent;
    private boolean hostNonceSent;
    private boolean authenticationProofProcessed;
    private byte[] kestrelNonce;
    private byte[] hostNonce;
    private double lastElevationMil = Double.NaN;
    private double lastWind1Mil = Double.NaN;
    private double lastWind2Mil = Double.NaN;
    private double lastRangeMeters;
    private volatile int elevationUnit = 1;
    private volatile int windUnit = 1;
    private volatile int activeGunIndex = -1;
    private boolean profileRefreshInProgress;
    private boolean awaitingActiveGunResponse;
    private boolean awaitingGunProfile;
    private volatile boolean awaitingRangeInputs;
    private volatile boolean awaitingRangeSolution;
    private volatile double pendingRangeMeters = Double.NaN;
    private int pendingRangeCounter = -1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable rangeInputTimeout;
    private Runnable rangeSolutionTimeout;
    private Runnable profileRefreshTimeout;
    private Runnable profileRefreshPoll;
    private int remoteSolutionCounter;
    private final Queue<Double> pendingRangeQueue = new ArrayDeque<>();
    private final List<byte[]> gunProfileParts = new ArrayList<>();
    private final List<String> debugLog = new ArrayList<>();
    private volatile int indicationChunkCount;
    private volatile int encryptedFrameCount;
    private volatile int clearFrameCount;
    private volatile int profilePartCount;
    private volatile String lastProfilePayloadHex = "";
    private volatile String lastEncryptedPayloadHex = "";
    private volatile String lastParsedOpcodeHex = "";
    private static final int OP_GET_ACTIVE_GUN = 0x0094;
    private static final int OP_GET_GUN = 0x0087;
    private static final int OP_END_OF_DATA = 0x0012;

    public BluetoothHelper(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager != null ? manager.getAdapter() : null;
        appendDebug("BluetoothHelper initialized");
    }

    public boolean isSupported() {
        return this.context.getPackageManager().hasSystemFeature("android.hardware.bluetooth_le")
                && adapter != null;
    }

    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public boolean startScan() {
        if (!isSupported() || !isEnabled()) {
            lastError = "Bluetooth is unavailable or disabled.";
            status = "Error";
            return false;
        }
        if (scanning) {
            return true;
        }
        scanResults.clear();
        try {
            adapter.getBluetoothLeScanner().startScan(scanCallback);
            scanning = true;
            status = "Scanning";
            lastError = "";
            appendDebug("BLE scan started");
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            status = "Error";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        appendDebug("BLE scan stop requested");
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        }
        scanning = false;
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            status = "Idle";
        }
    }

    public boolean isScanning() {
        return scanning;
    }

    public String[] getScanResults() {
        List<String> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : scanResults.entrySet()) {
            String name = entry.getValue();
            if (name == null || name.trim().isEmpty()) {
                name = "Unknown";
            }
            results.add(entry.getKey() + "|" + name);
        }
        return results.toArray(new String[0]);
    }

    @SuppressLint("MissingPermission")
    public boolean connect(String address) {
        if (!isEnabled()) {
            lastError = "Bluetooth is disabled.";
            status = "Error";
            return false;
        }
        stopScan();
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
            status = "Connecting";
            lastError = "";
            appendDebug("Connecting to " + address);
            return gatt != null;
        } catch (Exception e) {
            lastError = e.getMessage();
            status = "Error";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        appendDebug("Disconnect requested");
        synchronized (bleWriteLock) {
            bleWriteQueue.clear();
            bleWriteInProgress = false;
        }
        pendingRangeQueue.clear();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        connectionState = BluetoothProfile.STATE_DISCONNECTED;
        linkIndicationCharacteristic = null;
        linkWriteCharacteristic = null;
        indicationAccumulator.clear();
        kestrelAuthenticated = false;
        privacyResponseReceived = false;
        requestAuthSent = false;
        authenticationInProgress = false;
        kestrelNonceSent = false;
        hostNonceSent = false;
        authenticationProofProcessed = false;
        authenticationProofProcessed = false;
        lastElevationMil = Double.NaN;
        lastWind1Mil = Double.NaN;
        lastWind2Mil = Double.NaN;
        lastRangeMeters = Double.NaN;
        profileRefreshInProgress = false;
        gunProfileParts.clear();
        awaitingActiveGunResponse = false;
        awaitingGunProfile = false;
        awaitingRangeInputs = false;
        awaitingRangeSolution = false;
        pendingRangeMeters = Double.NaN;
        pendingRangeCounter = -1;
        clearRangeInputTimeout();
        clearRangeSolutionTimeout();
        stopProfileRefreshPolling();
        activeGunIndex = -1;
        elevationUnit = 1;
        windUnit = 1;
        status = "Disconnected";
    }

    public int getConnectionState() {
        return connectionState;
    }

    public String getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastCharacteristicUuid() {
        return lastCharacteristicUuid;
    }

    public String getLastCharacteristicValue() {
        return lastCharacteristicValue;
    }

    private synchronized void appendDebug(String message) {
        String entry = message;
        debugLog.add(entry);
        while (debugLog.size() > 300) {
            debugLog.remove(0);
        }
        Log.d(TAG, entry);
    }

    public synchronized String getDebugLog() {
        if (debugLog.isEmpty()) {
            return "No Bluetooth debug events recorded.";
        }
        StringBuilder output = new StringBuilder();
        for (String entry : debugLog) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(entry);
        }
        return output.toString();
    }

    public void clearDebugLog() {
        debugLog.clear();
        appendDebug("Bluetooth debug log cleared");
    }

    public String getDebugSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Status: ").append(status).append('\n');
        summary.append("Authenticated: ").append(kestrelAuthenticated).append('\n');
        summary.append("Scanning: ").append(scanning).append('\n');
        summary.append("Active gun index: ").append(activeGunIndex).append('\n');
        summary.append("Elevation unit: ").append(elevationUnit == 2 ? "MOA/TMOA (2)" : "MIL (1)").append('\n');
        summary.append("Wind unit: ").append(windUnit == 2 ? "MOA/TMOA (2)" : "MIL (1)").append('\n');
        summary.append("Profile refresh: ").append(profileRefreshInProgress ? "in progress" : "idle").append('\n');
        summary.append("Range meters: ").append(lastRangeMeters).append('\n');
        summary.append("Elevation MIL: ").append(lastElevationMil).append('\n');
        summary.append("Wind 1 MIL: ").append(lastWind1Mil).append('\n');
        summary.append("Wind 2 MIL: ").append(lastWind2Mil).append('\n');
        summary.append("Last opcode: ").append(lastParsedOpcodeHex.isEmpty() ? "none" : lastParsedOpcodeHex).append('\n');
        summary.append("Indication chunks: ").append(indicationChunkCount).append('\n');
        summary.append("Clear frames: ").append(clearFrameCount).append('\n');
        summary.append("Encrypted frames: ").append(encryptedFrameCount).append('\n');
        summary.append("Gun profile parts: ").append(profilePartCount).append('\n');
        summary.append("Last profile payload: ").append(lastProfilePayloadHex.isEmpty() ? "none" : lastProfilePayloadHex).append('\n');
        summary.append("Last packet payload: ").append(lastEncryptedPayloadHex.isEmpty() ? "none" : lastEncryptedPayloadHex).append('\n');
        summary.append("Last characteristic: ").append(lastCharacteristicUuid.isEmpty() ? "none" : lastCharacteristicUuid).append('\n');
        summary.append("Last characteristic hex: ").append(lastCharacteristicValue.isEmpty() ? "none" : lastCharacteristicValue).append('\n');
        summary.append("Last error: ").append(lastError.isEmpty() ? "none" : lastError);
        return summary.toString();
    }

    public boolean isKestrelAuthenticated() {
        return kestrelAuthenticated;
    }

    /**
     * True while the Kestrel protocol is completing a profile read or a
     * range-to-solution transaction. The Python UI must not start another
     * request until this returns false (the protocol is stop-and-wait).
     */
    public boolean isKestrelBusy() {
        return profileRefreshInProgress || awaitingActiveGunResponse
                || awaitingGunProfile || awaitingRangeInputs || awaitingRangeSolution;
    }

    public boolean isKestrelRangeBusy() {
        return awaitingRangeInputs || awaitingRangeSolution;
    }

    public double getLastElevationMil() {
        return lastElevationMil;
    }

    public double getLastWind1Mil() {
        return lastWind1Mil;
    }

    public double getLastWind2Mil() {
        return lastWind2Mil;
    }

    public double getLastRangeMeters() {
        return lastRangeMeters;
    }

    public int getElevationUnit() {
        return elevationUnit;
    }

    public int getWindUnit() {
        return windUnit;
    }

    private void clearProfileRefreshTimeout() {
        if (profileRefreshTimeout != null) {
            mainHandler.removeCallbacks(profileRefreshTimeout);
            profileRefreshTimeout = null;
        }
    }

    private void startProfileRefreshPolling() {
        stopProfileRefreshPolling();
        profileRefreshPoll = new Runnable() {
            @Override
            public void run() {
                if (!kestrelAuthenticated || connectionState != BluetoothProfile.STATE_CONNECTED) {
                    profileRefreshPoll = null;
                    return;
                }
                refreshKestrelProfile();
                profileRefreshPoll = this;
                mainHandler.postDelayed(this, 10000L);
            }
        };
        mainHandler.postDelayed(profileRefreshPoll, 10000L);
    }

    private void stopProfileRefreshPolling() {
        clearProfileRefreshTimeout();
        if (profileRefreshPoll != null) {
            mainHandler.removeCallbacks(profileRefreshPoll);
            profileRefreshPoll = null;
        }
    }

    public boolean refreshKestrelProfile() {
        if (!kestrelAuthenticated || linkWriteCharacteristic == null) {
            appendDebug("Profile refresh skipped: authentication or write characteristic unavailable");
            return false;
        }
        if (profileRefreshInProgress || awaitingActiveGunResponse || awaitingGunProfile
                || awaitingRangeInputs || awaitingRangeSolution) {
            appendDebug("Profile refresh skipped: another Kestrel transaction is active");
            return false;
        }
        profileRefreshInProgress = true;
        awaitingActiveGunResponse = true;
        awaitingGunProfile = false;
        gunProfileParts.clear();
        appendDebug("Requesting active gun/profile units (0x0094, 0x0087)");
        boolean queued = writeEncryptedPacket(0, new byte[]{
                (byte) 0x94, 0x00, 0x00, 0x00, 0x00
        });
        if (!queued) {
            profileRefreshInProgress = false;
            awaitingActiveGunResponse = false;
            awaitingGunProfile = false;
            gunProfileParts.clear();
            return false;
        }
        clearProfileRefreshTimeout();
        profileRefreshTimeout = () -> {
            if (profileRefreshInProgress) {
                profileRefreshInProgress = false;
                awaitingActiveGunResponse = false;
                awaitingGunProfile = false;
                gunProfileParts.clear();
                lastError = "Timed out refreshing the active Kestrel gun profile.";
                appendDebug(lastError);
                startNextQueuedRange();
            }
            profileRefreshTimeout = null;
        };
        mainHandler.postDelayed(profileRefreshTimeout, 8000L);
        return true;
    }

    private void applyGunProfileUnits() {
        // EAMARS get_gun() returns separate type-3 response parts.
        // The unit bytes are in response part 1 (the second part):
        // elevation unit at offset 181, wind unit at offset 182.
        if (gunProfileParts.size() < 2) {
            appendDebug("Gun profile incomplete: expected at least two response parts, got "
                    + gunProfileParts.size());
            return;
        }

        byte[] secondPart = gunProfileParts.get(1);
        if (secondPart.length <= 182) {
            appendDebug("Second gun profile response part too short: "
                    + secondPart.length + " bytes");
            return;
        }

        int eunit = secondPart[181] & 0xff;
        int wunit = secondPart[182] & 0xff;

        if ((eunit == 1 || eunit == 2) && (wunit == 1 || wunit == 2)) {
            elevationUnit = eunit;
            windUnit = wunit;
            appendDebug("Gun profile units updated: elevation="
                    + elevationUnit + ", wind=" + windUnit);
        } else {
            appendDebug("Invalid gun profile units at second-part offsets 181/182: "
                    + eunit + "/" + wunit + "; retaining current units");
        }
    }

    @SuppressLint("MissingPermission")
    public boolean sendKestrelRange(double rangeMeters) {
        if (!kestrelAuthenticated || linkWriteCharacteristic == null) {
            lastError = "Kestrel authentication is not complete.";
            return false;
        }
        if ((Double.isNaN(rangeMeters) || Double.isInfinite(rangeMeters)) || rangeMeters <= 0.0
                || Math.round(rangeMeters * 100.0) > 0x7fffff) {
            lastError = "Range must be positive and within the Kestrel input range.";
            return false;
        }
        if (profileRefreshInProgress || awaitingActiveGunResponse || awaitingGunProfile
                || awaitingRangeInputs || awaitingRangeSolution) {
            pendingRangeQueue.offer(rangeMeters);
            appendDebug("Queued range while Kestrel transaction is active: "
                    + rangeMeters + " m, queueSize=" + pendingRangeQueue.size());
            return true;
        }
        lastElevationMil = Double.NaN;
        lastWind1Mil = Double.NaN;
        lastWind2Mil = Double.NaN;
        lastRangeMeters = Double.NaN;

        // 0x0089 SetFullInputs writes range, inclination and direction of fire
        // together. Read the current target first so changing range does not
        // overwrite the existing inclination or DoF with zeroes.
        pendingRangeMeters = rangeMeters;
        awaitingRangeInputs = true;
        int counter = remoteSolutionCounter++ & 0xff;
        pendingRangeCounter = counter;
        appendDebug("Reading current target inputs before range update (0x00a0), counter="
                + counter);

        boolean queued = writeEncryptedPacket(0, new byte[]{
                (byte) 0xa0, 0x00, 0x00, (byte) counter
        });
        if (!queued) {
            awaitingRangeInputs = false;
            pendingRangeMeters = Double.NaN;
            pendingRangeCounter = -1;
            return false;
        }
        clearRangeInputTimeout();
        rangeInputTimeout = () -> {
            if (awaitingRangeInputs) {
                awaitingRangeInputs = false;
                pendingRangeMeters = Double.NaN;
                pendingRangeCounter = -1;
                lastError = "Timed out reading current target inputs; range was not changed.";
                appendDebug(lastError);
            }
            rangeInputTimeout = null;
        };
        mainHandler.postDelayed(rangeInputTimeout, 6000L);
        return true;
    }

    private void clearRangeInputTimeout() {
        if (rangeInputTimeout != null) {
            mainHandler.removeCallbacks(rangeInputTimeout);
            rangeInputTimeout = null;
        }
    }

    private void clearRangeSolutionTimeout() {
        if (rangeSolutionTimeout != null) {
            mainHandler.removeCallbacks(rangeSolutionTimeout);
            rangeSolutionTimeout = null;
        }
    }

    private void cancelPendingRangeOperation(String reason) {
        awaitingRangeInputs = false;
        awaitingRangeSolution = false;
        pendingRangeMeters = Double.NaN;
        pendingRangeCounter = -1;
        clearRangeInputTimeout();
        clearRangeSolutionTimeout();
        appendDebug(reason);
        startNextQueuedRange();
    }

    private void startNextQueuedRange() {
        if (!kestrelAuthenticated || pendingRangeQueue.isEmpty()) {
            return;
        }
        if (profileRefreshInProgress || awaitingActiveGunResponse || awaitingGunProfile
                || awaitingRangeInputs || awaitingRangeSolution) {
            return;
        }
        Double nextRange = pendingRangeQueue.poll();
        if (nextRange == null) {
            return;
        }
        mainHandler.post(() -> {
            appendDebug("Starting queued Kestrel range: " + nextRange + " m");
            sendKestrelRange(nextRange);
        });
    }

    private boolean sendKestrelFullInputs(double rangeMeters,
                                          double inclinationDegrees,
                                          double directionOfFireDegrees) {
        long rangeValue = Math.round(rangeMeters * 100.0);
        long inclinationValue = Math.round(inclinationDegrees * 1000.0);
        long directionValue = Math.round(directionOfFireDegrees * 1000.0);
        if (rangeValue < -0x800000L || rangeValue > 0x7fffffL
                || inclinationValue < -0x800000L || inclinationValue > 0x7fffffL
                || directionValue < -0x800000L || directionValue > 0x7fffffL) {
            lastError = "Current target inputs cannot be represented in the Kestrel command.";
            appendDebug(lastError);
            return false;
        }

        byte[] args = new byte[28];
        args[0] = 0;
        args[1] = 0;
        putI24(args, 2, (int) rangeValue);          // range, 0.01 m
        putI24(args, 5, (int) inclinationValue);    // inclination, 0.001 degree
        putI24(args, 8, (int) directionValue);      // DoF, 0.001 degree
        args[11] = (byte) 0xff;
        args[12] = (byte) 0xff;
        args[13] = (byte) 0xff;
        args[14] = (byte) 0xff;
        args[15] = (byte) 0xff;
        args[16] = (byte) 0xff;
        args[17] = 0x01;
        args[18] = 0x00;
        args[19] = (byte) 0x80;
        args[20] = 0x01;
        args[21] = 0x00;
        args[22] = (byte) 0x80;
        args[23] = (byte) 0x81;
        args[24] = (byte) 0xff;
        args[25] = 0x00;
        args[26] = (byte) 0xff;
        args[27] = 0x00;

        byte[] command = new byte[30];
        command[0] = (byte) 0x89;
        command[1] = 0x00;
        System.arraycopy(args, 0, command, 2, args.length);
        lastEncryptedPayloadHex = "<redacted; " + command.length + " bytes>";
        appendDebug("Sending range command 0x0089: range=" + rangeMeters
                + " m, preserving inclination=" + inclinationDegrees
                + " deg and DoF=" + directionOfFireDegrees + " deg");
        // Keep the operation locked until the corresponding 0x008A solution
        // arrives. A successful BLE write only means the frame was queued;
        // it does not mean the Kestrel completed the calculation.
        awaitingRangeSolution = true;
        clearRangeSolutionTimeout();
        rangeSolutionTimeout = () -> {
            if (awaitingRangeSolution) {
                cancelPendingRangeOperation(
                        "Timed out waiting for Kestrel 0x008A solution after range update.");
                lastError = "Timed out waiting for the Kestrel range solution.";
                status = "Error";
            }
            rangeSolutionTimeout = null;
        };
        mainHandler.postDelayed(rangeSolutionTimeout, 10000L);

        boolean queued = writeEncryptedPacket(0, command);
        if (!queued) {
            cancelPendingRangeOperation("Unable to queue Kestrel range update.");
        }
        return queued;
    }

    public String[] getServices() {
        if (gatt == null || connectionState != BluetoothProfile.STATE_CONNECTED) {
            return new String[0];
        }
        List<String> uuids = new ArrayList<>();
        for (BluetoothGattService service : gatt.getServices()) {
            uuids.add(service.getUuid().toString());
        }
        return uuids.toArray(new String[0]);
    }

    public String[] getCharacteristics(String serviceUuid) {
        if (gatt == null) {
            return new String[0];
        }
        for (BluetoothGattService service : gatt.getServices()) {
            if (service.getUuid().toString().equalsIgnoreCase(serviceUuid)) {
                List<String> uuids = new ArrayList<>();
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    uuids.add(characteristic.getUuid().toString());
                }
                return uuids.toArray(new String[0]);
            }
        }
        return new String[0];
    }

    @SuppressLint("MissingPermission")
    public boolean readCharacteristic(String serviceUuid, String characteristicUuid) {
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (gatt == null || characteristic == null) {
            lastError = "Characteristic not found.";
            return false;
        }
        lastError = "";
        return gatt.readCharacteristic(characteristic);
    }

    @SuppressLint("MissingPermission")
    public boolean writeCharacteristic(String serviceUuid, String characteristicUuid,
                                       byte[] value, boolean withResponse) {
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (gatt == null || characteristic == null) {
            lastError = "Characteristic not found.";
            return false;
        }
        lastError = "";

        // LiNK protocol writes must share one serialized queue. The Kestrel
        // transport uses write-with-response, even for caller-supplied frames.
        if (LINK_SERVICE.toString().equalsIgnoreCase(serviceUuid)
                && LINK_WRITE.toString().equalsIgnoreCase(characteristicUuid)) {
            return enqueueBleFrame(value);
        }

        int writeType = withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        characteristic.setWriteType(writeType);
        characteristic.setValue(value);
        return gatt.writeCharacteristic(characteristic);
    }

    public boolean writeCharacteristicHex(String serviceUuid, String characteristicUuid,
                                          String hex, boolean withResponse) {
        return writeCharacteristic(serviceUuid, characteristicUuid, hexToBytes(hex), withResponse);
    }

    @SuppressLint("MissingPermission")
    public boolean setCharacteristicNotification(String serviceUuid,
                                                 String characteristicUuid,
                                                 boolean enabled) {
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (gatt == null || characteristic == null) {
            lastError = "Characteristic not found.";
            return false;
        }
        if (!gatt.setCharacteristicNotification(characteristic, enabled)) {
            lastError = "Could not configure characteristic notifications.";
            return false;
        }
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor == null) {
            return true;
        }
        byte[] value = enabled
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        descriptor.setValue(value);
        return gatt.writeDescriptor(descriptor);
    }

    private BluetoothGattCharacteristic findCharacteristic(String serviceUuid,
                                                           String characteristicUuid) {
        if (gatt == null) {
            return null;
        }
        for (BluetoothGattService service : gatt.getServices()) {
            if (!service.getUuid().toString().equalsIgnoreCase(serviceUuid)) {
                continue;
            }
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (characteristic.getUuid().toString().equalsIgnoreCase(characteristicUuid)) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) {
                return;
            }
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            ScanRecord scanRecord = result.getScanRecord();
            String name = scanRecord != null ? scanRecord.getDeviceName() : null;
            if (name == null || name.trim().isEmpty()) {
                name = device.getName();
            }
            if (name != null && !name.trim().isEmpty()) {
                scanResults.put(address, name);
                appendDebug("Scan result: " + name + " [" + address + "]");
            } else if (!scanResults.containsKey(address)) {
                scanResults.put(address, "");
                appendDebug("Scan result: Unknown [" + address + "]");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            lastError = "BLE scan failed with error " + errorCode;
            status = "Error";
            appendDebug("BLE scan failed: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int statusCode, int newState) {
            connectionState = newState;
            appendDebug("GATT connection state: status=" + statusCode + ", state=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status = "Discovering services";
                bluetoothGatt.discoverServices();
            } else {
                status = newState == BluetoothProfile.STATE_DISCONNECTED
                        ? "Disconnected"
                        : "Connection error";

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized (bleWriteLock) {
                        bleWriteQueue.clear();
                        bleWriteInProgress = false;
                    }
                    pendingRangeQueue.clear();
                    kestrelAuthenticated = false;
                    privacyResponseReceived = false;
                    requestAuthSent = false;
                    authenticationInProgress = false;
                    kestrelNonceSent = false;
                    hostNonceSent = false;
                    lastElevationMil = Double.NaN;
                    lastWind1Mil = Double.NaN;
                    lastWind2Mil = Double.NaN;
                    lastRangeMeters = Double.NaN;
                    profileRefreshInProgress = false;
                    awaitingActiveGunResponse = false;
                    awaitingGunProfile = false;
                    awaitingRangeInputs = false;
                    awaitingRangeSolution = false;
                    pendingRangeMeters = Double.NaN;
                    pendingRangeCounter = -1;
                    clearRangeInputTimeout();
                    clearRangeSolutionTimeout();
                    linkIndicationCharacteristic = null;
                    linkWriteCharacteristic = null;
                    indicationAccumulator.clear();
                    appendDebug("BLE disconnected; pending writes cleared");
                        stopProfileRefreshPolling();
                    }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int statusCode) {
            appendDebug("GATT services discovered: status=" + statusCode);
            status = statusCode == BluetoothGatt.GATT_SUCCESS
                    ? "Connected"
                    : "Service discovery failed";
            lastError = statusCode == BluetoothGatt.GATT_SUCCESS
                    ? ""
                    : "Service discovery status " + statusCode;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                return;
            }
            BluetoothGattService linkService = bluetoothGatt.getService(LINK_SERVICE);
            if (linkService == null) {
                lastError = "Kestrel LiNK service not found.";
                status = "Error";
                appendDebug("Kestrel LiNK service not found");
                return;
            }
            linkIndicationCharacteristic =
                    linkService.getCharacteristic(LINK_INDICATION);
            linkWriteCharacteristic =
                    linkService.getCharacteristic(LINK_WRITE);
            if (linkIndicationCharacteristic == null || linkWriteCharacteristic == null) {
                lastError = "Kestrel LiNK characteristics not found.";
                status = "Error";
                appendDebug("Kestrel LiNK indication/write characteristic missing");
                return;
            }
            status = "Enabling Kestrel Link";
            bluetoothGatt.setCharacteristicNotification(
                    linkIndicationCharacteristic, true);
            BluetoothGattDescriptor descriptor =
                    linkIndicationCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                bluetoothGatt.writeDescriptor(descriptor);
            } else {
                startKestrelAuthentication();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt bluetoothGatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int statusCode) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                lastCharacteristicUuid = characteristic.getUuid().toString();
                lastCharacteristicValue = bytesToHex(characteristic.getValue());
            } else {
                lastError = "Characteristic read status " + statusCode;
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                            BluetoothGattCharacteristic characteristic) {
            lastCharacteristicUuid = characteristic.getUuid().toString();
            byte[] indication = characteristic.getValue();
            lastCharacteristicValue = "<redacted; "
                    + (indication == null ? 0 : indication.length) + " bytes>";
            appendDebug("Characteristic indication: " + lastCharacteristicUuid
                    + ", " + (indication == null ? 0 : indication.length) + " bytes");
            if (characteristic.getUuid().equals(LINK_INDICATION)) {
                handleKestrelIndication(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int statusCode) {
            if (characteristic == null
                    || !LINK_WRITE.equals(characteristic.getUuid())) {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    lastError = "Characteristic write status " + statusCode;
                    appendDebug("Characteristic write failed: " + statusCode);
                }
                return;
            }
            onBleWriteComplete(statusCode);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt,
                                     BluetoothGattDescriptor descriptor,
                                     int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                lastError = "Descriptor write status " + statusCode;
                return;
            }
            if (descriptor.getCharacteristic().getUuid().equals(LINK_INDICATION)) {
                startKestrelAuthentication();
            }
        }
    };

    private void startKestrelAuthentication() {
        if (linkWriteCharacteristic == null) {
            return;
        }
        if (authenticationInProgress || kestrelAuthenticated) {
            appendDebug("Authentication start ignored: already in progress or authenticated");
            return;
        }
        privacyResponseReceived = false;
        requestAuthSent = false;
        kestrelAuthenticated = false;
        authenticationInProgress = true;
        kestrelNonceSent = false;
        hostNonceSent = false;
        authenticationProofProcessed = false;
        status = "Authenticating Kestrel";
        writeClearPacket(0, new byte[]{(byte) 0x81, 0x00});
    }

    private void handleKestrelIndication(byte[] chunk) {
        indicationChunkCount++;
        indicationAccumulator.add(chunk);
        byte[][] frames = indicationAccumulator.takeCompleteFrames();
        for (byte[] frame : frames) {
            if (!kestrelAuthenticated) {
                clearFrameCount++;
                handleClearPacket(frame);
            } else {
                encryptedFrameCount++;
                handleEncryptedFrame(frame);
            }
        }
    }

    private void handleClearPacket(byte[] frame) {
        byte[] packet = null;
        if (isHdlcFrame(frame)) {
            packet = unwrapHdlc(frame);
        }
        if (packet == null) {
            return;
        }
        Packet parsed = parsePacket(packet);
        if (parsed == null) {
            appendDebug("Clear Kestrel frame could not be parsed");
            return;
        }
        if (parsed.type == 4 && parsed.payload.length >= 2) {
            int opcode = readU16(parsed.payload, 0);
            if (opcode == 0x0081) {
                return;
            }
            if (opcode == 0x007e) {
                return;
            }
            if (opcode == 0x007f) {
                appendDebug("Received clear packet opcode 0x007f");
                return;
            }
        }
        if (parsed.type == 3 && parsed.payload.length >= 1 && !privacyResponseReceived && !requestAuthSent) {
            privacyResponseReceived = true;
            writeClearPacket(4, new byte[]{(byte) 0x81, 0x00});
            requestAuthSent = true;
            writeClearPacket(0, new byte[]{0x7e, 0x00, 0x00});
            return;
        }
        if (parsed.type == 0 && parsed.payload.length >= 2) {
            int opcode = readU16(parsed.payload, 0);
            if (opcode == 0x007f && parsed.payload.length >= 18) {
                if (kestrelNonceSent) {
                    appendDebug("Ignoring duplicate Kestrel nonce frame");
                    return;
                }
                kestrelNonce = copyRange(parsed.payload, 2, 18);
                kestrelNonceSent = true;
                appendDebug("Authentication nonce received (16 bytes)");
                writeClearPacket(4, new byte[]{0x7f, 0x00});
                byte[] proof = aesCbcEncrypt(concat(KestrelProofBlock(), kestrelNonce));
                byte[] proofPacket = concat(proof, new byte[]{0, 0, 0, 0});
                writeClearPacket(3, proofPacket);
                return;
            }
            if (opcode == 0x007e && parsed.payload.length >= 2) {
                if (hostNonceSent) {
                    appendDebug("Ignoring duplicate Kestrel authentication request");
                    return;
                }
                writeClearPacket(4, new byte[]{0x7e, 0x00});
                hostNonce = new byte[16];
                new SecureRandom().nextBytes(hostNonce);
                hostNonceSent = true;
                writeClearPacket(0, concat(new byte[]{0x7f, 0x00}, hostNonce));
                return;
            }
        }
        if (parsed.type == 3 && parsed.payload.length >= 32 && hostNonce != null) {
            if (authenticationProofProcessed) {
                appendDebug("Ignoring duplicate Kestrel authentication proof");
                return;
            }
            authenticationProofProcessed = true;
            byte[] plaintext = aesCbcDecrypt(copyRange(parsed.payload, 0, 32));
            boolean verified = plaintext.length >= 32
                    && constantTimeEquals(copyRange(plaintext, 16, 32), hostNonce);
            writeClearPacket(4, new byte[]{0x7f, 0x00});
            if (verified) {
                kestrelAuthenticated = true;
                authenticationInProgress = false;
                status = "Connected";
                lastError = "";
                appendDebug("Kestrel mutual authentication verified");
                refreshKestrelProfile();
                startProfileRefreshPolling();
            } else {
                lastError = "Kestrel mutual authentication failed.";
                status = "Error";
                authenticationInProgress = false;
            }
        }
    }

    private void handleEncryptedFrame(byte[] frame) {
        byte[] packet = unwrapEncrypted(frame);
        if (packet == null) {
            lastError = "Invalid encrypted Kestrel frame.";
            appendDebug(lastError);
            return;
        }

        Packet parsed = parsePacket(packet);
        if (parsed == null) {
            appendDebug("Encrypted Kestrel frame could not be parsed");
            return;
        }

        appendDebug("Encrypted packet received: type=" + parsed.type
                + ", payloadBytes=" + parsed.payload.length);

        // Every Kestrel-initiated COMMAND is acknowledged with its own opcode,
        // including unsolicited commands that this app does not otherwise use.
        // Do this centrally so known commands are not accidentally ACKed twice.
        if (parsed.type == 0 && parsed.payload.length >= 2) {
            int commandOpcode = readU16(parsed.payload, 0);
            writeEncryptedPacket(4, new byte[]{
                    (byte) (commandOpcode & 0xff),
                    (byte) ((commandOpcode >>> 8) & 0xff)
            });
        }

        // Type 3 is RESPONSE. Its payload is response data, not an opcode.
        // GetActiveGunIndex returns the selected slot at payload bytes 2-3.
        if (awaitingActiveGunResponse
                && parsed.type == 3
                && parsed.payload.length >= 4) {

            activeGunIndex = readU16(parsed.payload, 2);
            lastParsedOpcodeHex = String.format("%04x", OP_GET_ACTIVE_GUN);

            appendDebug("Active gun response received: slot=" + activeGunIndex);

            awaitingActiveGunResponse = false;
            awaitingGunProfile = true;
            gunProfileParts.clear();
            profilePartCount = 0;

            // A RESPONSE is acknowledged using the opcode of the request.
            writeEncryptedPacket(4, new byte[]{
                    (byte) (OP_GET_ACTIVE_GUN & 0xff),
                    (byte) ((OP_GET_ACTIVE_GUN >>> 8) & 0xff)
            });

            // Request the selected gun. The Kestrel returns multiple type-3
            // RESPONSE parts followed by a type-0 COMMAND carrying EOD.
            boolean requestQueued = writeEncryptedPacket(0, new byte[]{
                    (byte) (OP_GET_GUN & 0xff),
                    (byte) ((OP_GET_GUN >>> 8) & 0xff),
                    (byte) (activeGunIndex & 0xff),
                    (byte) ((activeGunIndex >>> 8) & 0xff)
            });
            if (!requestQueued) {
                clearProfileRefreshTimeout();
                profileRefreshInProgress = false;
                awaitingGunProfile = false;
                gunProfileParts.clear();
                lastError = "Unable to request the active Kestrel gun profile.";
                appendDebug(lastError);
            }
            return;
        }

        // Each gun-profile RESPONSE part is type 3 and is ACKed with 0x0087.
        if (awaitingGunProfile && parsed.type == 3) {
            gunProfileParts.add(parsed.payload.clone());
            profilePartCount = gunProfileParts.size();
            lastProfilePayloadHex = "<redacted; "
                    + parsed.payload.length + " bytes>";

            appendDebug("Gun profile response part received: "
                    + profilePartCount + ", bytes=" + parsed.payload.length);

            writeEncryptedPacket(4, new byte[]{
                    (byte) (OP_GET_GUN & 0xff),
                    (byte) ((OP_GET_GUN >>> 8) & 0xff)
            });
            return;
        }

        // End-of-data is a Kestrel COMMAND (type 0), ACKed with its own opcode.
        if (awaitingGunProfile
                && parsed.type == 0
                && parsed.payload.length >= 2
                && readU16(parsed.payload, 0) == OP_END_OF_DATA) {

            appendDebug("Gun profile end-of-data received");

            applyGunProfileUnits();
            clearProfileRefreshTimeout();
            profileRefreshInProgress = false;
            awaitingGunProfile = false;

            appendDebug("Gun profile refresh finished: parts="
                    + gunProfileParts.size()
                    + ", elevationUnit=" + elevationUnit
                    + ", windUnit=" + windUnit);
            startNextQueuedRange();
            return;
        }

        // GetRemoteSoln (0x00a0) returns the current target inputs in a
        // Kestrel COMMAND (0x00a1). ACK it, then use its DoF and inclination
        // values when composing the state-changing 0x0089 range update.
        if (parsed.type == 0
                && parsed.payload.length >= 109
                && readU16(parsed.payload, 0) == 0x00a1) {
            if (awaitingRangeInputs) {
                int responseType = parsed.payload[2] & 0xff;
                int responseCounter = parsed.payload[3] & 0xff;
                if (responseType != 0 || responseCounter != pendingRangeCounter) {
                    appendDebug("Ignoring stale/mismatched 0x00a1 response: type="
                            + responseType + ", counter=" + responseCounter
                            + ", expected=" + pendingRangeCounter);
                    return;
                }
                clearRangeInputTimeout();
                float currentDoF = readF32(parsed.payload, 2 + 27);
                float currentInclination = readF32(parsed.payload, 2 + 103);
                double requestedRange = pendingRangeMeters;
                awaitingRangeInputs = false;

                if (Float.isNaN(currentDoF) || Float.isInfinite(currentDoF)
                        || Float.isNaN(currentInclination) || Float.isInfinite(currentInclination)) {
                    cancelPendingRangeOperation(
                            "Kestrel returned invalid current inclination/DoF; range was not changed.");
                    lastError = "Kestrel returned invalid current inclination/DoF; range was not changed.";
                    status = "Error";
                    return;
                }

                appendDebug("Current target inputs read: inclination="
                        + currentInclination + " deg, DoF=" + currentDoF + " deg");
                if (!sendKestrelFullInputs(requestedRange,
                        currentInclination, currentDoF)) {
                    cancelPendingRangeOperation("Range update could not be started.");
                    if (lastError == null || lastError.isEmpty()) {
                        lastError = "Unable to queue Kestrel range update.";
                    }
                }
            }
            return;
        }

        // 0x008A is a Kestrel COMMAND carrying the calculated solution.
        // Its record begins after the two-byte opcode.
        if (parsed.type == 0
                && parsed.payload.length >= 92
                && readU16(parsed.payload, 0) == 0x008a) {

            lastParsedOpcodeHex = "008a";

            byte[] solution = copyRange(parsed.payload, 2, 92);
            if (solution.length >= 23) {
                lastElevationMil = readF32(solution, 11);
                lastWind1Mil = readF32(solution, 15);
                lastWind2Mil = readF32(solution, 19);
                status = "Connected";

                appendDebug("Solution 0x008a received: elevationMIL="
                        + lastElevationMil
                        + ", wind1MIL=" + lastWind1Mil
                        + ", wind2MIL=" + lastWind2Mil);
            }

            if (awaitingRangeSolution) {
                // 0x008A carries the calculated target range as u24 centimetres
                // at record offset +2 (packet payload offset +4). Only finish
                // this operation when the returned solution matches our request.
                int returnedRangeCm = (parsed.payload[4] & 0xff)
                        | ((parsed.payload[5] & 0xff) << 8)
                        | ((parsed.payload[6] & 0xff) << 16);
                double returnedRangeMeters = returnedRangeCm / 100.0;
                if (Math.abs(returnedRangeMeters - pendingRangeMeters) <= 0.02) {
                    lastRangeMeters = returnedRangeMeters;
                    cancelPendingRangeOperation(
                            "Kestrel range update completed; matching 0x008A solution received.");
                    lastError = "";
                    status = "Connected";
                } else {
                    appendDebug("Ignoring 0x008A solution with range=" + returnedRangeMeters
                            + " m; waiting for requested range=" + pendingRangeMeters + " m");
                }
            }
            return;
        }

        if (parsed.type == 5 && parsed.payload.length >= 4) {
            int rejectedOpcode = readU16(parsed.payload, 0);
            int reason = readU16(parsed.payload, 2);
            lastError = "Kestrel rejected command 0x"
                    + String.format("%04x", rejectedOpcode)
                    + ", reason " + reason;
            if (rejectedOpcode == 0x0094 || rejectedOpcode == 0x0087) {
                clearProfileRefreshTimeout();
                profileRefreshInProgress = false;
                awaitingActiveGunResponse = false;
                awaitingGunProfile = false;
                gunProfileParts.clear();
                profilePartCount = 0;
                appendDebug("Profile refresh cancelled after Kestrel NACK for 0x"
                        + String.format("%04x", rejectedOpcode));
            }
            if (rejectedOpcode == 0x00a0 || rejectedOpcode == 0x0089) {
                cancelPendingRangeOperation("Pending range update cancelled after Kestrel NACK.");
            }
            status = "Error";
            appendDebug(lastError);
        }
        // Type 4 packets are acknowledgements for our commands; no UI action.
    }

    @SuppressLint("MissingPermission")
    private boolean enqueueBleFrame(byte[] frame) {
        if (frame == null || frame.length == 0
                || gatt == null || linkWriteCharacteristic == null) {
            lastError = "Kestrel BLE write characteristic unavailable.";
            return false;
        }

        synchronized (bleWriteLock) {
            for (int offset = 0; offset < frame.length;
                 offset += BLE_WRITE_CHUNK_SIZE) {
                int length = Math.min(BLE_WRITE_CHUNK_SIZE, frame.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(frame, offset, chunk, 0, length);
                bleWriteQueue.add(chunk);
            }

            appendDebug("BLE frame queued: " + frame.length + " bytes, "
                    + ((frame.length + BLE_WRITE_CHUNK_SIZE - 1)
                    / BLE_WRITE_CHUNK_SIZE) + " chunks");

            if (!bleWriteInProgress) {
                return sendNextBleChunkLocked();
            }
            return true;
        }
    }

    // Must be called while holding bleWriteLock.
    @SuppressLint("MissingPermission")
    private boolean sendNextBleChunkLocked() {
        if (bleWriteInProgress || bleWriteQueue.isEmpty()) {
            return true;
        }

        if (gatt == null || linkWriteCharacteristic == null) {
            bleWriteQueue.clear();
            bleWriteInProgress = false;
            lastError = "Kestrel BLE connection lost while writing.";
            return false;
        }

        byte[] chunk = bleWriteQueue.peek();
        linkWriteCharacteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        linkWriteCharacteristic.setValue(chunk);

        boolean started = gatt.writeCharacteristic(linkWriteCharacteristic);
        if (!started) {
            bleWriteQueue.clear();
            bleWriteInProgress = false;
            lastError = "Android could not start BLE characteristic write.";
            appendDebug(lastError);
            return false;
        }

        bleWriteInProgress = true;
        appendDebug("BLE chunk write started: " + chunk.length + " bytes");
        return true;
    }

    @SuppressLint("MissingPermission")
    private void onBleWriteComplete(int statusCode) {
        synchronized (bleWriteLock) {
            if (!bleWriteInProgress) {
                appendDebug("Unexpected LiNK write callback; no chunk was pending");
                return;
            }

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                lastError = "LiNK BLE chunk write failed: GATT status " + statusCode;
                appendDebug(lastError);
                bleWriteQueue.clear();
                bleWriteInProgress = false;
                return;
            }

            bleWriteQueue.poll();
            bleWriteInProgress = false;

            if (!bleWriteQueue.isEmpty()) {
                sendNextBleChunkLocked();
            } else {
                appendDebug("BLE frame/write queue drained");
            }
        }
    }

    private boolean writeClearPacket(int type, byte[] payload) {
        if (linkWriteCharacteristic == null || gatt == null) {
            lastError = "Kestrel BLE connection unavailable.";
            return false;
        }
        byte[] packet = encodePacket(type, payload);
        byte[] frame = hdlcWrap(packet);
        appendDebug("Queueing clear packet: type=" + type
                + ", frameBytes=" + frame.length);
        return enqueueBleFrame(frame);
    }

    private boolean writeEncryptedPacket(int type, byte[] payload) {
        if (!kestrelAuthenticated || linkWriteCharacteristic == null || gatt == null) {
            lastError = "Kestrel authentication or BLE connection unavailable.";
            return false;
        }
        byte[] packet = encodePacket(type, payload);
        byte[] prefix = new byte[16];
        new SecureRandom().nextBytes(prefix);
        byte[] plaintext = concat(prefix, packet);
        while (plaintext.length % 16 != 0) {
            plaintext = concat(plaintext, new byte[]{0});
        }
        byte[] ciphertext = aesCbcEncrypt(plaintext);
        if (ciphertext.length == 0) {
            lastError = "Kestrel packet encryption failed.";
            return false;
        }
        byte[] frame = hdlcWrap(ciphertext);
        appendDebug("Queueing encrypted packet: type=" + type
                + ", frameBytes=" + frame.length);
        return enqueueBleFrame(frame);
    }

    private static byte[] hdlcWrap(byte[] body) {
        int crc = crcX25(body);
        byte[] raw = concat(body, new byte[]{
                (byte) (crc & 0xff), (byte) ((crc >>> 8) & 0xff)});
        byte[] escaped = new byte[raw.length * 2 + 2];
        int out = 0;
        escaped[out++] = 0x7e;
        for (byte value : raw) {
            if ((value & 0xff) == 0x7e || (value & 0xff) == 0x7d) {
                escaped[out++] = 0x7d;
                escaped[out++] = (byte) ((value & 0xff) ^ 0x20);
            } else {
                escaped[out++] = value;
            }
        }
        escaped[out++] = 0x7e;
        return copyRange(escaped, 0, out);
    }

    private static boolean isHdlcFrame(byte[] frame) {
        return frame != null && frame.length >= 4 && frame[0] == 0x7e
                && frame[frame.length - 1] == 0x7e;
    }

    private static byte[] unwrapHdlc(byte[] frame) {
        byte[] raw = new byte[frame.length];
        int rawLength = 0;
        boolean escaped = false;
        for (int i = 1; i < frame.length - 1; i++) {
            byte value = frame[i];
            if ((value & 0xff) == 0x7d) {
                escaped = true;
                continue;
            }
            raw[rawLength++] = escaped
                    ? (byte) ((value & 0xff) ^ 0x20)
                    : value;
            escaped = false;
        }
        if (rawLength < 2) {
            return null;
        }
        byte[] data = copyRange(raw, 0, rawLength - 2);
        int storedCrc = (raw[rawLength - 2] & 0xff)
                | ((raw[rawLength - 1] & 0xff) << 8);
        return crcX25(data) == storedCrc ? data : null;
    }

    private static int crcX25(byte[] data) {
        int crc = 0xffff;
        for (byte value : data) {
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0
                        ? (crc >>> 1) ^ 0x8408
                        : crc >>> 1;
            }
        }
        return (crc ^ 0xffff) & 0xffff;
    }

    private byte[] unwrapEncrypted(byte[] frame) {
        byte[] body = unwrapHdlc(frame);
        if (body == null || body.length < 32 || body.length % 16 != 0) {
            return null;
        }
        byte[] plaintext = aesCbcDecrypt(body);
        if (plaintext.length < 20) {
            return null;
        }
        byte[] packet = copyRange(plaintext, 16, plaintext.length);
        while (packet.length > 4) {
            Packet parsed = parsePacket(packet);
            if (parsed != null) {
                return packet;
            }
            packet = copyRange(packet, 0, packet.length - 1);
        }
        return packet;
    }

    private static byte[] encodePacket(int type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) type);
        buffer.putShort((short) payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private static Packet parsePacket(byte[] packet) {
        if (packet == null || packet.length < 4) {
            return null;
        }
        int type = readU16(packet, 0);
        int length = readU16(packet, 2);
        if (4 + length > packet.length) {
            return null;
        }
        return new Packet(type, copyRange(packet, 4, 4 + length));
    }

    private byte[] aesCbcEncrypt(byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(KESTREL_KEY, "AES"),
                    new IvParameterSpec(new byte[16]));
            byte[] padded = input.clone();
            if (padded.length % 16 != 0) {
                byte[] buffer = new byte[(padded.length + 15) / 16 * 16];
                System.arraycopy(padded, 0, buffer, 0, padded.length);
                padded = buffer;
            }
            return cipher.doFinal(padded);
        } catch (Exception e) {
            lastError = "AES encryption failed: " + e.getMessage();
            return new byte[0];
        }
    }

    private byte[] aesCbcDecrypt(byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(KESTREL_KEY, "AES"),
                    new IvParameterSpec(new byte[16]));
            return cipher.doFinal(input);
        } catch (Exception e) {
            lastError = "AES decryption failed: " + e.getMessage();
            return new byte[0];
        }
    }

    private byte[] KestrelProofBlock() {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(KESTREL_KEY, "AES"));
            return cipher.doFinal(Kestrel_C0());
        } catch (Exception e) {
            return new byte[16];
        }
    }

    private byte[] Kestrel_C0() {
        return KESTREL_C0;
    }

    private static void putI24(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >>> 8) & 0xff);
        target[offset + 2] = (byte) ((value >>> 16) & 0xff);
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static float readF32(byte[] data, int offset) {
        return Float.intBitsToFloat(
                (data[offset] & 0xff)
                        | ((data[offset + 1] & 0xff) << 8)
                        | ((data[offset + 2] & 0xff) << 16)
                        | ((data[offset + 3] & 0xff) << 24));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] output = new byte[first.length + second.length];
        System.arraycopy(first, 0, output, 0, first.length);
        System.arraycopy(second, 0, output, first.length, second.length);
        return output;
    }

    private static byte[] copyRange(byte[] source, int start, int end) {
        byte[] output = new byte[end - start];
        System.arraycopy(source, start, output, 0, output.length);
        return output;
    }

    private static boolean constantTimeEquals(byte[] first, byte[] second) {
        if (first == null || second == null || first.length != second.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < first.length; i++) {
            result |= (first[i] ^ second[i]);
        }
        return result == 0;
    }

    private static byte[] hexBytes(String hex) {
        String clean = hex.replaceAll("[^0-9a-fA-F]", "");
        byte[] output = new byte[clean.length() / 2];
        for (int i = 0; i < output.length; i++) {
            int index = i * 2;
            output[i] = (byte) Integer.parseInt(clean.substring(index, index + 2), 16);
        }
        return output;
    }

    private static class Packet {
        final int type;
        final byte[] payload;

        Packet(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static class ByteArrayAccumulator {
        private byte[] pending = new byte[0];

        void add(byte[] chunk) {
            if (chunk == null || chunk.length == 0) {
                return;
            }
            pending = concat(pending, chunk);
        }

        void clear() {
            pending = new byte[0];
        }

        byte[][] takeCompleteFrames() {
            List<byte[]> frames = new ArrayList<>();
            int start = -1;
            for (int i = 0; i < pending.length; i++) {
                if ((pending[i] & 0xff) == 0x7e) {
                    if (start < 0) {
                        start = i;
                    } else {
                        byte[] frame = copyRange(pending, start, i + 1);
                        frames.add(frame);
                        pending = copyRange(pending, i + 1, pending.length);
                        start = -1;
                        i = -1;
                    }
                }
            }
            if (start >= 0 && start > 0) {
                pending = copyRange(pending, start, pending.length);
            }
            return frames.toArray(new byte[0][]);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        char[] hex = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            output[i * 2] = hex[value >>> 4];
            output[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(output);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String clean = hex.replaceAll("[^0-9a-fA-F]", "");
        if (clean.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] output = new byte[clean.length() / 2];
        for (int i = 0; i < output.length; i++) {
            int index = i * 2;
            output[i] = (byte) Integer.parseInt(clean.substring(index, index + 2), 16);
        }
        return output;
    }
}
