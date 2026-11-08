package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The phones that may control this TV, and how a new one joins. Only on the home Wi-Fi (PhoneServer listens on the
 * local network only), and only when someone at the TV opened Controls › Phone: that opens a short pairing window
 * with a fresh code (and a link with a one-time token for the QR code). A phone that knows either asks to join; the
 * TV shows the same code and asks "Allow this phone?"; only Allow gives the phone its key. The TV keeps a hash of each
 * key, never the key; Remove forgets a phone at once.
 */
final class Phones {
    private Phones() {
    }

    static final int PORT = 8765;
    private static final String PREFS = "phones", LIST = "list";
    private static final long SESSION_MS = 5 * 60_000L, GRACE_MS = 2 * 60_000L, ASK_MS = 60_000L;
    private static final int MAX_MISSES = 8;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- the phones that may control the TV --------------------------------------------------------------------

    static final class Phone {
        final String id, name, hash;
        final long added;
        long seen;

        Phone(String id, String name, String hash, long added, long seen) {
            this.id = id;
            this.name = name;
            this.hash = hash;
            this.added = added;
            this.seen = seen;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized List<Phone> list(Context c) {
        List<Phone> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Phone(o.getString("id"), o.optString("name", "Phone"), o.getString("hash"), o.optLong("added"), o.optLong("seen")));
            }
        } catch (Exception ignored) {
            // nothing stored, or unreadable: no phones
        }
        return out;
    }

    private static void save(Context c, List<Phone> phones) {
        JSONArray a = new JSONArray();
        try {
            for (Phone p : phones) {
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("hash", p.hash).put("added", p.added).put("seen", p.seen));
            }
        } catch (Exception ignored) {
            return;
        }
        prefs(c).edit().putString(LIST, a.toString()).apply();
    }

    /** The phone this key belongs to, or null. Seen-times are noted (at most once a minute). */
    static synchronized Phone byKey(Context c, String key) {
        if (key == null || key.length() < 32) return null;
        String h = sha(key);
        List<Phone> all = list(c);
        for (Phone p : all) {
            if (MessageDigest.isEqual(h.getBytes(StandardCharsets.US_ASCII), p.hash.getBytes(StandardCharsets.US_ASCII))) {
                long now = System.currentTimeMillis();
                if (now - p.seen > 60_000L) {
                    p.seen = now;
                    save(c, all);
                }
                return p;
            }
        }
        return null;
    }

    /** A new phone: its key (given to the phone once, never stored here). */
    private static synchronized String add(Context c, String name) {
        String key = hex(16) + hex(16);
        List<Phone> all = list(c);
        long now = System.currentTimeMillis();
        all.add(new Phone(hex(6), name, sha(key), now, now));
        save(c, all);
        return key;
    }

    static synchronized void remove(Context c, String id) {
        List<Phone> all = list(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(id)) all.remove(i);
        save(c, all);
    }

    // ---- joining ---------------------------------------------------------------------------------------------

    /** A pairing window: the code shown on the TV, and the token in its QR link. */
    static final class Session {
        final String token, code;
        final long until;

        Session(String token, String code, long until) {
            this.token = token;
            this.code = code;
            this.until = until;
        }

        String spaced() {
            return code.substring(0, 3) + " " + code.substring(3);
        }
    }

    private static Session session;
    private static long closedAt;
    private static int misses;

    /** Opens (or renews) the pairing window: called while Controls › Phone is on the TV. */
    static synchronized Session open() {
        long now = SystemClock.elapsedRealtime();
        if (session == null || now > session.until - 20_000L) {
            String code = String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
            session = new Session(hex(16), code, now + SESSION_MS);
            misses = 0;
        }
        closedAt = 0;
        return session;
    }

    /** The sheet closed: joining stays possible for a short while (someone mid-way on their phone). */
    static synchronized void closing() {
        closedAt = SystemClock.elapsedRealtime();
    }

    private static boolean live() {
        long now = SystemClock.elapsedRealtime();
        if (session == null || now > session.until) return false;
        return closedAt == 0 || now - closedAt < GRACE_MS;
    }

    /** The code for a phone that came by the QR link (so it can show the same code as the TV), or null. */
    static synchronized String codeFor(String token) {
        if (token == null || !live()) return null;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), session.token.getBytes(StandardCharsets.US_ASCII)) ? session.spaced() : null;
    }

    /** Whether a token (from the QR link) or a typed code opens the door. Too many wrong tries close it. */
    static synchronized boolean admits(String token, String code) {
        if (!live()) return false;
        boolean ok = token != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), session.token.getBytes(StandardCharsets.US_ASCII));
        if (!ok && code != null) {
            String digits = code.replaceAll("[^0-9]", "");
            ok = MessageDigest.isEqual(digits.getBytes(StandardCharsets.US_ASCII), session.code.getBytes(StandardCharsets.US_ASCII));
        }
        if (!ok && ++misses >= MAX_MISSES) session = null;   // someone guessing: the window shuts
        return ok;
    }

    /** A phone asking to join, waiting for Allow (or Not now) on the TV. */
    static final class Request {
        final String name, code;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean allowed;
        volatile String key;

        Request(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    interface Asker {
        void ask(Request r);
    }

    private static Asker asker;

    /** Whoever shows "Allow this phone?" on the TV (the open Phone sheet, else the desktop or the overlay). */
    static void setAsker(Asker a) {
        asker = a;
    }

    /** Asks the TV and waits (up to a minute) for the answer. The key, or null when not allowed. */
    static String ask(Context c, String name) {
        String code;
        synchronized (Phones.class) {
            code = session == null ? "" : session.spaced();
        }
        Request r = new Request(name, code);
        MAIN.post(() -> {
            Asker a = asker;
            if (a != null) a.ask(r);
            else PhoneSheet.askAnywhere(c, r);
        });
        try {
            r.done.await(ASK_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return r.allowed ? r.key : null;
    }

    /** The TV's answer. */
    static void decide(Context c, Request r, boolean allow) {
        if (r.done.getCount() == 0) return;
        if (allow) {
            r.key = add(c, r.name);
            r.allowed = true;
            synchronized (Phones.class) {
                session = null;   // one phone per code
            }
        }
        r.done.countDown();
    }

    // ---- small parts ----------------------------------------------------------------------------------------

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte x : d) out.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** "Android phone · Chrome", "iPhone · Safari": what the phone says it is, in a few words. */
    static String nameFrom(String ua) {
        if (ua == null) return "Phone";
        String s = ua.toLowerCase(Locale.ROOT);
        String device = s.contains("iphone") ? "iPhone" : s.contains("ipad") ? "iPad" : s.contains("android") ? (s.contains("mobile") ? "Android phone" : "Android tablet")
                : s.contains("macintosh") ? "Mac" : s.contains("windows") ? "PC" : "Phone";
        String browser = s.contains("edg/") ? "Edge" : s.contains("samsungbrowser") ? "Samsung Internet" : s.contains("firefox") || s.contains("fxios") ? "Firefox"
                : s.contains("crios") || s.contains("chrome") ? "Chrome" : s.contains("safari") ? "Safari" : null;
        return browser == null ? device : device + " · " + browser;
    }
}
