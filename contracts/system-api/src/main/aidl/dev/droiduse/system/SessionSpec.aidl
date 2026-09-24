package dev.droiduse.system;

/** @hide */
parcelable SessionSpec {
    int userId;
    int mode;
    String targetPackage;
    int targetTaskId;
    int requestedDisplayId;
    int[] requestedCapabilities;
    int requestedScreenshotFps;
    long timeoutMs;
    boolean allowGlobalSettings = false;
    boolean allowCallAudio = false;
    @nullable String callAddress;
}
