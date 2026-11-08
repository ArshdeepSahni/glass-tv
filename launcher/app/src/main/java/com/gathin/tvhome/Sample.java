package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.format.DateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sample content, for pictures of Glass (the website, the docs): weather for a neutral place, a made-up song, Glass's
 * own pictures as the photos from phones, a made-up network, address and names, and only well-known apps (the dock,
 * All apps, search, the open apps). While it's on, nobody's town, music, photos, network, devices or apps show.
 * Nothing real changes, and off brings everything back as it was. Only through the TV's debugging (there is no
 * switch for it on the TV):
 *
 *   adb shell am start -n com.gathin.tvhome/.GlassHomeActivity --es sample on      (off to stop)
 */
final class Sample {
    private Sample() {
    }

    static volatile boolean on;
    /** Pictures of the widgets' sizes: every widget at this size (Widgets.S, M or L), wherever there's room. -1: as
     * they are. Only while sample content is on, and never saved. */
    static volatile int size = -1;
    private static Bitmap art;

    static final String PLACE = "Lisbon";
    static final String NETWORK = "Home";
    static final String ADDRESS = "192.168.1.20";
    static final String TV = "Living Room TV";
    static final String APP = "Music";
    private static final String[] NETWORKS = {"Home", "Home 5G", "Studio", "Guest"};
    private static final int[] LEVELS = {-44, -52, -69, -81};
    private static final String[] DEVICES = {"Living room speaker", "Headphones", "Game controller", "Keyboard"};
    private static final String[] PHONES = {"Phone", "Tablet", "Phone 2"};
    private static final String[] PHOTOS = {"walls/dunes-d.jpg", "walls/ridges-d.jpg", "walls/aurora-n.jpg", "walls/silk-d.jpg"};
    /** The apps pictures show, in the dock's order, when the TV has them; with the TV's Live TV and Glass's own. */
    static final String[] APPS = {"com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.android.vending", "org.videolan.vlc"};

    /** Whether an app shows: always, unless sample content is on and it isn't one of APPS. */
    static boolean shows(Context c, String pkg) {
        if (!on || pkg == null) return true;
        for (String a : APPS) if (a.equals(pkg)) return true;
        return pkg.equals(c.getPackageName()) || pkg.equals(Config.load(c).liveTvPackage);
    }

    static void load(Context c) {
        on = Glass.prefs(c).getBoolean("sample", false);
        if (on) prepare(c);
    }

    static void set(Context c, boolean v) {
        Glass.prefs(c).edit().putBoolean("sample", v).commit();
        on = v;
        if (!v) size = -1;
        if (v) prepare(c);
        Media.changedBySample();
    }

    private static void prepare(Context c) {
        if (art == null) {
            try (InputStream in = c.getAssets().open("walls/orbs-n.jpg")) {
                Bitmap b = BitmapFactory.decodeStream(in);
                int s = Math.min(b.getWidth(), b.getHeight());
                Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2 + s / 6, (b.getHeight() - s) / 2, s - s / 6, s - s / 6);
                art = Bitmap.createScaledBitmap(sq, 300, 300, true);
            } catch (Exception ignored) {
            }
        }
        File dir = photoDir(c);
        if (!dir.isDirectory()) dir.mkdirs();
        for (int i = 0; i < PHOTOS.length; i++) {
            File f = new File(dir, "photo-" + (i + 1) + ".jpg");
            if (f.isFile()) continue;
            try (InputStream in = c.getAssets().open(PHOTOS[i]); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[1 << 15];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                f.setLastModified(System.currentTimeMillis() - i * 3_600_000L);
            } catch (Exception ignored) {
            }
        }
    }

    private static File photoDir(Context c) {
        return new File(c.getCacheDir(), "sample-photos");
    }

    // ---- what shows instead ----------------------------------------------------------------------------------------

    static Widgets.Weather.Now weather(Context c) {
        Widgets.Weather.Now n = new Widgets.Weather.Now();
        n.temp = 21;
        n.hi = 24;
        n.lo = 16;
        n.text = "Sunny";
        n.glyph = "sun";
        n.place = PLACE;
        n.sunrise = time(c, 7, 4);
        n.sunset = time(c, 17, 31);
        Calendar k = Calendar.getInstance();
        int[] temps = {22, 23, 23, 22, 21};
        String[] glyphs = {"sun", "sun", "sun_cloud", "sun_cloud", "sun"};
        for (int i = 0; i < 5; i++) {
            Widgets.Weather.Hour h = new Widgets.Weather.Hour();
            int hh = (k.get(Calendar.HOUR_OF_DAY) + 1 + i) % 24;
            h.label = DateFormat.is24HourFormat(c) ? String.format(Locale.ROOT, "%02d", hh)
                    : (hh % 12 == 0 ? 12 : hh % 12) + (hh < 12 ? "am" : "pm");
            h.glyph = hh >= 18 || hh < 7 ? "moon" : glyphs[i];
            h.temp = temps[i];
            n.hours.add(h);
        }
        int[][] days = {{24, 16}, {23, 15}, {21, 14}, {22, 15}, {25, 17}};
        String[] dg = {"sun", "sun_cloud", "rain", "sun_cloud", "sun"};
        SimpleDateFormat f = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < 5; i++) {
            Widgets.Weather.Day d = new Widgets.Weather.Day();
            d.label = i == 0 ? "Today" : f.format(k.getTime());
            d.glyph = dg[i];
            d.hi = days[i][0];
            d.lo = days[i][1];
            n.days.add(d);
            k.add(Calendar.DAY_OF_MONTH, 1);
        }
        return n;
    }

    private static String time(Context c, int hh, int mm) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, hh);
        k.set(Calendar.MINUTE, mm);
        return DateFormat.getTimeFormat(c).format(k.getTime());
    }

    private static final long STARTED = SystemClock.elapsedRealtime();

    static Media.Now playing(String pkg) {
        Media.Now n = new Media.Now();
        n.pkg = pkg;
        n.title = "Evening Light";
        n.artist = "Harbor & Pines";
        n.album = "Coastline";
        n.state = PlaybackState.STATE_PLAYING;
        n.dur = 214_000;
        n.pos = 74_000 + (SystemClock.elapsedRealtime() - STARTED) % 120_000;
        n.upd = SystemClock.elapsedRealtime();
        n.speed = 1f;
        n.actions = PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        return n;
    }

    static Bitmap art() {
        return art;
    }

    static List<File> photos(Context c) {
        prepare(c);
        List<File> out = new ArrayList<>();
        File[] fs = photoDir(c).listFiles();
        if (fs != null) Collections.addAll(out, fs);
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    static List<Net.Ap> networks() {
        List<Net.Ap> out = new ArrayList<>();
        for (int i = 0; i < NETWORKS.length; i++) {
            Net.Ap a = new Net.Ap();
            a.ssid = NETWORKS[i];
            a.level = LEVELS[i];
            a.secured = true;
            a.saved = i < 2;
            a.current = i == 0;
            a.caps = "[WPA2-PSK-CCMP]";
            out.add(a);
        }
        return out;
    }

    static String device(int i, boolean remote) {
        return remote ? "Remote" : DEVICES[i % DEVICES.length];
    }

    static String phone(int i) {
        return PHONES[i % PHONES.length];
    }
}
