package com.android.server.droiduse;

import android.os.Bundle;
import android.os.SystemClock;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.IRemoteComputerControlInputConnection;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.DroidUseWindowSnapshot;
import dev.droiduse.system.DroidUseContract;
import dev.droiduse.system.InputOperation;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Binds every edit to a probed input connection, window, display and editor generation. */
final class DroidUseEditor {
    private final DroidUseClipboard clipboard = new DroidUseClipboard();
    private IRemoteComputerControlInputConnection connection;
    private Bundle identity;
    private long generation;
    private String[] actions = new String[0];

    long generation() { return generation; }
    String[] actions() { return actions.clone(); }

    void observe(int display, DroidUseWindowSnapshot window) {
        invalidate();
        generation++;
        if (window.secure) return;
        InputMethodManagerInternal ime = LocalServices.getService(InputMethodManagerInternal.class);
        if (ime == null) return;
        IRemoteComputerControlInputConnection candidate =
                ime.getComputerControlInputConnection(0, display);
        if (candidate == null) return;
        Bundle request = new Bundle();
        request.putBinder("window", window.token);
        request.putInt("uid", window.uid);
        request.putInt("display", display);
        request.putString("action", "probe");
        try {
            Bundle reply = call(candidate, request);
            if (!"OK".equals(reply.getString("status"))) return;
            request.putInt("session", reply.getInt("session", -1));
            request.putInt("start", reply.getInt("start", -1));
            request.putInt("end", reply.getInt("end", -1));
            connection = candidate;
            identity = request;
            ArrayList<String> supported = new ArrayList<>();
            supported.add("text"); supported.add("edit_select"); supported.add("edit_select_all");
            if (reply.getBoolean("selected")) { supported.add("edit_copy"); supported.add("edit_cut"); }
            if (clipboard.readForPaste() != null) supported.add("edit_paste");
            actions = supported.toArray(new String[0]);
        } catch (Exception ignored) {
            // No editor capability on a dead, slow, or unsupported connection. Never log text.
        }
    }

    void execute(InputOperation input) throws Exception {
        if (connection == null || identity == null || input.editorGeneration != generation)
            throw failure(DroidUseContract.STALE_OBSERVATION);
        String action = switch (input.kind) {
            case DroidUseContract.INPUT_TEXT -> "text";
            case DroidUseContract.INPUT_SELECTION -> "select";
            case DroidUseContract.INPUT_SELECT_ALL -> "select_all";
            case DroidUseContract.INPUT_COPY -> "copy";
            case DroidUseContract.INPUT_CUT -> "cut";
            case DroidUseContract.INPUT_PASTE -> "paste";
            default -> "";
        };
        if (!java.util.Arrays.asList(actions).contains("text".equals(action) ? action : "edit_" + action))
            throw failure(DroidUseContract.UNSUPPORTED);
        InputMethodManagerInternal ime = LocalServices.getService(InputMethodManagerInternal.class);
        IRemoteComputerControlInputConnection current = ime == null ? null
                : ime.getComputerControlInputConnection(0, identity.getInt("display"));
        if (current == null || !connection.asBinder().equals(current.asBinder())) {
            invalidate();
            throw failure(DroidUseContract.STALE_OBSERVATION);
        }
        Bundle request = new Bundle(identity);
        request.putString("action", action);
        request.putInt("from", input.selectionStart); request.putInt("to", input.selectionEnd);
        if ("text".equals(action)) request.putString("text", input.text);
        if ("paste".equals(action)) request.putString("text", clipboard.readForPaste());
        try {
            Bundle reply = call(connection, request);
            String status = reply.getString("status", DroidUseContract.UNKNOWN_OUTCOME);
            if (!"OK".equals(status)) throw failure(status);
            if ("copy".equals(action) || "cut".equals(action))
                clipboard.replace(reply.getString("text"));
        } catch (Exception error) {
            // A timed-out cut may have deleted text: do not retain an unrelated previous clip.
            if ("copy".equals(action) || "cut".equals(action)) clipboard.clear();
            if (error instanceof DroidUsePlatform.Failure) throw error;
            throw failure(DroidUseContract.UNKNOWN_OUTCOME);
        } finally { invalidate(); }
    }

    void invalidate() { connection = null; identity = null; actions = new String[0]; }
    void close() { invalidate(); clipboard.close(); }

    private static Bundle call(IRemoteComputerControlInputConnection connection, Bundle request)
            throws Exception {
        AndroidFuture<Bundle> future = new AndroidFuture<>();
        request.putLong("deadline", SystemClock.elapsedRealtime() + 1000);
        connection.performDroidUseEdit(request, future);
        return future.get(1200, TimeUnit.MILLISECONDS);
    }

    private static DroidUsePlatform.Failure failure(String code) {
        int error = DroidUseContract.STALE_OBSERVATION.equals(code)
                ? DroidUseContract.ERROR_INVALID_REQUEST : DroidUseContract.ERROR_UNKNOWN;
        return new DroidUsePlatform.Failure(error, code);
    }
}
