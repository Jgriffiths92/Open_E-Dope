package com.openedope.open_edope;

public interface NfcProgressListener {
    void onProgress(int percent);
    void onRefreshSuccess();
    void onError(String message);
    void onRefreshError(String message);
}