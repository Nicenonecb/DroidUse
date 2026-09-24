package com.android.server.wm;

import android.os.IBinder;
import android.util.ArraySet;

/** Immutable per-display window identity; constructed only under the WM global lock. */
public final class DroidUseWindowSnapshot {
    public final IBinder token;
    public final String packageName;
    public final int uid;
    public final int taskId;
    public final boolean secure;
    public final int[] otherVisibleUids;

    private DroidUseWindowSnapshot(WindowState window, int[] otherUids) {
        token = window.mClient.asBinder();
        packageName = window.getOwningPackage();
        uid = window.getOwningUid();
        Task task = window.getTask();
        taskId = task == null ? -1 : task.mTaskId;
        secure = window.isSecureLocked();
        otherVisibleUids = otherUids;
    }

    static DroidUseWindowSnapshot capture(RootWindowContainer root, int displayId) {
        DisplayContent display = root.getDisplayContent(displayId);
        if (display == null || display.mCurrentFocus == null
                || !display.mCurrentFocus.isVisible()) return null;
        ArraySet<Integer> visible = new ArraySet<>();
        root.forAllWindows(window -> {
            if (window.getDisplayId() != displayId && window.isVisible()) {
                visible.add(window.getOwningUid());
            }
        }, false);
        int[] uids = new int[visible.size()];
        for (int i = 0; i < uids.length; i++) uids[i] = visible.valueAt(i);
        return new DroidUseWindowSnapshot(display.mCurrentFocus, uids);
    }

    public boolean isVisibleElsewhere(int targetUid) {
        for (int other : otherVisibleUids) if (other == targetUid) return true;
        return false;
    }

    public boolean sameWindow(DroidUseWindowSnapshot other) {
        return other != null && token.equals(other.token) && uid == other.uid
                && taskId == other.taskId && secure == other.secure
                && packageName.equals(other.packageName);
    }
}
