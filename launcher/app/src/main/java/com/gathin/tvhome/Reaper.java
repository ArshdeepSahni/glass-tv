package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import java.util.List;

/**
 * Glass: reclaims the RAM an app takes at boot without being asked. Some partner builds start Netflix in the
 * background at every power-on (its own BOOT_COMPLETED receiver, which the shell may not disable); on a 1 GB
 * set that is a tenth of the memory, held for nothing. Once Home is up after a boot, any app on the list that
 * is running in the background is asked to stop with ActivityManager.killBackgroundProcesses, a normal
 * permission that only ever stops background processes: an app in front, or playing, is never touched, and
 * the app starts normally, from scratch, the next time it is opened.
 */
final class Reaper {
    /** Only within this long after boot: later, a background process is one the owner put there. */
    private static final long BOOT_WINDOW_MS = 10 * 60 * 1000L;
    private static boolean done;

    private Reaper() {
    }

    static void afterBoot(Context ctx, List<String> packages) {
        if (done || packages.isEmpty()) return;
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) { done = true; return; }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        for (String pkg : packages) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (SecurityException ignored) {
                // the permission is missing from this build: nothing to do
            }
        }
        done = true;
    }
}
