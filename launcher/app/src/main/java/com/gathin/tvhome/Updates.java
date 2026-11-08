package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Glass's updates, only when someone asks (Settings ▸ About this TV ▸ Check for updates, or the phone's TV tab).
 *
 * The newest release is listed by Glass's own update service (a Cloudflare Worker that GitHub fills each time a
 * version is released, see updates/ in the repository). What's newer is downloaded into Glass's updates folder,
 * checked against the size and SHA-256 the list gives, and installed by Glass's key helper the way `adb install`
 * would, so nothing asks on the screen: the assistant first, Glass itself last (Android restarts Glass when it's
 * replaced). Without the helper the TV's own installer opens and asks. Android refuses an update that isn't signed
 * with the same key as the Glass on the TV, and this TV never goes back to an older version (it's a retail build).
 *
 * The service's address and key are built in by the release workflow (or ~/Desktop/tv/.updates.env on the Mac that
 * builds it); a build without them says updates aren't set up. Nothing is checked or downloaded unless asked.
 */
final class Updates {
    private Updates() {
    }

    static final String TAG = "GlassUpdates";
    static final String ASSISTANT = "com.gathin.glass.assistant";
    private static final String PENDING = "update_to";   // "version|versionCode" while Glass replaces itself

    static final class App {
        String pkg, name, version, file, sha256;
        long code, size, installed;

        boolean newer() {
            return code > installed;
        }
    }

    static final class Release {
        String version, date;
        final List<String> notes = new ArrayList<>();
        final List<App> apps = new ArrayList<>();

        boolean newer() {
            for (App a : apps) if (a.newer()) return true;
            return false;
        }

        long bytes() {
            long n = 0;
            for (App a : apps) if (a.newer()) n += a.size;
            return n;
        }
    }

    /** idle, checking, current, available, downloading, installing, failed: what the TV and the phone show. */
    static volatile String phase = "idle";
    static volatile String text = "";
    static volatile int percent = -1;
    static volatile Release found;
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    static boolean ready() {
        return !BuildConfig.UPDATE_URL.isEmpty() && !BuildConfig.UPDATE_TOKEN.isEmpty();
    }

    static synchronized boolean working() {
        return busy;
    }

    private static synchronized boolean claim() {
        if (busy) return false;
        busy = true;
        return true;
    }

    private static synchronized void release() {
        busy = false;
    }

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

