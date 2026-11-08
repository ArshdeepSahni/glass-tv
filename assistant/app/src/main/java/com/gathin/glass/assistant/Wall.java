package com.gathin.glass.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * The time-of-day wallpaper: an original light field (a sky, a sun or a small moon, two long soft bands, a little
 * grain), drawn once per look at the screen's size and kept in memory and on disk. Every glass surface bends this
 * same picture, so it is the one source of truth for what the glass shows.
 */
final class Wall {
    private Wall() {
    }

    private static final int VERSION = 5;
    private static Bitmap day, night;

    static synchronized Bitmap peek(boolean isNight) {
        return isNight ? night : day;
    }

    private static Bitmap frostDay, frostNight;

    static synchronized Bitmap frostPeek(boolean isNight) {
        return isNight ? frostNight : frostDay;
    }

    /** The wallpaper frosted (σ 22 px), small: the GPU scales it up smoothly under every frosted pane. */
    static Bitmap frost(Context c, boolean isNight) {
        synchronized (Wall.class) {
            Bitmap f = isNight ? frostNight : frostDay;
            if (f != null) return f;
        }
        Bitmap w = get(c, isNight);
        int d = 8;
        Bitmap small = Bitmap.createScaledBitmap(w, Math.max(2, w.getWidth() / d), Math.max(2, w.getHeight() / d), true);
        if (!small.isMutable()) small = small.copy(Bitmap.Config.ARGB_8888, true);
        Lens.boxBlur(small, Math.max(1, Math.round(22 * Ui.s / d)));
        synchronized (Wall.class) {
            if (isNight) frostNight = small; else frostDay = small;
        }
        return small;
    }

    /** The wallpaper for this look at the screen's size. Slow the first time (render), then instant. Any thread. */
    static Bitmap get(Context c, boolean isNight) {
        synchronized (Wall.class) {
            Bitmap b = isNight ? night : day;
            if (b != null) return b;
        }
        int w = Ui.px(1280), h = Ui.px(720);
        File f = new File(c.getCacheDir(), "wall-" + (isNight ? "n" : "d") + "-" + w + "x" + h + "-v" + VERSION + ".png");
        Bitmap b = null;
        if (f.exists()) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                b = BitmapFactory.decodeFile(f.getPath(), o);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                b = null;
            }
            // a cut-off file decodes with its bottom missing: never trust one that isn't opaque to the last row
            if (b != null && (b.getWidth() != w || b.getHeight() != h || !opaque(b))) {
                b = null;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (b == null) {
            b = render(w, h, isNight);
            // written to the side and renamed into place, so a process killed mid-write leaves no half picture
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.getFD().sync();
            } catch (Exception ignored) {
                // a cache only
            }
            //noinspection ResultOfMethodCallIgnored
            if (!tmp.renameTo(f)) tmp.delete();
        }
        synchronized (Wall.class) {
            if (isNight) night = b; else day = b;
        }
        return b;
    }

    private static boolean opaque(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[][] at = {{1, h - 2}, {w / 2, h - 2}, {w - 2, h - 2}, {w - 2, h / 2}, {1, 1}};
        for (int[] p : at) if ((b.getPixel(p[0], p[1]) >>> 24) != 255) return false;
        return true;
    }

    static synchronized void forgetOther(boolean keepNight) {
        if (keepNight) { day = null; frostDay = null; } else { night = null; frostNight = null; }
    }

    static Bitmap render(int w, int h, boolean isNight) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / 1280f, h / 720f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        // the sky: CSS linear-gradient(175deg, …) over the whole screen
        double ang = Math.toRadians(175);
        float dx = (float) Math.sin(ang), dy = (float) -Math.cos(ang);
        float len = Math.abs(1280 * dx) + Math.abs(720 * dy);
        int[] sky = isNight ? new int[]{0xFF0B1022, 0xFF141A33, 0xFF22182C, 0xFF2A1A22} : new int[]{0xFFA9C6E4, 0xFFC9D9EA, 0xFFEBDCCB, 0xFFD7B9A1};
        float[] at = isNight ? new float[]{0f, .40f, .72f, 1f} : new float[]{0f, .34f, .62f, 1f};
        p.setShader(new LinearGradient(640 - dx * len / 2, 360 - dy * len / 2, 640 + dx * len / 2, 360 + dy * len / 2, sky, at, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p);
        p.setShader(null);
        if (isNight) {
            blob(c, -200, 380, 1000, 520, Ui.a(0xFF8C5A, .30f));
            blob(c, 700, -300, 800, 700, Ui.a(0x6E82FF, .22f));
            blob(c, 476, 0, 240, 240, Ui.a(0xFFEED2, .13f));
            // a small moon: a lit disc with a soft glow
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(Ui.a(0xFFECCD, .40f));
            glow.setMaskFilter(new BlurMaskFilter(14, BlurMaskFilter.Blur.NORMAL));
            c.drawCircle(596, 120, 14, glow);
            Paint moon = new Paint(Paint.ANTI_ALIAS_FLAG);
            moon.setShader(new RadialGradient(582 + 28 * .38f, 106 + 28 * .36f, 25, new int[]{0xFFFFF8EC, 0xFFEADBC2, 0xFFD6C3A3}, new float[]{0f, .68f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(596, 120, 14, moon);
            band(c, 500, Ui.a(0xFFBE96, .16f));
        } else {
            blob(c, 780, -260, 900, 760, Ui.a(0xFFF0D6, .95f));
            blob(c, -260, 360, 1100, 520, Ui.a(0xFFC8AA, .55f));
            blob(c, 520, 420, 1100, 520, Ui.a(0xA0BEE6, .55f));
            band(c, 470, Ui.a(0xFFFFFF, .35f));
        }
        grain(c, isNight ? 0.08f : 0.07f);
        return b;
    }

    /** CSS radial-gradient(closest-side, colour, transparent) in an ellipse box. */
    private static void blob(Canvas c, float x, float y, float w, float h, int argb) {
        float cx = x + w / 2, cy = y + h / 2, r = w / 2;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(cx, cy, r, new int[]{argb, argb & 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, h / w, cx, cy);
        c.drawCircle(cx, cy, r, p);
        c.restore();
    }

    /** A long soft band of light across the lower sky, tilted 4°. */
    private static void band(Canvas c, float top, int mid) {
        float h = top == 470 ? 180 : 160;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(-200, 0, 1480, 0, new int[]{mid & 0x00FFFFFF, mid, mid & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(30, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.rotate(-4, 640, top + h / 2);
        c.drawOval(new RectF(-200, top, 1480, top + h), p);
        c.restore();
    }

    private static void grain(Canvas c, float opacity) {
        int n = 160;
        int[] px = new int[n * n];
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int v = 64 + r.nextInt(128);
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        p.setAlpha(Math.round(opacity * 255));
        c.save();
        // the grain is per screen pixel, not per design pixel
        float sx = c.getWidth() / 1280f;
        c.scale(1 / sx, 1 / sx);
        c.drawRect(0, 0, c.getWidth(), c.getHeight(), p);
        c.restore();
        tile.recycle();
    }
}
