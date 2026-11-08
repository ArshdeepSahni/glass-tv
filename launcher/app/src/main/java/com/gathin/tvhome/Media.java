package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the TV is playing, from any app (SoundCloud in the background, YouTube, a cast): title, artist, album, position,
 * artwork, playing or paused. For the Now playing widget, the phone's remote and the assistant.
 *
 * Where it comes from: Android shows other apps' media sessions to an app with notification access, but on this TV
 * (a low-memory device) it never grants that access, so Glass asks its key helper (KeyDaemon "m"), which as the TV's
 * shell user may read them. When a TV does grant the access, the sessions are read here directly instead.
 * Nothing is kept but the last answer and one small picture per app.
 */
final class Media {
    private Media() {
    }

    static final class Now {
        String pkg, title, artist, album, artKey, uri;
        int state;
        long pos, upd, dur, actions;
        float speed = 1f;

        boolean playing() {
            return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING;
        }

        boolean paused() {
            return state == PlaybackState.STATE_PAUSED;
        }

        /** Where it is now (the session says where it was at a moment, and how fast it moves). */
        long position() {
            long p = pos;
            if (state == PlaybackState.STATE_PLAYING && upd > 0) p += (long) ((SystemClock.elapsedRealtime() - upd) * speed);
            if (dur > 0) p = Math.min(p, dur);
            return Math.max(0, p);
        }

        boolean same(Now o) {
            return o != null && eq(pkg, o.pkg) && eq(title, o.title) && eq(artist, o.artist) && state == o.state;
        }
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glass-media");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile List<Now> last = Collections.emptyList();
    private static volatile long at;
    private static volatile boolean busy;
    private static final Map<String, Bitmap> arts = new HashMap<>();
    private static final Map<String, String> artKeys = new HashMap<>();
    private static final List<Runnable> listeners = new ArrayList<>();
    private static volatile int revision;

    /** Bumped whenever what's playing changes (title, app, playing/paused, artwork). */
    static int revision() {
        return revision;
    }

    /** The one to show: the first playing (the system lists the latest first), else the latest paused one. */
    static Now best() {
        if (Sample.on) return Sample.playing(SAMPLE_PKG);
        Now paused = null;
        for (Now n : last) {
            if (n.title == null) continue;
            if (n.playing()) return n;
            if (paused == null && n.paused()) paused = n;
        }
        return paused;
    }

    static List<Now> all() {
        if (Sample.on) return Collections.singletonList(Sample.playing(SAMPLE_PKG));
        return last;
    }

    /** Sample content plays "in" Glass itself: no one's app shows. */
    static final String SAMPLE_PKG = "com.gathin.tvhome";

    /** Sample content went on or off: everything showing what's playing shows it again. */
    static void changedBySample() {
        revision++;
        MAIN.post(() -> {
            List<Runnable> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Runnable r : copy) r.run();
        });
    }

    static Bitmap art(String pkg) {
        if (Sample.on) return Sample.art();
        synchronized (arts) {
            return pkg == null ? null : arts.get(pkg);
        }
    }

    /** Called (on the main thread) whenever what's playing changes. */
    static void listen(Runnable r) {
        synchronized (listeners) {
            if (!listeners.contains(r)) listeners.add(r);
        }
    }

    static void unlisten(Runnable r) {
        synchronized (listeners) {
            listeners.remove(r);
        }
    }

