package com.gathin.tvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Brightness on a TV that keeps its backlight out of Glass's reach (Backlight): the whole picture dims under a sheet of
 * black, over every app, films and the TV's own screens included (the helper's overlay, which takes no keys and no
 * touches). 100 takes the sheet away. At 0 it still lets FLOOR of the picture's light through, so the TV is never left
 * too dark to read. The level is kept, and comes back when the helper starts (a restart).
 */
final class Dim {
    private Dim() {
    }

    static final String PREF = "dim_level";
    private static final float FLOOR = 0.3f;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View sheet;

    /** Glass can dim the picture: its helper is running (only the helper's overlay sits over every app). */
    static boolean available() {
        return HomeKeyService.current() != null;
    }

    static int get(Context c) {
        return clamp(Glass.prefs(c).getInt(PREF, 100));
    }

    /** Sets it (0–100); false when the helper isn't running. Any thread. */
    static boolean set(Context c, int level) {
        final HomeKeyService svc = HomeKeyService.current();
        if (svc == null) return false;
        final int l = clamp(level);
        Glass.prefs(c).edit().putInt(PREF, l).apply();
        MAIN.post(() -> show(svc, l));
        return true;
    }

    /** The helper started: the picture as dim as it was left. */
    static void restore(HomeKeyService svc) {
        final int l = get(svc);
        if (l < 100) MAIN.post(() -> show(svc, l));
    }

    /** The helper is going: its overlay goes with it. */
    static void release() {
        sheet = null;
    }

    private static void show(HomeKeyService svc, int level) {
        if (HomeKeyService.current() != svc) return;
        WindowManager wm = svc.getSystemService(WindowManager.class);
        if (wm == null) return;
        float alpha = (1f - FLOOR) * (100 - level) / 100f;
        if (alpha < 0.005f) {
            if (sheet != null) {
                try {
                    wm.removeView(sheet);
                } catch (RuntimeException ignored) {
                }
                sheet = null;
            }
            return;
        }
        if (sheet == null) {
            View v = new View(svc);
            v.setBackgroundColor(0xFF000000);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("Glass brightness");
            try {
                wm.addView(v, lp);
            } catch (RuntimeException e) {
                return;
            }
            sheet = v;
        }
        sheet.setAlpha(alpha);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
