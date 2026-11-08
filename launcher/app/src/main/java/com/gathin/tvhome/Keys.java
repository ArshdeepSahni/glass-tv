package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Presses the TV's remote buttons for a phone, inside every app. An app may press keys only in its own windows, and
 * the TV's own remote service refuses connections from the TV itself, so Glass starts its small key helper
 * (KeyDaemon) as the TV's shell user, over the TV's network debugging on this TV only, and talks to it on 127.0.0.1
 * with a secret only Glass knows. The first time, the TV asks "Allow USB debugging?" for Glass's key (glass@tv): the
 * person answers it on the TV, as with any computer. The helper starts when a phone first needs it, stops by itself
 * after 30 idle minutes, and a press never waits for it: until it's up, presses go Glass's own ways (PhoneServer).
 */
final class Keys {
    private Keys() {
    }

    static final String TAG = "GlassKeys";
    static final int PORT = 8766;
    private static final String SECRET = "keys_secret";
    private static final String ALLOWED = "keys_allowed";
    private static final String LOG = "/data/local/tmp/glass-keys.log";   // the helper's own words (shell-owned)   // the helper has run here before (the TV allowed Glass's key)

    private static Socket sock;
    private static BufferedReader in;
    private static OutputStream out;
    private static volatile boolean starting, asking;
    private static volatile long failedAt, waitFor;
    private static volatile String why = "starting";

    /** One press of a remote button (an Android key code) in whatever app is in front; false if the helper isn't up. */
    static boolean press(Context c, int keyCode) {
        return send(c, "k " + keyCode);
    }

    /** A tap on the screen at x, y (screen pixels): a phone's pointer in Point & tap. */
    static boolean tap(Context c, float x, float y) {
        return send(c, "c " + Math.round(x) + " " + Math.round(y));
    }

    /** Wakes a TV in standby (the screen off), as the remote's power key would. */
    static boolean wake(Context c) {
        return press(c, android.view.KeyEvent.KEYCODE_WAKEUP);
    }

    /** Closes an app as Android's own Force stop does. */
    static boolean close(Context c, String pkg) {
        return send(c, "x " + pkg);
    }

    /** Turns one of the TV's built-in apps on or off (the TV's own home, casting). */
    static boolean enable(Context c, String pkg, boolean on) {
        return send(c, (on ? "e " : "d ") + pkg);
    }

    /** Makes an activity the TV's home app. */
    static boolean home(Context c, String component) {
        return send(c, "h " + component);
    }

