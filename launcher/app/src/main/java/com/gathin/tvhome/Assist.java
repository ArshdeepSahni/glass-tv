package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

/**
 * Who the remote's assistant button belongs to: Glass's assistant, unless someone chose Google (Settings ▸ Assistant).
 * Glass keeps that choice itself. Android's own setting for it isn't enough: on a TV with little memory (Android's
 * "low RAM" kind, like a 1 GB Xiaomi) Android clears voice_interaction_service at every start. Its other setting,
 * assistant, stays, so a choice made before this note existed is read from there.
 */
final class Assist {
    private Assist() {
    }

    static final String PREF = "assistant_button";   // "glass" | "google"
    static final String GLASS_VOICE = "com.gathin.glass.assistant/.GlassVoiceService";
    static final String GOOGLE_VOICE = "com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService";
    private static final String VIS = "voice_interaction_service", ASSISTANT = "assistant";

    static boolean owns(Context c) {
        String v = Glass.prefs(c).getString(PREF, null);
        if (v != null) return "glass".equals(v);
        // no choice made in Glass's Settings yet: Glass's, as the installer sets it up. Unless Android's setting holds
        // Google's assistant exactly as Glass's Settings wrote it, before Glass kept the choice itself. (Google's own
        // assistant writes these settings too, at its own start, so they alone can't say what you chose.)
        return !GOOGLE_VOICE.equals(Settings.Secure.getString(c.getContentResolver(), VIS));
    }

    /** The choice, from Settings. False when Android's own settings couldn't follow (the installer's permission). */
    static boolean set(Context c, boolean toGlass) {
        Glass.prefs(c).edit().putString(PREF, toGlass ? "glass" : "google").apply();
        String to = toGlass ? GLASS_VOICE : GOOGLE_VOICE;
        ContentResolver cr = c.getContentResolver();
        try {
            Settings.Secure.putString(cr, ASSISTANT, to);
            // a low-RAM TV runs no assistant service (Android clears this at every start): binding one costs memory
            if (!lowRam(c)) Settings.Secure.putString(cr, VIS, to);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * When the helper starts (each start of the TV): Android's settings back in line with the choice, where a start
     * cleared them, and Glass's assistant told to check who answers the TV's assistant request (AssistEntry). Off
     * the main thread: the assistant may have to start for it.
     */
    static void keep(Context c) {
        if (c.getPackageManager().getLaunchIntentForPackage(GlassKeys.ASSISTANT) == null) return;
        // the choice, noted once: Google's assistant rewrites Android's settings when it starts (a Xiaomi's deletes
        // assistant), and that must not take the button from Glass
        if (!Glass.prefs(c).contains(PREF)) Glass.prefs(c).edit().putString(PREF, owns(c) ? "glass" : "google").apply();
        // a development build of 1.0.1 could hand the button to Google by itself: it's Glass's again, as chosen
        if (Glass.prefs(c).contains("voice_unreadable")) {
            Glass.prefs(c).edit().remove("voice_unreadable").remove("voice_unread_rides").putString(PREF, "glass").apply();
        }
        // Google's assistant app on, where a development build turned it off (its recognizer is how Glass hears there)
        GoogleApp.restore(c);
        if (owns(c)) {
            ContentResolver cr = c.getContentResolver();
            try {
                if (!glass(Settings.Secure.getString(cr, ASSISTANT))) Settings.Secure.putString(cr, ASSISTANT, GLASS_VOICE);
                if (!lowRam(c) && !glass(Settings.Secure.getString(cr, VIS))) Settings.Secure.putString(cr, VIS, GLASS_VOICE);
            } catch (RuntimeException ignored) {
                // no permission (installed by hand): the button follows Glass's own note all the same
            }
        }
        if (assistUnsettled(c)) settleEntry(c);   // the assistant isn't started just for this (memory, on a 1 GB TV)
    }

    /** Glass's assistant looks again at who answers the TV's assistant request (AssistEntry): after Google's app went
     *  off or on (GoogleApp), it's the only one that does, or not. Off the main thread: the assistant may start. */
    static void settleEntry(Context c) {
        try {
            c.getContentResolver().call(Uri.parse("content://com.gathin.glass.assistant.chat"), "assist_entry", null, null);
        } catch (RuntimeException ignored) {
            // an older assistant: it answers the request as before
        }
    }

    /** Both Glass's assistant and another app answer the TV's assistant request (Android would ask which, at every
     *  press), or neither does: Glass's assistant should look again (AssistEntry). */
    private static boolean assistUnsettled(Context c) {
        boolean glass = false, other = false;
        try {
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_ASSIST), PackageManager.MATCH_DEFAULT_ONLY)) {
                if (ri.activityInfo == null) continue;
                if (GlassKeys.ASSISTANT.equals(ri.activityInfo.packageName)) glass = true;
                else other = true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return glass == other;
    }

    private static boolean glass(String v) {
        return v != null && v.startsWith(GlassKeys.ASSISTANT + "/");
    }

    private static boolean lowRam(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        return am != null && am.isLowRamDevice();
    }
}
