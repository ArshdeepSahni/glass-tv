package com.gathin.glass.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The things the assistant can do to the TV without root: open apps and inputs, volume, media keys, Home, Settings. */
final class Actions {
    private Actions() {
    }

    static final class App {
        final String label, pkg;
        App(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }

    static List<App> apps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            boolean dup = false;
            for (App a : out) if (a.pkg.equals(pkg)) { dup = true; break; }
            if (!dup) out.add(new App(String.valueOf(ri.loadLabel(pm)), pkg));
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Best app for a spoken name, or null. Common nicknames first, then label containment either way. */
    static App findApp(Context ctx, String name) {
        String n = norm(name);
        if (n.isEmpty()) return null;
        String[][] nick = {
                {"prime", "com.amazon.amazonvideo.livingroom"}, {"amazon", "com.amazon.amazonvideo.livingroom"},
                {"hotstar", "in.startv.hotstar"}, {"jiohotstar", "in.startv.hotstar"}, {"jio", "in.startv.hotstar"},
                {"youtube", "com.google.android.youtube.tv"}, {"netflix", "com.netflix.ninja"},
                {"music", "com.google.android.youtube.tvmusic"}, {"livetv", "com.mediatek.wwtv.tvcenter"},
                {"tv", "com.mediatek.wwtv.tvcenter"}, {"browser", "com.phlox.tvwebbrowser"},
        };
        List<App> all = apps(ctx);
        for (String[] k : nick) {
            if (n.equals(k[0])) for (App a : all) if (a.pkg.equals(k[1])) return a;
        }
        App best = null;
        for (App a : all) {
            String l = norm(a.label);
            if (l.equals(n)) return a;
            if (best == null && (l.startsWith(n) || l.contains(n) || n.contains(l))) best = a;
        }
        return best;
    }

    static boolean open(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        return start(ctx, i);
    }

    static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean home(Context ctx) {
        return start(ctx, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
    }

    static boolean settings(Context ctx, String which) {
        String a = "wifi".equals(which) ? Settings.ACTION_WIFI_SETTINGS
                : "bluetooth".equals(which) ? Settings.ACTION_BLUETOOTH_SETTINGS : Settings.ACTION_SETTINGS;
        return start(ctx, new Intent(a));
    }

    /** volume: "up" / "down" / "mute" / "unmute" / a number 0-100 (percent of max). Returns the new level text. */
    static String volume(Context ctx, String how) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return null;
        // no TV bar: Glass's own volume HUD follows the change (the desktop listens for it)
        int s = AudioManager.STREAM_MUSIC, f = 0, max = am.getStreamMaxVolume(s);
        switch (how) {
            case "up": am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f); am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f); break;
            case "down": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f); am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f); break;
            case "mute": am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, f); return "Muted";
            case "unmute": am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, f); break;
            default:
                try {
                    int pct = Math.max(0, Math.min(100, Integer.parseInt(how)));
                    // On this TV the scale is 0-100 already; on others, map a spoken number to the TV's own scale.
                    int v = max >= 100 ? Math.round(pct * max / 100f) : (pct <= max ? pct : Math.round(pct * max / 100f));
                    am.setStreamVolume(s, v, f);
                } catch (NumberFormatException e) {
                    return null;
                }
        }
        return "Volume " + am.getStreamVolume(s);
    }

    /** media: play / pause / next / previous / stop, sent to whatever is playing. */
    static boolean media(Context ctx, String what) {
        int code;
        switch (what) {
            case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            case "forward": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            case "rewind": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            default: return false;
        }
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return false;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        return true;
    }

    /** hdmi N (1-based): the Nth HDMI input, opened like the TV's own source menu does. */
    static boolean hdmi(Context ctx, int n) {
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        if (tim == null) return false;
        List<TvInputInfo> hdmi = new ArrayList<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) hdmi.add(info);
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (n < 1 || n > hdmi.size()) return false;
        Uri uri = TvContract.buildChannelUriForPassthroughInput(hdmi.get(n - 1).getId());
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        Intent live = new Intent(view).setPackage("com.mediatek.wwtv.tvcenter");
        return start(ctx, live) || start(ctx, view);
    }
}
