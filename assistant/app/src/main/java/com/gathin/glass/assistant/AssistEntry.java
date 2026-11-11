package com.gathin.glass.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * Whether Glass answers the TV's assistant request (android.intent.action.ASSIST) itself. Only where no other app
 * does: a TV with Google's assistant sends the request there, and the desktop takes over from Google's screen
 * (Takeover). With both answering, Android would ask which one at every press (a 1 GB Xiaomi, whose remote's
 * button arrives as that request). Checked when Glass's assistant opens and when the desktop starts.
 */
final class AssistEntry {
    private AssistEntry() {
    }

    static final String ALIAS = "com.gathin.glass.assistant.AssistEntry";

    static void check(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            boolean others = false;
            List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_ASSIST), PackageManager.MATCH_DEFAULT_ONLY);
            if (all != null) for (ResolveInfo ri : all) {
                if (ri.activityInfo != null && !c.getPackageName().equals(ri.activityInfo.packageName)) {
                    others = true;
                    break;
                }
            }
            ComponentName alias = new ComponentName(c.getPackageName(), ALIAS);
            int want = others ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (pm.getComponentEnabledSetting(alias) != want) {
                pm.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException ignored) {
            // the package manager said no: things stay as they were
        }
    }
}
