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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BluetoothHelper {
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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
            }
        }
    };

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
