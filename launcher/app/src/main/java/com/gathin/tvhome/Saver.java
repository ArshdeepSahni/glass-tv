package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Glass's screen savers (Looks.SAVERS), one view for the TV's screen saver (ClockDream), Home left idle
 * (ClockActivity) and the preview in Settings:
 *
 *   clock   the time, large and thin, over your background dimmed
 *   drift   the backgrounds, one after another, each slowly moving closer
 *   glass   the glass of the wake film, a sunrise inside it, breathing slowly on black
 *   glow    soft colours moving on black, like light through glass
 *   photos  photos sent from a phone, each slowly moving closer
 *   night   a dim clock on black, for a dark room
 *
 * Everything moves a little every minute so nothing stays put on the panel, and every animation is a plain move or
 * fade of a picture (the TV's GPU does them, nothing is redrawn per frame). Widgets chosen for the screen saver sit
 * at the bottom (Widgets).
 */
final class Saver extends FrameLayout {
    private static final long MINUTE = 60_000L;
    private final String style;
    private final boolean night;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final List<ObjectAnimator> running = new ArrayList<>();
    private LinearLayout clock;
    private ImageView a, b;   // two layers for crossfades
    private final List<Object> slides = new ArrayList<>();   // background ids for drift; Files for photos
    private int slide;
    private boolean started;
    private Widgets.Strip strip;

    Saver(Context c, String style) {
        super(c);
        Ui.init(c);
        this.style = "photos".equals(style) && Show.photos(c).isEmpty() ? "drift" : style;
        night = Ui.night(Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto")) || "night".equals(style) || "glass".equals(style) || "glow".equals(style);
        setBackgroundColor(0xFF000000);
        setClipChildren(true);
        build();
    }

    private void build() {
        Context c = getContext();
        // the widgets first: where the time goes depends on them (they're added last, over everything)
        strip = Widgets.strip(c, "saver", night || !"clock".equals(style));
        switch (style) {
            case "drift": {
                for (String[] w : Looks.WALLS) if (!"field".equals(w[0])) slides.add(w[0]);   // decoded one at a time
                java.util.Collections.shuffle(slides, rnd);
                layers();
                clock = clock(64, false);
                place(clock, 56, 520);
                break;
            }
            case "photos": {
                slides.addAll(Show.photos(c));
                layers();
                clock = clock(64, false);
                place(clock, 56, 520);
                break;
            }
            case "glass": {
                a = picture();
                a.setImageBitmap(Looks.asset(c, "saver/glass.jpg", 1));
                a.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(a, Ui.fill());
                // under the glass when it has the bottom to itself; beside the widgets, at the left, when they're there
                clock = clock(56, strip == null);
                if (strip == null) place(clock, 0, 560);
                else place(clock, 56, 520);
                break;
            }
            case "glow": {
                int[] colours = {0xFF2F5BFF, 0xFF00B3A4, 0xFF9B4DFF, 0xFFFF7A3D, 0xFFE0407A};
                for (int i = 0; i < colours.length; i++) {
                    ImageView blob = new ImageView(c);
                    blob.setImageBitmap(blob(colours[i]));
                    blob.setAlpha(0.55f);
                    int size = Ui.px(560 + 80 * (i % 3));
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
                    lp.leftMargin = Ui.px(rnd.nextInt(900) - 120);
                    lp.topMargin = Ui.px(rnd.nextInt(460) - 160);
                    addView(blob, lp);
                }
                clock = clock(120, true);
                place(clock, 0, 250);
                break;
            }
            case "night": {
                clock = clock(72, true);
                clock.setAlpha(0.32f);
                tint(clock, 0xFFFFB37A);
                place(clock, 0, 300);
                break;
            }
            default: {   // clock
                a = picture();
                a.setImageBitmap(Wall.get(c, night));
                a.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(a, Ui.fill());
                View shade = new View(c);
                shade.setBackgroundColor(Ui.a(0x000000, night ? .45f : .35f));
                addView(shade, Ui.fill());
                clock = clock(140, false);
                place(clock, 72, 380);
            }
        }
        if (strip != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = Ui.px(48);
            lp.bottomMargin = Ui.px(40);
            addView(strip.view, lp);
            if ("night".equals(style)) strip.view.setAlpha(0.3f);
        }
    }

    private ImageView picture() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private void layers() {
        a = picture();
        b = picture();
        addView(a, Ui.fill());
        addView(b, Ui.fill());
        b.setAlpha(0f);
        View shade = new View(getContext());
        shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Ui.a(0x000000, .45f), 0}));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, Ui.px(260), Gravity.BOTTOM);
        addView(shade, sl);
    }

    /** The time (thin, large) and the date under it. */
    private LinearLayout clock(float size, boolean centred) {
        Context c = getContext();
        LinearLayout col = Ui.column(c);
        col.setGravity(centred ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        TextClock t = new TextClock(c);
        t.setFormat12Hour("h:mm");
        t.setFormat24Hour("HH:mm");
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(size));
        t.setTextColor(0xF2FFFFFF);
        t.setIncludeFontPadding(false);
        t.setLetterSpacing(-0.03f);
        Typeface thin;
        try {
            thin = c.getResources().getFont(R.font.outfit_extralight);
        } catch (RuntimeException e) {
            thin = Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        t.setTypeface(thin);
        t.setShadowLayer(Ui.pxf(18), 0, Ui.pxf(2), Ui.a(0x000000, .35f));
        col.addView(t);
        TextClock d = new TextClock(c);
        String pattern = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM");
        d.setFormat12Hour(pattern);
        d.setFormat24Hour(pattern);
        d.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(Math.max(17, size / 5.2f)));
        d.setTextColor(Ui.a(0xFFFFFF, .78f));
        d.setTypeface(Ui.face(c, 500));
        d.setShadowLayer(Ui.pxf(12), 0, Ui.pxf(1), Ui.a(0x000000, .35f));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Ui.px(size / 14f);
        dl.leftMargin = centred ? 0 : Ui.px(size / 22f);
        col.addView(d, dl);
        return col;
    }

    private static void tint(LinearLayout col, int colour) {
        for (int i = 0; i < col.getChildCount(); i++) if (col.getChildAt(i) instanceof TextView) ((TextView) col.getChildAt(i)).setTextColor(colour);
    }

    /** x 0 centres it; y is its top (design px). */
    private void place(View v, float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, x == 0 ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.TOP | Gravity.START);
        lp.leftMargin = x == 0 ? 0 : Ui.px(x);
        lp.topMargin = Ui.px(y);
        addView(v, lp);
    }

    /** A soft disc of light: the colour in the middle fading to nothing (drawn once, moved by the GPU). */
    private static Bitmap blob(int colour) {
        int s = 256;
        Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{colour, colour & 0x80FFFFFF, colour & 0x00FFFFFF},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(bm).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return bm;
    }

    // ---- motion ---------------------------------------------------------------------------------------------

    void start() {
        if (started) return;
        started = true;
        switch (style) {
            case "drift":
            case "photos":
                nextSlide(true);
                break;
            case "glass":
                breathe(a, 1.0f, 1.06f, 24_000);
                break;
            case "glow":
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    if (v instanceof ImageView) wander(v, i);
                }
                break;
            default:
                break;
        }
        if (strip != null) strip.start();
        h.postDelayed(shift, MINUTE);
    }

    void stop() {
        started = false;
        h.removeCallbacksAndMessages(null);
        for (ObjectAnimator o : running) o.cancel();
        running.clear();
        if (strip != null) strip.stop();
    }

    /** Every minute the clock moves a little: nothing stays put on the panel. */
    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            if (clock != null) {
                float dx = Ui.pxf(rnd.nextInt(41) - 20), dy = Ui.pxf(rnd.nextInt(25) - 12);
                clock.animate().translationX(dx).translationY(dy).setDuration(Ui.ms(2400)).start();
            }
            if (strip != null) strip.view.animate().translationX(Ui.pxf(rnd.nextInt(21) - 10)).translationY(Ui.pxf(rnd.nextInt(13) - 6)).setDuration(2400).start();
            h.postDelayed(this, MINUTE);
        }
    };

    private void breathe(View v, float from, float to, long ms) {
        v.setScaleX(from);
        v.setScaleY(from);
        ObjectAnimator o = ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -Ui.pxf(10)));
        o.setDuration(ms);
        o.setRepeatMode(ValueAnimator.REVERSE);
        o.setRepeatCount(ValueAnimator.INFINITE);
        o.setInterpolator(Wake.SMOOTH);
        o.start();
        running.add(o);
    }

    private void wander(View v, int i) {
        float r = Ui.pxf(150 + 40 * i);
        ObjectAnimator o = ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -r, r),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, r * 0.5f, -r * 0.5f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.2f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.2f));
        o.setDuration(26_000 + 7_000L * i);
        o.setStartDelay(1_300L * i);
        o.setRepeatMode(ValueAnimator.REVERSE);
        o.setRepeatCount(ValueAnimator.INFINITE);
        o.setInterpolator(Wake.SMOOTH);
        o.start();
        running.add(o);
    }

    private static final long SLIDE_MS = 24_000, FADE_MS = 2_400;

    /** The next picture fades in over the last one and slowly moves closer (a Ken Burns move, alternating sides). */
    private void nextSlide(boolean first) {
        if (!started || slides.isEmpty()) return;
        Object s = slides.get(slide % slides.size());
        slide++;
        Bitmap bm = s instanceof String ? Looks.picture(getContext(), (String) s, night, Ui.px(1280), Ui.px(720))
                : s instanceof File ? decode((File) s) : null;
        ImageView in = first ? a : (a.getAlpha() > 0.5f ? b : a);
        ImageView out = in == a ? b : a;
        if (bm != null) in.setImageBitmap(bm);
        in.bringToFront();
        bringOverlaysForward();
        float dir = slide % 2 == 0 ? 1 : -1;
        in.setScaleX(1.0f);
        in.setScaleY(1.0f);
        in.setTranslationX(0);
        ObjectAnimator move = ObjectAnimator.ofPropertyValuesHolder(in,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.12f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.12f),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dir * Ui.pxf(36)));
        move.setDuration(SLIDE_MS + FADE_MS);
        move.setInterpolator(new LinearInterpolator());
        move.start();
        running.add(move);
        if (first) {
            in.setAlpha(1f);
        } else {
            in.setAlpha(0f);
            in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> out.setAlpha(0f)).start();
        }
        if (slides.size() > 1) h.postDelayed(() -> nextSlide(false), SLIDE_MS);
    }

    /** The shade, the clock and the widgets stay over the pictures. */
    private void bringOverlaysForward() {
        List<View> over = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v != a && v != b) over.add(v);
        }
        for (View v : over) v.bringToFront();
    }

    private Bitmap decode(File f) {
        return Show.thumb(f, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
    }
}
