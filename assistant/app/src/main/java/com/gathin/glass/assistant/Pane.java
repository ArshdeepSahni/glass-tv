package com.gathin.glass.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * Live liquid glass. Every frame the glass shows what is really behind it on screen: the wallpaper, magnified a
 * little about the glass's centre (frosted panes show the frosted wallpaper), and bent harder through the lip at
 * the edge (sharp scene, magnified more the nearer the edge). It is drawn by the GPU from two textures, so when a
 * pane slides, grows or morphs, the scene inside it stays put and the glass moves over it, like a real lens.
 * Over it: the body tint, a specular bloom under the top edge, inner depth, and a rim that splits the light a
 * little (warm one way, cool the other). The outline clips everything and casts the shadow.
 *
 * Over an app there is no wallpaper behind: tinted frost only. Positioned in design px (x, y, w, h, r); the
 * visible shape can morph (pour from a bar item, the dock widening, the assistant's bead → capsule → card).
 */
class Pane extends FrameLayout {
    final boolean frost;
    final float x, y, w, h;
    float r;
    private boolean night, overApp, solid;
    private Bitmap sharp, soft;
    private BitmapShader sharpShader, softShader;
    private final Matrix m = new Matrix();
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF(), boxR = new RectF(), band = new RectF(), edge = new RectF();
    private static final float[][] FROST_BANDS = {{0, 2, 1.16f}, {2, 5, 1.07f}};
    private static final float[][] CLEAR_BANDS = {{0, 3, 1.30f}, {3, 11, 1.14f}};
    // gradients depend only on the box and the look: made again only when one of those changes
    private float gL = Float.NaN, gT, gW, gH, gR;
    private boolean gDay;
    private Shader bloom, line, top, bottom, rimLight, rimWarm, rimCool;
    private float shapeR;
    private boolean customShape;
    private final int[] loc = new int[2];
    private float lastX = Float.NaN, lastY = Float.NaN, lastS = Float.NaN;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Paint lipTint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ViewTreeObserver.OnPreDrawListener follow = () -> {
        if (sharp != null && !overApp && moved()) invalidate();
        return true;
    };

    Pane(Context c, boolean frost, float x, float y, float w, float h, float r) {
        super(c);
        this.frost = frost;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.r = r;
        setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                if (customShape) {
                    o.setRoundRect(Math.round(shape.left), Math.round(shape.top), Math.round(shape.right), Math.round(shape.bottom), shapeR);
                } else {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(Pane.this.r));
                }
            }
        });
        setElevation(Ui.pxf(frost ? 18 : 14));
        rim.setStyle(Paint.Style.STROKE);
        lip.setStyle(Paint.Style.STROKE);
        lipTint.setStyle(Paint.Style.STROKE);
        night = Ui.t.night;
    }

    private static ColorMatrixColorFilter color(float saturate, float bright) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(saturate);
        ColorMatrix b = new ColorMatrix();
        b.setScale(bright, bright, bright, 1f);
        m.postConcat(b);
        return new ColorMatrixColorFilter(m);
    }

    FrameLayout.LayoutParams params() {
        return Ui.at(x, y, w, h);
    }

    /** Fully opaque where it would otherwise let a little through (the volume HUD hides the TV's bar). */
    void solid(boolean on) {
        solid = on;
        invalidate();
    }

    /** Over an app there is no wallpaper behind: tinted frost only (and no textures are fetched for it). */
    void overApp(boolean on) {
        overApp = on;
        if (!on && bakeLater) {
            bakeLater = false;
            bake(getContext());
        }
        invalidate();
    }

    private boolean bakeLater;

    /** The frosted picture for this look if it is ready (for a full-screen veil), else null. */
    static synchronized Bitmap frostNow(boolean n) {
        return frostTex != null && texNight == n ? frostTex : null;
    }

    /** Fetches the glass textures (made once per look, shared by every pane), then draws live. */
    void bake(Context c) {
        night = Ui.t.night;
        if (overApp) {   // nothing of the wallpaper shows over an app: fetch only if that changes
            bakeLater = true;
            return;
        }
        final boolean n = night;
        Bitmap[] ready = glassPeek(n);
        if (ready != null) {
            textures(ready[0], ready[1]);
            return;
        }
        final Context app = c.getApplicationContext();
        Ui.BG.execute(() -> {
            try {
                Bitmap[] t = glass(app, n);
                MAIN.post(() -> {
                    if (n == night) textures(t[0], t[1]);
                });
            } catch (OutOfMemoryError | RuntimeException ignored) {
                // the tint alone still reads as glass
            }
        });
    }

    // What the glass shows, with the glass's colour lift and body tint already in the picture: the GPU then draws
    // each pane as a plain picture (no colour maths and no full-size tint layer every frame on this small GPU).
    private static Bitmap clearTex, frostTex;
    private static boolean texNight;

    private static synchronized Bitmap[] glassPeek(boolean n) {
        return clearTex != null && texNight == n ? new Bitmap[]{clearTex, frostTex} : null;
    }

    /** {clear glass, frosted glass} for this look. Slow once per look (any thread), then instant. */
    static Bitmap[] glass(Context c, boolean n) {
        Bitmap[] ready = glassPeek(n);
        if (ready != null) return ready;
        Bitmap s = Wall.get(c, n), f = Wall.frost(c, n);
        Bitmap cl = coloured(s, 1.8f, n ? 1.10f : 1.07f, n ? 0x330C0C10 : 0x1AFFFFFF);
        Bitmap fr = coloured(f, 1.8f, 1f, n ? 0x9E18181E : 0x99FAFAFC);
        synchronized (Pane.class) {
            clearTex = cl;
            frostTex = fr;
            texNight = n;
        }
        return new Bitmap[]{cl, fr};
    }

    private static Bitmap coloured(Bitmap src, float saturate, float bright, int tint) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(color(saturate, bright));
        cv.drawBitmap(src, 0, 0, p);
        cv.drawColor(tint);
        return out;
    }

    private void textures(Bitmap s, Bitmap f) {
        sharp = s;
        soft = f;
        sharpShader = new BitmapShader(s, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        softShader = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        lastX = Float.NaN;
        invalidate();
    }

    /** The visible shape inside the pane, in pane pixels (pour, widen, bead → capsule → card). */
    void shape(float l, float t, float rr, float b, float radius) {
        customShape = true;
        shape.set(l, t, rr, b);
        shapeR = radius;
        invalidateOutline();
        invalidate();
    }

    void fullShape() {
        customShape = false;
        invalidateOutline();
        invalidate();
    }

    /** Opens like a drop leaving the bar: from a small pill at `cx` (pane px) along the top edge to the whole pane. */
    void pour(float cx, Runnable end) {
        float W = Ui.pxf(w), H = Ui.pxf(h), half = Ui.pxf(24), pill = Ui.pxf(28);
        float l0 = Math.max(0, cx - half), r0 = Math.min(W, cx + half);
        float R = Ui.pxf(r);
        shape(l0, 0, r0, pill, pill / 2);
        Spring.value(0f, 1f, Spring.PANEL, f -> {
            float g = Math.max(0f, f);
            float bottom = pill + (H - pill) * Math.min(1.04f, g);
            shape(l0 + (0 - l0) * g, 0, r0 + (W - r0) * g, Math.min(H, bottom), pill / 2 + (R - pill / 2) * Math.min(1f, g));
        }, () -> {
            fullShape();
            if (end != null) end.run();
        });
    }

    /** The reverse: back into a small pill at `cx`, then `end`. */
    void unpour(float cx, Runnable end) {
        float W = Ui.pxf(w), H = Ui.pxf(h), half = Ui.pxf(24), pill = Ui.pxf(28);
        float l0 = Math.max(0, cx - half), r0 = Math.min(W, cx + half), R = Ui.pxf(r);
        Spring.value(1f, 0f, Spring.SOFT, f -> {
            float g = Math.max(0f, Math.min(1f, f));
            shape(l0 * (1 - g), 0, r0 + (W - r0) * g, pill + (H - pill) * g, pill / 2 + (R - pill / 2) * g);
        }, end);
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    private float scale() {
        float s = 1f;
        View v = this;
        while (v != null) {
            s *= v.getScaleX();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return s;
    }

    private boolean moved() {
        getLocationOnScreen(loc);
        float s = scale();
        boolean changed = loc[0] != lastX || loc[1] != lastY || s != lastS;
        return changed;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(follow);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(follow);
        super.onDetachedFromWindow();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        drawGlass(c);
        super.dispatchDraw(c);
    }

    private void drawGlass(Canvas c) {
        int vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0) return;
        boolean day = !night;
        RectF box = boxR;
        if (customShape) box.set(shape); else box.set(0, 0, vw, vh);
        float rr = customShape ? shapeR : Ui.pxf(r);
        // 1 the scene through the glass (or, over an app or before the textures arrive, a denser tint)
        if (sharp != null && !overApp) {
            getLocationOnScreen(loc);
            float s = scale();
            lastX = loc[0];
            lastY = loc[1];
            lastS = s;
            float cx = loc[0] + box.centerX() * s, cy = loc[1] + box.centerY() * s;   // the glass's centre on screen
            BitmapShader sh = frost ? softShader : sharpShader;
            Bitmap tex = frost ? soft : sharp;
            lens(sh, tex, cx, cy, loc[0], loc[1], s, frost ? 1.05f : 1.04f);
            body.setShader(sh);
            c.drawRect(box, body);
            // the lip: thin and sharp on frosted panes, deep on clear glass. Each band is a stroked rounded rect
            // (drawn analytically by the GPU, so a morphing pane costs no path rasterising per frame)
            for (float[] b : frost ? FROST_BANDS : CLEAR_BANDS) {
                float o = Ui.pxf(b[0]), i = Ui.pxf(b[1]), mid = (o + i) / 2;
                band.set(box);
                band.inset(mid, mid);
                if (band.width() <= 0 || band.height() <= 0) continue;
                lens(sharpShader, sharp, cx, cy, loc[0], loc[1], s, b[2]);
                lip.setShader(sharpShader);
                lip.setStrokeWidth(i - o);
                float br = Math.max(0, rr - mid);
                c.drawRoundRect(band, br, br, lip);
            }
            if (frost) {
                // the frosted body's tint lies over its lip too (the clear picture carries only the clear tint)
                float wTint = Ui.pxf(FROST_BANDS[FROST_BANDS.length - 1][1]), mid = wTint / 2;
                band.set(box);
                band.inset(mid, mid);
                lipTint.setColor(day ? 0x99FAFAFC : 0x9E18181E);
                lipTint.setStrokeWidth(wTint);
                float br = Math.max(0, rr - mid);
                if (band.width() > 0 && band.height() > 0) c.drawRoundRect(band, br, br, lipTint);
            }
        } else {
            // no picture behind (over an app, or the textures are still coming): a dense fill, then the tint
            fill.setShader(null);
            int base = overApp ? (day ? 0xEBF4F4F7 : 0xE0141418) : (day ? (frost ? 0xF0EEF1F5 : 0xB0E9EDF2) : (frost ? 0xF01A1A22 : 0xB0181822));
            fill.setColor(solid ? base | 0xFF000000 : base);
            c.drawRect(box, fill);
            fill.setColor(frost ? (day ? 0x99FAFAFC : 0x9E18181E) : (day ? 0x1AFFFFFF : 0x330C0C10));
            c.drawRect(box, fill);
        }
        // 3-5 bloom under the top edge (by day: at night it is too faint to earn a layer), depth, and the rim
        float bw = box.width(), bh = box.height();
        gradients(box, rr, day);
        float hh = Math.min(bh, Ui.pxf(170));
        float ex = box.left + bw * .03f, ew = bw * .64f, ey = box.top - hh * .6f, eh = hh * 1.15f;
        float ecx = ex + ew / 2, ecy = ey + eh / 2;
        if (ew > 1) {
            if (day) {
                fill.setShader(bloom);
                c.save();
                c.scale(1f, eh / ew, ecx, ecy);
                c.drawCircle(ecx, ecy, ew / 2, fill);
                c.restore();
            }
            fill.setShader(line);
            c.drawRect(box.left + bw * .12f, box.top + Ui.pxf(1), box.left + bw * .70f, box.top + Ui.pxf(1) + Math.max(1, Ui.pxf(1)), fill);
        }
        float d1 = Math.min(bh / 2, Ui.pxf(28)), d2 = Math.min(bh / 2, Ui.pxf(40));
        if (day) {
            fill.setShader(top);
            c.drawRect(box.left, box.top, box.right, box.top + d1, fill);
        }
        fill.setShader(bottom);
        c.drawRect(box.left, box.bottom - d2, box.right, box.bottom, fill);
        fill.setShader(null);
        edge(c, box, rr, 1.4f, 0, 0, rimLight);
        edge(c, box, rr, 1f, -.6f, -.4f, rimWarm);
        edge(c, box, rr, 1f, .6f, .4f, rimCool);
    }

    private void gradients(RectF box, float rr, boolean day) {
        if (box.left == gL && box.top == gT && box.width() == gW && box.height() == gH && rr == gR && day == gDay && bloom != null) return;
        gL = box.left;
        gT = box.top;
        gW = box.width();
        gH = box.height();
        gR = rr;
        gDay = day;
        float bw = gW, bh = gH;
        float hh = Math.min(bh, Ui.pxf(170));
        float bl = day ? (frost ? .24f : .32f) : .10f;
        float ex = box.left + bw * .03f, ew = Math.max(2, bw * .64f), ey = box.top - hh * .6f, eh = hh * 1.15f;
        bloom = new RadialGradient(ex + ew / 2, ey + eh / 2, ew / 2, new int[]{Ui.a(0xFFFFFF, bl), 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        line = new LinearGradient(box.left + bw * .12f, 0, box.left + bw * .70f + 1, 0, new int[]{0x00FFFFFF, Ui.a(0xFFFFFF, day ? .9f : .5f), 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        float d1 = Math.max(1, Math.min(bh / 2, Ui.pxf(28))), d2 = Math.max(1, Math.min(bh / 2, Ui.pxf(40)));
        top = new LinearGradient(0, box.top, 0, box.top + d1, Ui.a(0xFFFFFF, day ? .10f : .04f), 0x00FFFFFF, Shader.TileMode.CLAMP);
        bottom = new LinearGradient(0, box.bottom - d2, 0, box.bottom, 0x00000000, Ui.a(0x000000, day ? .05f : .20f), Shader.TileMode.CLAMP);
        float light = day ? .95f : .6f;
        rimLight = angled(box, 150, new int[]{Ui.a(0xFFFFFF, light), Ui.a(0xFFFFFF, .22f), Ui.a(0xFFFFFF, .03f), Ui.a(0xFFFFFF, light * .55f)}, new float[]{0f, .28f, .55f, 1f});
        rimWarm = angled(box, 150, new int[]{Ui.a(0xFF7878, .30f), 0x00FF7878}, new float[]{0f, .4f});
        rimCool = angled(box, 330, new int[]{Ui.a(0x78A0FF, .30f), 0x0078A0FF}, new float[]{0f, .4f});
    }

    /** A CSS-style angled gradient over the box. */
    private static Shader angled(RectF box, float deg, int[] colors, float[] at) {
        double a = Math.toRadians(deg);
        float ux = (float) Math.sin(a), uy = (float) -Math.cos(a);
        float len = Math.max(1, Math.abs(box.width() * ux) + Math.abs(box.height() * uy));
        float mx = box.centerX(), my = box.centerY();
        return new LinearGradient(mx - ux * len / 2, my - uy * len / 2, mx + ux * len / 2, my + uy * len / 2, colors, at, Shader.TileMode.CLAMP);
    }

    /** A stroke along the rounded edge, nudged by (dx, dy). */
    private void edge(Canvas c, RectF box, float radius, float width, float dx, float dy, Shader sh) {
        float wpx = Math.max(1f, Ui.pxf(width));
        rim.setShader(sh);
        rim.setStrokeWidth(wpx);
        edge.set(box);
        edge.inset(wpx / 2, wpx / 2);
        edge.offset(Ui.pxf(dx), Ui.pxf(dy));
        c.drawRoundRect(edge, Math.max(0, radius - wpx / 2), Math.max(0, radius - wpx / 2), rim);
    }

    /** Points the shader so the texture shows the screen behind the glass, magnified `mag` about (cx, cy). */
    private void lens(BitmapShader sh, Bitmap tex, float cx, float cy, float ox, float oy, float s, float mag) {
        float screenW = Ui.pxf(1280), k = screenW / tex.getWidth();
        m.reset();
        m.setScale(k, k);                       // texture px → screen px
        m.postTranslate(-cx, -cy);
        m.postScale(mag, mag);                  // magnified about the glass's centre
        m.postTranslate(cx, cy);
        m.postTranslate(-ox, -oy);              // screen → this view
        m.postScale(1f / s, 1f / s);
        sh.setLocalMatrix(m);
    }

}