    private static void say(String ph, String words, int pct) {
        phase = ph;
        text = words;
        percent = pct;
        MAIN.post(() -> {
            List<Runnable> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Runnable r : copy) r.run();
        });
    }

    /** "5.0 glass widgets" → "5.0" (the words after the number are for the people reading the source). */
    static String plain(String versionName) {
        if (versionName == null) return "";
        int sp = versionName.indexOf(' ');
        return sp > 0 ? versionName.substring(0, sp) : versionName;
    }

    static long installedCode(Context c, String pkg) {
        try {
            return c.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The version on the TV ("5.1"), or null when the app isn't there. */
    static String installedName(Context c, String pkg) {
        try {
            return plain(c.getPackageManager().getPackageInfo(pkg, 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ---- checking ---------------------------------------------------------------------------------------------

    static void check(Context c) {
        final Context app = c.getApplicationContext();
        if (!ready()) {
            say("failed", "Updates aren't set up in this copy of Glass", -1);
            return;
        }
        if (!claim()) return;
        say("checking", "Checking…", -1);
        new Thread(() -> {
            try {
                Release r = fetch(app);
                found = r;
                if (r.newer()) say("available", "Glass " + r.version + " is ready  ·  " + size(r.bytes()), -1);
                else say("current", "Up to date", -1);
                Log.i(TAG, "checked: " + (r.newer() ? r.version + " is newer" : "up to date"));
            } catch (java.io.IOException e) {
                Log.w(TAG, "check: " + e.getClass().getSimpleName());
                say("failed", !Glass.online(app) ? "Offline: connect the TV to the internet first"
                        : e instanceof Refused ? e.getMessage() : "The update service didn't answer. Try again in a while.", -1);
            } catch (Exception e) {
                Log.w(TAG, "check: " + e.getClass().getSimpleName() + " " + e.getMessage());
                say("failed", "The list of updates couldn't be read", -1);
            } finally {
                release();
            }
        }, "glass-update-check").start();
    }

    private static Release fetch(Context c) throws Exception {
        JSONObject o = new JSONObject(new String(get(BuildConfig.UPDATE_URL + "/v1/manifest", null, null), StandardCharsets.UTF_8));
        Release r = new Release();
        r.version = o.getString("release");
        r.date = o.optString("date", "");
        JSONArray notes = o.optJSONArray("notes");
        if (notes != null) for (int i = 0; i < notes.length() && i < 12; i++) r.notes.add(notes.getString(i));
        JSONArray apps = o.getJSONArray("apps");
        String self = c.getPackageName();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.getJSONObject(i);
            App p = new App();
            p.pkg = a.getString("pkg");
            // only Glass and its assistant, and the assistant only where it's on this TV already (Glass adds no app)
            if (!p.pkg.equals(self) && !p.pkg.equals(ASSISTANT)) continue;
            p.name = a.optString("name", p.pkg.equals(self) ? "Glass" : "Glass Assistant");
            p.version = a.optString("version", "");
            p.code = a.getLong("code");
            p.file = a.getString("file");
            p.size = a.getLong("size");
            p.sha256 = a.getString("sha256").toLowerCase(Locale.ROOT);
            if (!p.file.matches("[A-Za-z0-9._-]{1,80}\\.apk") || !p.sha256.matches("[0-9a-f]{64}") || p.size <= 0 || p.size > 60L * 1024 * 1024) {
                throw new JSONException("an app in the list doesn't look right");
            }
            p.installed = installedCode(c, p.pkg);
            if (p.installed == 0 && !p.pkg.equals(self)) continue;
            r.apps.add(p);
        }
        r.apps.sort((x, y) -> Boolean.compare(x.pkg.equals(self), y.pkg.equals(self)));   // Glass itself last
        return r;
    }

    // ---- installing -----------------------------------------------------------------------------------------

    static void install(Context c) {
        final Release r = found;
        if (r == null || !r.newer() || !claim()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                File dir = app.getExternalFilesDir("updates");
                if (dir == null) throw new IllegalStateException("the TV's storage isn't ready");
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                if (dir.getUsableSpace() < r.bytes() * 3 + 20L * 1024 * 1024) {
                    say("failed", "Not enough room on the TV for the update", -1);
                    return;
                }
                List<App> todo = new ArrayList<>();
                for (App a : r.apps) if (a.newer()) todo.add(a);
                // everything down and checked first: a cut download changes nothing on the TV
                long total = Math.max(1, r.bytes()), done = 0;
                for (App a : todo) {
                    download(a, new File(dir, a.file), done, total);
                    done += a.size;
                }
                for (int i = 0; i < todo.size(); i++) {
                    App a = todo.get(i);
                    File f = new File(dir, a.file);
                    boolean me = a.pkg.equals(app.getPackageName());
                    say("installing", me ? "Installing Glass " + r.version + ". Glass restarts in a moment." : "Installing " + a.name + "…", -1);
                    // "|away": started from a phone while another app was in front, so Glass doesn't come back over it
                    if (me) Glass.prefs(app).edit().putString(PENDING, r.version + "|" + a.code
                            + (Glass.selfInFront() ? "" : "|away")).commit();
                    String res = Keys.install(app, f.getPath());
                    Log.i(TAG, "install " + a.pkg + " " + a.code + ": " + (res == null ? "no helper" : res.startsWith("ok") ? "ok" : "failed"));
                    if (res == null) {   // no key helper: the TV's own installer asks on the screen
                        if (me) Glass.prefs(app).edit().remove(PENDING).apply();
                        askTheTv(app, f);
                        say("installing", "The TV asks to install " + a.name + ": choose Install"
                                + (i < todo.size() - 1 ? ", then check for updates again" : ""), -1);
                        return;
                    }
                    if (!res.startsWith("ok")) {
                        if (me) Glass.prefs(app).edit().remove(PENDING).apply();
                        say("failed", a.name + " wasn't installed (" + res.replaceFirst("^no ", "") + ")", -1);
                        return;
                    }
                    a.installed = a.code;
                    f.delete();
                }
                say("current", "Updated to Glass " + r.version, -1);
            } catch (java.io.IOException e) {
                Log.w(TAG, "install: " + e.getClass().getSimpleName());
                say("failed", e instanceof Refused ? e.getMessage() : "The download stopped. Check the internet and try again.", -1);
            } catch (Exception e) {
                Log.w(TAG, "install: " + e.getClass().getSimpleName() + " " + e.getMessage());
                say("failed", "The update stopped: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), -1);
            } finally {
                release();
            }
        }, "glass-update-install").start();
    }

    private static void download(App a, File f, long done, long total) throws Exception {
        File part = new File(f.getPath() + ".part");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long[] got = {0}, shown = {0};
        say("downloading", "Downloading…  " + (int) (done * 100 / total) + "%", (int) (done * 100 / total));
        get(BuildConfig.UPDATE_URL + "/v1/apk/" + a.file, part, (chunk, n) -> {
            sha.update(chunk, 0, n);
            got[0] += n;
            if (got[0] > a.size) throw new IllegalStateException("the download is bigger than it should be");
            long now = SystemClock.elapsedRealtime();
            if (now - shown[0] > 400) {
                shown[0] = now;
                int pct = (int) Math.min(99, (done + got[0]) * 100 / total);
                say("downloading", "Downloading…  " + pct + "%", pct);
            }
        });
        if (got[0] != a.size) {
            part.delete();
            throw new IllegalStateException("the download was cut short");
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        if (!hex.toString().equals(a.sha256)) {
            part.delete();
            throw new IllegalStateException("the download didn't match its fingerprint");
        }
        if (!part.renameTo(f)) throw new IllegalStateException("the download couldn't be kept");
        f.setReadable(true, false);   // the key helper (the TV's shell user) installs it from here
    }

    interface Chunk {
        void got(byte[] b, int n);
    }

    /** The service turned the TV away (a wrong key, nothing released): said as it is, not as a network problem. */
    static final class Refused extends java.io.IOException {
        Refused(String why) {
            super(why);
        }
    }

    /** A GET to the update service, its key in the header: the body, or into `to` when given. */
    private static byte[] get(String url, File to, Chunk each) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(20000);
        h.setInstanceFollowRedirects(false);
        h.setRequestProperty("Authorization", "Bearer " + BuildConfig.UPDATE_TOKEN);
        h.setRequestProperty("User-Agent", "Glass-TV");
        try {
            int code = h.getResponseCode();
            if (code == 401 || code == 403) throw new Refused("The update service didn't accept this copy of Glass");
            if (code == 404) throw new Refused("No release has been published yet");
            if (code != 200) throw new java.io.IOException("answered " + code);
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[32 * 1024];
                if (to == null) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    for (int n; (n = in.read(buf)) > 0; ) {
                        b.write(buf, 0, n);
                        if (b.size() > 256 * 1024) throw new JSONException("the list of updates is too big");
                    }
                    return b.toByteArray();
                }
                try (OutputStream out = new FileOutputStream(to)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        if (each != null) each.got(buf, n);
                    }
                }
                return null;
            }
        } finally {
            h.disconnect();
        }
    }

    /** Without the key helper: the TV's installer, which asks on the screen (Glass may ask the TV to allow it first). */
    private static void askTheTv(Context c, File apk) {
        try {
            Uri u = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".files", apk);
            c.startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "installer: " + e.getClass().getSimpleName());
        }
    }

    /** Glass started again after replacing itself: what to say once (null when nothing happened). */
    static String afterRestart(Context c) {
        tidy(c);
        SharedPreferences p = Glass.prefs(c);
        String to = p.getString(PENDING, null);
        if (to == null) return null;
        p.edit().remove(PENDING).apply();
        String[] part = to.split("\\|");
        String version = part[0];
        long code = 0;
        try {
            if (part.length > 1) code = Long.parseLong(part[1]);
        } catch (NumberFormatException ignored) {
        }
        if (installedCode(c, c.getPackageName()) >= code) {
            say("current", "Updated to Glass " + version, -1);
            return "Updated to Glass " + version;
        }
        say("failed", "Glass " + version + " wasn't installed. Try again.", -1);
        return null;
    }

    /**
     * Glass started again a moment ago after replacing itself, with About this TV on screen when Install was pressed:
     * that page comes back (saying it's updated) instead of the TV dropping into the app that was under Glass. Called
     * when the remote helper connects, which is what starts Glass again. An update started from a phone while another
     * app was in front stays out of the way (Glass says so the next time it's opened). An update started by an older
     * Glass doesn't say where it started, and comes back.
     */
    static void comeBack(Context c) {
        String to = Glass.prefs(c).getString(PENDING, null);
        if (to == null || to.endsWith("|away")) return;
        long since;
        try {
            since = System.currentTimeMillis() - c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        if (since < 0 || since > 2 * 60_000) return;
        try {
            c.startActivity(new Intent(c, GlassHomeActivity.class)
                    .putExtra(GlassHomeActivity.EXTRA_OPEN, "settings:about")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "back: " + e.getClass().getSimpleName());
        }
    }

    /** What an installed update left behind: Glass can't delete its own APK (Android replaces it mid-way). */
    private static void tidy(Context c) {
        if (working()) return;
        File dir = c.getExternalFilesDir("updates");
        File[] left = dir == null ? null : dir.listFiles();
        if (left != null) for (File f : left) f.delete();
    }

    /** For the phone's TV tab. */
    static JSONObject json(Context c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("ready", ready());
        o.put("phase", phase);
        o.put("text", text);
        o.put("percent", percent);
        o.put("glass", installedName(c, c.getPackageName()));
        String a = installedName(c, ASSISTANT);
        if (a != null) o.put("assistant", a);
        Release r = found;
        if (r != null) {
            JSONObject rel = new JSONObject();
            rel.put("version", r.version);
            rel.put("date", r.date);
            rel.put("newer", r.newer());
            rel.put("size", r.bytes());
            rel.put("notes", new JSONArray(r.notes));
            o.put("release", rel);
        }
        return o;
    }

    static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f) : Math.max(1, bytes / 1024) + " KB";
    }
}
