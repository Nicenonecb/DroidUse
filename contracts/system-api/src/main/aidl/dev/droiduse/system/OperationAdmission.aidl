package dev.droiduse.system;

/** @hide */
parcelable OperationAdmission {
    String sessionId;
    String requestId;
    boolean accepted;
    String code;
    String message;
    long acceptedAtElapsedRealtimeMs;
}
