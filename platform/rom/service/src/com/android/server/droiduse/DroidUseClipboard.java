package com.android.server.droiduse;

/** Per-session plain text only. No persistence, global clipboard, logging, or client export. */
final class DroidUseClipboard {
    private String text;
    private boolean closed;

    void replace(String value) {
        if (closed || value == null || value.isEmpty() || value.length() > 4000)
            throw new IllegalArgumentException("invalid session clipboard");
        text = value;
    }

    String readForPaste() { return closed ? null : text; }
    void clear() { text = null; }
    void close() { clear(); closed = true; }
}
