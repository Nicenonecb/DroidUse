package dev.droiduse.probe;

import android.app.Activity;
import android.os.*;
import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;

/** Silent local-video fixture. Measures primary-display playback, no user media is accessed. */
public final class VideoActivity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private MediaPlayer player;
    private TextView status;
    private long lastFrame,started;
    private final List<Long> frameIntervals=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean rendered;
    private final Runnable sample=new Runnable() {
        public void run() { report();handler.postDelayed(this,5000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(getDisplay().getDisplayId()!=0) { finish();return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        status=new TextView(this);status.setText("DroidUse 主屏视频并行测试 · 本地无声视频");layout.addView(status);
        SurfaceView video=new SurfaceView(this);video.getHolder().addCallback(this);
        layout.addView(video,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
        started=SystemClock.elapsedRealtime();Choreographer.getInstance().postFrameCallback(this);handler.post(sample);
    }
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            player=new MediaPlayer();player.setDisplay(holder);player.setLooping(true);player.setVolume(0,0);
            try(AssetFileDescriptor source=getAssets().openFd("video-test.mp4")) {
                player.setDataSource(source.getFileDescriptor(),source.getStartOffset(),source.getLength());
            }
            player.setOnPreparedListener(p -> p.start());
            player.setOnInfoListener((p,what,extra) -> { if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered=true;return false; });
            player.setOnErrorListener((p,what,extra) -> { Log.i("DroidUseVideo","ERROR="+what+":"+extra);return true; });
            player.prepareAsync();
        } catch(Exception e) { Log.i("DroidUseVideo","SETUP_ERROR="+e.getClass().getSimpleName()); }
    }
    public void surfaceChanged(SurfaceHolder h,int format,int width,int height) {}
    public void surfaceDestroyed(SurfaceHolder h) { report();if(player!=null) { player.release();player=null; } }
    public void doFrame(long time) {
        if(lastFrame!=0) frameIntervals.add((time-lastFrame)/1000000);
        lastFrame=time;Choreographer.getInstance().postFrameCallback(this);
    }
    private void report() {
        try {
            JSONObject record=new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("display",getDisplay().getDisplayId()).put("rendered",rendered).put("windowFocus",hasWindowFocus());
            if(player!=null) {
                record.put("playing",player.isPlaying()).put("positionMs",player.getCurrentPosition());
                PersistableBundle metrics=player.getMetrics();
                for(String key:new String[]{MediaPlayer.MetricsConstants.FRAMES,MediaPlayer.MetricsConstants.FRAMES_DROPPED,
                    MediaPlayer.MetricsConstants.CODEC_VIDEO,MediaPlayer.MetricsConstants.WIDTH,MediaPlayer.MetricsConstants.HEIGHT})
                    record.put(key,metrics.get(key)==null?JSONObject.NULL:metrics.get(key));
            }
            List<Long> sorted=new ArrayList<>(frameIntervals);Collections.sort(sorted);
            record.put("uiFrames",sorted.size()).put("uiOver50ms",sorted.stream().filter(v->v>50).count());
            if(!sorted.isEmpty()) record.put("uiP95Ms",sorted.get(Math.min(sorted.size()-1,(int)(sorted.size()*.95))));
            frameIntervals.clear();Log.i("DroidUseVideo",record.toString());
            status.setText("主屏播放中 · "+record.optInt("positionMs")+"ms · 窗口焦点="+hasWindowFocus());
        } catch(Exception e) { Log.i("DroidUseVideo","SAMPLE_ERROR="+e.getClass().getSimpleName()); }
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(this);
        if(player!=null) { player.release();player=null; }super.onDestroy();
    }
}
