package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Brightness. Where the TV keeps its own backlight (its Picture ▸ Backlight, 0–100) in Android's global settings and
 * applies any change at once (a MediaTek board: g_disp__disp_back_light, the panel's PWM), Glass writes it there;
 * writing a global setting needs WRITE_SECURE_SETTINGS, which the installer grants. Android's own screen brightness
 * does nothing to the panel on TVs like that one, and other TVs keep their backlight behind their own service, out of
 * any app's reach (an Amlogic Xiaomi): there, Glass dims the picture itself (Dim). So the slider is on every TV.
 */
final class Backlight {
    private Backlight() {
    }

    static final String KEY = "picture_backlight";
    static final int STEP = 10;

    /** This TV keeps its backlight where Glass can reach it. */
    static boolean hardware(Context c) {
        return Settings.Global.getString(c.getContentResolver(), KEY) != null
                && c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Glass can change the brightness here: the TV's backlight, or else the picture, dimmed (Dim). */
    static boolean works(Context c) {
        return hardware(c) || Dim.available();
    }

    /** What the setting changes, in a few words (Settings ▸ Display). */
    static String about(Context c) {
        return hardware(c) ? "The TV's backlight" : "Dims the picture, in every app";
    }

    static int get(Context c) {
        if (!hardware(c)) return Dim.get(c);
        return clamp(Settings.Global.getInt(c.getContentResolver(), KEY, 100));
    }

    /** Sets it (0–100); false when the TV wouldn't take it. */
    static boolean set(Context c, int level) {
        if (!hardware(c)) return Dim.set(c, level);
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
