package dev.droiduse.system;

/** @hide */
parcelable StreamSpec {
    String sessionId;
    long epoch;
    int kind;
    int direction;
    int format;
    int sampleRateHz;
    int channelCount;
    int capacityBytes;
}
