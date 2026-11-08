package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the assistant may know about the TV right now. Read only at the moment someone asks it something, never in
 * the background, and handed only to the assistant (signed like this app, through DeskProvider):
 *   app      the app in front ("Glass home" on the desktop)
 *   screen   what that app's screen says, in reading order, with [focused] where the remote is pointing
 *   focused  the item the remote is on, in full
 *   playing  what is playing: title, subtitle, how far in, paused or playing, in which app
 *   tv       volume, sound output, Wi-Fi, sleep timer
 *   recent   apps opened lately and what was being watched in them
 * Text typed into fields and passwords are never read. Settings > Assistant > Reads the screen turns the screen part
 * off (then only the app's name and what is playing are known).
 */
final class Screen {
    private Screen() {
    }

    static final String PREF = "assist_screen";
    private static final int MAX = 2400;
    private static Bundle cached;
    private static long cachedAt;

    /** For the assistant: the snapshot taken when its button was pressed (a moment ago), or one taken now. */
    static synchronized Bundle context(Context c) {
        if (cached != null && SystemClock.uptimeMillis() - cachedAt < 25_000) {
            Bundle b = cached;
            cached = null;
            return b;
        }
        return take(c);
    }

    /** Taken as the assistant is called up, before its own window (or Google's) covers anything. Any thread. */
    static void snapshot(Context c) {
        Bundle b = take(c);
        synchronized (Screen.class) {
            cached = b;
            cachedAt = SystemClock.uptimeMillis();
        }
    }

    static boolean reads(Context c) {
        return Glass.prefs(c).getBoolean(PREF, true);
    }

    static Bundle take(Context c) {
        Bundle b = new Bundle();
        String front = Glass.front();
        boolean desk = GlassHomeActivity.showing() || Glass.selfInFront() || front == null;
        if (desk) {
            b.putString("app", "Glass home (the TV's desktop: menu bar, dock of apps)");
        } else {
            CharSequence l = Glass.label(c, front);
            b.putString("app", l != null ? l.toString() : front);
            b.putString("pkg", front);
            HomeKeyService svc = HomeKeyService.current();
            if (svc != null && reads(c)) {
                StringBuilder focused = new StringBuilder();
                String s = read(svc, focused);
                if (s != null && !s.isEmpty()) b.putString("screen", s);
                if (focused.length() > 0) b.putString("focused", focused.toString());
            }
        }
        String p = playing(c);
        if (p != null) b.putString("playing", p);
        // the conversation's header: what Glass can see, in a few words
        String sees = desk ? "the desktop" : b.getString("app");
        String title = nowTitle(c);
        if (title != null) sees = title + (desk ? "" : " · " + b.getString("app"));
        else if (b.getString("focused") != null && !desk) sees = b.getString("app") + " · " + shortly(b.getString("focused"));
        b.putString("sees", sees);
        b.putString("tv", tv(c));
        String r = recent(c);
        if (r != null) b.putString("recent", r);
        return b;
    }

    // ---- the screen ---------------------------------------------------------------------------------------------

    private static final class Item {
        final String text;
        final int top, left;
        final boolean focused;

        Item(String text, int top, int left, boolean focused) {
            this.text = text;
            this.top = top;
            this.left = left;
            this.focused = focused;
        }
    }

    /** The app's own window (under the assistant's or Google's, never ours), as text in reading order. */
    static String read(HomeKeyService svc, StringBuilder focused) {
        AccessibilityNodeInfo root = null;
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {   // top of the screen first
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                String pkg = p == null ? "" : p.toString();
                if (pkg.equals(svc.getPackageName()) || pkg.startsWith("com.gathin.glass") || pkg.equals("com.google.android.katniss")) {
                    r.recycle();
                    continue;
                }
                root = r;
                break;
            }
        } catch (RuntimeException ignored) {
            // windows unavailable: no screen, the rest still helps
        }
        if (root == null) return null;
        List<Item> items = new ArrayList<>();
        walk(root, items, 0, focused, false);
        root.recycle();
        Collections.sort(items, (a, b) -> Math.abs(a.top - b.top) > 14 ? Integer.compare(a.top, b.top) : Integer.compare(a.left, b.left));
        StringBuilder out = new StringBuilder();
        int lastTop = Integer.MIN_VALUE;
        String last = null;
        for (Item it : items) {
            if (it.text.equals(last)) continue;
            if (out.length() > 0) out.append(Math.abs(it.top - lastTop) > 14 ? "\n" : "  ·  ");
            if (it.focused) out.append("[focused] ");
            out.append(it.text);
            last = it.text;
            lastTop = it.top;
            if (out.length() > MAX) break;
        }
        return out.length() > MAX ? out.substring(0, MAX) + "…" : out.toString();
    }

    private static void walk(AccessibilityNodeInfo n, List<Item> out, int depth, StringBuilder focused, boolean inFocus) {
        if (n == null || depth > 45 || out.size() > 260) return;
        if (!n.isVisibleToUser()) return;
        boolean secret = n.isPassword() || n.isEditable();   // what someone typed stays theirs
        String s = words(secret ? null : n.getText(), n.getContentDescription());
        boolean f = !inFocus && (n.isFocused() || n.isAccessibilityFocused());
        if (s != null) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            out.add(new Item(s, r.top, r.left, f || n.isSelected()));
        }
        if (f) {
            // the remote's item: its own words and its children's (a card's title, channel, length)
            StringBuilder all = new StringBuilder();
            gather(n, all, 0);
            if (all.length() > 0 && focused.length() == 0) focused.append(all.length() > 400 ? all.substring(0, 400) + "…" : all);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch;
            try {
                ch = n.getChild(i);
            } catch (RuntimeException e) {
                continue;
            }
            if (ch == null) continue;
            walk(ch, out, depth + 1, focused, inFocus || f);
            ch.recycle();
        }
    }

    private static void gather(AccessibilityNodeInfo n, StringBuilder all, int depth) {
        if (n == null || depth > 8 || all.length() > 400 || !n.isVisibleToUser()) return;
        boolean secret = n.isPassword() || n.isEditable();
        String s = words(secret ? null : n.getText(), n.getContentDescription());
        if (s != null && all.indexOf(s) < 0) all.append(all.length() > 0 ? " · " : "").append(s);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch;
            try {
                ch = n.getChild(i);
            } catch (RuntimeException e) {
                continue;
            }
            if (ch == null) continue;
            gather(ch, all, depth + 1);
            ch.recycle();
        }
    }

    private static String words(CharSequence text, CharSequence desc) {
        String t = text == null ? "" : text.toString().replaceAll("\\s+", " ").trim();
        String d = desc == null ? "" : desc.toString().replaceAll("\\s+", " ").trim();
        String s = t.isEmpty() ? d : d.isEmpty() || t.contains(d) ? t : d.contains(t) ? d : t + " (" + d + ")";
        if (s.isEmpty()) return null;
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    // ---- what is playing ------------------------------------------------------------------------------------------

    static String playing(Context c) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            if (m == null) return null;
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, ThreadListener.class));
            MediaController best = null;
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (mc.getMetadata() == null) continue;
                if (best == null || (st != null && st.getState() == PlaybackState.STATE_PLAYING)) best = mc;
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) break;
            }
            if (best == null) return null;
            MediaMetadata md = best.getMetadata();
            PlaybackState st = best.getPlaybackState();
            String title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) return null;
            String sub = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            String album = first(md, MediaMetadata.METADATA_KEY_ALBUM);
            String desc = first(md, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            StringBuilder s = new StringBuilder("\"").append(title).append("\"");
            if (sub != null && !sub.equals(title)) s.append(" — ").append(sub);
            if (album != null && !album.equals(title) && !album.equals(sub)) s.append(" (").append(album).append(")");
            CharSequence app = Glass.label(c, best.getPackageName());
            s.append(" in ").append(app != null ? app : best.getPackageName());
            if (st != null) {
                long pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                }
                if (dur > 0 && pos >= 0) s.append(", ").append(clock(pos)).append(" of ").append(clock(dur));
                s.append(st.getState() == PlaybackState.STATE_PLAYING ? ", playing" : st.getState() == PlaybackState.STATE_PAUSED ? ", paused" : "");
            }
            if (desc != null && desc.length() < 300) s.append(". ").append(desc);
            return s.toString();
        } catch (SecurityException | IllegalStateException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The title of what's playing (or paused), if an app says so. */
    private static String nowTitle(Context c) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            if (m == null) return null;
            for (MediaController mc : m.getActiveSessions(new ComponentName(c, ThreadListener.class))) {
                PlaybackState st = mc.getPlaybackState();
                MediaMetadata md = mc.getMetadata();
                if (md == null || st == null) continue;
                if (st.getState() != PlaybackState.STATE_PLAYING && st.getState() != PlaybackState.STATE_PAUSED) continue;
                String t = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                if (t != null) return t;
            }
        } catch (RuntimeException ignored) {
            // no sessions to read
        }
        return null;
    }

    private static String shortly(String s) {
        String one = s.split(" · ")[0];
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    // ---- the TV and lately -----------------------------------------------------------------------------------------

    private static String tv(Context c) {
        StringBuilder s = new StringBuilder();
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            s.append(am.isStreamMute(AudioManager.STREAM_MUSIC) ? "muted" : "volume " + v + " of " + max);
            boolean bt = false;
            for (Bt.Dev d : Bt.devices()) bt |= d.audio && d.connected;
            s.append(bt ? ", sound on Bluetooth headphones" : ", sound on the TV speakers");
        }
        s.append(Net.on(c) ? (Glass.connected(c) ? ", Wi-Fi connected" : ", Wi-Fi on but not connected") : ", Wi-Fi off");
        int left = SleepTimer.minutesLeft();
        if (left > 0) s.append(", sleep timer: ").append(left).append(" min left");
        return s.toString();
    }

    private static String recent(Context c) {
        StringBuilder s = new StringBuilder();
        long now = System.currentTimeMillis();
        try {
            for (Threads.Thread t : Threads.shelf(c, 4)) {
                CharSequence app = Glass.label(c, t.pkg);
                if (s.length() > 0) s.append("; ");
                s.append(t.title != null ? "\"" + t.title + "\" in " : "").append(app != null ? app : t.pkg);
                if (t.hasProgress()) s.append(" (").append(t.percent()).append("% in, ").append(t.leftMs() / 60_000).append(" min left)");
                if (t.updatedAt > 0) s.append(", ").append(DateUtils.getRelativeTimeSpanString(t.updatedAt, now, DateUtils.MINUTE_IN_MILLIS));
            }
        } catch (RuntimeException ignored) {
            // the shelf is a nicety
        }
        return s.length() == 0 ? null : s.toString();
    }
}
