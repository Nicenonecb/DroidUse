package dev.droiduse.telecom;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/** Non-UI system InCallService; the normal phone UI remains Telecom's UI service. */
public final class CallObserverService extends InCallService {
    @Override public void onCallAdded(Call call) { CallController.get(this).added(this, call); }
    @Override public void onCallRemoved(Call call) { CallController.get(this).removed(call); }
    @Override public void onCallAudioStateChanged(CallAudioState state) {
        CallController.get(this).audioChanged(state);
    }
    @Override public boolean onUnbind(Intent intent) {
        CallController.get(this).disconnected();
        return super.onUnbind(intent);
    }
}
