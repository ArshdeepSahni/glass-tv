package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Glass on a phone, served by the TV itself on the home network (nothing in between, nothing on the internet): the
 * page the QR code opens, and the few calls it makes. Only devices on the local network are answered; only phones
 * the TV allowed (Phones) may press buttons, type, read what's playing or talk to Glass.
 */
final class PhoneServer {
    private PhoneServer() {
    }

    private static final String TAG = "GlassPhone";
    private static final String CHAT = "content://com.gathin.glass.assistant.chat";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Semaphore SLOTS = new Semaphore(16);

    private static ServerSocket server;
    private static ExecutorService pool;
    private static byte[] page;

    // ---- on and off --------------------------------------------------------------------------------------------

    static synchronized void start(Context ctx) {
        if (server != null && !server.isClosed()) return;
        final Context c = ctx.getApplicationContext();
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Phones.PORT), 16);
            server = s;
        } catch (IOException e) {
            Log.i(TAG, "server: could not listen, " + e.getMessage());
            return;
        }
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "glass-phone");
            t.setDaemon(true);
            return t;
        });
        final ServerSocket s = server;
        Thread accept = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket sock = s.accept();
                    if (!local(sock.getInetAddress()) || !SLOTS.tryAcquire()) {
                        sock.close();
                        continue;
                    }
                    pool.execute(() -> {
                        try {
                            serve(c, sock);
                        } finally {
                            SLOTS.release();
                        }
                    });
                } catch (IOException e) {
                    if (s.isClosed()) break;
                } catch (RuntimeException e) {
                    Log.i(TAG, "server: " + e.getClass().getSimpleName());
                }
            }
        }, "glass-phone-accept");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "server: listening on " + Phones.PORT);
    }

    static synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    /** Home networks only: private, link-local and loopback addresses. */
    private static boolean local(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;   // IPv6 unique-local
    }

    /** The TV's address on the home network (Wi-Fi or cable), for the QR code; null when offline. */
    static String address(Context c) {
        if (Sample.on) return Sample.ADDRESS;
        try {
            String best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String n = ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (best == null) best = a.getHostAddress();
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    static String link(Context c, String token) {
        if (Sample.on) token = token != null ? "glass" : null;   // the real token never shows in a picture
        String ip = address(c);
        return ip == null ? null : "http://" + ip + ":" + Phones.PORT + (token != null ? "/p/" + token : "");
    }

    // ---- HTTP, just enough ---------------------------------------------------------------------------------------

    private static final class Req {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String param(String name) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals(name)) return Uri.decode(kv.substring(eq + 1));
            }
            return null;
        }

        JSONObject json() {
            try {
                return body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        boolean keepAlive() {
            String c = header("connection");
            return c == null || !c.equalsIgnoreCase("close");
        }
    }

    private static final class Resp {
        final int code;
        final String type;
        final byte[] body;
        String extra = "";   // more header lines ("Name: value\r\n")

        Resp(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }
    }

    private static void serve(Context c, Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            for (int n = 0; n < 500; n++) {
                Req r = read(in);
                if (r == null) return;
                Resp resp;
                try {
                    resp = "/api/send".equals(r.path) ? receive(c, r, in) : route(c, r);
                } catch (Exception e) {
                    Log.i(TAG, "server: " + r.path + " failed, " + e.getClass().getSimpleName());
                    resp = json(500, obj("ok", false));
                    if ("/api/send".equals(r.path)) {   // the rest of a big body can't be read past: start afresh
                        r.headers.put("connection", "close");
                    }
                }
                boolean keep = r.keepAlive();
                String head = "HTTP/1.1 " + resp.code + " " + reason(resp.code) + "\r\n"
                        + "Content-Type: " + resp.type + "\r\n"
                        + "Content-Length: " + resp.body.length + "\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "X-Content-Type-Options: nosniff\r\n"
                        + "Referrer-Policy: no-referrer\r\n"
                        + resp.extra
                        + "Connection: " + (keep ? "keep-alive" : "close") + "\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(resp.body);
                out.flush();
                if (!keep) return;
            }
        } catch (IOException ignored) {
            // the phone went away
        }
    }

    private static Req read(InputStream in) throws IOException {
        String line = line(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) r.query = target.substring(q + 1);
        for (int i = 0; i < 64; i++) {
            String h = line(in);
            if (h == null) return null;
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        String len = r.header("content-length");
        if (len != null && !"/api/send".equals(r.path)) {   // a sent photo or video is streamed to disk, not read here
            int n = Integer.parseInt(len.trim());
            if (n < 0 || n > 32_768) throw new IOException("too big");
            byte[] b = new byte[n];
            int got = 0;
            while (got < n) {
                int k = in.read(b, got, n - got);
                if (k < 0) throw new IOException("short body");
                got += k;
            }
            r.body = b;
        }
        return r;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int ch = in.read();
            if (ch < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
            if (ch == '\n') {
                String s = b.toString("ISO-8859-1");
                return prev == '\r' ? s.substring(0, s.length() - 1) : s;
            }
            b.write(ch);
            prev = ch;
            if (b.size() > 8192) throw new IOException("line too long");
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            default: return "Error";
        }
    }

    private static Resp json(int code, JSONObject o) {
        return new Resp(code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---- the calls ---------------------------------------------------------------------------------------------

    private static Resp route(Context c, Req r) throws Exception {
        String p = r.path;
        if ("GET".equals(r.method) && ("/".equals(p) || p.startsWith("/p/"))) {
            return new Resp(200, "text/html; charset=utf-8", page(c));
        }
        if ("GET".equals(r.method) && ("/icon.png".equals(p) || "/icon-512.png".equals(p) || "/manifest.json".equals(p))) {
            // the home-screen icon and the "add to Home Screen" details (nothing private in them)
            return new Resp(200, p.endsWith(".png") ? "image/png" : "application/manifest+json", asset(c, "phone" + p));
        }
        if ("GET".equals(r.method) && "/wake.mp4".equals(p)) {
            return ranged(r, film(c, "1".equals(r.param("night"))), "video/mp4");
        }
        if ("GET".equals(r.method) && "/api/hello".equals(p)) {
            Phones.Phone me = Phones.byKey(c, bearer(r));
            JSONObject o = obj("ok", true, "tv", tvName(c), "glass", version(c), "paired", me != null,
                    "night", Ui.night(Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto")));
            String code = Phones.codeFor(r.param("token"));
            if (code != null) o.put("code", code);
            return json(200, o);
        }
        if ("POST".equals(r.method) && "/api/pair".equals(p)) {
            JSONObject b = r.json();
            if (!Phones.admits(b.optString("token", null), b.optString("code", null))) return json(403, obj("ok", false, "why", "code"));
            String key = Phones.ask(c, Phones.nameFrom(r.header("user-agent")));
            return key != null ? json(200, obj("ok", true, "key", key)) : json(403, obj("ok", false, "why", "declined"));
        }
        if (!p.startsWith("/api/")) return json(404, obj("ok", false));

        Phones.Phone me = Phones.byKey(c, bearer(r));
        if (me == null) return json(401, obj("ok", false, "why", "pair"));
        JSONObject b = r.json();
        switch (p) {
            case "/api/state":
                return json(200, state(c, me));
            case "/api/key":
                return json(200, obj("ok", key(c, b.optString("key"))));
            case "/api/text": {
                String text = b.optString("text");
                boolean enter = b.optBoolean("enter", false);
                // Glass's keyboard is up on the TV: straight into its box (any app, any characters)
                if (GlassKeyboard.commitFromPhone(text, enter)) return json(200, obj("ok", true));
                boolean ok = text.isEmpty() || type(c, text);
                if (ok && enter) Keys.press(c, KeyEvent.KEYCODE_ENTER);
                return json(200, obj("ok", ok));
            }
            case "/api/pointer": {
                final String phase = b.optString("p", "m");
                final float dx = (float) b.optDouble("x", 0), dy = (float) b.optDouble("y", 0);
                HomeKeyService svc = HomeKeyService.current();
                if (svc == null) return json(200, obj("ok", false));
                MAIN.post(() -> Hud.of(svc).pointer(phase, dx, dy));
                return json(200, obj("ok", true));
            }
            case "/api/apps":
                return json(200, apps(c));
            case "/api/appicon": {
                String pkg = r.param("pkg");
                if (pkg == null) return json(404, obj("ok", false));
                android.graphics.Bitmap bm = AppIcons.of(c, pkg, 96);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
                return new Resp(200, "image/png", png.toByteArray());
            }
            case "/api/open": {
                String pkg = b.optString("pkg", "");
                if (pkg.startsWith(":")) {   // one of Glass's own places, on the TV's desktop
                    String where = AppIcons.FILES.equals(pkg) ? "files" : AppIcons.SETTINGS.equals(pkg) ? "settings"
                            : AppIcons.ALL_APPS.equals(pkg) ? "apps" : AppIcons.CONTROLS.equals(pkg) ? "controls"
                            : ":search".equals(pkg) ? "search" : ":inputs".equals(pkg) ? "inputs" : null;
                    if (where == null) return json(200, obj("ok", false));
                    GlassKeys.start(c, new android.content.Intent(c, GlassHomeActivity.class).putExtra(GlassHomeActivity.EXTRA_OPEN, where));
                    return json(200, obj("ok", true));
                }
                final boolean[] ok = {false};
                CountDownLatch done = new CountDownLatch(1);
                MAIN.post(() -> {
                    try {
                        ok[0] = Glass.open(c, pkg);
                    } finally {
                        done.countDown();
                    }
                });
                done.await(2, TimeUnit.SECONDS);
                return json(200, obj("ok", ok[0]));
            }
            case "/api/close": {
                String pkg = b.optString("pkg", "");
                if (pkg.isEmpty()) return json(200, obj("ok", false));
                Running.close(c, pkg, null);
                return json(200, obj("ok", true));
            }
            case "/api/show":
                Show.open(c, b.optString("name", null));
                return json(200, obj("ok", true));
            case "/api/bt": {
                boolean on = b.optBoolean("on");
                if (!on && Bt.remoteConnected() && !b.optBoolean("sure")) return json(200, obj("ok", false, "why", "remote"));
                return json(200, obj("ok", Bt.set(on)));
            }
            case "/api/casting":
                return json(200, obj("ok", casting(c, b.optBoolean("on"))));
            case "/api/chat": {
                Bundle out;
                if ("GET".equals(r.method)) out = chat(c, "turns", null, null);
                else if (b.has("pick")) out = chat(c, "play", b.optJSONObject("pick") != null ? b.optJSONObject("pick").toString() : null, null);
                else {
                    Bundle extra = new Bundle();
                    if (b.has("turn")) extra.putInt("turn", b.optInt("turn", -1));
                    out = chat(c, "send", b.optString("text"), extra);
                }
                String j = out == null ? null : out.getString("json");
                return j == null ? json(200, obj("ok", false, "why", "assistant")) : new Resp(200, "application/json; charset=utf-8", j.getBytes(StandardCharsets.UTF_8));
            }
            case "/api/art": {   // the picture of what's playing
                Media.Now n = Media.best();
                android.graphics.Bitmap art = n == null ? null : Media.art(n.pkg);
                if (art == null) return json(404, obj("ok", false));
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                art.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, jpg);
                return new Resp(200, "image/jpeg", jpg.toByteArray());
            }
            case "/api/media": {   // play/pause, next, previous: to whatever is playing
                String d = b.optString("do", "toggle");
                boolean ok = "next".equals(d) ? Widgets.mediaKey(c, KeyEvent.KEYCODE_MEDIA_NEXT)
                        : "prev".equals(d) ? Widgets.mediaKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        : Widgets.playPause(c) || Widgets.mediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                MAIN.postDelayed(() -> Media.fresh(c, 0), 300);
                return json(200, obj("ok", ok));
            }
            case "/api/look": {   // Light, Dark or Auto, as the TV's Control Center sets it
                String look = b.optString("look", "");
                if (!look.equals("light") && !look.equals("dark") && !look.equals("auto")) return json(200, obj("ok", false));
                Glass.prefs(c).edit().putString(GlassHomeActivity.APPEARANCE, look).apply();
                MAIN.post(GlassHomeActivity::appearanceChanged);
                return json(200, obj("ok", true, "night", Ui.night(look)));
            }
            case "/api/sent": {   // what phones sent to the TV (Glass's own folder), newest first
                JSONArray list = new JSONArray();
                for (java.io.File f : Show.all(c)) {
                    list.put(obj("name", f.getName(), "size", f.length(), "at", f.lastModified(), "video", Show.video(f.getName())));
                }
                return json(200, obj("ok", true, "items", list));
            }
            case "/api/sentthumb": {
                String name = r.param("name");
                java.io.File f = name == null ? null : new java.io.File(Show.dir(c), new java.io.File(name).getName());
                android.graphics.Bitmap t = f == null || !f.exists() ? null : Show.thumb(f, 240, 240);
                if (t == null) return json(404, obj("ok", false));
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                t.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, jpg);
                return new Resp(200, "image/jpeg", jpg.toByteArray());
            }
            case "/api/sentdelete": {
                String name = b.optString("name", "");
                java.io.File f = new java.io.File(Show.dir(c), new java.io.File(name).getName());
                boolean ok = !name.isEmpty() && f.exists() && f.delete();
                return json(200, obj("ok", ok));
            }
            case "/api/update": {   // Glass's updates: what's on the TV and what's newer; check or install when asked
                String d = b.optString("do", "");
                if ("check".equals(d)) Updates.check(c);
                else if ("install".equals(d)) Updates.install(c);
                return json(200, Updates.json(c));
            }
            case "/api/forget":
                Phones.remove(c, me.id);
                return json(200, obj("ok", true));
            default:
                return json(404, obj("ok", false));
        }
    }

    private static String bearer(Req r) {
        String a = r.header("authorization");
        return a != null && a.startsWith("Bearer ") ? a.substring(7).trim() : null;
    }

    private static String version(Context c) {
        try {
            return Updates.plain(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "";
        }
    }

    private static String tvName(Context c) {
        if (Sample.on) return Sample.TV;
        String n = Settings.Global.getString(c.getContentResolver(), "device_name");
        return n == null || n.trim().isEmpty() ? "TV" : n.trim();
    }

    private static JSONObject state(Context c, Phones.Phone me) throws Exception {
        JSONObject o = obj("ok", true, "tv", tvName(c), "glass", version(c), "me", Sample.on ? Sample.phone(0) : me.name);
        JSONObject now = playing(c);
        if (now != null) o.put("playing", now);
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            o.put("volume", obj("level", am.getStreamVolume(AudioManager.STREAM_MUSIC), "max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    "muted", am.isStreamMute(AudioManager.STREAM_MUSIC)));
        }
        if (Backlight.works(c)) o.put("bright", obj("level", Backlight.get(c), "max", 100));
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        o.put("screen", pm == null || pm.isInteractive());
        String wants = GlassKeyboard.wants;
        if (wants != null) {
            o.put("wants", wants);
            if (GlassKeyboard.wantsHint != null) o.put("wantsHint", GlassKeyboard.wantsHint);
        }
        o.put("look", Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto"));
        o.put("night", Ui.night(Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto")));
        o.put("bt", Bt.on());
        o.put("casting", enabled(c, "com.google.android.apps.mediashell"));
        String keys = Keys.status();
        if (!"ready".equals(keys)) Keys.warm(c);   // a phone is open: have the key helper ready for its first press
        o.put("keys", keys);
        JSONArray phones = new JSONArray();
        int pi = 0;
        for (Phones.Phone ph : Phones.list(c)) phones.put(obj("name", Sample.on ? Sample.phone(pi++) : ph.name, "seen", ph.seen, "me", ph.id.equals(me.id)));
        o.put("phones", phones);
        return o;
    }

    /** What's playing (or paused), as the app tells the TV (Media: read through the key helper on this TV). */
    private static JSONObject playing(Context c) {
        Media.fresh(c, 1500);
        Media.Now n = Media.best();
        if (n == null) return null;
        CharSequence app = Sample.on ? Sample.APP : Glass.label(c, n.pkg);
        JSONObject o = obj("title", n.title, "app", app != null ? app.toString() : n.pkg, "pkg", n.pkg, "playing", n.playing(),
                "pos", n.position(), "dur", n.dur);
        try {
            if (n.artist != null) o.put("artist", n.artist);
            android.graphics.Bitmap art = Media.art(n.pkg);
            if (art != null) o.put("art", n.pkg + "-" + art.getGenerationId());
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---- buttons -------------------------------------------------------------------------------------------------

    /** One remote button from a phone: a real press in whatever app is in front (Keys), else Glass's own ways. */
    static boolean key(Context c, String k) {
        switch (k) {
            case "volup":
            case "voldown":
            case "mute":
                GlassKeys.userVolume();   // a person's change: its HUD shows even while the TV wakes
                break;
            case "del":
                if (GlassKeyboard.deleteFromPhone()) return true;
                break;
            case "power": {
                // off when it's on; on (a wake, never a toggle) when the screen is off
                android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
                if (pm != null && !pm.isInteractive()) return Keys.wake(c);
                break;
            }
            default:
                break;
        }
        switch (k) {
            case "home":
                // Glass's desktop itself, not a Home key: a pressed key reaches only Android, which sends it to
                // whatever the TV ranks as its home (on a Xiaomi, its own home, whatever was chosen)
                if (Glass.original(c)) break;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> GlassKeys.home(c));
                return true;
            case "assistant":
                return GlassKeys.start(c, new Intent().setClassName(GlassKeys.ASSISTANT, GlassKeys.ASSISTANT + ".AskActivity").putExtra("button", true));
            case "brightup":
            case "brightdown":
                if (!Backlight.works(c)) return false;
                Backlight.step(c, "brightup".equals(k) ? 1 : -1);
                return true;
            default:
                break;
        }
        int code = code(k);
        if (code == 0) return false;
        if (Keys.press(c, code)) return true;
        // the key helper isn't up (yet): what Glass can do by itself
        HomeKeyService svc = HomeKeyService.current();
        AudioManager am = c.getSystemService(AudioManager.class);
        switch (k) {
            case "home":
                return svc != null && svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            case "back":
                return svc != null && svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case "volup":
            case "voldown":
                if (am == null) return false;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, "volup".equals(k) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case "mute":
                if (am == null) return false;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return true;
            case "playpause":
            case "rewind":
            case "forward":
            case "prev":
            case "next":
                if (am == null) return false;
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
                return true;
            case "power":
                return svc != null && svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            default:
                return svc != null && move(svc, code);
        }
    }

    private static int code(String k) {
        switch (k) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "home": return KeyEvent.KEYCODE_HOME;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "volup": return KeyEvent.KEYCODE_VOLUME_UP;
            case "voldown": return KeyEvent.KEYCODE_VOLUME_DOWN;
            case "mute": return KeyEvent.KEYCODE_VOLUME_MUTE;
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "power": return KeyEvent.KEYCODE_POWER;
            case "del": return KeyEvent.KEYCODE_DEL;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "forward": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "prev": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            default: return 0;
        }
    }

    /** Without the key helper: move the focus (or press) through the screen's own views, where an app has them. */
    private static boolean move(AccessibilityService svc, int code) {
        final boolean[] ok = {false};
        CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null) {
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                        ok[0] = f.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    } else {
                        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? android.view.View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? android.view.View.FOCUS_DOWN
                                : code == KeyEvent.KEYCODE_DPAD_LEFT ? android.view.View.FOCUS_LEFT : android.view.View.FOCUS_RIGHT;
                        AccessibilityNodeInfo next = f.focusSearch(dir);
                        ok[0] = next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    }
                }
            } catch (RuntimeException ignored) {
                // nothing focusable to move through
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    /** Types into the text box on the TV (the focused one, else the first on screen). */
    private static boolean type(Context c, String text) {
        HomeKeyService svc = HomeKeyService.current();
        if (text == null) return false;
        if (svc == null) return Keys.type(c, text);
        final boolean[] ok = {false};
        CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root == null) return;
                AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f == null || !f.isEditable()) f = editable(root, 0);
                if (f == null) return;
                Bundle a = new Bundle();
                a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok[0] = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
            } catch (RuntimeException ignored) {
                // no text box to type into
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // a box that isn't an Android text box (YouTube's search, a game's): typed key by key, plain characters only
        return ok[0] || Keys.type(c, text);
    }

    private static AccessibilityNodeInfo editable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = editable(n.getChild(i), depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** The TV's apps for the phone's Apps tab, A to Z, with which are open in the background. */
    private static JSONObject apps(Context c) throws Exception {
        JSONArray list = new JSONArray();
        java.util.Set<String> open = Running.quick(c);
        // Glass's own places first (they open on the TV's desktop), then every app
        String[][] places = {{AppIcons.FILES, "Files"}, {AppIcons.SETTINGS, "Settings"}, {AppIcons.ALL_APPS, "All apps"}, {AppIcons.CONTROLS, "Control Center"}};
        for (String[] g : places) list.put(obj("pkg", g[0], "label", g[1], "open", false, "glass", true));
        for (String[] a : GlassHomeActivity.apps(c)) list.put(obj("pkg", a[1], "label", a[0], "open", open.contains(a[1])));
        return obj("ok", true, "apps", list);
    }

    private static boolean enabled(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean casting(Context c, boolean on) {
        if (!"ready".equals(Keys.status())) {
            Keys.warm(c);
            return false;
        }
        boolean ok = true;
        for (String p : SettingsSheet.CASTING) {
            try {
                c.getPackageManager().getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (Exception missing) {
                continue;
            }
            ok &= Keys.enable(c, p, on);
        }
        return ok;
    }

    private static final byte[][] FILMS = new byte[2][];

    private static synchronized byte[] film(Context c, boolean night) throws IOException {
        int i = night ? 1 : 0;
        if (FILMS[i] == null) {
            try (InputStream in = c.getResources().openRawResource(night ? R.raw.wake_night : R.raw.wake_day)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                FILMS[i] = b.toByteArray();
            }
        }
        return FILMS[i];
    }

    /** A file in parts, as a browser's video player asks for it (bytes=a-b). */
    private static Resp ranged(Req r, byte[] all, String type) {
        String range = r.header("range");
        if (range == null || !range.startsWith("bytes=")) {
            Resp full = new Resp(200, type, all);
            full.extra = "Accept-Ranges: bytes\r\n";
            return full;
        }
        String[] ab = range.substring(6).split("-", -1);
        long a, b;
        try {
            a = ab[0].isEmpty() ? Math.max(0, all.length - Long.parseLong(ab[1])) : Long.parseLong(ab[0]);
            b = ab[0].isEmpty() || ab.length < 2 || ab[1].isEmpty() ? all.length - 1 : Math.min(all.length - 1, Long.parseLong(ab[1]));
        } catch (NumberFormatException e) {
            a = 0;
            b = all.length - 1;
        }
        if (a >= all.length || a > b) {
            Resp bad = new Resp(416, type, new byte[0]);
            bad.extra = "Content-Range: bytes */" + all.length + "\r\n";
            return bad;
        }
        byte[] part = java.util.Arrays.copyOfRange(all, (int) a, (int) b + 1);
        Resp out = new Resp(206, type, part);
        out.extra = "Accept-Ranges: bytes\r\nContent-Range: bytes " + a + "-" + b + "/" + all.length + "\r\n";
        return out;
    }

    /** A photo or video from a phone: streamed to Glass's own folder, then shown on the TV. */
    private static Resp receive(Context c, Req r, InputStream in) throws Exception {
        Phones.Phone me = Phones.byKey(c, bearer(r));
        String len = r.header("content-length");
        long n = len == null ? -1 : Long.parseLong(len.trim());
        if (me == null || !"POST".equals(r.method) || n <= 0) {
            r.headers.put("connection", "close");
            return json(me == null ? 401 : 400, obj("ok", false, "why", me == null ? "pair" : "empty"));
        }
        String name = r.param("name");
        if (n > Show.MAX_BYTES || name == null || !Show.sendable(name)) {
            r.headers.put("connection", "close");
            return json(413, obj("ok", false, "why", n > Show.MAX_BYTES ? "big" : "kind"));
        }
        java.io.File dir = Show.dir(c);
        if (dir.getUsableSpace() < n + 200L * 1024 * 1024) {
            r.headers.put("connection", "close");
            return json(413, obj("ok", false, "why", "space"));
        }
        java.io.File f = Show.arriving(c, name), part = new java.io.File(f.getPath() + ".part");
        long got = 0;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            while (got < n) {
                int k = in.read(buf, 0, (int) Math.min(buf.length, n - got));
                if (k < 0) break;
                out.write(buf, 0, k);
                got += k;
            }
        }
        if (got < n || !part.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            r.headers.put("connection", "close");
            return json(400, obj("ok", false, "why", "short"));
        }
        Log.i(TAG, "send: " + (got / 1024) + " KB arrived");
        if (!"0".equals(r.param("show"))) Show.open(c, f.getName());
        return json(200, obj("ok", true, "name", f.getName()));
    }

    // ---- Glass, on the phone -------------------------------------------------------------------------------------

    private static Bundle chat(Context c, String method, String arg, Bundle extras) {
        try {
            return c.getContentResolver().call(Uri.parse(CHAT), method, arg, extras);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- the page --------------------------------------------------------------------------------------------------

    private static synchronized byte[] page(Context c) throws IOException {
        if (page == null) page = asset(c, "phone/index.html");
        return page;
    }

    private static byte[] asset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
