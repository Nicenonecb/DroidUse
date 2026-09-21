package dev.droiduse.system;

/** @hide */
parcelable SessionHandle {
    String sessionId;
    long epoch;
    int userId;
    int mode;
    int displayId;
    long openedAtElapsedRealtimeMs;
}
