package com.openedope.open_edope;

/**
 * Callback interface for reporting NFC refresh progress and outcomes.
 */
public interface NfcProgressListener {
    /**
     * Reports the current refresh progress as a percentage.
     */
    void onProgress(int percent);

    /**
     * Called when the refresh completes successfully.
     */
    void onRefreshSuccess();

    /**
     * Called when a general NFC operation error occurs.
     */
    void onError(String message);

    /**
     * Called when the refresh operation fails.
     */
    void onRefreshError(String message);
}
