package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Glass: predicted focus. Which app Home should land on, learned from what the owner opens, on this TV only.
 *
 * Every launch from Home is counted in a bucket of (weekday-or-weekend, hour). Asked for a prediction, it adds
 * the buckets for this hour and its neighbours (a three-hour window, so a 7:58 habit counts at 8:05), and names
 * the app with the most opens when that app holds at least {@link #CONFIDENCE} of the window's opens and the
 * window has seen at least {@link #MIN_OPENS}. Below that it names nothing, and Home falls back to the app last
 * opened, then the first tile: a wrong guess costs a press, so a guess is only offered when it is likely right.
 *
 * Storage is one SharedPreferences file of "wd:h" -> "pkg=n;pkg=n". Nothing leaves the TV, nothing is read from
 * other apps, and a factory reset or a clear of this app's data forgets it all.
 */
final class Predict {
    private static final String PREFS = "predict";
    static final double CONFIDENCE = 0.6;
    static final int MIN_OPENS = 3;
    /** Counts decay so a changed habit wins within a couple of weeks: once a bucket passes this, all its counts halve. */
    private static final int DECAY_AT = 40;

    private Predict() {
    }

    /** Notes that Home opened this app now. */
    static void record(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Calendar now = Calendar.getInstance();
        record(prefs(ctx), pkg, bucket(now));
    }

    static void record(SharedPreferences prefs, String pkg, String bucket) {
        Map<String, Integer> counts = parse(prefs.getString(bucket, ""));
        Integer n = counts.get(pkg);
        counts.put(pkg, n == null ? 1 : n + 1);
        int total = 0;
        for (int v : counts.values()) total += v;
        if (total > DECAY_AT) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) e.setValue(Math.max(1, e.getValue() / 2));
        }
        prefs.edit().putString(bucket, format(counts)).apply();
    }

    /** The package Home should focus now, or null when no app is likely enough. */
    static String predicted(Context ctx) {
        return predicted(prefs(ctx), Calendar.getInstance());
    }

    static String predicted(SharedPreferences prefs, Calendar now) {
        Map<String, Integer> window = new HashMap<>();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String day = dayClass(now);
        for (int d = -1; d <= 1; d++) {
            int h = ((hour + d) % 24 + 24) % 24;
            // The hour itself counts double: a habit at 8 should beat one at 7 when asked at 8.
            int weight = d == 0 ? 2 : 1;
            for (Map.Entry<String, Integer> e : parse(prefs.getString(day + ":" + h, "")).entrySet()) {
                Integer v = window.get(e.getKey());
                window.put(e.getKey(), (v == null ? 0 : v) + e.getValue() * weight);
            }
        }
        int total = 0, best = 0;
        String bestPkg = null;
        for (Map.Entry<String, Integer> e : window.entrySet()) {
            total += e.getValue();
            if (e.getValue() > best) { best = e.getValue(); bestPkg = e.getKey(); }
        }
        if (bestPkg == null || total < MIN_OPENS * 2 || best < CONFIDENCE * total) return null;
        return bestPkg;
    }

    /** "wd:20" or "we:20": weekdays and weekends keep separate habits. */
    static String bucket(Calendar now) {
        return dayClass(now) + ":" + now.get(Calendar.HOUR_OF_DAY);
    }

    private static String dayClass(Calendar now) {
        int dow = now.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY ? "we" : "wd";
    }

    static Map<String, Integer> parse(String s) {
        Map<String, Integer> out = new HashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static String format(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