    /** The packages with a process right now, or null when the helper isn't up. */
    static java.util.Set<String> running(Context c) {
        String r = ask(c, "r");
        if (r == null || "no".equals(r)) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!"-".equals(r)) java.util.Collections.addAll(out, r.split(" "));
        return out;
    }

    /** What the apps say is playing (KeyDaemon "m": a JSON list), or null when the helper isn't up. */
    static String media(Context c) {
        String r = ask(c, "m");
        return r == null || "no".equals(r) ? null : r;
    }

    /** That app's artwork as JPEG bytes, or null. */
    static byte[] art(Context c, String pkg) {
        String r = ask(c, "a " + pkg);
        if (r == null || "no".equals(r)) return null;
        try {
            return android.util.Base64.decode(r, android.util.Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Installs an update Glass downloaded into its updates folder (KeyDaemon "i"): "ok", "no <why>", or null when the
     * helper can't be reached. Waits for the helper to start if it isn't up (up to half a minute), and gives the
     * installer up to four minutes, on a line of its own (keys from the phone keep going meanwhile). Call it off the
     * main thread.
     */
    static String install(Context c, String path) {
        for (int i = 0; i < 15; i++) {
            if (connect(c)) {   // the helper is up, and new enough to install
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
                    s.setSoTimeout(240_000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream o = s.getOutputStream();
                    o.write((secret(c) + "\ni " + path + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    if (!"ok".equals(r.readLine())) return "no helper";
                    String res = r.readLine();
                    return res != null ? res : "no answer";
                } catch (IOException e) {
                    return "no " + e.getClass().getSimpleName();
                } finally {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (!everAllowed(c)) return null;   // never started on this TV: that's a question for the TV, not for now
            warm(c);
            SystemClock.sleep(2000);
        }
        return null;
    }

    /** Is the helper up right now (a question to it costs nothing then)? */
    static boolean up() {
        synchronized (Keys.class) {
            return sock != null && !sock.isClosed();
        }
    }

    /** Types plain characters (letters, digits, common symbols) into whatever has focus. */
    static boolean type(Context c, String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 0x20 || text.charAt(i) > 0x7e) return false;
        return send(c, "t " + text);
    }

    /** "ready"; "asking" (the TV is asking to allow Glass's key); "declined" (it wasn't allowed); "off" (no network
     *  debugging); or "starting". */
    static String status() {
        synchronized (Keys.class) {
            if (sock != null && !sock.isClosed()) return "ready";
        }
        if (asking) return "asking";
        if (starting) return "starting";
        return failedAt != 0 ? why : "starting";
    }

    /** The TV has allowed Glass's key before, so starting the helper again asks nothing on the TV. */
    static boolean everAllowed(Context c) {
        return Glass.prefs(c).getBoolean(ALLOWED, false);
    }

    /** Asks again after "declined" (Settings ▸ Glass ▸ Remote helper): the TV shows its question once more. */
    static void askAgain(Context c) {
        failedAt = 0;
        waitFor = 0;
        warm(c);
    }

    /** Starts the helper in the background (a phone opened Glass, or a press found it down). */
    static void warm(Context c) {
        synchronized (Keys.class) {
            if (sock != null && !sock.isClosed()) return;
        }
        if (starting || (failedAt != 0 && SystemClock.elapsedRealtime() - failedAt < waitFor)) return;
        starting = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!connect(app)) start(app);
            } finally {
                starting = false;
            }
        }, "glass-keys").start();
    }

    private static boolean send(Context c, String line) {
        return "ok".equals(ask(c, line));
    }

    /** One line to the helper and its answer, or null when it isn't up (it is started for next time). */
    private static String ask(Context c, String line) {
        synchronized (Keys.class) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (sock == null || sock.isClosed()) {
                    if (attempt > 0 || !connect(c)) break;
                }
                try {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return in.readLine();
                } catch (IOException e) {
                    drop();
                }
            }
        }
        warm(c);
        return null;
    }

    private static void start(Context c) {
        boolean asked = false;
        try {
            String apk = c.getPackageCodePath();
            // The helper leaves in its own session, and the shell waits a moment before it ends: a debugging session
            // that closes at once takes its whole process group with it (Glass's closes in a millisecond, on this TV),
            // which ended the helper before it had begun. What it says goes to a small log for when it won't start.
            String cmd = "P=" + KeyDaemon.PID + "; [ -f $P ] && kill $(cat $P) 2>/dev/null; S=$(command -v setsid); "
                    + "CLASSPATH=" + apk + " nohup $S app_process / " + KeyDaemon.class.getName() + " " + secret(c) + " " + PORT
                    + " </dev/null >" + LOG + " 2>&1 & sleep 2";
            final boolean[] q = {false};
            try {
                LocalAdb.shell(cmd, 90_000L, fp -> {
                    q[0] = true;
                    asking = true;
                    Log.i(TAG, "key helper: the TV asks to allow Glass's key (glass@tv), answered on the TV");
                });
            } finally {
                asked = q[0];
                asking = false;
            }
            for (int i = 0; i < 80; i++) {   // the helper's runtime takes a moment to start (2-4 s here)
                Thread.sleep(150);
                if (connect(c)) {
                    Log.i(TAG, "key helper: running");
                    failedAt = 0;
                    Glass.prefs(c).edit().putBoolean(ALLOWED, true).apply();
                    return;
                }
            }
            String said = "";
            try {
                said = LocalAdb.shell("head -c 600 " + LOG + " 2>/dev/null", 5000L, null).trim().replace('\n', ' ');
            } catch (Exception ignored) {
            }
            Log.i(TAG, "key helper: didn't start" + (said.isEmpty() ? "" : ", it said: " + said));
            why = "off";
            waitFor = 60_000L;
        } catch (java.net.ConnectException off) {
            Log.i(TAG, "key helper: the TV's network debugging is off");
            why = "off";
            waitFor = 60_000L;
        } catch (Exception e) {
            Log.i(TAG, "key helper: can't start, " + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            // asked and not allowed: don't ask again for a while (it comes back when a phone opens Glass later)
            why = asked ? "declined" : "off";
            waitFor = asked ? 10 * 60_000L : 60_000L;
        } finally {
            asking = false;
        }
        failedAt = SystemClock.elapsedRealtime();
    }

    private static boolean connect(Context c) {
        synchronized (Keys.class) {
            if (sock != null && !sock.isClosed()) return true;
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
                s.setSoTimeout(3000);
                s.setTcpNoDelay(true);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream o = s.getOutputStream();
                o.write((secret(c) + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
                if (!"ok".equals(r.readLine())) {
                    s.close();
                    return false;
                }
                // a helper left running by an older Glass doesn't know the newer commands: it is replaced
                o.write("v\n".getBytes(StandardCharsets.UTF_8));
                o.flush();
                if (!KeyDaemon.VERSION.equals(r.readLine())) {
                    s.close();
                    Log.i(TAG, "key helper: an older one is running, replacing it");
                    return false;
                }
                sock = s;
                in = r;
                out = o;
                return true;
            } catch (IOException e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                return false;
            }
        }
    }

    private static void drop() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
        sock = null;
        in = null;
        out = null;
    }

    private static synchronized String secret(Context c) {
        SharedPreferences p = Glass.prefs(c);
        String s = p.getString(SECRET, null);
        if (s == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            s = sb.toString();
            p.edit().putString(SECRET, s).apply();
        }
        return s;
    }
}
