package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Glass IV: the household's threads, the things it is in the middle of. One per app: what the app last said was
 * playing (title, artwork, position) when it publishes a media session, otherwise just how long it was watched
 * and when. Built bottom-up from what the TV observed, so it works for apps that never integrated with Android's
 * "watch next". Threads nobody has touched for three days fold away by themselves (they stay one level down).
 * Everything is local: one small JSON list and at most a few artwork files.
 */
final class Threads {
    private Threads() {
    }

    static final long TIDY_MS = 3L * 24 * 60 * 60 * 1000;
    private static final String PREFS = "threads";
    private static final int MAX = 12;

    static final class Thread {
        String pkg;
        String title;          // null when the app didn't say
        String sub;            // "Season 2 · Episode 4", a channel name...
        long durationMs, positionMs, updatedAt, watchedMs;
        boolean playing, art;
        int tint;              // the artwork's own colour, for the light on the wall

        boolean hasProgress() { return durationMs > 60_000 && positionMs > 0 && positionMs < durationMs; }

        int percent() { return hasProgress() ? (int) Math.min(100, positionMs * 100 / durationMs) : 0; }

        long leftMs() { return hasProgress() ? durationMs - positionMs : 0; }
    }

    // ---- store --------------------------------------------------------------------------------------------

    private static List<Thread> cache;

