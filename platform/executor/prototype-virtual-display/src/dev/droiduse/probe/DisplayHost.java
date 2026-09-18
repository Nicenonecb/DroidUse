package dev.droiduse.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileOutputStream;

/** Throwaway shell-UID probe, not a production executor. */
public final class DisplayHost {
    private static Bitmap latest;
    private static int flag(String name) throws Exception {
        return DisplayManager.class.getField(name).getInt(null);
    }
    public static void main(String[] args) {
        try {
        Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        new Thread(() -> {
            try { run(args, threadClass, thread); }
            catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        }, "probe-commands").start();
        Looper.loop();
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args, Class<?> threadClass, Object thread) throws Exception {
        Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell", 0);
        // app_process has no Application. Native audio attribution otherwise resolves to
        // package android under shell UID and AudioFlinger rejects the virtual audio mix.
        android.app.Application application = new android.app.Application() {
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public android.content.AttributionSource getAttributionSource() {
                return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell").build();
            }
        };
        java.lang.reflect.Method attach = android.app.Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, shell);
        java.lang.reflect.Field initialApplication = threadClass.getDeclaredField("mInitialApplication");
        initialApplication.setAccessible(true);
        initialApplication.set(thread, application);
        HandlerThread frames = new HandlerThread("probe-frames");
        frames.start();
        ImageReader reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                Image.Plane plane = image.getPlanes()[0];
                int paddedWidth = plane.getRowStride() / plane.getPixelStride();
                Bitmap padded = Bitmap.createBitmap(paddedWidth, 1280, Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(plane.getBuffer());
                Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, 720, 1280);
                synchronized (DisplayHost.class) {
                    if (latest != null) latest.recycle();
                    latest = cropped;
                }
                if (padded != cropped) padded.recycle();
            } catch (Exception error) {
                System.out.println("FRAME_ERROR " + error);
            }
        }, new Handler(frames.getLooper()));
        VirtualDisplay display = null;
        Object device = null;
        Object virtualAudio = null;
        try {
            DisplayManager manager = shell.getSystemService(DisplayManager.class);
            // Own content only: never mirror the user's main screen.
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | flag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
                    | flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
                    | flag("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED")
                    | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL");
            if (args.length > 0) {
                Class<?> params = Class.forName("android.companion.virtual.VirtualDeviceParams");
                Class<?> builderType = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
                Object builder = builderType.getConstructor().newInstance();
                builderType.getMethod("setName", String.class).invoke(builder, "DroidUse-Isolation");
                for (String policy : new String[]{"POLICY_TYPE_AUDIO", "POLICY_TYPE_CAMERA", "POLICY_TYPE_RECENTS"})
                    builderType.getMethod("setDevicePolicy", int.class, int.class)
                        .invoke(builder, params.getField(policy).getInt(null), 1);
                android.media.AudioManager audioManager = shell.getSystemService(android.media.AudioManager.class);
                builderType.getMethod("setAudioPlaybackSessionId", int.class).invoke(builder, audioManager.generateAudioSessionId());
                builderType.getMethod("setAudioRecordingSessionId", int.class).invoke(builder, audioManager.generateAudioSessionId());
                builderType.getMethod("setInputMethodComponent", android.content.ComponentName.class)
                    .invoke(builder, new android.content.ComponentName("dev.droiduse.probe", "dev.droiduse.probe.ProbeIme"));
                Object config = builderType.getMethod("build").invoke(builder);
                Class<?> vdm = Class.forName("android.companion.virtual.VirtualDeviceManager");
                Context applicationContext = new android.content.ContextWrapper(shell) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public String getOpPackageName() { return "com.android.shell"; }
                    @Override public android.content.AttributionSource getAttributionSource() {
                        return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                            .setPackageName("com.android.shell").build();
                    }
                };
                android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "virtualdevice");
                Class<?> serviceType = Class.forName("android.companion.virtual.IVirtualDeviceManager");
                Object remote = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                Object service = vdm.getConstructor(serviceType, Context.class).newInstance(remote, applicationContext);
                device = vdm.getMethod("createVirtualDevice", int.class, params)
                    .invoke(service, Integer.parseInt(args[0]), config);
                display = (VirtualDisplay) device.getClass().getMethod("createVirtualDisplay", int.class,
                    int.class, int.class, android.view.Surface.class, int.class,
                    java.util.concurrent.Executor.class, VirtualDisplay.Callback.class)
                    .invoke(device, 720, 1280, 240, reader.getSurface(), flags, null, null);
                System.out.println("VIRTUAL_DEVICE_ID=" + device.getClass().getMethod("getDeviceId").invoke(device));
                Class<?> callback = Class.forName("android.companion.virtual.audio.VirtualAudioDevice$AudioConfigurationChangeCallback");
                java.util.concurrent.Executor executor = task -> new Handler(frames.getLooper()).post(task);
                virtualAudio = device.getClass().getMethod("createVirtualAudioDevice", VirtualDisplay.class,
                    java.util.concurrent.Executor.class, callback).invoke(device, display, executor, null);
                android.media.AudioFormat format = new android.media.AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO).build();
                virtualAudio.getClass().getMethod("startAudioCapture", android.media.AudioFormat.class).invoke(virtualAudio, format);
                android.media.AudioFormat injectionFormat = new android.media.AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build();
                Object injection = virtualAudio.getClass().getMethod("startAudioInjection", android.media.AudioFormat.class).invoke(virtualAudio, injectionFormat);
                Handler silenceHandler = new Handler(frames.getLooper());
                java.lang.reflect.Method writeSilence = injection.getClass().getMethod("write", short[].class, int.class, int.class, int.class);
                silenceHandler.post(new Runnable() {
                    final short[] silence = new short[960];
                    @Override public void run() {
                        try { writeSilence.invoke(injection, silence, 0, silence.length, android.media.AudioTrack.WRITE_NON_BLOCKING); }
                        catch (Exception error) { System.out.println("INJECTION_ERROR " + error); return; }
                        silenceHandler.postDelayed(this, 20);
                    }
                });
                System.out.println("VIRTUAL_AUDIO_REQUESTED (routing must be verified separately)");
            } else {
                display = manager.createVirtualDisplay("DroidUse-Probe", 720, 1280, 240, reader.getSurface(), flags);
            }
            if (display == null) throw new IllegalStateException("Virtual display unavailable");
            System.out.println("DISPLAY_ID=" + display.getDisplay().getDisplayId());
            BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
            String command;
            while ((command = commands.readLine()) != null && !command.equals("quit")) {
                if ((command.equals("deny-test-mic") || command.equals("deny-reader-mic") || command.startsWith("deny-package-mic ")) && device != null) {
                    String permissionPackage=command.startsWith("deny-package-mic ") ? command.substring(17) : command.equals("deny-reader-mic") ? "com.dragon.read" : "dev.droiduse.probe";
                    if(!permissionPackage.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package");
                    String persistentId = (String) device.getClass().getMethod("getPersistentDeviceId").invoke(device);
                    if (persistentId == null || persistentId.equals("default:0"))
                        throw new IllegalStateException("Refusing default device permission mutation");
                    Class<?> permissionType = Class.forName("android.permission.PermissionManager");
                    Object permissions = shell.getSystemService(permissionType);
                    String[] requested=shell.getPackageManager().getPackageInfo(permissionPackage,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
                    if(requested!=null && java.util.Arrays.asList(requested).contains("android.permission.RECORD_AUDIO"))
                        permissionType.getMethod("revokeRuntimePermission", String.class, String.class, String.class, String.class)
                            .invoke(permissions, permissionPackage, "android.permission.RECORD_AUDIO", persistentId, "DroidUse scoped diagnostic");
                    System.out.println("DEVICE_MIC_REVOKE_RETURNED=" + persistentId);
                    continue;
                }
                if (command.startsWith("clipboard ") && device != null) {
                    Context deviceContext = (Context) device.getClass().getMethod("createContext").invoke(device);
                    if (deviceContext.getDeviceId() == 0) throw new IllegalStateException("Refusing primary clipboard");
                    Context clipboardContext = new android.content.ContextWrapper(deviceContext) {
                        @Override public String getOpPackageName() { return "com.android.shell"; }
                    };
                    android.content.ClipboardManager clipboard = android.content.ClipboardManager.class
                        .getConstructor(Context.class, Handler.class).newInstance(clipboardContext, new Handler(frames.getLooper()));
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DroidUse test", command.substring(10)));
                    System.out.println("DEVICE_CLIPBOARD_SET");
                    continue;
                }
                if (!command.equals("capture")) continue;
                synchronized (DisplayHost.class) {
                    if (latest == null) { System.out.println("NO_FRAME"); continue; }
                    try (FileOutputStream out = new FileOutputStream("/data/local/tmp/droiduse-probe.png")) {
                        latest.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
                System.out.println("CAPTURED");
            }
        } finally {
            if (virtualAudio != null) virtualAudio.getClass().getMethod("close").invoke(virtualAudio);
            if (display != null) display.release();
            if (device != null) device.getClass().getMethod("close").invoke(device);
            reader.close();
            frames.quitSafely();
        }
        System.exit(0);
    }
}
