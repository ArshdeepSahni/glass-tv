package com.gathin.tvhome;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Glass's key helper. Not part of the app at run time: Keys starts it as the TV's own shell user (over the TV's
 * network debugging, from this APK, with app_process — the way screen-mirroring tools press keys), because only that
 * user may press a remote button inside other apps. It listens on this TV only (127.0.0.1) and answers only a caller
 * that first says Glass's secret, ends after 30 idle minutes, and is gone when the TV restarts.
 *
 * Lines in: the secret first, then "k <keycode>" (press), "t <text>" (type plain characters), "r" (which apps have a
 * process: package names, space-separated), "x <package>" (close an app: force-stop), "e <package>" / "d <package>"
 * (turn the TV's own home, its casting apps or one of its extras on / off, those only: Extras; noted on the TV with the
 * homes Glass turned off, for the TV's start and ./glass restore), "b <package> <1|0>" (whether an app may run in the
 * background: Android's own switch for it; never Android's, Google's services or assistant, or Glass's), "g <package>"
 * (that switch now: "1" or "0"), "f" (close the apps waiting in the background, to free memory), "c <x> <y>" (a tap on
 * the screen there: a phone's pointer), "h <package/activity>" (make Glass or the TV's own
 * home the home app), "m" (what the apps say is playing: one line of JSON), "a <package>" (that app's artwork, a small
 * JPEG in base64), "i <file>" (install an update Glass downloaded into its own updates folder: Glass's or the
 * assistant's, the way the TV's own installer would, so it needs no prompt), "v" (its version) or "p" (ping). Lines out: "ok", "no", or for "r" the names. Arguments are checked before anything
 * runs, and tools run directly (no shell in between).
 *
 * Why "m": on this TV (a low-memory device) Android doesn't let apps hear other apps' media sessions at all, even with
 * the notification access that normally allows it; the shell user may read them (it's how "dumpsys media_session"
 * works). The helper reads only what a TV's Now Playing shows: app, title, artist, album, position and artwork.
 */
public final class KeyDaemon {
    static final String PID = "/data/local/tmp/glass-keys.pid";
    /** Bumped with each new command: Glass restarts a helper left running by an older Glass ("v" answers it). */
    static final String VERSION = "5";
    private static final long IDLE_MS = 30 * 60 * 1000L;

    private static volatile long last = SystemClock.uptimeMillis();
    private static Object im;
    private static Method inject;

    private KeyDaemon() {
    }

