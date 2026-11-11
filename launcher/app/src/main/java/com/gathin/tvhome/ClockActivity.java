package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Glass's screen saver (Saver, the one chosen in Settings) as an activity: Settings ▸ Try the screen saver, and Home
 * itself after home_idle_clock_minutes without input (some TVs' own screens don't keep the screen on, so the system
 * screen saver would cut into programmes; this one only ever starts from Home). Any key returns.
 */
public class ClockActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    /** Settings ▸ Try the screen saver: the chosen one, now. */
    static final String PREVIEW = "preview";
    /** Pictures of each screen saver (only while sample content is on): this one instead of the chosen one. */
    static final String STYLE = "style";

    private Saver saver;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String want = Sample.on ? getIntent().getStringExtra(STYLE) : null;
        saver = new Saver(this, want != null ? want : Looks.saver(this));
        setContentView(saver);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        saver.start();
    }

    @Override
    protected void onPause() {
        saver.stop();
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The key that wakes the clock should not also act on Home, so it is consumed here.
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
