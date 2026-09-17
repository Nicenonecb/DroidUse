package dev.droiduse.ipc;
import android.os.Bundle;
import android.os.IBinder;
interface IExecutor {
    Bundle getCapabilities();
    Bundle beginSession(IBinder clientToken);
    Bundle getStatus(String sessionId);
    Bundle pauseSession(String sessionId);
    Bundle resumeSession(String sessionId);
    Bundle cancelSession(String sessionId);
    Bundle submitAction(String sessionId, String requestId, in Bundle action);
    Bundle observe(String sessionId);
}
