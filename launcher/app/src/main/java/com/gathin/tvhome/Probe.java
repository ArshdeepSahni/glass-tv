package com.gathin.tvhome;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Glass IV, first three days only: notes which remote keys reach the helper and what each app tells Android about
 * what's playing, so the shelf and the key grammar can be tuned to this TV without anyone timing a test.
 * Key names and yes/no flags only: no titles, nothing about what was watched. One small file in the app's own
 * external folder (readable over ADB by the installer), capped at 64 KB, and it stops writing after 72 hours.
 */
final class Probe {
    private Probe() {
    }

    private static final long WINDOW_MS = 72L * 60 * 60 * 1000;
    private static final Set<String> seen = new HashSet<>();

    private static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "probe.log");
    }

    private static boolean on(Context ctx) {
        long first = Glass.prefs(ctx).getLong("probe_since", 0);
        if (first == 0) {
            Glass.prefs(ctx).edit().putLong("probe_since", System.currentTimeMillis()).apply();
            return true;
        }
        return System.currentTimeMillis() - first < WINDOW_MS;
    }

    private static synchronized void write(Context ctx, String line) {
        if (!seen.add(line) || !on(ctx)) return;
        File f = file(ctx);
        if (f == null || f.length() > 64 * 1024) return;
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (Exception ignored) {
        }
    }

    static void key(Context ctx, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
        InputDevice d = e.getDevice();
        write(ctx, "key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " scan=" + e.getScanCode()
                + " device=" + (d == null ? "?" : d.getName()));
    }

    static void media(Context ctx, String pkg, boolean title, boolean sub, boolean art, boolean duration, boolean position, int state) {
        write(ctx, "media " + pkg + " title=" + yn(title) + " subtitle=" + yn(sub) + " art=" + yn(art)
                + " duration=" + yn(duration) + " position=" + yn(position) + " state=" + state);
    }

    private static String yn(boolean b) {
        return b ? "yes" : "no";
    }
}
