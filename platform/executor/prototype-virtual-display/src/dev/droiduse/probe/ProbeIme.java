package dev.droiduse.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

/** Temporary diagnostic IME. Only shell may send commands; never logs typed content. */
public final class ProbeIme extends InputMethodService {
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            EditorInfo info = getCurrentInputEditorInfo();
            String expected = intent.getStringExtra("target");
            if (info == null || expected == null || !expected.equals(info.packageName)
                    || getCurrentInputConnection() == null) {
                Log.i("DroidUseLab", "IME_REFUSED expected=" + expected + " actual="
                        + (info == null ? "none" : info.packageName));
                return;
            }
            boolean result = getCurrentInputConnection().commitText(intent.getStringExtra("text"), 1);
            Log.i("DroidUseLab", "IME_COMMIT target=" + expected + " accepted=" + result);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        registerReceiver(receiver, new IntentFilter("dev.droiduse.probe.COMMIT"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
    }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        Log.i("DroidUseLab", "IME_EDITOR=" + info.packageName);
    }
    @Override public void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
