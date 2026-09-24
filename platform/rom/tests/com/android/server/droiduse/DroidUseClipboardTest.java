package com.android.server.droiduse;

/** Host regression: run with javac/java, no device or Android stubs required. */
public final class DroidUseClipboardTest {
    public static void main(String[] args) {
        DroidUseClipboard first = new DroidUseClipboard();
        DroidUseClipboard second = new DroidUseClipboard();
        check(first.readForPaste() == null);
        first.replace("中文\nplain text");
        check("中文\nplain text".equals(first.readForPaste()));
        check(second.readForPaste() == null);
        reject(() -> first.replace("x".repeat(4001)));
        check("中文\nplain text".equals(first.readForPaste()));
        reject(() -> first.replace(null));
        reject(() -> first.replace(""));
        first.replace("x".repeat(4000));
        check(first.readForPaste().length() == 4000);
        first.clear();
        check(first.readForPaste() == null);
        first.replace("discard on close");
        first.close();
        check(first.readForPaste() == null);
        reject(() -> first.replace("late completion"));
        second.replace("independent");
        check("independent".equals(second.readForPaste()));
        System.out.println("Clipboard isolation, bounds, failure clearing and lifecycle passed");
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection");
    }
}
