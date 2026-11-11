package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Google's assistant app (Takeover.GOOGLE) stays on: on a TV whose remote's microphone reaches only that app (a
 * Xiaomi's), its speech recognizer is how Glass's assistant hears the remote at all. A development build of 1.0.1
 * turned it off there (noted with the TV's homes Glass turned off, Homes.TURNED_OFF): this turns it back on, once, at
 * the helper's start. Through Glass's own key (LocalAdb), only where the TV already allows it; nothing is asked on the TV.
 */
final class GoogleApp {
    private GoogleApp() {
    }

    private static final String TAG = "GlassKeys";

    /** Whether Google's app is on now (installed, and not turned off). */
    static boolean on(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(Takeover.GOOGLE, PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return true;   // not on this TV: nothing to turn on
        }
    }

    /** Off the main thread. Google's app back on where Glass turned it off, and out of Glass's note. */
    static void restore(Context c) {
        final Context app = c.getApplicationContext();
        if (on(app) || !Keys.everAllowed(app)) return;
        final String p = Takeover.GOOGLE, f = Homes.TURNED_OFF;
        // only where Glass turned it off (it's in the note): someone else's choice to turn it off stays
        final String cmd = "grep -qx " + p + " " + f + " 2>/dev/null || { echo not-glass; exit 0; }; "
                + "pm enable " + p + " >/dev/null 2>&1; echo x; grep -vx " + p + " " + f + " > " + f + ".n; mv " + f + ".n " + f + "; "
                + "pm list packages -e 2>/dev/null | grep -qx package:" + p + " && echo done-on";
        try {
            String out = LocalAdb.quiet(cmd);
            Log.i(TAG, "google's assistant app: " + (out.contains("done-on") ? "on again" : out.contains("not-glass") ? "off, not by Glass" : "couldn't turn on"));
        } catch (Exception e) {
            Log.i(TAG, "google's assistant app: couldn't turn on (" + e.getClass().getSimpleName() + ")");
        }
        Assist.settleEntry(app);   // Google's app answers the TV's assistant request again: Glass's entry steps aside
    }
}
