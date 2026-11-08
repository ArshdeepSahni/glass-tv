package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Glass II: shared state for the Glass screens — preferences (mode, guest curtain, simple size, the day palette,
 * the night volume ceiling), the app in front and the one before it (for Flip and Resume), the day palette itself,
 * and the two typefaces. Plain statics and one SharedPreferences file: nothing here touches the network.
 */
final class Glass {
    private Glass() {
    }

    // ---- preferences ----------------------------------------------------------------------------------------

    static final String PREFS = "glass";
    static final String MODE = "mode";                 // "glass" | "original"
    static final String ON_POWER = "on_power";         // "ask" | "glass" | "original"
    static final String STREAK = "streak";             // same choice in a row at the power-on chooser
    static final String GUEST = "guest";
    static final String LIGHT_FOLLOWS_DAY = "light_follows_day";
    static final String NIGHT_CEILING = "night_ceiling"; // 0 = off
    static final String STOCK_HOME = "com.google.android.tvlauncher";

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean original(Context ctx) {
        return "original".equals(prefs(ctx).getString(MODE, "glass")) && stockAvailable(ctx);
    }

    /** The original home can only be offered while its package is enabled: Android always ranks a built-in home
     *  above an installed one, so keeping it enabled costs memory and Glass relies on the Home key to stay in
     *  front. The installer decides; everything Original-related hides itself when it is off. */
    static boolean stockAvailable(Context ctx) {
        try {
            return ctx.getPackageManager().getApplicationInfo(STOCK_HOME, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean guest(Context ctx) {
        return prefs(ctx).getBoolean(GUEST, false);
    }

    static boolean simple(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(Config.SIMPLE_SIZE_KEY, false);
    }

    static void setSimple(Context ctx, boolean on) {
        SharedPreferences s = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        s.edit().putBoolean(Config.SIMPLE_SIZE_KEY, on)
                .putLong(Config.SETTINGS_REVISION_KEY, s.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    // ---- the app in front, and the one before it ----------------------------------------------------------

    private static String front, previous;
    private static boolean selfInFront;

    static boolean selfInFront() {
        return selfInFront;
    }

    /** From HomeKeyService's window events: activities only, and our own screens never count as "an app". */
    static void onFront(String pkg, String self) {
        if (pkg == null) return;
        // the assistant (ours or Google's) sits over whatever is in front: it never becomes "the app"
        if (pkg.startsWith("com.gathin.glass") || pkg.equals("com.google.android.katniss")) return;
        if (pkg.equals(self)) { selfInFront = true; return; }
        selfInFront = false;
        if (pkg.equals(front)) return;
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) return;
        previous = front;
        front = pkg;
    }

    static String front() {
        return front;
    }

    /** Every real app that comes to the front joins the recent apps (the switcher, the dock's dashes). */
    static void noteFront(Context ctx, String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName()) || pkg.equals("android") || pkg.equals("com.android.systemui")
                || pkg.equals(STOCK_HOME) || pkg.startsWith("com.gathin.glass") || pkg.equals("com.google.android.katniss")
                || pkg.contains(".settings") || guest(ctx)) return;
        if (!pkg.equals(front)) return;
        PackageManager pm = ctx.getPackageManager();
        // TV apps only (what All apps shows): firmware helpers that flash up at boot never join the recents
        if (pm.getLeanbackLaunchIntentForPackage(pkg) == null) return;
        LastOpened.record(ctx, pkg);
    }

    static String previous() {
        return previous;
    }

    /** Opens an app the way Home does, and remembers it for Continue and Predict unless the curtain is down. */
    static boolean open(Context ctx, String pkg) {
        return open(ctx, pkg, null);
    }

    /** The same, the app's window zooming out of `from` (its icon) on its way in. */
    static boolean open(Context ctx, String pkg, android.view.View from) {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return false;
        if (!guest(ctx)) {
            LastOpened.record(ctx, pkg);
            Predict.record(ctx, pkg);
        }
        try {
            android.os.Bundle zoom = null;
            if (from != null && from.isAttachedToWindow() && from.getWidth() > 0) {
                zoom = android.app.ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            }
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), zoom);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static CharSequence label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Config cfg = Config.load(ctx);
            String custom = cfg.appLabels.get(pkg);
            if (custom != null) return custom;
            return Cards.cleanLabel(String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm)), pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** True when the default network has passed Android's internet check. */
    static boolean online(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    static boolean connected(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    // ---- the light follows the day ------------------------------------------------------------------------

    /** One palette per part of the day. Every colour a Glass screen uses comes from here. */
    static final class Palette {
        final int ground, ink, dim, faint, accent, glow;
        final String period;
        final boolean warmGlowLow;   // evening light sits low-left; morning light high-right
        Palette(String period, int ground, int ink, int dim, int faint, int accent, int glow, boolean warmGlowLow) {
            this.period = period; this.ground = ground; this.ink = ink; this.dim = dim; this.faint = faint;
            this.accent = accent; this.glow = glow; this.warmGlowLow = warmGlowLow;
        }
    }

    static final Palette MORNING = new Palette("morning", 0xFF0C1016, 0xFFEEF2F8, 0xFF8EA0B8, 0xFF5F6E82, 0xFF7DAAFF, 0x527DAAFF, false);
    static final Palette DAY = new Palette("afternoon", 0xFF121315, 0xFFFFFFFF, 0xFF9AA0A6, 0xFF6B7078, 0xFFFFFFFF, 0x00000000, false);
    static final Palette EVENING = new Palette("evening", 0xFF0C0A09, 0xFFF4EFE9, 0xFFA79E94, 0xFF6F675F, 0xFFFF6A3D, 0x8CC2410C, true);
    static final Palette NIGHT = new Palette("night", 0xFF050404, 0xFFE8C4A2, 0xFF6E5646, 0xFF4D3D32, 0xFF8A5A3A, 0x00000000, true);
    static final Palette GUEST_PALETTE = new Palette("guests", 0xFF0E0D10, 0xFFEEEAF2, 0xFF8C8696, 0xFF6C6676, 0xFF8B7CF6, 0x00000000, false);

    static Palette palette(Context ctx) {
        if (guest(ctx)) return GUEST_PALETTE;
        if (!prefs(ctx).getBoolean(LIGHT_FOLLOWS_DAY, true)) return EVENING;
        return paletteAt(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    /** 06–11 morning, 11–18:30 afternoon, 18:30–23 evening, 23–06 night (minute precision is not worth the code). */
    static Palette paletteAt(int hour) {
        if (hour >= 6 && hour < 11) return MORNING;
        if (hour >= 11 && hour < 18) return DAY;
        if (hour >= 18 && hour < 23) return EVENING;
        return NIGHT;
    }

    static boolean lateNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 6;
    }

    static String partOfDay() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String day = Calendar.getInstance().getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.getDefault());
        if (h >= 5 && h < 12) return day + " morning";
        if (h >= 12 && h < 17) return day + " afternoon";
        if (h >= 17 && h < 23) return day + " evening";
        return "Late";
    }

    // ---- type ---------------------------------------------------------------------------------------------

    private static Typeface serif, serifItalic, sans, sansMedium, sansSemibold;

    static Typeface serif(Context ctx) {
        if (serif == null) serif = load(ctx.getResources(), R.font.instrument_serif, Typeface.SERIF);
        return serif;
    }

    static Typeface sans(Context ctx) {
        if (sans == null) sans = load(ctx.getResources(), R.font.inter_regular, Typeface.SANS_SERIF);
        return sans;
    }

    static Typeface sansMedium(Context ctx) {
        if (sansMedium == null) sansMedium = load(ctx.getResources(), R.font.inter_medium, Typeface.SANS_SERIF);
        return sansMedium;
    }

    static Typeface sansSemibold(Context ctx) {
        if (sansSemibold == null) sansSemibold = load(ctx.getResources(), R.font.inter_semibold, Typeface.DEFAULT_BOLD);
        return sansSemibold;
    }

    static Typeface serifItalic(Context ctx) {
        if (serifItalic == null) serifItalic = load(ctx.getResources(), R.font.instrument_serif_italic, Typeface.SERIF);
        return serifItalic;
    }

    private static Typeface load(Resources res, int id, Typeface fallback) {
        try {
            Typeface t = res.getFont(id);
            return t != null ? t : fallback;
        } catch (Resources.NotFoundException e) {
            return fallback;
        }
    }

    // ---- small helpers ----------------------------------------------------------------------------------------

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    static List<String> list(String... items) {
        List<String> out = new ArrayList<>();
        for (String s : items) out.add(s);
        return out;
    }
}
