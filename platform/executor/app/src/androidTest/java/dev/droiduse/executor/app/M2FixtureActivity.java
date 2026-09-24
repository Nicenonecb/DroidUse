package dev.droiduse.executor.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;

/** Standalone synthetic target; Java avoids the instrumentation-only Kotlin classpath. */
public class M2FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("m3NotificationOpen", false)) {
            getSharedPreferences("system-proof", MODE_PRIVATE).edit()
                    .putInt("openedDisplay", getDisplay().getDisplayId()).commit();
        }
        SharedPreferences proof = getSharedPreferences("proof", MODE_PRIVATE);
        proof.edit().clear().putInt("display", getDisplay() == null ? -1 : getDisplay().getDisplayId()).commit();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        Button button = new Button(this);
        button.setText("M2 TAP TARGET");
        button.setOnClickListener(v -> {
            button.setText("M2 TAP PASSED");
            proof.edit().putInt("clicks", proof.getInt("clicks", 0) + 1).commit();
        });
        content.addView(button, new LinearLayout.LayoutParams(-1, 180));
        EditText editor = new EditText(this);
        editor.setHint("M2 TEXT TARGET");
        editor.setSingleLine(true);
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                proof.edit().putString("text", s.toString()).commit();
            }
            public void afterTextChanged(Editable s) {}
        });
        content.addView(editor, new LinearLayout.LayoutParams(-1, 180));
        Button picker = new Button(this);
        picker.setText("M3 OPEN TEST DOCUMENT");
        picker.setOnClickListener(v -> startActivityForResult(
                new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                        .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")),
                31));
        content.addView(picker, new LinearLayout.LayoutParams(-1, 180));
        EditText password = new EditText(this);
        password.setHint("M3 SYNTHETIC PASSWORD FIELD");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(password, new LinearLayout.LayoutParams(-1, 180));
        EditText second = new EditText(this);
        second.setHint("M3 SECOND EDITOR");
        second.setSingleLine(true);
        content.addView(second, new LinearLayout.LayoutParams(-1, 180));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 40; i++) {
            TextView row = new TextView(this);
            row.setText("M2 SCROLL ROW " + i);
            row.setTextSize(24f);
            rows.addView(row, new LinearLayout.LayoutParams(-1, 120));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> proof.edit().putInt("scroll", y).commit());
        content.addView(scroll);
        setContentView(content);
    }

    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 31 || result != RESULT_OK || data == null || data.getData() == null) return;
        // Synthetic test document only; never write document contents to logcat.
        try (java.io.InputStream stream = getContentResolver().openInputStream(data.getData())) {
            byte[] bytes = new byte[128];
            int count = stream.read(bytes);
            getSharedPreferences("proof", MODE_PRIVATE).edit()
                    .putString("fileUri", data.getData().toString())
                    .putString("fileText", count < 0 ? "" : new String(bytes, 0, count,
                            java.nio.charset.StandardCharsets.UTF_8)).commit();
        } catch (java.io.IOException error) {
            getSharedPreferences("proof", MODE_PRIVATE).edit().putString("fileText", "READ_FAILED").commit();
        }
    }
}
