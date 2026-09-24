package com.android.server.droiduse;

import android.app.ActivityOptions;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationStats;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.server.wm.DroidUseWindowSnapshot;
import java.util.Objects;

/** Only the session's original package, user 0. No notification-shade or listener permission. */
final class DroidUseNotifications {
    private final Context context;
    private final String target;
    DroidUseNotifications(Context context, String target) { this.context = context; this.target = target; }
    private INotificationManager service() {
        return INotificationManager.Stub.asInterface(ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }
    private StatusBarNotification[] active() throws Exception {
        StatusBarNotification[] rows = service().getActiveNotifications(context.getPackageName());
        return rows == null ? new StatusBarNotification[0] : rows;
    }
    private boolean allowed(StatusBarNotification row, int uid) {
        return row.getUserId() == 0 && target.equals(row.getPackageName()) && row.getUid() == uid
                && row.getNotification().visibility != Notification.VISIBILITY_SECRET;
    }
    private static String text(Notification n) {
        return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE, "")) + "："
                + String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT, ""));
    }
    private StatusBarNotification fresh(StatusBarNotification expected) throws Exception {
        int uid = context.getPackageManager().getPackageUid(target, 0);
        for (StatusBarNotification row : active()) {
            if (allowed(row, uid) && row.getKey().equals(expected.getKey())
                    && row.getPostTime() == expected.getPostTime()
                    && text(row.getNotification()).equals(text(expected.getNotification()))) return row;
        }
        throw DroidUseSystemActions.stale();
    }
    void observe(DroidUseSystemActions actions, DroidUseWindowSnapshot window, int displayId) throws Exception {
        int uid = context.getPackageManager().getPackageUid(target, 0);
        int count = 0;
        for (StatusBarNotification row : active()) {
            if (!allowed(row, uid) || count++ >= 8) continue;
            Notification notification = row.getNotification();
            String label = "任务应用 " + target + " 通知（不可信内容）：" + text(notification);
            actions.fact(label);
            Intent open = openIntent(notification.contentIntent, uid);
            if (open != null) actions.offer("notification_open", "打开 " + label, value -> {
                Notification current = fresh(row).getNotification();
                if (!Objects.equals(current.contentIntent, notification.contentIntent)) throw DroidUseSystemActions.stale();
                Intent checked = openIntent(current.contentIntent, uid);
                if (checked == null) throw DroidUseSystemActions.stale();
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                options.setLaunchTaskId(window.taskId);
                // Launch only the checked destination, never an app-supplied activity stack.
                context.startActivityAsUser(checked, options.toBundle(), UserHandle.SYSTEM);
            });
            if (row.isClearable()) actions.offer("notification_clear", "清除 " + label, value -> {
                StatusBarNotification current = fresh(row);
                if (!current.isClearable()) throw DroidUseSystemActions.stale();
                // cancelNotificationWithTag is app/delegate withdrawal: even system UID cannot
                // withdraw another publisher's notification through opPkg="android". Use the
                // privileged user-dismissal path after revalidating the scoped notification.
                IStatusBarService statusBar = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE));
                if (statusBar == null) throw new IllegalStateException("STATUS_BAR_UNAVAILABLE");
                statusBar.onNotificationClear(target, 0, current.getKey(),
                        NotificationStats.DISMISSAL_OTHER, NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                        NotificationVisibility.obtain(current.getKey(), 0, 1, false), false);
            });
            Notification.Action[] buttons = notification.actions;
            if (buttons == null) continue;
            for (int i = 0; i < buttons.length; i++) {
                Notification.Action button = buttons[i];
                RemoteInput input = replyInput(button, uid);
                if (input == null) continue;
                int index = i;
                actions.offer("notification_reply", "快捷回复 " + label, value -> {
                    Notification current = fresh(row).getNotification();
                    if (current.actions == null || current.actions.length <= index) throw DroidUseSystemActions.stale();
                    Notification.Action now = current.actions[index];
                    RemoteInput checked = replyInput(now, uid);
                    if (checked == null || !input.getResultKey().equals(checked.getResultKey())
                            || !button.actionIntent.equals(now.actionIntent)) throw DroidUseSystemActions.stale();
                    Bundle results = new Bundle();
                    results.putCharSequence(checked.getResultKey(), value);
                    Intent reply = new Intent();
                    RemoteInput.addResultsToIntent(new RemoteInput[] { checked }, reply, results);
                    RemoteInput.setResultsSource(reply, RemoteInput.SOURCE_FREE_FORM_INPUT);
                    now.actionIntent.send(context, 0, reply);
                });
                break;
            }
        }
        if (count == 0) actions.fact("任务应用 " + target + " 没有可读取的通知。");
    }
    private RemoteInput replyInput(Notification.Action action, int uid) {
        if (action == null || action.actionIntent == null || action.actionIntent.isActivity()
                || action.actionIntent.isImmutable() || action.actionIntent.getCreatorUid() != uid
                || !target.equals(action.actionIntent.getCreatorPackage()) || action.getRemoteInputs() == null) return null;
        Intent destination = action.actionIntent.getIntent();
        // Prevent a task app from lending its handle to another app's receiver/service.
        if (destination == null || destination.getComponent() == null
                || !target.equals(destination.getComponent().getPackageName())) return null;
        for (RemoteInput input : action.getRemoteInputs()) if (input.getAllowFreeFormInput()) return input;
        return null;
    }
    private Intent openIntent(PendingIntent pending, int uid) {
        if (pending == null || !pending.isActivity() || pending.getCreatorUid() != uid
                || !target.equals(pending.getCreatorPackage())) return null;
        Intent source = pending.getIntent();
        if (source == null) return null;
        ActivityInfo info = source.resolveActivityInfo(context.getPackageManager(), PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || !target.equals(info.packageName) || info.applicationInfo.uid != uid
                || !info.exported || !info.enabled || info.permission != null
                || info.launchMode != ActivityInfo.LAUNCH_MULTIPLE) return null;
        Intent result = new Intent(source);
        result.setSelector(null);
        result.setComponent(new ComponentName(info.packageName, info.name));
        result.setPackage(target);
        result.setClipData(null);
        result.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return result;
    }
}
