package dev.droiduse.executor.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/** Synthetic solid-color secret; no real user data. Test APK only. */
public class SecureFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        View content = new View(this);
        content.setBackgroundColor(Color.MAGENTA);
        setContentView(content);
        apply(getIntent());
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        apply(intent);
    }
    private void apply(Intent intent) {
        boolean secure = intent.getBooleanExtra("secure", false);
        if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getSharedPreferences("proof", MODE_PRIVATE).edit()
            .putInt("display", getDisplay().getDisplayId()).putBoolean("secure", secure).commit();
    }
}
