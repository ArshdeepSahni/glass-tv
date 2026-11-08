package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Liquid glass, baked. A glass surface over the static wallpaper always shows the same thing, so what it shows is
 * rendered once per position into a bitmap and the GPU only draws that bitmap: no live blur on this TV.
 *
 *   frost (windows, panels with words): the scene magnified 1.05, blurred (σ 22), saturated 1.8, and bent through
 *          a thin 5 px lip (two bands) so the pane reads as one clean sheet.
 *   clear (dock, bead, switcher): magnified 1.04, barely blurred, saturated 1.7 and brightened, bent through a deep
 *          13 px lip (three bands, the nearer the edge the stronger).
 */
final class Lens {
    private Lens() {
    }

    static Bitmap bake(Bitmap wall, boolean night, boolean frost, float x, float y, float w, float h, float r) {
        float s = Ui.s;
        int ow = Math.max(1, Math.round(w * s)), oh = Math.max(1, Math.round(h * s));
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float cx = (x + w / 2) * s, cy = (y + h / 2) * s;   // the pane's centre, in wallpaper pixels
        float ocx = ow / 2f, ocy = oh / 2f;
        if (frost) core(c, wall, cx, cy, ocx, ocy, ow, oh, 1.05f, 22 * s, color(1.8f, 1f));
        else core(c, wall, cx, cy, ocx, ocy, ow, oh, 1.04f, 1.5f * s, color(1.7f, night ? 1.12f : 1.06f));
        float[][] lip = frost ? new float[][]{{0, 2, 1.16f}, {2, 5, 1.07f}} : new float[][]{{0, 3, 1.30f}, {3, 7, 1.18f}, {7, 13, 1.09f}};
        for (float[] b : lip) band(c, wall, cx, cy, ocx, ocy, ow, oh, r * s, b[0] * s, b[1] * s, b[2]);
        return out;
    }

    /** The whole scene behind the glass, frosted: for a full-screen veil (All apps). */
    static Bitmap veil(Bitmap wall, float sigma, float saturate) {
        int w = wall.getWidth(), h = wall.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        core(new Canvas(out), wall, w / 2f, h / 2f, w / 2f, h / 2f, w, h, 1f, sigma, color(saturate, 1f));
        return out;
    }

    private static ColorMatrixColorFilter color(float saturate, float bright) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(saturate);
        ColorMatrix b = new ColorMatrix();
        b.setScale(bright, bright, bright, 1f);
        m.postConcat(b);
        return new ColorMatrixColorFilter(m);
    }

    /** Magnify the wallpaper about the pane's centre, blur it (σ in wallpaper pixels) and colour it. */
    private static void core(Canvas c, Bitmap wall, float cx, float cy, float ocx, float ocy, int ow, int oh,
                             float mag, float sigma, ColorMatrixColorFilter tint) {
        int d = Math.max(1, (int) Math.floor(sigma / 3f));            // work small: σ ≈ 3 px after the downscale
        float margin = sigma * 3f;
        float left = Math.max(0, cx - ocx / mag - margin), top = Math.max(0, cy - ocy / mag - margin);
        float right = Math.min(wall.getWidth(), cx + ocx / mag + margin), bottom = Math.min(wall.getHeight(), cy + ocy / mag + margin);
        if (right - left < 2 || bottom - top < 2) return;
        int sw = Math.max(2, Math.round((right - left) / d)), sh = Math.max(2, Math.round((bottom - top) / d));
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        new Canvas(small).drawBitmap(wall, new Rect(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom)),
                new Rect(0, 0, sw, sh), f);
        int radius = Math.max(1, Math.round(sigma / d));
        if (sigma >= 1f) boxBlur(small, radius);
        Matrix m = new Matrix();
        m.postScale((right - left) / sw, (bottom - top) / sh);
        m.postTranslate(left - cx, top - cy);
        m.postScale(mag, mag);
        m.postTranslate(ocx, ocy);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        p.setColorFilter(tint);
        c.drawBitmap(small, m, p);
        small.recycle();
    }

    /** One band of the lip: the scene magnified `mag`, between `outer` and `inner` px in from the edge. */
    private static void band(Canvas c, Bitmap wall, float cx, float cy, float ocx, float ocy, int ow, int oh,
                             float r, float outer, float inner, float mag) {
        Matrix m = new Matrix();
        m.postTranslate(-cx, -cy);
        m.postScale(mag, mag);
        m.postTranslate(ocx, ocy);
        BitmapShader sh = new BitmapShader(wall, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(sh);
        p.setColorFilter(color(1.9f, 1.08f));
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addRoundRect(new RectF(outer, outer, ow - outer, oh - outer), Math.max(2, r - outer), Math.max(2, r - outer), Path.Direction.CW);
        ring.addRoundRect(new RectF(inner, inner, ow - inner, oh - inner), Math.max(0, r - inner), Math.max(0, r - inner), Path.Direction.CW);
        c.drawPath(ring, p);
    }

    /** Three box passes ≈ a Gaussian. Edges clamp, so the glass near the screen's edge doesn't darken. */
    static void boxBlur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] a = new int[w * h], t = new int[w * h];
        b.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            blurLine(a, t, w, h, r, true);
            blurLine(t, a, w, h, r, false);
        }
        b.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[index(l, clamp(i, len), w, horizontal)];
                sa += px >>> 24; sr += (px >> 16) & 255; sg += (px >> 8) & 255; sb += px & 255;
            }
            for (int i = 0; i < len; i++) {
                out[index(l, i, w, horizontal)] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[index(l, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[index(l, clamp(i - r, len), w, horizontal)];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
