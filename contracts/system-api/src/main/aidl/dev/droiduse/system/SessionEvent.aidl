package dev.droiduse.system;

/** @hide */
parcelable SessionEvent {
    String sessionId;
    long epoch;
    int kind;
    String code;
    String message;
    long atElapsedRealtimeMs;
}