    public static void main(String[] args) throws Exception {
        final String secret = args[0];
        int port = Integer.parseInt(args[1]);
        ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        try (FileWriter w = new FileWriter(PID)) {
            w.write(String.valueOf(android.os.Process.myPid()));
        } catch (Exception ignored) {
        }
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        im = c.getMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        Thread idle = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (SystemClock.uptimeMillis() - last > IDLE_MS) System.exit(0);
            }
        });
        idle.setDaemon(true);
        idle.start();
        while (true) {
            final Socket s = server.accept();
            Thread t = new Thread(() -> serve(s, secret));
            t.setDaemon(true);
            t.start();
        }
    }

    private static void serve(Socket s, String secret) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(15 * 60 * 1000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();
            if (!secret.equals(in.readLine())) return;
            out.write("ok\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                last = SystemClock.uptimeMillis();
                String reply;
                try {
                    if (line.startsWith("k ")) reply = yes(key(Integer.parseInt(line.substring(2).trim())));
                    else if (line.startsWith("t ")) reply = yes(type(line.substring(2)));
                    else if (line.startsWith("c ")) {
                        String[] xy = line.substring(2).trim().split(" ");
                        reply = yes(tap(Float.parseFloat(xy[0]), Float.parseFloat(xy[1])));
                    }
                    else if ("r".equals(line)) reply = running();
                    else if (line.startsWith("x ") && PKG.matcher(line.substring(2)).matches()) reply = yes(run("am", "force-stop", line.substring(2)));
                    else if (line.startsWith("e ") && switchable(line.substring(2))) reply = yes(run("pm", "enable", line.substring(2)) && note(TURNED_OFF, line.substring(2), false));
                    else if (line.startsWith("d ") && switchable(line.substring(2))) reply = yes(run("pm", "disable-user", "--user", "0", line.substring(2)) && note(TURNED_OFF, line.substring(2), true));
                    else if (line.startsWith("b ") && (line.endsWith(" 1") || line.endsWith(" 0")) && line.length() > 4
                            && backgroundable(line.substring(2, line.length() - 2))) {
                        String pkg = line.substring(2, line.length() - 2);
                        boolean allow = line.endsWith(" 1");
                        reply = yes(run("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", allow ? "allow" : "ignore") && note(LIMITED, pkg, !allow));
                    }
                    else if (line.startsWith("g ") && PKG.matcher(line.substring(2)).matches()) reply = background(line.substring(2));
                    else if ("f".equals(line)) reply = yes(run("am", "kill-all"));
                    else if (line.startsWith("h ") && home(line.substring(2))) reply = yes(run("cmd", "package", "set-home-activity", "--user", "0", line.substring(2)));
                    else if ("v".equals(line)) reply = VERSION;
                    else if (line.startsWith("i ") && UPDATE.matcher(line.substring(2)).matches()) reply = install(line.substring(2));
                    else if ("m".equals(line)) reply = media();
                    else if (line.startsWith("a ") && PKG.matcher(line.substring(2)).matches()) reply = art(line.substring(2));
                    else reply = yes("p".equals(line));
                } catch (Exception e) {
                    reply = "no";
                }
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {
            // the caller went away
        }
    }

    private static final java.util.regex.Pattern PKG = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+");
    private static final java.util.regex.Pattern COMPONENT = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+");

    /** The only apps the helper turns on or off: the TV's own home, its casting apps and its extras (nothing else). */
    private static final java.util.Set<String> SWITCHABLE = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.tvlauncher", "com.google.android.apps.mediashell",
            "com.homwee.mmp.screenshare", "com.homwee.multiscreenshare"));

    private static boolean switchable(String pkg) {
        return SWITCHABLE.contains(pkg) || Extras.known(pkg);
    }

    private static boolean backgroundable(String pkg) {
        return PKG.matcher(pkg).matches() && !Extras.backgroundProtected(pkg);
    }

    /** What Glass turned off (Homes.TURNED_OFF), and the apps it keeps from running in the background, on the TV. */
    private static final String TURNED_OFF = "/data/local/tmp/glass-turned-off", LIMITED = "/data/local/tmp/glass-background-limited";

    /** A package in or out of one of those notes (true when the note is as asked). */
    private static boolean note(String path, String pkg, boolean in) {
        java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
        java.io.File f = new java.io.File(path);
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) if (!l.trim().isEmpty()) lines.add(l.trim());
            } catch (Exception e) {
                return false;
            }
        }
        if (in ? !lines.add(pkg) : !lines.remove(pkg)) return true;
        try (FileWriter w = new FileWriter(f)) {
            for (String l : lines) w.write(l + "\n");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether an app may run in the background now: "1", or "0" where Android's switch for it is off. */
    private static String background(String pkg) throws Exception {
        Process p = new ProcessBuilder("cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND").redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        boolean off = false;
        for (String l; (l = r.readLine()) != null; ) if (l.contains("RUN_ANY_IN_BACKGROUND") && l.contains("ignore")) off = true;
        p.waitFor();
        return off ? "0" : "1";
    }

    /** A home activity of Glass or of the TV's own home, and nothing else. */
    private static boolean home(String component) {
        if (!COMPONENT.matcher(component).matches()) return false;
        String pkg = component.substring(0, component.indexOf('/'));
        return "com.gathin.tvhome".equals(pkg) || "com.google.android.tvlauncher".equals(pkg);
    }

    private static String yes(boolean ok) {
        return ok ? "ok" : "no";
    }

    /** Only APKs in Glass's own updates folder, by plain name: nothing else on the TV can be installed this way. */
    private static final java.util.regex.Pattern UPDATE = java.util.regex.Pattern.compile("/(sdcard|storage/emulated/0)/Android/data/com\\.gathin\\.tvhome/files/updates/[A-Za-z0-9._-]{1,80}\\.apk");

    /**
     * "ok", or "no <what the installer said>". The APK is copied to the shell's own folder first (where `adb install`
     * puts it, so the installer reads it the usual way), then installed over the app as it is. An app being updated
     * restarts; Glass may be gone before the reply.
     */
    private static String install(String path) {
        java.io.File src = new java.io.File(path), tmp = new java.io.File("/data/local/tmp/glass-update.apk");
        try {
            if (!src.isFile()) return "no missing";
            String from = path;
            try (java.io.InputStream in = new java.io.FileInputStream(src); java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                from = tmp.getPath();
            } catch (java.io.IOException e) {
                tmp.delete();   // straight from Glass's folder instead
            }
            Process p = new ProcessBuilder("pm", "install", "-r", from).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String last = "", l;
            boolean ok = false;
            while ((l = r.readLine()) != null) {
                if (l.trim().isEmpty()) continue;
                last = l.trim();
                if (last.startsWith("Success")) ok = true;
            }
            p.waitFor();
            return ok ? "ok" : "no " + last.replaceAll("[\\r\\n]", " ");
        } catch (Exception e) {
            return "no " + e.getClass().getSimpleName();
        } finally {
            tmp.delete();
        }
    }

    /** A system tool with fixed arguments (no shell in between); true when it ran and exited cleanly. */
    private static boolean run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream();
        byte[] b = new byte[4096];
        while (in.read(b) > 0) {
            // drained so the tool never blocks on a full pipe
        }
        return p.waitFor() == 0;
    }

    /** The packages that have a process right now (from /proc: the shell user may read every process's name). */
    private static String running() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        java.io.File[] procs = new java.io.File("/proc").listFiles();
        if (procs != null) {
            for (java.io.File d : procs) {
                String n = d.getName();
                if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
                try (java.io.FileInputStream in = new java.io.FileInputStream(new java.io.File(d, "cmdline"))) {
                    byte[] b = new byte[256];
                    int len = in.read(b);
                    if (len <= 0) continue;
                    int end = 0;
                    while (end < len && b[end] != 0) end++;
                    String name = new String(b, 0, end, StandardCharsets.UTF_8);
                    int colon = name.indexOf(':');
                    if (colon > 0) name = name.substring(0, colon);
                    if (PKG.matcher(name).matches()) names.add(name);
                } catch (Exception ignored) {
                    // gone already
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(sb.length() == 0 ? "" : " ").append(n);
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ---- what's playing ---------------------------------------------------------------------------------------------

    private static Object sessions;          // ISessionManager
    private static Method getSessions, asController;

    /** The TV's media sessions, most recent first (the system's own order), as controller binders. */
    private static java.util.List<Object> controllers() throws Exception {
        if (sessions == null) {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            android.os.IBinder b = (android.os.IBinder) sm.getMethod("getService", String.class).invoke(null, "media_session");
            Object mgr = Class.forName("android.media.session.ISessionManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
            for (Method m : mgr.getClass().getMethods()) {
                if (m.getName().equals("getSessions") && m.getParameterTypes().length == 2) getSessions = m;
            }
            asController = Class.forName("android.media.session.ISessionController$Stub").getMethod("asInterface", android.os.IBinder.class);
            sessions = mgr;
        }
        java.util.List<?> list = (java.util.List<?>) getSessions.invoke(sessions, null, 0);
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (list == null) return out;
        for (Object o : list) {
            if (o instanceof android.os.IBinder) out.add(asController.invoke(null, o));
            else if (o != null) out.add(o.getClass().getMethod("getBinder").invoke(o));   // a session token (newer Android)
        }
        return out;
    }

    private static Object call(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static String words(android.media.MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    private static android.graphics.Bitmap picture(android.media.MediaMetadata md) {
        android.graphics.Bitmap b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b != null && b.getWidth() >= 16 && b.getHeight() >= 16 ? b : null;
    }

    /** One line of JSON: [{pkg, st, pos, upd, spd, act, t, a, al, dur, art | uri}], most recent first. */
    private static String media() {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Object ctl : controllers()) {
                String pkg = (String) call(ctl, "getPackageName");
                if (pkg == null) continue;
                org.json.JSONObject o = new org.json.JSONObject().put("pkg", pkg);
                android.media.session.PlaybackState st = (android.media.session.PlaybackState) call(ctl, "getPlaybackState");
                if (st != null) {
                    o.put("st", st.getState()).put("pos", st.getPosition()).put("upd", st.getLastPositionUpdateTime())
                            .put("spd", (double) st.getPlaybackSpeed()).put("act", st.getActions());
                }
                android.media.MediaMetadata md = (android.media.MediaMetadata) call(ctl, "getMetadata");
                if (md != null) {
                    String t = words(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, android.media.MediaMetadata.METADATA_KEY_TITLE);
                    String ar = words(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, android.media.MediaMetadata.METADATA_KEY_ARTIST,
                            android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST, android.media.MediaMetadata.METADATA_KEY_AUTHOR);
                    String al = words(md, android.media.MediaMetadata.METADATA_KEY_ALBUM);
                    if (t != null) o.put("t", t);
                    if (ar != null) o.put("a", ar);
                    if (al != null) o.put("al", al);
                    o.put("dur", md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION));
                    android.graphics.Bitmap b = picture(md);
                    if (b != null) o.put("art", (t == null ? "" : t) + "|" + b.getWidth() + "x" + b.getHeight());
                    else {
                        String u = words(md, android.media.MediaMetadata.METADATA_KEY_ART_URI, android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                                android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                        if (u != null && (u.startsWith("https://") || u.startsWith("http://"))) o.put("uri", u);
                    }
                }
                a.put(o);
            }
            return a.toString();
        } catch (Throwable e) {
            return fromDump();
        }
    }

    /** The same from "dumpsys media_session" (titles only, no artwork), should the sessions be unreadable directly. */
    private static String fromDump() {
        try {
            Process p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            org.json.JSONArray a = new org.json.JSONArray();
            org.json.JSONObject o = null;
            java.util.regex.Pattern num = java.util.regex.Pattern.compile("(state|position|speed|updated|actions)=(-?[0-9.]+)");
            for (String line; (line = r.readLine()) != null; ) {
                String l = line.trim();
                if (l.startsWith("package=")) {
                    o = new org.json.JSONObject().put("pkg", l.substring(8).trim());
                    a.put(o);
                } else if (o != null && l.startsWith("state=PlaybackState")) {
                    java.util.regex.Matcher m = num.matcher(l);
                    while (m.find()) {
                        String k = m.group(1), v = m.group(2);
                        if (k.equals("state")) o.put("st", Integer.parseInt(v));
                        else if (k.equals("position")) o.put("pos", Long.parseLong(v));
                        else if (k.equals("speed")) o.put("spd", Double.parseDouble(v));
                        else if (k.equals("updated")) o.put("upd", Long.parseLong(v));
                        else o.put("act", Long.parseLong(v));
                    }
                } else if (o != null && l.startsWith("metadata:") && l.contains("description=")) {
                    String d = l.substring(l.indexOf("description=") + 12);
                    String[] parts = d.split(", ");
                    if (parts.length > 0 && !"null".equals(parts[0])) o.put("t", parts[0]);
                    if (parts.length > 1 && !"null".equals(parts[1])) o.put("a", parts[1]);
                }
            }
            p.waitFor();
            return a.toString();
        } catch (Throwable e) {
            return "[]";
        }
    }

    /** That app's artwork, at most 320 px, as a JPEG in base64 (one line), or "no". */
    private static String art(String pkg) {
        try {
            for (Object ctl : controllers()) {
                if (!pkg.equals(call(ctl, "getPackageName"))) continue;
                android.media.MediaMetadata md = (android.media.MediaMetadata) call(ctl, "getMetadata");
                android.graphics.Bitmap b = md == null ? null : picture(md);
                if (b == null) return "no";
                float k = Math.min(1f, 320f / Math.max(b.getWidth(), b.getHeight()));
                android.graphics.Bitmap s = k < 1f ? android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                        Math.max(1, Math.round(b.getHeight() * k)), true) : b;
                java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream();
                s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, jpg);
                return android.util.Base64.encodeToString(jpg.toByteArray(), android.util.Base64.NO_WRAP);
            }
        } catch (Throwable ignored) {
            // the session went away
        }
        return "no";
    }

    /** One press: down, then up, as the remote does it (the arrows and OK as a D-pad, the rest as keys). */
    private static boolean key(int code) throws Exception {
        long t = SystemClock.uptimeMillis();
        int source = code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_KEYBOARD;
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        KeyEvent up = new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        return send(down) & send(up);
    }

    private static boolean type(String text) throws Exception {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) return false;
        boolean ok = true;
        for (KeyEvent e : events) ok &= send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
        return ok;
    }

    /** A tap on the screen at x, y (a phone's pointer, Point & tap). */
    private static boolean tap(float x, float y) throws Exception {
        if (x < 0 || y < 0 || x > 8192 || y > 8192) return false;
        long t = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean ok = send(down);
        Thread.sleep(45);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ok &= send(up);
        down.recycle();
        up.recycle();
        return ok;
    }

    private static boolean send(InputEvent e) throws Exception {
        return (Boolean) inject.invoke(im, e, 0);   // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    }
}