    static synchronized List<Thread> all(Context ctx) {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Thread t = new Thread();
                t.pkg = o.getString("pkg");
                t.title = o.has("title") ? o.getString("title") : null;
                t.sub = o.has("sub") ? o.getString("sub") : null;
                t.durationMs = o.optLong("dur");
                t.positionMs = o.optLong("pos");
                t.updatedAt = o.optLong("at");
                t.watchedMs = o.optLong("watched");
                t.art = o.optBoolean("art");
                t.tint = o.optInt("tint", 0);
                cache.add(t);
            }
        } catch (Exception ignored) {
            // a damaged list starts over; nothing here is precious
        }
        return cache;
    }

    private static synchronized void save(Context ctx) {
        JSONArray a = new JSONArray();
        try {
            for (Thread t : all(ctx)) {
                JSONObject o = new JSONObject().put("pkg", t.pkg).put("dur", t.durationMs).put("pos", t.positionMs)
                        .put("at", t.updatedAt).put("watched", t.watchedMs).put("art", t.art).put("tint", t.tint);
                if (t.title != null) o.put("title", t.title);
                if (t.sub != null) o.put("sub", t.sub);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putString("list", a.toString()).apply();
        revision++;
    }

    /** Bumped on every change, so the shelf can tell cheaply whether to rebuild. */
    static volatile int revision;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Thread find(Context ctx, String pkg, boolean create) {
        for (Thread t : all(ctx)) if (t.pkg.equals(pkg)) return t;
        if (!create) return null;
        Thread t = new Thread();
        t.pkg = pkg;
        all(ctx).add(0, t);
        while (all(ctx).size() > MAX) all(ctx).remove(all(ctx).size() - 1);
        return t;
    }

    // ---- what the TV observes -----------------------------------------------------------------------------

    /** From a media session (ThreadListener): what is playing, where, with its artwork. */
    static synchronized void fromSession(Context ctx, String pkg, MediaMetadata md, PlaybackState st) {
        if (pkg == null || pkg.equals(ctx.getPackageName()) || Glass.guest(ctx)) return;
        Thread t = find(ctx, pkg, true);
        boolean changed = false;
        if (md != null || st != null) {
            Probe.media(ctx, pkg, md != null && text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE) != null,
                    md != null && text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST) != null,
                    md != null && (md.getBitmap(MediaMetadata.METADATA_KEY_ART) != null || md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                            || md.getString(MediaMetadata.METADATA_KEY_ART_URI) != null),
                    md != null && md.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0,
                    st != null && st.getPosition() > 0, st == null ? -1 : st.getState());
        }
        if (md != null) {
            String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            String sub = text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
            if (title != null && !title.equals(t.title)) {
                t.title = title;
                t.sub = sub;
                t.positionMs = 0;
                t.art = saveArt(ctx, t, md);
                changed = true;
            } else if (title != null && !t.art) {
                t.art = saveArt(ctx, t, md);
            }
            long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (d > 0) t.durationMs = d;
        }
        if (st != null) {
            t.playing = st.getState() == PlaybackState.STATE_PLAYING;
            long pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
            if (pos > 0) t.positionMs = pos;
        }
        t.updatedAt = System.currentTimeMillis();
        moveToFront(ctx, t);
        save(ctx);
        if (changed) revision++;
    }

    /** From Media (what an app says is playing, read through the key helper where sessions can't be heard here). */
    static synchronized void fromMedia(Context ctx, Media.Now n, Bitmap picture) {
        if (n == null || n.pkg == null || n.title == null || n.pkg.equals(ctx.getPackageName()) || Glass.guest(ctx)) return;
        Thread t = find(ctx, n.pkg, true);
        boolean was = t.playing;
        boolean changed = !n.title.equals(t.title);
        if (changed) {
            t.title = n.title;
            t.sub = n.artist;
            t.art = false;
        }
        if (!t.art && picture != null) t.art = savePicture(ctx, t, picture);
        if (n.dur > 0) t.durationMs = n.dur;
        long pos = n.position();
        if (pos > 0) t.positionMs = pos;
        t.playing = n.playing();
        // a song that keeps playing touches the list at most once a minute (the list is written to disk)
        if (!changed && was == t.playing && System.currentTimeMillis() - t.updatedAt < 60_000) return;
        t.updatedAt = System.currentTimeMillis();
        moveToFront(ctx, t);
        save(ctx);
    }

    /** From the app in front (HomeKeyService): watch time for apps that never say what is playing. */
    static synchronized void watched(Context ctx, String pkg, long ms) {
        if (pkg == null || ms < 60_000 || Glass.guest(ctx)) return;
        Thread t = find(ctx, pkg, true);
        // a new sitting after six quiet hours starts the count again
        if (System.currentTimeMillis() - t.updatedAt > 6L * 60 * 60 * 1000) t.watchedMs = 0;
        t.watchedMs += ms;
        t.updatedAt = System.currentTimeMillis();
        moveToFront(ctx, t);
        save(ctx);
    }

    static synchronized void forget(Context ctx, String pkg) {
        Thread t = find(ctx, pkg, false);
        if (t == null) return;
        all(ctx).remove(t);
        new File(ctx.getFilesDir(), artName(pkg)).delete();
        save(ctx);
    }

    private static void moveToFront(Context ctx, Thread t) {
        List<Thread> l = all(ctx);
        l.remove(t);
        l.add(0, t);
    }

    /** The shelf: recent threads, newest first, three days at most. */
    static synchronized List<Thread> shelf(Context ctx, int max) {
        List<Thread> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Thread t : all(ctx)) {
            if (now - t.updatedAt > TIDY_MS) continue;
            if (ctx.getPackageManager().getLeanbackLaunchIntentForPackage(t.pkg) == null
                    && ctx.getPackageManager().getLaunchIntentForPackage(t.pkg) == null) continue;
            out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    static synchronized Thread of(Context ctx, String pkg) {
        return find(ctx, pkg, false);
    }

    // ---- artwork ------------------------------------------------------------------------------------------

    private static String artName(String pkg) {
        return "art_" + pkg.replace('.', '_') + ".jpg";
    }

    static Bitmap art(Context ctx, Thread t) {
        if (!t.art) return null;
        File f = new File(ctx.getFilesDir(), artName(t.pkg));
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** Stores a 16:9 crop no wider than 448 px (the hero's size on a 720p panel) and its average colour. */
    private static boolean saveArt(Context ctx, Thread t, MediaMetadata md) {
        Bitmap src = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (src == null) src = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (src == null) src = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return savePicture(ctx, t, src);
    }

    private static boolean savePicture(Context ctx, Thread t, Bitmap src) {
        if (src == null || src.getWidth() < 32 || src.getHeight() < 32) return false;
        try {
            int w = 448, h = 252;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
            int cw = Math.round(w / scale), ch = Math.round(h / scale);
            int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
            new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
            try (FileOutputStream os = new FileOutputStream(new File(ctx.getFilesDir(), artName(t.pkg)))) {
                out.compress(Bitmap.CompressFormat.JPEG, 86, os);
            }
            t.tint = tintOf(out);
            out.recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The picture's own colour, lifted a little so it reads as light rather than mud. */
    static int tintOf(Bitmap b) {
        Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
        int c = one.getPixel(0, 0);
        one.recycle();
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.08f);
        hsv[2] = Math.max(0.55f, Math.min(0.9f, hsv[2] * 1.3f));
        return Color.HSVToColor(hsv);
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    // ---- words ----------------------------------------------------------------------------------------------

    /** "18 min left", "42 min", or null. */
    static String amount(Thread t) {
        if (t.hasProgress()) return Math.max(1, Math.round(t.leftMs() / 60000f)) + " min left";
        if (t.watchedMs >= 60_000) return Math.round(t.watchedMs / 60000f) + " min";
        return null;
    }

    /** "just now", "2 hours ago", "last night", "yesterday", "Tuesday". */
    static String when(long at) {
        long ago = System.currentTimeMillis() - at;
        if (ago < 15 * 60_000L) return "just now";
        if (ago < 60 * 60_000L) return (ago / 60_000L) + " min ago";
        java.util.Calendar now = java.util.Calendar.getInstance(), then = java.util.Calendar.getInstance();
        then.setTimeInMillis(at);
        int days = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR);
        if (days < 0) days += 365;
        int h = then.get(java.util.Calendar.HOUR_OF_DAY);
        if (days == 0) return ago < 6 * 60 * 60_000L ? (ago / (60 * 60_000L)) + (ago < 2 * 60 * 60_000L ? " hour ago" : " hours ago") : "today";
        if (days == 1) return h >= 18 || h < 4 ? "last night" : "yesterday";
        return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(then.getTime());
    }

    static List<Thread> copy(List<Thread> l) {
        return Collections.unmodifiableList(new ArrayList<>(l));
    }
}
