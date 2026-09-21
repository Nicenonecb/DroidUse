package dev.droiduse.system;

/** @hide */
parcelable SemanticNode {
    String nodeId;
    String parentNodeId;
    String role;
    String text;
    String contentDescription;
    int left;
    int top;
    int right;
    int bottom;
    long actionMask;
    long stateMask;
}
