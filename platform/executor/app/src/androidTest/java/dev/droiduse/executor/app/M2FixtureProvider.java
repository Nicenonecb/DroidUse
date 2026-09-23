package dev.droiduse.executor.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Read-only synthetic evidence; ships only in the instrumentation APK. */
public class M2FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        SharedPreferences p = getContext().getSharedPreferences("proof", 0);
        MatrixCursor cursor = new MatrixCursor(new String[]{"display", "clicks", "text", "scroll", "secure"});
        cursor.addRow(new Object[]{p.getInt("display", -1), p.getInt("clicks", 0), p.getString("text", ""), p.getInt("scroll", 0), p.getBoolean("secure", false) ? 1 : 0});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/m2-proof"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
