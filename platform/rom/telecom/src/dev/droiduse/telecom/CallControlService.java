package dev.droiduse.telecom;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceSpecificException;
import dev.droiduse.system.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Only system_server may cross this bridge. Calls and streams stay off its main thread. */
public final class CallControlService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private <T> T run(Callable<T> action) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) throw new SecurityException("SYSTEM_UID_REQUIRED");
        Object completion = new Object();
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicReference<T> outcome = new AtomicReference<>();
        FutureTask<T> task = new FutureTask<>(() -> {
            T value = action.call();
            synchronized (completion) {
                if (abandoned.get()) discard(value);
                else outcome.set(value);
            }
            return value;
        });
        main.post(task);
        try { return task.get(1500, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) {
            synchronized (completion) { abandoned.set(true); discard(outcome.getAndSet(null)); }
            task.cancel(false); main.removeCallbacks(task);
            main.post(() -> controller().shutdown());
            // Started actions can have an unknown outcome and must not be retried.
            throw new ServiceSpecificException(DroidUseContract.ERROR_TIMEOUT, "UNKNOWN_OUTCOME");
        } catch (InterruptedException e) {
            synchronized (completion) { abandoned.set(true); discard(outcome.getAndSet(null)); }
            task.cancel(false); main.removeCallbacks(task); Thread.currentThread().interrupt();
            main.post(() -> controller().shutdown());
            throw new ServiceSpecificException(DroidUseContract.ERROR_UNKNOWN, "UNKNOWN_OUTCOME");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SecurityException denied) throw denied;
            if (e.getCause() instanceof IllegalArgumentException invalid)
                throw new ServiceSpecificException(DroidUseContract.ERROR_INVALID_REQUEST, invalid.getMessage());
            throw new ServiceSpecificException(DroidUseContract.ERROR_UNSUPPORTED,
                    e.getCause() instanceof IllegalStateException ? e.getCause().getMessage() : "TELECOM_BACKEND_FAILURE");
        }
    }
    private static void discard(Object value) {
        if (value instanceof StreamHandle stream && stream.descriptor != null)
            try { stream.descriptor.close(); } catch (java.io.IOException ignored) { }
    }
    private CallController controller() { return CallController.get(this); }
    private final IDroidUseTelecom.Stub binder = new IDroidUseTelecom.Stub() {
        public CapabilityStatus[] getCapabilities() { return run(() -> controller().capabilities()); }
        public void open(SessionHandle h, SessionSpec s, IBinder owner) {
            run(() -> { controller().open(h, s, owner); return null; });
        }
        public Observation observe(SessionHandle h) { return run(() -> controller().observe(h)); }
        public void execute(SessionHandle h, OperationRequest r) {
            run(() -> { controller().execute(h, r); return null; });
        }
        public StreamHandle openStream(SessionHandle h, StreamSpec s) {
            return run(() -> controller().stream(h, s));
        }
        public void pause(SessionHandle h, boolean p) { run(() -> { controller().pause(h, p); return null; }); }
        public boolean close(SessionHandle h) { return run(() -> controller().close(h)); }
        public boolean healthy(SessionHandle h) { return run(() -> controller().healthy(h)); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { controller().shutdown(); return false; }
    @Override public void onDestroy() { controller().shutdown(); super.onDestroy(); }
}
