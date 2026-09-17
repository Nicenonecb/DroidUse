package dev.droiduse.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** One continuous touch stream, explicitly bound to a non-primary display. */
final class TouchGesture {
    private final int display;
    private final Object manager;
    private final Method inject;
    private long down;
    private boolean pressed;
    private float x,y;
    TouchGesture(int display) throws Exception {
        if(display<=0) throw new IllegalArgumentException("PRIMARY_DISPLAY_REFUSED");
        this.display=display;
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
    }
    private void event(int action,float x,float y) throws Exception {
        this.x=x;this.y=y;
        if(action==MotionEvent.ACTION_DOWN) { down=SystemClock.uptimeMillis();pressed=true; }
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        try {
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            InputEvent.class.getMethod("setDisplayId",int.class).invoke(e,display);
            if(!((Boolean)inject.invoke(manager,e,2))) throw new IllegalStateException("TOUCH_REJECTED");
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) pressed=false;
        } finally { e.recycle(); }
    }
    void perform(boolean twice,int x1,int y1,int x2,int y2,int hold,int duration) throws Exception {
        try {
            for(int tap=0;tap<(twice?2:1);tap++) {
                event(MotionEvent.ACTION_DOWN,x1,y1);
                if(hold>0) Thread.sleep(hold);
                if(duration>0) {
                    long start=SystemClock.uptimeMillis();
                    while(true) {
                        float fraction=Math.min(1f,(SystemClock.uptimeMillis()-start)/(float)duration);
                        event(MotionEvent.ACTION_MOVE,x1+(x2-x1)*fraction,y1+(y2-y1)*fraction);
                        if(fraction>=1f) break;
                        Thread.sleep(16);
                    }
                }
                event(MotionEvent.ACTION_UP,x2,y2);
                if(twice && tap==0) Thread.sleep(80);
            }
        } finally {
            if(pressed) try { event(MotionEvent.ACTION_CANCEL,x,y); } catch(Exception ignored) { }
        }
    }
}
