package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * App icons as the dock shows them: one rounded square (radius 23.5 % of the side), the app's own art inside,
 * a faint sheen across the top and a soft edge of light that fades down the sides (no hard line at the top: that
 * read as the icon being cut off). Adaptive icons fill the square; a legacy full-bleed icon is clipped to it; a
 * round or irregular one sits on a light tile. Glass's own places (Files, Settings, All apps) are drawn here too.
 * Rendered once per size and cached.
 */
final class AppIcons {
    private AppIcons() {
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    static final String FILES = ":files", SETTINGS = ":settings", ALL_APPS = ":apps", CONTROLS = ":controls";

    static Bitmap of(Context c, String pkg, int size) {
        String key = pkg + "@" + size;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        b = pkg.startsWith(":") ? builtin(pkg, size) : render(c, pkg, size);
        CACHE.put(key, b);
        return b;
    }

    static void forget(String pkg) {
        for (String k : CACHE.snapshot().keySet()) if (k.startsWith(pkg + "@")) CACHE.remove(k);
    }

    private static Drawable icon(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = pm.getLaunchIntentForPackage(pkg);
            if (li != null) return pm.getActivityIcon(li);
        } catch (Exception ignored) {
        }
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap render(Context c, String pkg, int size) {
        Drawable d = icon(c, pkg);
        Bitmap art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas ac = new Canvas(art);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            int bleed = Math.round(size * 0.25f);   // the outer sixth of an adaptive layer is bleed
            Drawable bg = ad.getBackground(), fg = ad.getForeground();
            if (bg != null) { bg.setBounds(-bleed, -bleed, size + bleed, size + bleed); bg.draw(ac); }
            if (fg != null) { fg.setBounds(-bleed, -bleed, size + bleed, size + bleed); fg.draw(ac); }
        } else if (d != null) {
            Bitmap probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(probe));
            int m = Math.max(1, size / 24);
            boolean corners = opaque(probe, m, m) && opaque(probe, size - 1 - m, m) && opaque(probe, m, size - 1 - m) && opaque(probe, size - 1 - m, size - 1 - m);
            boolean edges = opaque(probe, size / 2, m) && opaque(probe, m, size / 2) && opaque(probe, size - 1 - m, size / 2) && opaque(probe, size / 2, size - 1 - m);
            if (corners) {
                ac.drawBitmap(probe, 0, 0, null);
            } else if (edges) {
                // its own rounded square: a hair larger so our corners replace its corners (no more: that cut the art)
                int grow = Math.round(size * 0.025f);
                d.setBounds(-grow, -grow, size + grow, size + grow);
                d.draw(ac);
            } else {
                tile(ac, size, 0xFFFFFFFF, 0xFFE6E9EF);
                int in = Math.round(size * 0.16f);
                d.setBounds(in, in, size - in, size - in);
                d.draw(ac);
            }
            probe.recycle();
        } else {
            tile(ac, size, 0xFF8A93A3, 0xFF4B5363);
            CharSequence l = Glass.label(c, pkg);
            if (l != null && l.length() > 0) {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xF2FFFFFF);
                tp.setTypeface(Ui.face(c, 600));
                tp.setTextSize(size * 0.44f);
                tp.setTextAlign(Paint.Align.CENTER);
                ac.drawText(l.subSequence(0, 1).toString().toUpperCase(java.util.Locale.ROOT), size / 2f, size / 2f + size * 0.16f, tp);
            }
        }
        return finish(art, size, 0.12f);
    }

    private static boolean opaque(Bitmap b, int x, int y) {
        return (b.getPixel(x, y) >>> 24) > 200;
    }

    /** A rounded square from the top-left colour to the bottom-right one (CSS 160°). */
    private static void tile(Canvas c, int size, int from, int to) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        double a = Math.toRadians(160);
        float ux = (float) Math.sin(a), uy = (float) -Math.cos(a);
        float len = Math.abs(size * ux) + Math.abs(size * uy);
        float m = size / 2f;
        p.setShader(new LinearGradient(m - ux * len / 2, m - uy * len / 2, m + ux * len / 2, m + uy * len / 2, from, to, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
    }

    /** Clips the art to the rounded square and lays the sheen and edge light over it. */
    private static Bitmap finish(Bitmap art, int size, float sheen) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float r = size * 0.235f;
        RectF box = new RectF(0, 0, size, size);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, p);
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        s.setShader(new LinearGradient(0, 0, 0, size * 0.5f, Ui.a(0xFFFFFF, sheen * 0.7f), 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, s);
        Paint e = new Paint(Paint.ANTI_ALIAS_FLAG);
        e.setStyle(Paint.Style.STROKE);
        float w = Math.max(1f, size / 72f);
        e.setStrokeWidth(w);
        e.setShader(new LinearGradient(0, 0, 0, size, Ui.a(0xFFFFFF, .16f), Ui.a(0xFFFFFF, .03f), Shader.TileMode.CLAMP));
        RectF in = new RectF(box);
        in.inset(w / 2, w / 2);
        c.drawRoundRect(in, r - w / 2, r - w / 2, e);
        art.recycle();
        return out;
    }

    /** Glass's own places, drawn like the boards' icons. */
    private static Bitmap builtin(String kind, int size) {
        Bitmap art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(art);
        switch (kind) {
            case FILES: {
                tile(c, size, 0xFF3CC9B6, 0xFF14897C);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xEBFFFFFF);
                c.drawRoundRect(new RectF(size * .18f, size * .26f, size * .82f, size * .76f), size * .08f, size * .08f, p);
                p.setColor(0xBFFFFFFF);
                c.drawRoundRect(new RectF(size * .18f, size * .20f, size * .46f, size * .36f), size * .05f, size * .05f, p);
                p.setShader(new LinearGradient(0, size * .40f, 0, size * .76f, 0xFFE9FBF8, 0xFFC9F1EB, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(size * .18f, size * .40f, size * .82f, size * .76f), size * .08f, size * .08f, p);
                break;
            }
            case SETTINGS:
                tile(c, size, 0xFF8A93A3, 0xFF4B5363);
                glyph(c, "sliders", size);
                break;
            case CONTROLS:
                tile(c, size, 0xFF6E8FD8, 0xFF3A55A8);
                glyph(c, "controls", size);
                break;
            default:
                tile(c, size, 0xFFB9A6F5, 0xFF6B54C9);
                glyph(c, "apps", size);
        }
        return finish(art, size, 0.22f);
    }

    private static void glyph(Canvas c, String name, int size) {
        Glyph g = Glyph.of(name, 0xF2FFFFFF, 2.1f);
        int gs = Math.round(size * 0.5f), o = (size - gs) / 2;
        g.setBounds(o, o, o + gs, o + gs);
        g.draw(c);
    }
}
