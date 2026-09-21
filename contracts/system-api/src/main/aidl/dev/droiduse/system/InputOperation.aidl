package dev.droiduse.system;

import dev.droiduse.system.PointerSample;

/** @hide */
parcelable InputOperation {
    int kind;
    int x1;
    int y1;
    int x2;
    int y2;
    int durationMs;
    int keyCode;
    String text;
    int selectionStart;
    int selectionEnd;
    int deleteBefore;
    int deleteAfter;
    long editorGeneration;
    PointerSample[] pointerSamples;
}
