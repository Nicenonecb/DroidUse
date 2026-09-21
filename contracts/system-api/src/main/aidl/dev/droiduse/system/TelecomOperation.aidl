package dev.droiduse.system;

/** @hide */
parcelable TelecomOperation {
    int kind;
    String callId;
    String address;
    String digits;
    int audioRoute;
    boolean enabled;
}
