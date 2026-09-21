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
}
