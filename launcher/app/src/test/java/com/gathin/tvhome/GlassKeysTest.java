package com.gathin.tvhome;

import android.view.KeyEvent;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which keys can turn out to be a remote's Home button (learned when the TV answers one with its own home). */
public class GlassKeysTest {
    @Test public void aVendorKeyCanBeHome() {
        assertTrue(GlassKeys.couldBeHome(KeyEvent.KEYCODE_BUTTON_2));   // a Xiaomi's Bluetooth remote
        assertTrue(GlassKeys.couldBeHome(KeyEvent.KEYCODE_ALL_APPS));
        assertTrue(GlassKeys.couldBeHome(KeyEvent.KEYCODE_GUIDE));
    }

    @Test public void theKeysEveryoneUsesNeverAre() {
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_DPAD_CENTER));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_DPAD_LEFT));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_BACK));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_5));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_Q));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_POWER));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_MENU));
        assertFalse(GlassKeys.couldBeHome(KeyEvent.KEYCODE_SEARCH));
    }
}