    /** Asks again unless the last answer is younger than maxAgeMs (or a question is already out). */
    static void fresh(Context c, long maxAgeMs) {
        if (busy || SystemClock.elapsedRealtime() - at < maxAgeMs) return;
        busy = true;
        final Context app = c.getApplicationContext();
        WORK.execute(() -> {
            try {
                read(app);
            } finally {
                at = SystemClock.elapsedRealtime();
                busy = false;
            }
        });
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    private static void read(Context c) {
        List<Now> now = direct(c);
        if (now == null) now = viaHelper(c);
        if (now == null) return;   // nobody to ask right now: the last answer stands
        Now before = best();
        last = now;
        Now after = best();
        boolean changed = before == null ? after != null : !before.same(after);
        if (changed) {   // which app and whether it plays: never the title
            android.util.Log.i("GlassMedia", "sessions=" + now.size() + " best=" + (after == null ? "none" : after.pkg + " state=" + after.state
                    + " title=" + (after.title != null ? "yes" : "no") + " art=" + (after.artKey != null || after.uri != null ? "yes" : "no")));
        }
        if (after != null) changed |= fetchArt(c, after);
        if (after != null && after.title != null) Threads.fromMedia(c, after, art(after.pkg));
        if (changed) {
            revision++;
            MAIN.post(() -> {
                List<Runnable> copy;
                synchronized (listeners) {
                    copy = new ArrayList<>(listeners);
                }
                for (Runnable r : copy) r.run();
            });
        }
    }

    /** In-process, where the TV grants notification access (not this one); null when it doesn't. */
    private static volatile boolean directDenied;

    private static List<Now> direct(Context c) {
        if (directDenied) return null;
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            if (m == null) return null;
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, ThreadListener.class));
            List<Now> out = new ArrayList<>();
            for (MediaController mc : list) {
                Now n = new Now();
                n.pkg = mc.getPackageName();
                PlaybackState st = mc.getPlaybackState();
                if (st != null) {
                    n.state = st.getState();
                    n.pos = st.getPosition();
                    n.upd = st.getLastPositionUpdateTime();
                    n.speed = st.getPlaybackSpeed();
                    n.actions = st.getActions();
                }
                MediaMetadata md = mc.getMetadata();
                if (md != null) {
                    n.title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                    n.artist = text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                    n.album = text(md, MediaMetadata.METADATA_KEY_ALBUM);
                    n.dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (b != null) {
                        n.artKey = n.title + "|" + b.getWidth() + "x" + b.getHeight();
                        keepArt(n.pkg, n.artKey, scaled(b));
                    }
                }
                if (!c.getPackageName().equals(n.pkg)) out.add(n);
            }
            return out;
        } catch (SecurityException notGranted) {
            directDenied = true;   // this TV doesn't grant it (until Glass restarts)
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** From the key helper (it may read every session), when the TV has allowed it before. */
    private static List<Now> viaHelper(Context c) {
        if (!Keys.up() && !Keys.everAllowed(c)) return null;
        String json = Keys.media(c);
        if (json == null) return null;
        try {
            JSONArray a = new JSONArray(json);
            List<Now> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Now n = new Now();
                n.pkg = o.optString("pkg", null);
                if (n.pkg == null || c.getPackageName().equals(n.pkg)) continue;
                n.state = o.optInt("st", 0);
                n.pos = o.optLong("pos", 0);
                n.upd = o.optLong("upd", 0);
                n.speed = (float) o.optDouble("spd", 1);
                n.actions = o.optLong("act", 0);
                n.title = o.optString("t", null);
                n.artist = o.optString("a", null);
                n.album = o.optString("al", null);
                n.dur = o.optLong("dur", 0);
                n.artKey = o.optString("art", null);
                n.uri = o.optString("uri", null);
                out.add(n);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The artwork of what's shown, fetched once per song; true when it changed. */
    private static boolean fetchArt(Context c, Now n) {
        String key = n.artKey != null ? n.artKey : n.uri != null ? n.uri : null;
        synchronized (arts) {
            if (key == null) {
                if (arts.remove(n.pkg) != null) {
                    artKeys.remove(n.pkg);
                    return true;
                }
                return false;
            }
            if (key.equals(artKeys.get(n.pkg))) return false;
        }
        Bitmap b = null;
        try {
            if (n.artKey != null) {
                byte[] jpg = Keys.art(c, n.pkg);
                if (jpg != null) b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            } else if (n.uri != null) {
                b = download(n.uri);
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // no picture this time
        }
        if (b == null) return false;
        keepArt(n.pkg, key, b);
        return true;
    }

    private static void keepArt(String pkg, String key, Bitmap b) {
        synchronized (arts) {
            if (key.equals(artKeys.get(pkg))) return;
            arts.put(pkg, b);
            artKeys.put(pkg, key);
        }
    }

    private static Bitmap scaled(Bitmap b) {
        float k = Math.min(1f, 320f / Math.max(b.getWidth(), b.getHeight()));
        return k < 1f ? Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true) : b;
    }

    private static Bitmap download(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(5000);
        h.setReadTimeout(6000);
        try (InputStream in = h.getInputStream()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            return b == null ? null : scaled(b);
        } finally {
            h.disconnect();
        }
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** "1:23" or "1:02:03". */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }
}
