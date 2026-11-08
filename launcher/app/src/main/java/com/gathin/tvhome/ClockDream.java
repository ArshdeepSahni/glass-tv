package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * The TV's screen saver: Glass's (Saver), the one chosen in Settings ▸ Backgrounds & savers. Any key wakes the TV
 * back to where it was.
 */
public class ClockDream extends DreamService {

    private Saver saver;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(!"night".equals(Looks.saver(this)));
        saver = new Saver(this, Looks.saver(this));
        setContentView(saver);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        saver.start();
    }

    @Override
    public void onDreamingStopped() {
        saver.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (saver != null) saver.stop();
        super.onDetachedFromWindow();
    }
}
