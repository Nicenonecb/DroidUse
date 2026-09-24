package dev.droiduse.system;

import android.os.ParcelFileDescriptor;

/** @hide */
parcelable StreamHandle {
    String streamId;
    String sessionId;
    int kind;
    int direction;
    int format;
    int sampleRateHz;
    int channelCount;
    int capacityBytes;
    long openedAtElapsedRealtimeMs;
    ParcelFileDescriptor descriptor;
    // 0: legacy unspecified; 1: reliable pipe with CallPcmFrame headers and PCM16 mono.
    int transport = 0;
}
