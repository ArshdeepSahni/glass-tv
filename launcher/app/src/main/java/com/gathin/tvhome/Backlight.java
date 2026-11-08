package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Brightness: the TV's own backlight (its Picture ▸ Backlight, 0–100). The TV keeps it in Android's global settings
 * and applies any change at once (on this MediaTek board: g_disp__disp_back_light, the panel's PWM), so Glass writes
 * it there. Android's own screen brightness does nothing to the panel on TVs like this one. Writing a global setting
 * needs WRITE_SECURE_SETTINGS, which the installer grants.
 */
final class Backlight {
    private Backlight() {
    }

    static final String KEY = "picture_backlight";
    static final int STEP = 10;

    /** This TV keeps its backlight where Glass can reach it. */
    static boolean works(Context c) {
        return Settings.Global.getString(c.getContentResolver(), KEY) != null
                && c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static int get(Context c) {
        return clamp(Settings.Global.getInt(c.getContentResolver(), KEY, 100));
    }

    /** Sets it (0–100); false when the TV wouldn't take it. */
    static boolean set(Context c, int level) {
        ContentResolver cr = c.getContentResolver();
        try {
            return Settings.Global.putInt(cr, KEY, clamp(level));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One step up or down (a remote's ← →, a phone's − +). False at the end of the range. */
    static boolean step(Context c, int dir) {
        int now = get(c), next = clamp(now + dir * STEP);
        if (next == now) return false;
        return set(c, next);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
