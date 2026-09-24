package android.view.inputmethod;

import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.TextView;

/**
 * Plain-text edits on the served view's looper. Never accesses ClipboardManager.
 * @hide
 */
public final class DroidUseEditorActions {
    private DroidUseEditorActions() {}

    public static Bundle perform(View view, InputConnection connection, int session,
            boolean deactivated, Bundle request) {
        try {
            return performChecked(view, connection, session, deactivated, request);
        } catch (RuntimeException ignored) {
            // Custom editors may throw after a mutation. Do not crash the app or replay the edit.
            Bundle result = new Bundle();
            result.putString("status", "UNKNOWN_OUTCOME");
            return result;
        }
    }

    private static Bundle performChecked(View view, InputConnection connection, int session,
            boolean deactivated, Bundle request) {
        Bundle result = new Bundle();
        result.putString("status", "STALE_OBSERVATION");
        if (request == null || deactivated || connection == null
                || !(view instanceof TextView editor) || !view.isAttachedToWindow()
                || !view.isFocused() || !view.hasWindowFocus() || view.getDisplay() == null
                || view.getDisplay().getDisplayId() != request.getInt("display", -1)
                || android.os.Process.myUid() != request.getInt("uid", -1)
                || !view.getWindowToken().equals(request.getBinder("window"))
                || SystemClock.elapsedRealtime() >= request.getLong("deadline", 0)) return result;
        String action = request.getString("action", "");
        if (!"probe".equals(action) && session != request.getInt("session", -1)) return result;
        result.putString("status", "UNSUPPORTED");
        if (view.getRootView().getLayoutParams() instanceof android.view.WindowManager.LayoutParams layout
                && (layout.flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0)
            return result;
        int inputType = editor.getInputType();
        int type = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        boolean password = type == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || type == InputType.TYPE_CLASS_NUMBER
                    && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if (!editor.isEnabled() || !editor.onCheckIsTextEditor() || password
                || editor.getTransformationMethod() instanceof PasswordTransformationMethod
                || editor.getEditableText() == null
                || BaseInputConnection.getComposingSpanStart(editor.getEditableText()) >= 0)
            return result;
        int start = editor.getSelectionStart();
        int end = editor.getSelectionEnd();
        if (start < 0 || end < 0) return result;
        if (!"probe".equals(action) && (start != request.getInt("start", -1)
                || end != request.getInt("end", -1))) {
            result.putString("status", "STALE_OBSERVATION");
            return result;
        }
        boolean ok;
        switch (action) {
            case "probe":
                result.putInt("session", session);
                result.putInt("start", start);
                result.putInt("end", end);
                result.putBoolean("selected", start != end && Math.abs(end - start) <= 4000);
                ok = true;
                break;
            case "copy":
            case "cut":
                if (start == end || Math.abs(end - start) > 4000) return result;
                CharSequence selected = connection.getSelectedText(0);
                if (selected == null || selected.length() == 0 || selected.length() > 4000)
                    return result;
                String plain = selected.toString();
                if (SystemClock.elapsedRealtime() >= request.getLong("deadline", 0)) return result;
                ok = "copy".equals(action) || connection.commitText("", 1);
                if (ok) result.putString("text", plain);
                break;
            case "paste":
            case "text":
                String text = request.getString("text");
                ok = text != null && !text.isEmpty() && text.length() <= 4000
                        && connection.commitText(text, 1);
                break;
            case "select_all":
                ok = connection.setSelection(0, editor.length());
                break;
            case "select":
                int from = request.getInt("from", -1);
                int to = request.getInt("to", -1);
                ok = from >= 0 && to >= from && to <= editor.length()
                        && connection.setSelection(from, to);
                break;
            default:
                return result;
        }
        result.putString("status", ok ? "OK" : "UNKNOWN_OUTCOME");
        return result;
    }
}
