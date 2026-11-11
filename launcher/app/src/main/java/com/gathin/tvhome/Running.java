package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which apps are open in the background (the dock's dashes, the switcher's cards), and closing one.
 *
 * An app Glass saw in front counts while its process is still there (and it was in front in the last 12 hours), unless
 * it has been closed since: by Glass (the switcher, the dock's menu), or by anything else (Force stop in the TV's
 * settings, the TV running short of memory). Knowing about the process needs the key helper (Keys), the one thing on
 * the TV that may list every app's process; while it isn't up, an app counts for 3 hours after it was in front, and
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
    /** With its process known to be there: in front within this long (an app left running since yesterday isn't "open"). */
    private static final long ALIVE_MS = 12L * 60 * 60 * 1000;
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
            if (closed.getLong(r[0], 0L) > at) continue;   // closed after it was last in front
            if (live != null) {
                if (!live.contains(r[0])) continue;      // its process is gone (force-stopped, out of memory)
                if (now - at > ALIVE_MS) continue;       // still in memory, but not used today
            } else if (now - at > RECENT_MS) {
                continue;                                // nobody to ask: the last 3 hours
            }
            s.add(r[0]);
        }
        String front = Glass.front();
        if (front != null && !Glass.selfInFront() && closed.getLong(front, 0L) < LastOpened.get(c, front) + 1) s.add(front);
        return s;
    }

    /**
     * The apps to switch between, newest first (the switcher): what quick() counts, TV apps only, in the order they
     * were last in front; the app in front first. {package, when it was last in front}.
     */
    static List<String[]> recent(Context c, int max) {
        if (Sample.on) return Sample.recents(c, max);   // pictures: a few well-known apps, open
        Set<String> open = quick(c);
        PackageManager pm = c.getPackageManager();
        String front = Glass.selfInFront() ? null : Glass.front();
        List<String[]> out = new ArrayList<>();
        for (String[] r : LastOpened.recent(c, 24)) {
            if (!open.remove(r[0])) continue;
            if (r[0].equals(c.getPackageName()) || pm.getLeanbackLaunchIntentForPackage(r[0]) == null) continue;
            if (!Sample.shows(c, r[0])) continue;
            if (r[0].equals(front)) out.add(0, r); else out.add(r);
        }
        // in front but never noted (started some other way): it's still the one on screen
        if (front != null && open.contains(front) && !front.equals(c.getPackageName()) && pm.getLeanbackLaunchIntentForPackage(front) != null
                && Sample.shows(c, front)) {
            out.add(0, new String[]{front, String.valueOf(System.currentTimeMillis())});
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
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
