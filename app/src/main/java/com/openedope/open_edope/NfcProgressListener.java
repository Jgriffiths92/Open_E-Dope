package com.openedope.open_edope;

public interface NfcProgressListener {
    void onProgress(int percent);
    void onError(String message); 
}