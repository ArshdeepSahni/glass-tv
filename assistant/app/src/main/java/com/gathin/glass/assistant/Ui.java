package com.gathin.glass.assistant;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The desktop's proportion system, colours and motion. Everything is laid out in 1280×720 design pixels (the
 * boards' grid) and scaled to the screen, so the TV shows the boards' proportions at any resolution.
 *
 * Type: 13 meta · 15 body · 17 rows · 20 titles · 22–24 the one big thing. Space: 4/8/12/16/20/24.
 * Radii nest: inner = outer − the gap between them (windows and panels 28, their rows 16 or 18, dock 31).
 * Self-contained on purpose: the assistant app carries an identical copy, so both draw the same glass.
 */
final class Ui {
    private Ui() {
    }

    static float s = 1f;        // device px per design px
    static float fs = 1f;       // the TV's Text size, applied to every word (0.9–1.3)
    static boolean reduced;     // Settings ▸ Motion ▸ Reduced

    static final int BAR_H = 34, EDGE = 16, PANEL_Y = 42, PANEL_R = 28;

    static void init(Context c) {
        readScale(c);
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        s = Math.max(m.widthPixels, m.heightPixels) / 1280f;
        float f = c.getResources().getConfiguration().fontScale;
        fs = Math.max(0.85f, Math.min(1.3f, f));   // S (0.85) really is smaller than M, as in the launcher
    }

    static int px(float v) {
        return Math.round(v * s);
    }

    static float pxf(float v) {
        return v * s;
    }

    // ---- colour: the two looks ------------------------------------------------------------------------------

    static final class Tone {
        final boolean night;
        final int ink, ink2, ink3, hair, hover, sel, selink, field, sep, glass2, edge, lozenge, selSub, selChip;

        Tone(boolean night, int ink, int ink2, int ink3, int hair, int hover, int sel, int selink, int field, int sep,
             int glass2, int edge, int lozenge, int selSub, int selChip) {
            this.night = night; this.ink = ink; this.ink2 = ink2; this.ink3 = ink3; this.hair = hair; this.hover = hover;
            this.sel = sel; this.selink = selink; this.field = field; this.sep = sep; this.glass2 = glass2; this.edge = edge;
            this.lozenge = lozenge; this.selSub = selSub; this.selChip = selChip;
        }
    }

    static int a(int rgb, float alpha) {
        return (Math.round(alpha * 255) << 24) | (rgb & 0xFFFFFF);
    }

    static final Tone DAY = new Tone(false, 0xFF16181D, a(0x16181D, .62f), a(0x16181D, .40f), a(0x16181D, .08f),
            a(0x16181D, .06f), 0xFF0E0E10, 0xFFFFFFFF, a(0xFFFFFF, .55f), a(0x16181D, .16f), a(0xFFFFFF, .70f),
            a(0xFFFFFF, .65f), a(0x16181D, .10f), a(0xFFFFFF, .66f), a(0xFFFFFF, .18f));
    static final Tone NIGHT = new Tone(true, 0xFFF2F2F5, a(0xF2F2F5, .62f), a(0xF2F2F5, .38f), a(0xFFFFFF, .08f),
            a(0xFFFFFF, .07f), 0xFFF2F2F5, 0xFF0E0E10, a(0xFFFFFF, .08f), a(0xFFFFFF, .16f), a(0x26262E, .72f),
            a(0xFFFFFF, .12f), a(0xFFFFFF, .14f), a(0x0E0E10, .60f), a(0x0E0E10, .12f));

    static Tone t = DAY;

    /** "auto" follows the hour: the night look from 18:00 to 06:00. */
    static boolean night(String appearance) {
        if ("light".equals(appearance)) return false;
        if ("dark".equals(appearance)) return true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 18 || h < 6;
    }

    static void tone(boolean night) {
        t = night ? NIGHT : DAY;
    }

    // ---- type -----------------------------------------------------------------------------------------------

    private static Typeface w400, w500, w600;

    static Typeface face(Context c, int weight) {
        if (weight >= 600) {
            if (w600 == null) w600 = load(c, R.font.inter_semibold, Typeface.DEFAULT_BOLD);
            return w600;
        }
        if (weight >= 500) {
            if (w500 == null) w500 = load(c, R.font.inter_medium, Typeface.DEFAULT);
            return w500;
        }
        if (w400 == null) w400 = load(c, R.font.inter_regular, Typeface.DEFAULT);
        return w400;
    }

    private static Typeface load(Context c, int id, Typeface fallback) {
        try {
            Typeface f = c.getResources().getFont(id);
            return f != null ? f : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static TextView text(Context c, float size, int color, int weight) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s * fs);
        v.setTextColor(color);
        v.setTypeface(face(c, weight));
        v.setIncludeFontPadding(false);
        return v;
    }

