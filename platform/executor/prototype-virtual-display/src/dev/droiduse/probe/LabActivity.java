package dev.droiduse.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.media.*;
import android.hardware.camera2.*;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Instrumented resource adversary. No captured media is saved or transmitted. */
public final class LabActivity extends Activity {
    private TextView status;
    private EditText editor;
    private AudioFocusRequest focus;
    private AudioTrack track;
    private AudioRecord recorder;
    private CameraDevice camera;
    private String role;
    private int touches;
    private float multiDistance,multiAngle,multiX,multiY;
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN) report("POINTERS="+e.getPointerCount());
        if(e.getPointerCount()==2) {
            float dx=e.getX(1)-e.getX(0),dy=e.getY(1)-e.getY(0);
            float distance=(float)Math.hypot(dx,dy),angle=(float)Math.toDegrees(Math.atan2(dy,dx));
            float cx=(e.getX(0)+e.getX(1))/2,cy=(e.getY(0)+e.getY(1))/2;
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN) {
                multiDistance=distance;multiAngle=angle;multiX=cx;multiY=cy;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP && multiDistance>0)
                report("MULTI scale="+(distance/multiDistance)+" angle="+(angle-multiAngle)+" dx="+(cx-multiX)+" dy="+(cy-multiY));
        }
        return super.dispatchTouchEvent(e);
    }
    private Button touchButton;
    private CheckBox choice;
    private SeekBar slider;
    private Spinner options;
    private ScrollView scroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private void recordState() {
        for (AudioRecordingConfiguration c : getSystemService(AudioManager.class).getActiveRecordingConfigurations())
            if (recorder != null && c.getClientAudioSessionId() == recorder.getAudioSessionId())
                report("MIC_CONFIG ownSession=" + c.getClientAudioSessionId() + " silenced=" + c.isClientSilenced()
                    + " route=" + (c.getAudioDevice() == null ? "none" : c.getAudioDevice().getType()));
    }
    private void report(String event) {
        String line = role + " display=" + getDisplay().getDisplayId() + " " + event;
        Log.i("DroidUseLab", line);
        status.append("\n" + event);
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { command(intent.getStringExtra("op")); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        role = getPackageName();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(20, 30, 20, 20);
        column.setBackgroundColor(0xffeaf2f8);
        editor = new EditText(this);
        editor.setSingleLine(false);
        editor.setHint("Test editor / 中文输入验证");
        editor.setMinLines(2);
        column.addView(editor);
        Button button = new Button(this);
        touchButton = button;
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { report("DOUBLE_TAP"); return true; }
        });
        button.setOnTouchListener((v, e) -> { gestures.onTouchEvent(e); return false; });
        button.setText("Tap / long press");
        button.setOnClickListener(v -> report("TAP=" + (++touches)));
        button.setOnLongClickListener(v -> { report("LONG_PRESS"); return true; });
        column.addView(button);
        status = new TextView(this);
        status.setTextSize(14);
        status.setText(role + "\nDisplay=" + getDisplay().getDisplayId());
        choice = new CheckBox(this); choice.setText("Test checkbox");
        choice.setOnCheckedChangeListener((v, checked) -> report("CHECKED=" + checked)); column.addView(choice);
        slider = new SeekBar(this); slider.setMax(100);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) report("SLIDER=" + value); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        }); column.addView(slider);
        options = new Spinner(this);
        options.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Option A", "Option B", "Option C"}));
        options.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { report("OPTION=" + pos); }
            public void onNothingSelected(AdapterView<?> p) {}
        }); column.addView(options);
        scroll = new ScrollView(this);
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY) -> Log.i("DroidUseLab", role + " display=" + getDisplay().getDisplayId() + " SCROLL_Y=" + y));
        scroll.addView(status);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
        registerReceiver(receiver, new IntentFilter("dev.droiduse.lab.COMMAND"),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        report("CREATED");
    }
    private void bounds(String name, View view) {
        int[] pos = new int[2]; view.getLocationOnScreen(pos);
        report("BOUNDS " + name + " " + pos[0] + " " + pos[1] + " " + view.getWidth() + " " + view.getHeight());
    }
    private void command(String op) {
        if (op == null) return;
        try {
            switch (op) {
                case "sensitive_test":
                    status.setText("请输入验证码 123456");status.setTextSize(30);break;
                case "controls":
                    bounds("button", touchButton); bounds("checkbox", choice); bounds("slider", slider); bounds("spinner", options); bounds("scroll", scroll); break;
                case "scrolltest":
                    for (int i = 0; i < 80; i++) status.append("\nSynthetic scroll row " + i); break;
                case "edit":
                    editor.requestFocus();
                    getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                    report("EDIT_REQUEST"); break;
                case "inspect":
                    // Test-only field, deliberately no user App text inspection.
                    report("TEXT=" + editor.getText() + " WINDOW_FOCUS=" + hasWindowFocus()); break;
                case "clipcheck":
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    android.content.ClipData clip = cm.getPrimaryClip();
                    report("DEVICE=" + getDeviceId() + " APP_DEVICE=" + getApplicationContext().getDeviceId()
                        + " CLIP_PRESENT=" + (clip != null)); break;
                case "focus":
                case "duck":
                    AudioManager audio = getSystemService(AudioManager.class);
                    if (focus != null) audio.abandonAudioFocusRequest(focus);
                    focus = new AudioFocusRequest.Builder(op.equals("duck")
                            ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK : AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        .setOnAudioFocusChangeListener(change -> report("FOCUS_CHANGE=" + change), handler).build();
                    report("FOCUS_REQUEST=" + audio.requestAudioFocus(focus)); break;
                case "play":
                    if (track != null) { track.release(); track = null; }
                    track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(8000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(16000).build();
                    track.write(new short[8000], 0, 8000);
                    track.setLoopPoints(0, 8000, -1);
                    track.play();
                    report("SILENT_TRACK state=" + track.getPlayState()
                            + " route=" + (track.getRoutedDevice() == null ? "pending" : track.getRoutedDevice().getType()));
                    break;
                case "mic":
                    report("MIC_PERMISSION=" + checkSelfPermission("android.permission.RECORD_AUDIO") + " DEVICE=" + getDeviceId());
                    if (recorder != null) { recorder.release(); recorder = null; }
                    int buffer = Math.max(3200, AudioRecord.getMinBufferSize(16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
                    recorder.startRecording();
                    report("MIC_STARTED state=" + recorder.getRecordingState());
                    handler.postDelayed(this::recordState, 500);
                    handler.postDelayed(() -> { if (recorder != null) { recorder.release(); recorder = null; report("MIC_RELEASED"); } }, 8000);
                    break;
                case "micstate": recordState(); break;
                case "playstate":
                    report("PLAY_ROUTE=" + (track == null || track.getRoutedDevice() == null ? "none" : track.getRoutedDevice().getType())); break;
                case "camera":
                case "camera-known":
                    CameraManager manager = getSystemService(CameraManager.class);
                    if (op.equals("camera") && manager.getCameraIdList().length == 0) { report("CAMERA_UNAVAILABLE"); break; }
                    // Pixel test camera ID 0: verify hiding enumeration is not the only protection.
                    manager.openCamera(op.equals("camera-known") ? "0" : manager.getCameraIdList()[0], new CameraDevice.StateCallback() {
                        @Override public void onOpened(CameraDevice device) {
                            camera = device; report("CAMERA_OPENED");
                            handler.postDelayed(() -> { device.close(); if (camera == device) camera = null; }, 5000);
                        }
                        @Override public void onDisconnected(CameraDevice device) { report("CAMERA_DISCONNECTED"); device.close(); }
                        @Override public void onError(CameraDevice device, int error) { report("CAMERA_ERROR=" + error); device.close(); }
                    }, handler); break;
                case "dialog": new AlertDialog.Builder(this).setTitle("Background test dialog")
                    .setMessage("Must stay on the virtual display").setPositiveButton("Close", null).show(); break;
                case "permission": requestPermissions(new String[]{"android.permission.CAMERA"}, 71); break;
                case "jump": startActivity(getPackageManager().getLaunchIntentForPackage("com.baidu.searchbox")); break;
                case "stop": release(); break;
                default: report("UNKNOWN=" + op);
            }
        } catch (Exception error) { report(op + " ERROR=" + error.getClass().getSimpleName() + ":" + error.getMessage()); }
    }
    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (track != null) { track.release(); track = null; }
        if (recorder != null) { recorder.release(); recorder = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (focus != null) { getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); focus = null; }
        report("RELEASED");
    }
    @Override public void onDestroy() { release(); unregisterReceiver(receiver); super.onDestroy(); }
}
