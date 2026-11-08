package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * Which apps are open in the background (the dock's dashes, the switcher's cards), and closing one.
 *
 * An app that Glass saw in front in the last 3 hours counts, unless it has been closed since: by Glass (the switcher,
 * the dock's menu), or by anything else (Force stop in the TV's settings, the TV running short of memory). That last
 * part needs the key helper (Keys), the one thing on the TV that may list every app's process; while it isn't up,
 * what Glass closed itself is still left out.
 *
 * Closing goes through the helper when it's up (as Android's own Force stop does it); without it, Android lets Glass
 * close an app only while it's in the background, which it always is here (the switcher is in front).
 */
final class Running {
    private Running() {
    }

    private static final String PREFS = "closed_apps";
    private static final long RECENT_MS = 3L * 60 * 60 * 1000;
    /** A list of processes this fresh stands in for a new one. */
    private static final long FRESH_MS = 20_000;

    private static volatile Set<String> procs;
    private static volatile long procsAt;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Its own thread: a question to the helper can take a moment, and nothing else should wait behind it. */
    private static final java.util.concurrent.ExecutorService WORK = java.util.concurrent.Executors.newSingleThreadExecutor();

    interface Done {
        void running(Set<String> pkgs);
    }

    /** Right now, without waiting for anything: the last process list if it's fresh, else what Glass knows. */
    static Set<String> quick(Context c) {
        Set<String> s = new HashSet<>();
        long now = System.currentTimeMillis();
        SharedPreferences closed = prefs(c);
        Set<String> live = procs != null && SystemClock.elapsedRealtime() - procsAt < FRESH_MS ? procs : null;
        for (String[] r : LastOpened.recent(c, 10)) {
            long at;
            try {
                at = Long.parseLong(r[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (now - at > RECENT_MS) continue;
            if (closed.getLong(r[0], 0L) > at) continue;   // closed after it was last in front
            if (live != null && !live.contains(r[0])) continue;   // its process is gone (force-stopped, out of memory)
            s.add(r[0]);
        }
        String front = Glass.front();
        if (front != null && !Glass.selfInFront() && closed.getLong(front, 0L) < LastOpened.get(c, front) + 1) s.add(front);
        return s;
    }

    /** The same, after asking the helper for every app's process (in the background); `done` runs on the main thread. */
    static void refresh(Context c, Done done) {
        final Context app = c.getApplicationContext();
        if (!"ready".equals(Keys.status())) {
            if (Keys.everAllowed(app)) Keys.warm(app);   // allowed before: no question on the TV, just up again
            MAIN.post(() -> done.running(quick(app)));
            return;
        }
        WORK.execute(() -> {
            Set<String> p = Keys.running(app);
            if (p != null) {
                procs = p;
                procsAt = SystemClock.elapsedRealtime();
            }
            MAIN.post(() -> done.running(quick(app)));
        });
    }

    /** True when an app closed by Glass hasn't been opened again since. */
    static boolean closed(Context c, String pkg, long openedAt) {
        return prefs(c).getLong(pkg, 0L) > openedAt;
    }

    /** Closes an app (off the main thread: the helper may take a moment), then `then` on the main thread. */
    static void close(Context c, String pkg, Runnable then) {
        final Context app = c.getApplicationContext();
        prefs(app).edit().putLong(pkg, System.currentTimeMillis()).apply();
        if (procs != null) {
            Set<String> p = new HashSet<>(procs);
            p.remove(pkg);
            procs = p;
        }
        WORK.execute(() -> {
            boolean done = "ready".equals(Keys.status()) && Keys.close(app, pkg);
            if (!done) {
                ActivityManager am = app.getSystemService(ActivityManager.class);
                try {
                    if (am != null) am.killBackgroundProcesses(pkg);
                } catch (RuntimeException ignored) {
                    // not allowed on this TV: it still leaves the dock and the switcher
                }
            }
            if (then != null) MAIN.post(then);
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
