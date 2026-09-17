package dev.droiduse.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Two stable pointer IDs with synchronized piecewise-linear paths. */
final class MultiTouchGesture {
    private final int display;
    private final Object manager;
    private final Method inject;
    private final MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];
    private final MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
    private long down;
    private int active;
    MultiTouchGesture(int display) throws Exception {
        if(display<=0) throw new IllegalArgumentException("PRIMARY_DISPLAY_REFUSED");
        this.display=display;
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        for(int i=0;i<2;i++) {
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;
            properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].pressure=1;coords[i].size=1;
        }
    }
    private void event(int action,int count) throws Exception {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,
            0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {
            InputEvent.class.getMethod("setDisplayId",int.class).invoke(e,display);
            if(!((Boolean)inject.invoke(manager,e,2))) throw new IllegalStateException("MULTITOUCH_REJECTED");
        } finally { e.recycle(); }
    }
    void perform(int[][][] points,int duration) throws Exception {
        int count=points[0].length;
        for(int i=0;i<2;i++) { coords[i].x=points[i][0][0];coords[i].y=points[i][0][1]; }
        down=SystemClock.uptimeMillis();
        try {
            active=1;event(MotionEvent.ACTION_DOWN,1);
            active=2;event(MotionEvent.ACTION_POINTER_DOWN|(1<<8),2);
            long start=SystemClock.uptimeMillis();
            while(true) {
                float fraction=Math.min(1f,(SystemClock.uptimeMillis()-start)/(float)duration);
                float position=fraction*(count-1);
                int segment=Math.min(count-2,(int)position);float t=position-segment;
                for(int i=0;i<2;i++) {
                    coords[i].x=points[i][segment][0]+t*(points[i][segment+1][0]-points[i][segment][0]);
                    coords[i].y=points[i][segment][1]+t*(points[i][segment+1][1]-points[i][segment][1]);
                }
                event(MotionEvent.ACTION_MOVE,2);
                if(fraction>=1f) break;
                Thread.sleep(16);
            }
            event(MotionEvent.ACTION_POINTER_UP|(1<<8),2);active=1;
            event(MotionEvent.ACTION_UP,1);active=0;
        } finally { if(active>0) try { event(MotionEvent.ACTION_CANCEL,active); } catch(Exception ignored) { } }
    }
}
