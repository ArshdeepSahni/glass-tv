package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.InputStream;

/**
 * What the desktop and the screen saver look like: the background (six, each with a day and a night light, the
 * night one when the look is dark) and the screen saver (six). Settings ▸ Backgrounds & savers chooses; the desktop
 * and every pane of glass bend whichever background is chosen (Wall).
 *
 * The backgrounds other than the light field are rendered pictures in the APK's assets (walls/<id>-d.jpg, -n.jpg,
 * 1280 x 720): dunes ray-marched in low sun and under the moon, draped silk, the glass of the wake film, ribbons of
 * light and the northern lights, mountain ridges in haze.
 */
final class Looks {
    private Looks() {
    }

    static final String WALL = "look_wall", SAVER = "look_saver";

    /** {id, name}: the backgrounds, in the order the picker shows them. */
    static final String[][] WALLS = {
            {"field", "Light field"}, {"dunes", "Dunes"}, {"silk", "Silk"}, {"orbs", "Glass"}, {"aurora", "Aurora"}, {"ridges", "Ridges"}};

    /** {id, name, what it is}: the screen savers. */
    static final String[][] SAVERS = {
            {"clock", "Clock", "The time, large, over your background"},
            {"drift", "Drift", "The backgrounds, slowly drifting"},
            {"glass", "Glass", "The glass, with a sunrise inside"},
            {"glow", "Glow", "Soft colours moving on black"},
            {"photos", "Photos", "Photos sent from your phone"},
            {"night", "Night", "A dim clock for a dark room"}};

    /**
     * Light and Dark each keep their own background and screen saver ("look_wall_d" / "look_wall_n", "look_saver_d" /
     * "look_saver_n"): choosing one for Dark leaves Light's as it was. A choice from before there were two (one
     * "look_wall" for both) is where both start.
     */
    static String wall(Context c, boolean night) {
        SharedPreferences p = Glass.prefs(c);
        String id = p.getString(WALL + (night ? "_n" : "_d"), p.getString(WALL, "field"));
        for (String[] w : WALLS) if (w[0].equals(id)) return id;
        return "field";
    }

    /** The background of the look on screen now. */
    static String wall(Context c) {
        return wall(c, Ui.t.night);
    }

    static void setWall(Context c, boolean night, String id) {
        Glass.prefs(c).edit().putString(WALL + (night ? "_n" : "_d"), id).apply();
        Wall.reset();
    }

    static String saver(Context c, boolean night) {
        SharedPreferences p = Glass.prefs(c);
        String id = p.getString(SAVER + (night ? "_n" : "_d"), p.getString(SAVER, "clock"));
        for (String[] s : SAVERS) if (s[0].equals(id)) return id;
        return "clock";
    }

    /** The screen saver of the look in force now (Appearance: Light, Dark, or Auto by the hour). */
    static String saver(Context c) {
        return saver(c, Ui.night(Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto")));
    }

    static void setSaver(Context c, boolean night, String id) {
        Glass.prefs(c).edit().putString(SAVER + (night ? "_n" : "_d"), id).apply();
    }

    static int index(String[][] list, String id) {
        for (int i = 0; i < list.length; i++) if (list[i][0].equals(id)) return i;
        return 0;
    }

    /** A rendered background at full size (null for the light field, which Wall draws itself). Any thread. */
    static Bitmap picture(Context c, String id, boolean night, int w, int h) {
        if ("field".equals(id)) return null;
        try (InputStream in = c.getAssets().open("walls/" + id + "-" + (night ? "n" : "d") + ".jpg")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) return null;
            if (b.getWidth() != w || b.getHeight() != h) {
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(24);

    /** A small picture of a background for the picker (160 x 90 design px). Any thread. */
    static Bitmap thumb(Context c, String id, boolean night) {
        String key = id + (night ? "n" : "d");
        Bitmap b = THUMBS.get(key);
        if (b != null) return b;
        int w = Ui.px(160), h = Ui.px(90);
        if ("field".equals(id)) {
            b = Wall.render(w, h, night);
        } else {
            try (InputStream in = c.getAssets().open("walls/" + id + "-" + (night ? "n" : "d") + ".jpg")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap big = BitmapFactory.decodeStream(in, null, o);
                if (big != null) {
                    b = Bitmap.createScaledBitmap(big, w, h, true);
                    if (b != big) big.recycle();
                }
            } catch (Exception | OutOfMemoryError ignored) {
                b = null;
            }
        }
        if (b != null) THUMBS.put(key, b);
        return b;
    }

    /** A still for a screen saver's picker card. */
    static Bitmap saverThumb(Context c, String id, boolean night) {
        switch (id) {
            case "drift": return thumb(c, "dunes", night);
            case "glass": return asset(c, "saver/glass.jpg", 4);
            case "photos": {
                java.util.List<java.io.File> p = Show.photos(c);
                if (!p.isEmpty()) return Show.thumb(p.get(0), Ui.px(160), Ui.px(90));
                return thumb(c, "silk", night);
            }
            case "glow": return asset(c, "saver/glow.jpg", 4);
            case "night": return null;   // the picker draws a dim clock on black
            default: return thumb(c, wall(c, night), night);
        }
    }

    static Bitmap asset(Context c, String path, int sample) {
        String key = path + "@" + sample;
        Bitmap b = THUMBS.get(key);
        if (b != null) return b;
        try (InputStream in = c.getAssets().open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        if (b != null && sample > 1) THUMBS.put(key, b);
        return b;
    }

    static SharedPreferences prefs(Context c) {
        return Glass.prefs(c);
    }
}
