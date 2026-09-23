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
}
