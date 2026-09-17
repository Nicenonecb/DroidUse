package dev.droiduse.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Fixed two-finger gesture on an explicitly selected non-default display. */
public final class GestureProbe {
    public static void main(String[] args) throws Exception {
        int display = Integer.parseInt(args[0]);
        if (display <= 0) throw new IllegalArgumentException("Requires virtual display");
        Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = type.getMethod("getInstance").invoke(null);
        Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 280 + i * 160; coords[i].y = 600;
            coords[i].pressure = 1; coords[i].size = 1;
        }
        long down = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN | (1 << 8),
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP | (1 << 8), MotionEvent.ACTION_UP};
        for (int step = 0; step < actions.length; step++) {
            int count = step == 0 || step == actions.length-1 ? 1 : 2;
            if (step == 2 || step == 3) { coords[0].x -= 30; coords[1].x += 30; }
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), actions[step],
                count, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            InputEvent.class.getMethod("setDisplayId", int.class).invoke(event, display);
            boolean accepted = (Boolean) inject.invoke(manager, event, 2);
            event.recycle();
            if (!accepted) throw new IllegalStateException("Gesture rejected at step " + step);
            Thread.sleep(60);
        }
        System.out.println("PINCH_INJECTED display=" + display);
    }
}
