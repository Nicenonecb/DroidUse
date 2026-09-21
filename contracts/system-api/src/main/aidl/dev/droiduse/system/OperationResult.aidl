package dev.droiduse.system;

/** @hide */
parcelable OperationResult {
    String sessionId;
    String requestId;
    String code;
    String message;
    long completedAtElapsedRealtimeMs;
    long resultingFrameId;
}
