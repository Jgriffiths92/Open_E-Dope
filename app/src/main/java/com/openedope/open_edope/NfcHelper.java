package com.openedope.open_edope;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.os.Parcelable;
import android.util.Log;
import java.io.IOException;

public class NfcHelper {
    private static final String TAG = "NfcHelper";

    // Command constants
    private static final byte CMD_PREFIX_F0 = (byte) 0xF0;
    private static final byte CMD_DIY_DB = (byte) 0xDB;
    private static final byte CMD_SEND_DATA_D2 = (byte) 0xD2;
    private static final byte CMD_REFRESH_D4 = (byte) 0xD4;

    private static final byte IDX_BW_BUFFER = 0x00;
    private static final byte IDX_R_BUFFER = 0x01;

    // Config
    private static final int CHUNK_SIZE = 250; // Max data bytes per transceive for image
    private static final int MAX_RETRIES = 3;
    private static final int NFC_TIMEOUT_MS = 60000;

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
        Log.d(TAG, "JAVA: processNfcIntentByteBuffer entered. Listener: " + (listener != null ? listener.hashCode() : "null"));
        buffer.rewind(); // Always reset position before reading
        byte[] image_buffer = new byte[buffer.remaining()];
        buffer.get(image_buffer);

        int expectedSize = width0 * height0 / 8;
        if (image_buffer == null || epd_init == null || epd_init.length < 2) {
            Log.e(TAG, "Null or invalid arguments!");
            if (listener != null) {
                Log.d(TAG, "JAVA: Calling listener.onError (Null or invalid arguments). Listener: " + listener.hashCode());
                listener.onError("Null or invalid arguments.");
            }
            return;
        }
        if (image_buffer.length != expectedSize) {
            Log.e(TAG, "ERROR: image_buffer size (" + image_buffer.length + ") does not match expected (" + expectedSize + ")");
            if (listener != null) {
                Log.d(TAG, "JAVA: Calling listener.onError (Image buffer size mismatch). Listener: " + listener.hashCode());
                listener.onError("Image buffer size mismatch.");
            }
            return;
        }
        processNfcIntent(intent, width0, height0, image_buffer, epd_init, listener);
    }

    public static void processNfcIntent(Intent intent, int width0, int height0, byte[] image_buffer, String[] epd_init, NfcProgressListener listener) {
        Log.d(TAG, "JAVA: processNfcIntent CALLED. Listener: " + (listener != null ? listener.hashCode() : "null"));
        Log.d(TAG, "image_buffer length in processNfcIntent: " + image_buffer.length);

        Parcelable p = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (p == null) {
            Log.e(TAG, "No NFC tag found in intent!");
            if (listener != null) listener.onError("No NFC tag detected.");
            return;
        }
        Tag tag = (Tag) p;
        Log.d(TAG, "JAVA: Tag obtained: " + tag.toString() + ", ID: " + hexToString(tag.getId()));

        Object nfcTech = IsoDep.get(tag);
        String techType = "IsoDep";
        if (nfcTech == null) {
            nfcTech = NfcA.get(tag);
            techType = "NfcA";
            if (nfcTech == null) {
                Log.e(TAG, "Neither IsoDep nor NfcA is supported by this tag.");
                if (listener != null) {
                    Log.d(TAG, "JAVA: Calling listener.onError (Tag not supported). Listener: " + listener.hashCode());
                    listener.onError("Tag not supported.");
                }
                return;
            }
        }
        Log.d(TAG, "Using " + techType);

        try {
            connectToTag(nfcTech);
            setTagTimeout(nfcTech, NFC_TIMEOUT_MS);
            executeWriteProtocol(nfcTech, width0, height0, image_buffer, epd_init, listener);
        } catch (Exception e) {
            Log.e(TAG, techType + " Exception: " + e.getMessage(), e);
            if (listener != null) {
                Log.d(TAG, "JAVA: Calling listener.onError (NFC error from general catch). Listener: " + listener.hashCode());
                listener.onError("NFC error: " + e.getMessage());
            }
        } finally {
            closeTagConnection(nfcTech);
        }
    }

    private static void executeWriteProtocol(Object nfcTech, int width0, int height0, byte[] image_buffer, String[] epd_init, NfcProgressListener listener) throws IOException {
        // Send DIY command before init
        byte[] diyCmd = hexStringToBytes("F0DB020000"); // Consider making "F0DB020000" a constant
        byte[] response = transceiveWithRetry(nfcTech, diyCmd, "DIY_CMD", listener);
        Log.d(TAG, "DIY command response: " + hexToString(response));

        // Send main init command
        byte[] cmd = hexStringToBytes(epd_init[0]);
        response = transceiveWithRetry(nfcTech, cmd, "EPD_INIT_0", listener);
        Log.d(TAG, "EPD Init [0] response: " + hexToString(response));
        if (!isSuccessResponse(response)) {
            Log.w(TAG, "EPD Init [0] command possibly failed or no 9000 status.");
        } else {
            Log.i(TAG, "EPD Init [0] command success (9000).");
        }

        cmd = hexStringToBytes(epd_init[1]);
        response = transceiveWithRetry(nfcTech, cmd, "EPD_INIT_1", listener);
        Log.d(TAG, "EPD Init [1] response: " + hexToString(response));

        int totalDataBytes = width0 * height0 / 8;
        int numFullChunks = totalDataBytes / CHUNK_SIZE;

        // Send BW buffer
        Log.d(TAG, "Sending BW buffer...");
        for (int i = 0; i < numFullChunks; i++) {
            cmd = new byte[5 + CHUNK_SIZE];
            cmd[0] = CMD_PREFIX_F0;
            cmd[1] = CMD_SEND_DATA_D2;
            cmd[2] = IDX_BW_BUFFER;
            cmd[3] = (byte) i;      // Chunk index
            cmd[4] = (byte) CHUNK_SIZE; // Chunk data length
            System.arraycopy(image_buffer, i * CHUNK_SIZE, cmd, 5, CHUNK_SIZE);
            transceiveWithRetry(nfcTech, cmd, "BW_CHUNK_" + i, listener);

            if (listener != null) {
                // Progress based on full chunks of BW buffer
                int percent = (int) (((i + 1) * 100.0) / numFullChunks);
                Log.d(TAG, "JAVA: Calling listener.onProgress (" + percent + "%). Listener: " + listener.hashCode());
                listener.onProgress(percent);
            }
        }

        // --- Skip R buffer if this is a 2.9-inch display (detected by epd_init) ---
        boolean is29Inch = false;
        if (epd_init != null && epd_init.length > 0 && epd_init[0] != null) {
            // Match the actual prefix used in Python EPD_INIT_MAP for 2.9-inch
            String epdInit0 = epd_init[0].replaceAll("\\s+", "").toUpperCase();
            // Use the first 32 hex chars of your actual init string
            if (epdInit0.startsWith("F0DB000067A006012000800128A4010C")) {
                is29Inch = true;
            }
        }

        if (!is29Inch) {
            // Send R buffer (inverted)
            Log.d(TAG, "Sending R buffer (inverted)...");
            for (int i = 0; i < numFullChunks; i++) {
                cmd = new byte[5 + CHUNK_SIZE];
                cmd[0] = CMD_PREFIX_F0;
                cmd[1] = CMD_SEND_DATA_D2;
                cmd[2] = IDX_R_BUFFER;
                cmd[3] = (byte) i;      // Chunk index
                cmd[4] = (byte) CHUNK_SIZE; // Chunk data length
                for (int j = 0; j < CHUNK_SIZE; j++) {
                    cmd[j + 5] = (byte) ~image_buffer[j + i * CHUNK_SIZE];
                }
                transceiveWithRetry(nfcTech, cmd, "R_CHUNK_" + i, listener);
            }
        } else {
            Log.d(TAG, "Skipping R buffer for 2.9-inch display (detected by epd_init).");
        }

        // Handle tail data for BW buffer (if any)
        int tailBytes = totalDataBytes % CHUNK_SIZE;
        if (tailBytes != 0) {
            Log.d(TAG, "Sending BW tail (" + tailBytes + " bytes)..."); 
            cmd = new byte[5 + CHUNK_SIZE]; // Pad to full chunk_size for command structure
            cmd[0] = CMD_PREFIX_F0;
            cmd[1] = CMD_SEND_DATA_D2;
            cmd[2] = IDX_BW_BUFFER;
            cmd[3] = (byte) numFullChunks; // Index of the tail chunk
            cmd[4] = (byte) CHUNK_SIZE;   // Command expects full chunk declaration, actual data might be less
            System.arraycopy(image_buffer, numFullChunks * CHUNK_SIZE, cmd, 5, tailBytes);
            for (int j = tailBytes; j < CHUNK_SIZE; j++) {
                cmd[j + 5] = 0;
            }
            transceiveWithRetry(nfcTech, cmd, "BW_TAIL", listener);
            }
        }

        // Send refresh command
        byte[] refreshCmd = new byte[]{CMD_PREFIX_F0, CMD_REFRESH_D4, (byte) 0x05, (byte) 0x80, (byte) 0x00};
        response = transceiveWithRetry(nfcTech, refreshCmd, "REFRESH", listener);
        Log.d(TAG, "Refresh command response: " + hexToString(response));
        if (isSuccessResponse(response)) {
            Log.i(TAG, "Refresh command success (9000).");
            if (listener != null && numFullChunks == 0 && tailBytes > 0) { // If only tail was sent
                 Log.d(TAG, "JAVA: Calling listener.onProgress (100% - tail only). Listener: " + listener.hashCode());
                 listener.onProgress(100); // Ensure 100% if only a tail was sent
            }
        } else {
            Log.w(TAG, "Refresh command possibly failed or no 9000 status.");
        }
    }

    private static byte[] transceiveWithRetry(Object nfcTech, byte[] cmd, String cmdName, NfcProgressListener listener) throws IOException {
        byte[] response = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Log.v(TAG, "JAVA: Attempting " + cmdName + ", try " + (attempt + 1) + ". Tech: " + nfcTech.getClass().getSimpleName());
                response = doTransceive(nfcTech, cmd);
                Log.v(TAG, cmdName + " attempt " + (attempt + 1) + " response: " + hexToString(response));
                return response; // Success
            } catch (IOException e) {
                Log.w(TAG, cmdName + " attempt " + (attempt + 1) + " failed: " + e.getMessage(), e); // Log with exception
                if (attempt == MAX_RETRIES - 1) {
                    Log.e(TAG, "JAVA: Max retries reached for " + cmdName + ". Rethrowing.");
                    throw e; // Rethrow on last attempt
                }
                // Optional: Short delay before retry
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
        return response; // Should not be reached if MAX_RETRIES > 0
    }

    private static boolean isSuccessResponse(byte[] response) {
        return response != null && response.length >= 2 &&
               response[response.length - 2] == (byte) 0x90 &&
               response[response.length - 1] == (byte) 0x00;
    }

    // --- Tag Technology Abstraction Helpers ---

    private static void connectToTag(Object tech) throws IOException {
        Log.d(TAG, "JAVA: Connecting to tag. Tech: " + tech.getClass().getSimpleName());
        if (tech instanceof IsoDep) {
            ((IsoDep) tech).connect();
        } else if (tech instanceof NfcA) {
            ((NfcA) tech).connect();
        } else {
            throw new IOException("Unsupported tag technology for connect");
        }
        Log.i(TAG, "JAVA: " + tech.getClass().getSimpleName() + " connected.");
    }

    private static void closeTagConnection(Object tech) {
        if (tech == null) return;
        Log.d(TAG, "JAVA: Closing tag connection. Tech: " + tech.getClass().getSimpleName());
        try {
            if (tech instanceof IsoDep) {
                if (((IsoDep) tech).isConnected()) ((IsoDep) tech).close();
            } else if (tech instanceof NfcA) {
                if (((NfcA) tech).isConnected()) ((NfcA) tech).close();
            }
            Log.i(TAG, "JAVA: " + tech.getClass().getSimpleName() + " closed.");
        } catch (IOException e) {
            Log.w(TAG, "Error closing " + tech.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void setTagTimeout(Object tech, int timeoutMs) {
        if (tech instanceof IsoDep) {
            Log.d(TAG, "JAVA: Setting IsoDep timeout to " + timeoutMs + "ms");
            ((IsoDep) tech).setTimeout(timeoutMs);
        } else if (tech instanceof NfcA) {
            Log.d(TAG, "JAVA: Setting NfcA timeout to " + timeoutMs + "ms");
            ((NfcA) tech).setTimeout(timeoutMs);
        }
    }

    private static byte[] doTransceive(Object tech, byte[] data) throws IOException {
        Log.v(TAG, "JAVA: doTransceive. Tech: " + tech.getClass().getSimpleName() + ", Command: " + hexToString(data));
        if (tech instanceof IsoDep) {
            return ((IsoDep) tech).transceive(data);
        } else if (tech instanceof NfcA) {
            return ((NfcA) tech).transceive(data);
        }
        throw new IOException("Unsupported tag technology for transceive");
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
}