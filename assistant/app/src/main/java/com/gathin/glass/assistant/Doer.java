package com.gathin.glass.assistant;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Does what the conversation decided, on the TV, and says what it did in a few words (the receipt). Things this
 * app can do itself (apps, searches, playback, volume, inputs, links) happen here; the rest (the sleep timer, Wi-Fi,
 * Bluetooth, Glass's own places, reminders, standby) is asked of the desktop, which holds those permissions.
 * `leaves`: the act puts something else in front, so the conversation steps aside.
 */
final class Doer {
    private Doer() {
    }

    static final class Done {
        final String label;
        final boolean ok, leaves;

        Done(String label, boolean ok, boolean leaves) {
            this.label = label;
            this.ok = ok;
            this.leaves = leaves;
        }
    }

    static final String DESK = "content://com.gathin.tvhome.desk";

    static Done run(Context c, Brain.Act a) {
        JSONObject j = a.j;
        String v = j.optString("value", "").trim();
        switch (a.act()) {
            case "open": {
                Actions.App app = Actions.findApp(c, j.optString("app", v));
                if (app == null) return no("Couldn't find " + j.optString("app", v));
                return Actions.open(c, app.pkg) ? new Done("Opened " + app.label, true, true) : no("Couldn't open " + app.label);
            }
            case "search":
                return search(c, j.optString("app", "YouTube"), j.optString("query", v));
            case "media": {
                String what = v.toLowerCase(Locale.ROOT);
                if (!Actions.media(c, what)) return no("Couldn't " + what);
                switch (what) {
                    case "pause": return new Done("Paused", true, false);
                    case "play": return new Done("Playing", true, false);
                    case "next": return new Done("Next", true, false);
                    case "previous": return new Done("Previous", true, false);
                    case "forward": return new Done("Forward", true, false);
                    case "rewind": return new Done("Back a bit", true, false);
                    default: return new Done("Stopped", true, false);
                }
            }
            case "volume": {
                String r = Actions.volume(c, v.toLowerCase(Locale.ROOT).replace("%", ""));
                return r != null ? new Done(r, true, false) : no("Couldn't change the volume");
            }
            case "input":
                return input(c, v);
            case "home":
                return Actions.home(c) ? new Done("Home", true, true) : no("Couldn't go home");
            case "url": {
                if (!v.startsWith("http")) return no("That link didn't look right");
                Uri u = Uri.parse(v);
                return Actions.start(c, new Intent(Intent.ACTION_VIEW, u)) ? new Done("Opened " + u.getHost(), true, true) : no("No browser for that");
            }
            case "sleep": case "night_volume": case "wifi": case "bluetooth": case "settings":
            case "controls": case "apps": case "files": case "remind": case "off": {
                Bundle b = desk(c, j.toString());
                boolean leaves = a.act().equals("settings") || a.act().equals("controls") || a.act().equals("apps") || a.act().equals("files");
                if (b != null && b.getBoolean("ok")) return new Done(b.getString("label", "Done"), true, leaves);
                return no("Couldn't do that here");
            }
            default:
                return no("I can't do that yet");
        }
    }

    private static Done no(String label) {
        return new Done(label, false, false);
    }

    private static Bundle desk(Context c, String json) {
        try {
            return c.getContentResolver().call(Uri.parse(DESK), "do", json, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Done search(Context c, String appName, String q) {
        q = q.trim();
        if (q.isEmpty()) return no("Nothing to search for");
        String app = appName.toLowerCase(Locale.ROOT);
        Uri yt = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q));
        if (app.contains("smarttube")) {
            for (String p : new String[]{"org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv.beta"}) {
                if (Actions.start(c, new Intent(Intent.ACTION_VIEW, yt).setPackage(p))) return new Done("SmartTube: “" + q + "”", true, true);
            }
        }
        if (app.contains("youtube") || app.contains("smarttube")) {
            return Actions.start(c, new Intent(Intent.ACTION_VIEW, yt).setPackage("com.google.android.youtube.tv"))
                    ? new Done("YouTube: “" + q + "”", true, true) : no("Couldn't search YouTube");
        }
        if (app.contains("play store") || app.contains("playstore")) {
            return Actions.start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q))).setPackage("com.android.vending"))
                    ? new Done("Play Store: “" + q + "”", true, true) : no("Couldn't search the Play Store");
        }
        // apps without a search link: open the app, the words are in the conversation to type or say there
        Actions.App a = Actions.findApp(c, appName);
        if (a != null && Actions.open(c, a.pkg)) return new Done("Opened " + a.label + " · search “" + q + "” there", true, true);
        return Actions.start(c, new Intent(Intent.ACTION_VIEW, yt).setPackage("com.google.android.youtube.tv"))
                ? new Done("YouTube: “" + q + "”", true, true) : no("Couldn't search for that");
    }

    private static Done input(Context c, String v) {
        String s = v.toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.startsWith("hdmi")) {
            int n;
            try {
                n = Integer.parseInt(s.substring(4));
            } catch (NumberFormatException e) {
                n = 1;
            }
            return Actions.hdmi(c, n) ? new Done("HDMI " + n, true, true) : no("No HDMI " + n + " here");
        }
        if (s.contains("live") || s.equals("tv")) {
            Actions.App live = Actions.findApp(c, "livetv");
            return live != null && Actions.open(c, live.pkg) ? new Done("Live TV", true, true) : no("Couldn't open Live TV");
        }
        if (s.equals("av") || s.contains("composite")) {
            TvInputManager tim = c.getSystemService(TvInputManager.class);
            if (tim != null) {
                try {
                    for (TvInputInfo info : tim.getTvInputList()) {
                        if (info.getType() == TvInputInfo.TYPE_COMPOSITE && info.getParentId() == null) {
                            Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
                            if (Actions.start(c, new Intent(view).setPackage("com.mediatek.wwtv.tvcenter")) || Actions.start(c, view)) return new Done("AV", true, true);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // no inputs to read
                }
            }
            return no("No AV input here");
        }
        return no("I don't know that input");
    }

    /** Whether someone's own words already asked for this input (then there's nothing to confirm). */
    static boolean saidInput(String said) {
        String s = said.toLowerCase(Locale.ROOT);
        return s.contains("hdmi") || s.contains("input") || s.contains("live tv") || s.matches(".*\\bav\\b.*") || s.contains("playstation") || s.contains("set-top") || s.contains("set top");
    }
}
