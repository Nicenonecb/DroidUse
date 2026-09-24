package dev.droiduse.executor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Synthetic messages only, packaged exclusively in the instrumentation APK. */
public class M3SystemFixture extends ContentProvider {
    public static final String AUTHORITY = "dev.droiduse.m3.systemfixture";
    private static final String CHANNEL = "m3-synthetic";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            Context context = getContext();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if ("post".equals(method)) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, "M3 synthetic test", NotificationManager.IMPORTANCE_DEFAULT));
                context.getSharedPreferences("system-proof", 0).edit().clear().commit();
                PendingIntent open = PendingIntent.getActivity(context, 903,
                        new Intent(context, M2FixtureActivity.class).putExtra("m3NotificationOpen", true),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                PendingIntent reply = PendingIntent.getBroadcast(context, 904, new Intent(context, Reply.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                Notification.Action action = new Notification.Action.Builder(null, "Synthetic reply", reply)
                        .addRemoteInput(new RemoteInput.Builder("synthetic-reply").setAllowFreeFormInput(true).build()).build();
                manager.notify(903, new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("M3 SYNTHETIC NOTIFICATION")
                        .setContentText(arg == null ? "synthetic content" : arg)
                        .setContentIntent(open).addAction(action).build());
            } else if ("clear".equals(method)) manager.cancel(903);
            else if (!"status".equals(method)) throw new IllegalArgumentException("Unknown synthetic command");
            Bundle result = new Bundle();
            result.putInt("count", manager.getActiveNotifications().length);
            result.putString("reply", context.getSharedPreferences("system-proof", 0).getString("reply", ""));
            result.putInt("openedDisplay", context.getSharedPreferences("system-proof", 0).getInt("openedDisplay", -1));
            return result;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    public static class Reply extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            Bundle values = RemoteInput.getResultsFromIntent(intent);
            if (values != null) context.getSharedPreferences("system-proof", 0).edit()
                    .putString("reply", String.valueOf(values.getCharSequence("synthetic-reply"))).commit();
        }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
