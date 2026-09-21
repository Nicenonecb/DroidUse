package dev.droiduse.system;

/** @hide */
parcelable SessionUpdate {
    String sessionId;
    long epoch;
    int kind;
    String targetPackage;
    int targetTaskId;
    int requestedScreenshotFps;
    long timeoutMs;
}
