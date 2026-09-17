package dev.droiduse.probe;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    private int count;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.rgb(20, 35, 55));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);
        layout.addView(status);
        Button button = new Button(this);
        button.setText("TAP COUNTER");
        button.setOnClickListener(view -> { count++; update(); });
        layout.addView(button, new LinearLayout.LayoutParams(360, 100));
        setContentView(layout);
        update();
    }
    private void update() {
        status.setText("DroidUse virtual display probe\nDisplay: "
                + getDisplay().getDisplayId() + "\nCount: " + count);
    }
}