    static TextView line(Context c, float size, int color, int weight, String s) {
        TextView v = text(c, size, color, weight);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setText(s);
        return v;
    }

    // ---- layout ---------------------------------------------------------------------------------------------

    static int dim(float v) {
        return v < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(v);
    }

    /** Absolute placement in design px inside a FrameLayout; a negative size wraps the content. */
    static FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dim(w), dim(h));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    static LinearLayout.LayoutParams ll(float w, float h) {
        return new LinearLayout.LayoutParams(w == -2 ? ViewGroup.LayoutParams.MATCH_PARENT : dim(w), h == -2 ? ViewGroup.LayoutParams.MATCH_PARENT : dim(h));
    }

    static LinearLayout.LayoutParams ll(float w, float h, float left, float top) {
        LinearLayout.LayoutParams lp = ll(w, h);
        lp.leftMargin = px(left);
        lp.topMargin = px(top);
        return lp;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static View space(Context c) {
        return new View(c);
    }

    // ---- shapes ---------------------------------------------------------------------------------------------

    static GradientDrawable fillRound(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(pxf(r));
        return g;
    }

    static GradientDrawable ring(int color, float width, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        g.setStroke(Math.max(1, Math.round(pxf(width))), color);
        g.setCornerRadius(pxf(r));
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Focus as the boards draw it: a 2.5 px ink ring hugging the element. */
    static void focusRing(View v, float r) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, ring(t.ink, 2.5f, r));
        s.addState(new int[]{}, new ColorDrawable(0));
        v.setForeground(s);
    }

    /** The bar's lozenge: a quiet tint behind the focused or open item. */
    static Drawable lozenge(float r) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, fillRound(t.lozenge, r));
        s.addState(new int[]{android.R.attr.state_selected}, fillRound(t.lozenge, r));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    // ---- words cut short scroll into view (as in the desktop's copy) -------------------------------------------

    private static final int MARQUEE = 0x7f0f0f03;

    /** Words cut short (…) scroll along while what holds them has focus, and settle back after (Apple TV's way). */
    static void marquee(View root) {
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (was != null) settle(was);
            if (now != null) scroll(now, 0);
        });
    }

    private static void scroll(View v, int depth) {
        if (v.getVisibility() != View.VISIBLE || depth > 5) return;
        if (v instanceof TextView && !(v instanceof android.widget.EditText)) {
            TextView tv = (TextView) v;
            android.text.Layout l = tv.getLayout();
            boolean cut = l != null && l.getLineCount() == 1 && l.getEllipsisCount(0) > 0;
            if (cut && tv.getEllipsize() == android.text.TextUtils.TruncateAt.END) {
                tv.setTag(MARQUEE, Boolean.TRUE);
                tv.setHorizontalFadingEdgeEnabled(true);
                tv.setFadingEdgeLength(px(18));
                tv.setMarqueeRepeatLimit(-1);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
                tv.setSelected(true);
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scroll(g.getChildAt(i), depth + 1);
        }
    }

    private static void settle(View v) {
        if (v instanceof TextView) {
            if (v.getTag(MARQUEE) != null) {
                TextView tv = (TextView) v;
                tv.setTag(MARQUEE, null);
                tv.setSelected(false);
                tv.setHorizontalFadingEdgeEnabled(false);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) settle(g.getChildAt(i));
        }
    }

    // ---- motion ---------------------------------------------------------------------------------------------

    static final TimeInterpolator SPR = new PathInterpolator(0.2f, 1.3f, 0.4f, 1f);     // focus, panels: a little overshoot
    static final TimeInterpolator SPRW = new PathInterpolator(0.18f, 1.22f, 0.32f, 1f); // windows growing from their icon
    static final TimeInterpolator EIN = new PathInterpolator(0.55f, 0f, 0.8f, 0.2f);    // leaving
    static final TimeInterpolator STD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final TimeInterpolator OUT = new PathInterpolator(0.2f, 0.8f, 0.3f, 1f);     // arriving, no overshoot

    /** The TV's own animator speed (Settings ▸ animation scale): durations are divided by it so Glass keeps its pace. */
    static float animatorScale = 1f;

    static void readScale(Context c) {
        try {
            animatorScale = android.provider.Settings.Global.getFloat(c.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (RuntimeException e) {
            animatorScale = 1f;
        }
    }

    static long ms(long d) {
        if (reduced) return Math.min(d, 90);
        return animatorScale > 0.05f ? Math.round(d / animatorScale) : d;
    }

    static TimeInterpolator spring(TimeInterpolator i) {
        return reduced ? STD : i;
    }

    static final ExecutorService BG = Executors.newSingleThreadExecutor();
}
