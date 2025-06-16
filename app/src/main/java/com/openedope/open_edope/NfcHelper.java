package com.openedope.open_edope;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.os.Parcelable;
import android.util.Log;

public class NfcHelper {

    // --- Overloads for backward compatibility (no listener) ---

    public static void processNfcIntentByteBufferAsync(final Intent intent, final int width0, final int height0, final java.nio.ByteBuffer buffer, final String[] epd_init) {
        processNfcIntentByteBufferAsync(intent, width0, height0, buffer, epd_init, null);
    }

    public static void processNfcIntentByteBuffer(final Intent intent, final int width0, final int height0, final java.nio.ByteBuffer buffer, final String[] epd_init) {
        processNfcIntentByteBuffer(intent, width0, height0, buffer, epd_init, null);
    }

    public static void processNfcIntent(final Intent intent, final int width0, final int height0, final byte[] image_buffer, final String[] epd_init) {
        processNfcIntent(intent, width0, height0, image_buffer, epd_init, null);
    }

    // --- Main methods with listener support ---

    public static void processNfcIntentByteBufferAsync(final Intent intent, final int width0, final int height0, final java.nio.ByteBuffer buffer, final String[] epd_init, final NfcProgressListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                processNfcIntentByteBuffer(intent, width0, height0, buffer, epd_init, listener);
            }
        }).start();
    }

    public static void processNfcIntentByteBuffer(Intent intent, int width0, int height0, java.nio.ByteBuffer buffer, String[] epd_init, NfcProgressListener listener) {
        buffer.rewind(); // Always reset position before reading
        byte[] image_buffer = new byte[buffer.remaining()];
        buffer.get(image_buffer);

        // Defensive checks
        int expectedSize = width0 * height0 / 8;
        if (image_buffer == null || epd_init == null || epd_init.length < 2) {
            Log.e("NfcHelper", "Null or invalid arguments!");
            if (listener != null) listener.onError("Null or invalid arguments.");
            return;
        }
        if (image_buffer.length != expectedSize) {
            Log.e("NfcHelper", "ERROR: image_buffer size (" + image_buffer.length + ") does not match expected (" + expectedSize + ")");
            if (listener != null) listener.onError("Image buffer size mismatch.");
            return;
        }

        processNfcIntent(intent, width0, height0, image_buffer, epd_init, listener);
    }

    public static void processNfcIntent(Intent intent, int width0, int height0, byte[] image_buffer, String[] epd_init, NfcProgressListener listener) {
        Log.e("NfcHelper", "processNfcIntent CALLED" + (listener != null ? " (with progress listener)" : ""));
        Log.e("NfcHelper", "image_buffer class in processNfcIntent: " + image_buffer.getClass().getName());
        Parcelable p = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (p == null) {
            Log.e("NfcHelper", "No NFC tag found in intent!");
            return;
        }
        Tag tag = (Tag) p;
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep != null) {
            try {
                isoDep.connect();
                Log.e("NfcHelper", "IsoDep connected: " + isoDep.isConnected());
                isoDep.setTimeout(60000);

                // Send DIY command before init
                byte[] diyCmd = hexStringToBytes("F0DB020000");
                byte[] response = isoDep.transceive(diyCmd);
                Log.e("diy_state", hexToString(response));

                // Now send the main init command as before
                byte[] cmd = hexStringToBytes(epd_init[0]);
                response = isoDep.transceive(cmd);
                Log.e("epdinit_state", hexToString(response));
                // Check for success (0x90 at end)
                if (response.length >= 2 && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == (byte) 0x00) {
                    Log.e("NfcHelper", "Init command success");
                } else {
                    Log.e("NfcHelper", "Init command failed");
                }

                cmd = hexStringToBytes(epd_init[1]);
                response = isoDep.transceive(cmd);
                Log.e("epdinit_state", hexToString(response));

                int datas = width0 * height0 / 8;
                int chunkSize = 250; // Increased chunk size to 250
                int maxRetries = 3;
                int totalChunks = datas / chunkSize;
                // Send BW buffer
                for (int i = 0; i < totalChunks; i++) {
                    cmd = new byte[5 + chunkSize];
                    cmd[0] = (byte) 0xF0;
                    cmd[1] = (byte) 0xD2;
                    cmd[2] = 0x00; // BW index
                    cmd[3] = (byte) i;
                    cmd[4] = (byte) chunkSize;
                    for (int j = 0; j < chunkSize; j++) {
                        cmd[j + 5] = image_buffer[j + chunkSize * i];
                    }
                    int attempt = 0;
                    boolean success = false;
                    while (attempt < maxRetries && !success) {
                        try {
                            response = isoDep.transceive(cmd);
                            Log.e((i + 1) + " sendData_state:", hexToString(response));
                            success = true;
                        } catch (Exception e) {
                            attempt++;
                            Log.e("NfcHelper", "Retry " + attempt + " for chunk " + i + ": " + e);
                            if (attempt == maxRetries) throw e;
                        }
                    }
                    // Progress callback for BW only
                    if (listener != null) {
                        int percent = (int)(((i + 1) * 100.0) / totalChunks);
                        listener.onProgress(percent);
                    }
                }

                // Send R buffer (inverted) -- no progress callback here
                for (int i = 0; i < totalChunks; i++) {
                    cmd = new byte[5 + chunkSize];
                    cmd[0] = (byte) 0xF0;
                    cmd[1] = (byte) 0xD2;
                    cmd[2] = 0x01; // R index
                    cmd[3] = (byte) i;
                    cmd[4] = (byte) chunkSize;
                    for (int j = 0; j < chunkSize; j++) {
                        cmd[j + 5] = (byte) ~image_buffer[j + chunkSize * i];
                    }
                    int attempt = 0;
                    boolean success = false;
                    while (attempt < maxRetries && !success) {
                        try {
                            response = isoDep.transceive(cmd);
                            Log.e((i + 1) + " sendData_state:", hexToString(response));
                            success = true;
                        } catch (Exception e) {
                            attempt++;
                            Log.e("NfcHelper", "Retry " + attempt + " for chunk " + i + ": " + e);
                            if (attempt == maxRetries) throw e;
                        }
                    }
                }

                // Java, after the main chunk loop
                int tail = datas % chunkSize;
                if (tail != 0) {
                    cmd = new byte[5 + chunkSize];
                    cmd[0] = (byte) 0xF0;
                    cmd[1] = (byte) 0xD2;
                    cmd[2] = 0x00;
                    cmd[3] = (byte) (datas / chunkSize);
                    cmd[4] = (byte) chunkSize;
                    for (int j = 0; j < chunkSize; j++) {
                        if (j < tail) {
                            cmd[j + 5] = image_buffer[j + chunkSize * (datas / chunkSize)];
                        } else {
                            cmd[j + 5] = 0; // pad with zeros
                        }
                    }
                    response = isoDep.transceive(cmd);
                }

                // Send refresh command and check response
                byte[] refreshCmd = new byte[] {(byte)0xF0, (byte)0xD4, (byte)0x05, (byte)0x80, (byte)0x00};
                response = isoDep.transceive(refreshCmd);
                Log.e("RefreshData1_state:", hexToString(response));
                if (response.length >= 2 && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == (byte) 0x00) {
                    Log.e("NfcHelper", "Refresh command success");
                } else {
                    Log.e("NfcHelper", "Refresh command failed");
                }
            } catch (Exception e) {
                Log.e("NfcHelper", "IsoDep Exception: " + e);
                if (listener != null) {
                    listener.onError("NFC error: " + e.getMessage());
                }
            } finally {
                try {
                    isoDep.close();
                } catch (Exception ignored) {}
            }
        } else {
            Log.e("NfcHelper", "IsoDep not supported, falling back to NfcA");
            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                try {
                    Log.e("NfcHelper", "Before connect, isConnected: " + nfcA.isConnected());
                    Log.e("NfcHelper", "Attempting to connect to NFC tag...");
                    nfcA.connect();
                    Log.e("NfcHelper", "After connect, isConnected: " + nfcA.isConnected());
                    Log.e("NfcHelper", "NFC tag connected: " + nfcA.toString());
                    Log.e("NfcHelper", "Tag timeout (ms): " + nfcA.getTimeout());
                    byte[] cmd;
                    byte[] response;
                    nfcA.setTimeout(60000);

                    // Send DIY command before init
                    byte[] diyCmd = hexStringToBytes("F0DB020000");
                    response = nfcA.transceive(diyCmd);
                    Log.e("diy_state", hexToString(response));

                    // Now send the main init command as before
                    cmd = hexStringToBytes(epd_init[0]);
                    response = nfcA.transceive(cmd);
                    Log.e("epdinit_state", hexToString(response));

                    cmd = hexStringToBytes(epd_init[1]);
                    response = nfcA.transceive(cmd);
                    Log.e("epdinit_state", hexToString(response));

                    int datas = width0 * height0 / 8;
                    if (image_buffer.length != datas) {
                        Log.e("NfcHelper", "WARNING: Image buffer size (" + image_buffer.length +
                              ") does not match expected size (" + datas + ") for " +
                              width0 + "x" + height0 + " display.");
                    }
                    int chunkSize = 250; // Increased chunk size to 250
                    int maxRetries = 3;
                    int totalChunks = datas / chunkSize;
                    // Send BW buffer
                    for (int i = 0; i < totalChunks; i++) {
                        cmd = new byte[5 + chunkSize];
                        cmd[0] = (byte) 0xF0;
                        cmd[1] = (byte) 0xD2;
                        cmd[2] = 0x00; // BW index
                        cmd[3] = (byte) i;
                        cmd[4] = (byte) chunkSize;
                        for (int j = 0; j < chunkSize; j++) {
                            cmd[j + 5] = image_buffer[j + chunkSize * i];
                        }
                        int attempt = 0;
                        boolean success = false;
                        while (attempt < maxRetries && !success) {
                            try {
                                response = nfcA.transceive(cmd);
                                Log.e((i + 1) + " sendData_state:", hexToString(response));
                                success = true;
                            } catch (Exception e) {
                                attempt++;
                                Log.e("NfcHelper", "Retry " + attempt + " for chunk " + i + ": " + e);
                                if (attempt == maxRetries) throw e;
                            }
                        }
                        // Progress callback for BW only
                        if (listener != null) {
                            int percent = (int)(((i + 1) * 100.0) / totalChunks);
                            listener.onProgress(percent);
                        }
                    }

                    // Send R buffer (inverted) -- no progress callback here
                    for (int i = 0; i < totalChunks; i++) {
                        cmd = new byte[5 + chunkSize];
                        cmd[0] = (byte) 0xF0;
                        cmd[1] = (byte) 0xD2;
                        cmd[2] = 0x01; // R index
                        cmd[3] = (byte) i;
                        cmd[4] = (byte) chunkSize;
                        for (int j = 0; j < chunkSize; j++) {
                            cmd[j + 5] = (byte) ~image_buffer[j + chunkSize * i];
                        }
                        int attempt = 0;
                        boolean success = false;
                        while (attempt < maxRetries && !success) {
                            try {
                                response = nfcA.transceive(cmd);
                                Log.e((i + 1) + " sendData_state:", hexToString(response));
                                success = true;
                            } catch (Exception e) {
                                attempt++;
                                Log.e("NfcHelper", "Retry " + attempt + " for chunk " + i + ": " + e);
                                if (attempt == maxRetries) throw e;
                            }
                        }
                    }

                    // Java, after the main chunk loop
                    int tail = datas % chunkSize;
                    if (tail != 0) {
                        cmd = new byte[5 + chunkSize];
                        cmd[0] = (byte) 0xF0;
                        cmd[1] = (byte) 0xD2;
                        cmd[2] = 0x00;
                        cmd[3] = (byte) (datas / chunkSize);
                        cmd[4] = (byte) chunkSize;
                        for (int j = 0; j < chunkSize; j++) {
                            if (j < tail) {
                                cmd[j + 5] = image_buffer[j + chunkSize * (datas / chunkSize)];
                            } else {
                                cmd[j + 5] = 0; // pad with zeros
                            }
                        }
                        response = nfcA.transceive(cmd);
                    }

                    // Send refresh command and check response
                    byte[] refreshCmd = new byte[] {(byte)0xF0, (byte)0xD4, (byte)0x05, (byte)0x80, (byte)0x00};
                    response = nfcA.transceive(refreshCmd);
                    Log.e("RefreshData1_state:", hexToString(response));
                    if (response.length >= 2 && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == (byte) 0x00) {
                        Log.e("NfcHelper", "Refresh command success");
                    } else {
                        Log.e("NfcHelper", "Refresh command failed");
                    }
                } catch (Exception e) {
                    Log.e("NfcHelper", "NfcA Exception: " + e);
                    if (listener != null) {
                        listener.onError("NFC error: " + e.getMessage());
                    }
                } finally {
                    try {
                        nfcA.close();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // --- Utility methods ---

    public static byte[] hexStringToBytes(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                                 + Character.digit(hexString.charAt(i+1), 16));
        }
        return data;
    }

    public static String hexToString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    public static byte[] transceive(Intent intent, byte[] command) {
        try {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            IsoDep isoDep = IsoDep.get(tag);
            isoDep.connect();
            byte[] response = isoDep.transceive(command);
            isoDep.close();
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}