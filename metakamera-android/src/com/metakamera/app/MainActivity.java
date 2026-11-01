package com.metakamera.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Placeholder — replaced by the real UI. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText(R.string.app_name);
        setContentView(t);
    }
}
