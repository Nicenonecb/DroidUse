package dev.droiduse.system;

/** @hide */
parcelable DeviceOperation {
    int kind;
    String targetPackage;
    String targetId;
    int intValue;
    boolean boolValue;
    String stringValue;
    String[] stringValues;
    String sourceStreamId;
}
