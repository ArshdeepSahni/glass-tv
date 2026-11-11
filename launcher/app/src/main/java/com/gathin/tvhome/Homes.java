package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * Glass or the TV's original home (Android TV's own launcher, kept on the TV's system partition and turned off when
 * Glass was installed). Switching turns the original on, makes it the TV's home and steps Glass aside (its keys, its
 * volume bar: the TV behaves as it shipped); switching back makes Glass the home again and turns the original off
 * (it costs memory). The phone remote keeps working either way. In the original home, Glass's tile in the apps row
 * offers the way back (SwitchHomeActivity).
 *
 * Turning a built-in app on or off and choosing the home needs the TV's shell user: Glass's helper (Keys) does it,
 * once the TV has allowed Glass's key (Settings ▸ Remote & keyboard ▸ Phone remote helper).
 */
final class Homes {
    private Homes() {
    }

    static final String GLASS_HOME = "com.gathin.tvhome/.GlassHomeActivity";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Done {
        /** ok, or why not: "helper" (Glass's helper isn't allowed yet) or "failed". */
        void done(boolean ok, String why);
    }

    /** The original home's own HOME activity, once it's on. */
    static ComponentName stock(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> homes = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(Glass.STOCK_HOME), 0);
        if (homes != null && !homes.isEmpty()) return new ComponentName(homes.get(0).activityInfo.packageName, homes.get(0).activityInfo.name);
        return new ComponentName(Glass.STOCK_HOME, Glass.STOCK_HOME + ".MainActivity");
    }

    static void toOriginal(Context c, Done done) {
        final Context app = c.getApplicationContext();
        if (!"ready".equals(Keys.status())) {
            Keys.askAgain(app);
            MAIN.post(() -> done.done(false, "helper"));
            return;
        }
        new Thread(() -> {
            boolean on = Glass.stockAvailable(app) || Keys.enable(app, Glass.STOCK_HOME, true);
            long until = SystemClock.elapsedRealtime() + 4000;
            while (!Glass.stockAvailable(app) && SystemClock.elapsedRealtime() < until) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (!on || !Glass.stockAvailable(app)) {
                MAIN.post(() -> done.done(false, "failed"));
                return;
            }
            ComponentName home = stock(app);
            Keys.home(app, home.flattenToShortString());
            Glass.prefs(app).edit().putString(Glass.MODE, "original").putString(Glass.ON_POWER, "original").putInt(Glass.STREAK, 0).apply();
            MAIN.post(() -> {
                Hud.closeAll();
                GlassKeys.start(app, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(home));
                done.done(true, null);
            });
        }, "glass-homes").start();
    }

    static void toGlass(Context c, Done done) {
        final Context app = c.getApplicationContext();
        Glass.prefs(app).edit().putString(Glass.MODE, "glass").putString(Glass.ON_POWER, "glass").putInt(Glass.STREAK, 0).apply();
        Runnable open = () -> {
            GlassKeys.start(app, new Intent(app, GlassHomeActivity.class));
            done.done(true, null);
        };
        if (!"ready".equals(Keys.status())) {
            // Glass comes back now either way (its keys catch Home); the TV's own home is turned off once the helper is up
            Keys.askAgain(app);
            Glass.prefs(app).edit().putBoolean("stock_off_pending", true).apply();
            MAIN.post(open);
            return;
        }
        new Thread(() -> {
            Keys.home(app, GLASS_HOME);
            Keys.enable(app, Glass.STOCK_HOME, false);
            Glass.prefs(app).edit().remove("stock_off_pending").apply();
            MAIN.post(open);
        }, "glass-homes").start();
    }

    /** Where the installer notes, on the TV, the TV's own homes it turned off (in the TV shell's own folder). */
    static final String TURNED_OFF = "/data/local/tmp/glass-turned-off";

    /**
     * At every start of the TV, in Glass's mode: the TV's own homes the installer turned off, off again, and Glass the
     * home again. Some TVs turn their home back on as they start (a Xiaomi does: its home is an updated system app,
     * which Android 9 scans again at every start, and the app comes back on), and then Android ranks it above any
     * app. Only the TV's shell user can read the installer's note or turn an app off, so this goes through Glass's
     * own key (LocalAdb), and only where the TV already allows that key: it never asks anything as the TV starts.
     * Tried a few times: the TV's debugging can take a while to come up after a start.
     */
    /** keepOff is at work: the power-on chooser waits for it (it would offer a home that's about to go). */
    static volatile boolean keeping;

    static void keepOff(Context c) {
        final Context app = c.getApplicationContext();
        if (Glass.original(app) || !Keys.everAllowed(app)) return;
        keeping = true;
        // Google's assistant app may be in the note from a development build of 1.0.1: never turned off (GoogleApp)
        final String skip = Takeover.GOOGLE;
        // a word after each step: the connection gives up on a long silence (pm takes seconds on a 1 GB TV)
        final String cmd = "F=" + TURNED_OFF + "; [ -f $F ] || { echo none; exit 0; }; echo go; "
                + "for p in $(cat $F); do case \"$p\" in *[!A-Za-z0-9._]*|''|" + skip + ") continue;; esac; echo \"is $p\"; "
                + "pm list packages -e \"$p\" 2>/dev/null | grep -qx \"package:$p\" && pm disable-user --user 0 \"$p\" >/dev/null 2>&1 && echo \"off $p\"; done; "
                + "cmd package set-home-activity --user 0 " + GLASS_HOME + " >/dev/null 2>&1; echo done";
        new Thread(() -> {
            try {
                for (int i = 0; i < 4; i++) {
                    try {
                        String out = LocalAdb.quiet(cmd);
                        if (out.contains("done") || out.contains("none")) {
                            android.util.Log.i(Keys.TAG, "homes at start: " + out.replace('\n', ' ').trim());
                            return;
                        }
                    } catch (Exception e) {
                        android.util.Log.i(Keys.TAG, "homes at start: not yet (" + e.getClass().getSimpleName() + ")");
                    }
                    if (i == 0) keeping = false;   // the chooser waits for the first try only
                    Thread.sleep(10_000L * (i + 1));
                }
            } catch (InterruptedException ignored) {
            } finally {
                keeping = false;
            }
        }, "glass-homes").start();
    }

    /** After a switch back made while the helper was down: finish it once the helper is up (no question on the TV). */
    static void tidy(Context c) {
        final Context app = c.getApplicationContext();
        if (!Glass.prefs(app).getBoolean("stock_off_pending", false) || Glass.original(app) || !"ready".equals(Keys.status())) return;
        new Thread(() -> {
            Keys.home(app, GLASS_HOME);
            if (Keys.enable(app, Glass.STOCK_HOME, false)) Glass.prefs(app).edit().remove("stock_off_pending").apply();
        }, "glass-homes").start();
    }
}
