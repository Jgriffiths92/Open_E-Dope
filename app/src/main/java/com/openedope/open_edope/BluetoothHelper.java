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
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class BluetoothHelper {
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
    private final ByteArrayAccumulator indicationAccumulator = new ByteArrayAccumulator();
    private boolean kestrelAuthenticated;
    private boolean privacyResponseReceived;
    private boolean requestAuthSent;
    private byte[] kestrelNonce;
    private byte[] hostNonce;
    private double lastElevationMil = Double.NaN;
    private double lastWind1Mil = Double.NaN;
    private double lastWind2Mil = Double.NaN;
    private double lastRangeMeters;

    public BluetoothHelper(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager != null ? manager.getAdapter() : null;
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
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            status = "Error";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
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
            return gatt != null;
        } catch (Exception e) {
            lastError = e.getMessage();
            status = "Error";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        connectionState = BluetoothProfile.STATE_DISCONNECTED;
        kestrelAuthenticated = false;
        privacyResponseReceived = false;
        requestAuthSent = false;
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

    public boolean isKestrelAuthenticated() {
        return kestrelAuthenticated;
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

    @SuppressLint("MissingPermission")
    public boolean sendKestrelRange(double rangeMeters, double inclinationDegrees,
                                    double dofDegrees) {
        if (!kestrelAuthenticated || linkWriteCharacteristic == null) {
            lastError = "Kestrel authentication is not complete.";
            return false;
        }
        lastRangeMeters = rangeMeters;

        byte[] args = new byte[28];
        args[0] = 0;
        args[1] = 0;
        putI24(args, 2, (int) Math.round(rangeMeters * 100.0));
        putI24(args, 5, (int) Math.round(inclinationDegrees * 1000.0));
        putI24(args, 8, (int) Math.round(dofDegrees * 1000.0));
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
        return writeEncryptedPacket(0, command);
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
        int writeType = withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        characteristic.setWriteType(writeType);
        characteristic.setValue(value);
        lastError = "";
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
            String name = device.getName();
            if (name != null && !name.isEmpty()) {
                scanResults.put(address, name);
            } else if (!scanResults.containsKey(address)) {
                scanResults.put(address, "");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            lastError = "BLE scan failed with error " + errorCode;
            status = "Error";
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int statusCode, int newState) {
            connectionState = newState;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status = "Discovering services";
                bluetoothGatt.discoverServices();
            } else {
                status = newState == BluetoothProfile.STATE_DISCONNECTED
                        ? "Disconnected"
                        : "Connection error";
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int statusCode) {
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
                return;
            }
            linkIndicationCharacteristic =
                    linkService.getCharacteristic(LINK_INDICATION);
            linkWriteCharacteristic =
                    linkService.getCharacteristic(LINK_WRITE);
            if (linkIndicationCharacteristic == null || linkWriteCharacteristic == null) {
                lastError = "Kestrel LiNK characteristics not found.";
                status = "Error";
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
            lastCharacteristicValue = bytesToHex(characteristic.getValue());
            if (characteristic.getUuid().equals(LINK_INDICATION)) {
                handleKestrelIndication(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                lastError = "Characteristic write status " + statusCode;
            }
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
        privacyResponseReceived = false;
        requestAuthSent = false;
        kestrelAuthenticated = false;
        status = "Authenticating Kestrel";
        writeClearPacket(0, new byte[]{(byte) 0x81, 0x00});
    }

    private void handleKestrelIndication(byte[] chunk) {
        indicationAccumulator.add(chunk);
        byte[][] frames = indicationAccumulator.takeCompleteFrames();
        for (byte[] frame : frames) {
            if (!kestrelAuthenticated) {
                handleClearPacket(frame);
            } else {
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
                kestrelNonce = copyRange(parsed.payload, 2, 18);
                writeClearPacket(4, new byte[]{0x7f, 0x00});
                byte[] proof = aesCbcEncrypt(concat(KestrelProofBlock(), kestrelNonce));
                byte[] proofPacket = concat(proof, new byte[]{0, 0, 0, 0});
                writeClearPacket(3, proofPacket);
                return;
            }
            if (opcode == 0x007e && parsed.payload.length >= 4) {
                writeClearPacket(4, new byte[]{0x7e, 0x00});
                hostNonce = new byte[16];
                new SecureRandom().nextBytes(hostNonce);
                writeClearPacket(0, concat(new byte[]{0x7f, 0x00}, hostNonce));
                return;
            }
        }
        if (parsed.type == 3 && parsed.payload.length >= 36 && hostNonce != null) {
            byte[] plaintext = aesCbcDecrypt(copyRange(parsed.payload, 0, 32));
            boolean verified = plaintext.length >= 32
                    && constantTimeEquals(copyRange(plaintext, 16, 32), hostNonce);
            writeClearPacket(4, new byte[]{0x7f, 0x00});
            if (verified) {
                kestrelAuthenticated = true;
                status = "Connected";
                lastError = "";
            } else {
                lastError = "Kestrel mutual authentication failed.";
                status = "Error";
            }
        }
    }

    private void handleEncryptedFrame(byte[] frame) {
        byte[] packet = unwrapEncrypted(frame);
        if (packet == null) {
            lastError = "Invalid encrypted Kestrel frame.";
            return;
        }
        Packet parsed = parsePacket(packet);
        if (parsed == null) {
            return;
        }
        if (parsed.type == 0 && parsed.payload.length >= 92) {
            int opcode = readU16(parsed.payload, 0);
            if (opcode == 0x008a) {
                byte[] solution = copyRange(parsed.payload, 2,
                        Math.min(parsed.payload.length, 92));
                if (solution.length >= 23) {
                    lastElevationMil = readF32(solution, 11);
                    lastWind1Mil = readF32(solution, 15);
                    lastWind2Mil = readF32(solution, 19);
                    status = "Connected";
                }
                writeEncryptedPacket(4, new byte[]{(byte) 0x8a, 0x00});
            }
        } else if (parsed.type == 4 && parsed.payload.length >= 2) {
            // Command acknowledgement; no additional UI action required.
        } else if (parsed.type == 5 && parsed.payload.length >= 4) {
            int reason = readU16(parsed.payload, 2);
            lastError = "Kestrel rejected command, reason " + reason;
            status = "Error";
        }
    }

    private boolean writeClearPacket(int type, byte[] payload) {
        if (linkWriteCharacteristic == null || gatt == null) {
            return false;
        }
        byte[] packet = encodePacket(type, payload);
        byte[] frame = hdlcWrap(packet);
        linkWriteCharacteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        linkWriteCharacteristic.setValue(frame);
        return gatt.writeCharacteristic(linkWriteCharacteristic);
    }

    private boolean writeEncryptedPacket(int type, byte[] payload) {
        if (!kestrelAuthenticated || linkWriteCharacteristic == null || gatt == null) {
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
        byte[] frame = hdlcWrap(ciphertext);
        linkWriteCharacteristic.setWriteType(
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        linkWriteCharacteristic.setValue(frame);
        return gatt.writeCharacteristic(linkWriteCharacteristic);
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
