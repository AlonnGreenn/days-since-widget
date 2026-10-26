package com.vibecoded.clock;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setPadding(48, 96, 48, 48);
        t.setTextSize(18);
        t.setText("Long-press your home screen → Widgets → Clock Widget, and drag one of the three styles onto the screen.");
        setContentView(t);
    }
}
