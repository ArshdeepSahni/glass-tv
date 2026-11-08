package com.gathin.tvhome;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Anything that opens over the desktop or over an app. Panels and menus pour out of their bar item like a drop
 * (the glass grows from a small pill at the top edge on the PANEL spring, the words fading in behind it).
 * Windows grow out of their dock icon on the WINDOW spring and fold back into it. Full-screen sheets (the
 * switcher, All apps) fade and settle. Focus stays inside until Back closes it, then goes back where it was.
 */
abstract class Sheet {
    final Desk a;
    final Context c;
    final Ui.Tone t = Ui.t;
    Trap root;
    Pane pane;
    View content;           // what fades in once the glass has poured (defaults to the pane's first child)
    View anchor;            // focus returns here
    String barKey;          // the bar item that stays lit while this is open
    boolean window;         // grows from an icon; one window at a time
    boolean overlay;        // full-screen (switcher, all apps): fades
    float ox = 640, oy = 360;   // where a window grows from (design px)
    float dropX = -1;       // where a panel hangs from (design px)
    boolean closing;

    Sheet(Desk a) {
        this.a = a;
        this.c = a.context();
    }

    /** Adds the pane (and anything else) to `root`. */
    abstract void build();

    View firstFocus() {
        return null;
    }

    /** Key down before the window handles it. True when taken. */
    boolean onKey(KeyEvent e) {
        return false;
    }

    /** Back inside the sheet (up a folder, out of a page). True when handled; false closes the sheet. */
    boolean onBack() {
        return false;
    }

    void onClosed() {
    }

    final void attach(FrameLayout layer) {
        root = new Trap(c);
        build();
        if (content == null && pane != null && pane.getChildCount() > 0) content = pane.getChildAt(0);
        layer.addView(root, Ui.fill());
    }

    void animateIn() {
        Sound.pop();
        if (overlay) {
            root.setAlpha(0f);
            Spring.to(root, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
            if (pane != null) {
                pane.setScaleX(.94f);
                pane.setScaleY(.94f);
                Spring.scale(pane, 1f, Spring.PANEL);
            }
            return;
        }
        if (window) {
            pane.setPivotX(Ui.pxf(ox - pane.x));
            pane.setPivotY(Ui.pxf(oy - pane.y));
            pane.setScaleX(.06f);
            pane.setScaleY(.06f);
            pane.setAlpha(0f);
            Spring.scale(pane, 1f, Spring.WINDOW);
            Spring.to(pane, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
            if (content != null) {
                content.setAlpha(0f);
                content.postDelayed(() -> Spring.to(content, DynamicAnimation.ALPHA, 1f, Spring.SOFT), Ui.ms(120));
            }
            return;
        }
        // a drop leaving the bar
        float cx = Ui.pxf((dropX < 0 ? pane.x + pane.w / 2 : dropX) - pane.x);
        if (content != null) {
            content.setAlpha(0f);
            content.setTranslationY(-Ui.pxf(6));
            content.postDelayed(() -> {
                Spring.to(content, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
                Spring.to(content, DynamicAnimation.TRANSLATION_Y, 0f, Spring.PANEL);
            }, Ui.ms(90));
        }
        pane.pour(cx, null);
    }

    void animateOut(Runnable end) {
        closing = true;
        Sound.close();
        if (overlay) {
            root.animate().alpha(0f).setDuration(Ui.ms(160)).setInterpolator(Ui.STD).withEndAction(end).start();
            return;
        }
        if (window) {
            Spring.stop(pane);
            pane.animate().scaleX(.06f).scaleY(.06f).alpha(0f).setDuration(Ui.ms(320)).setInterpolator(Ui.EIN).withEndAction(end).start();
            return;
        }
        if (content != null) content.animate().alpha(0f).setDuration(Ui.ms(90)).start();
        float cx = Ui.pxf((dropX < 0 ? pane.x + pane.w / 2 : dropX) - pane.x);
        pane.unpour(cx, () -> pane.animate().alpha(0f).setDuration(Ui.ms(60)).withEndAction(end).start());
    }

    /**
     * Full-screen and transparent: keeps focus inside the sheet; a push against the edge bumps. The next focus is
     * looked for among the sheet's own views only. (Looked for in the whole window, a view of the desktop under the
     * sheet, a widget or a dock icon, could be the nearest one in that direction; refused, it left focus stuck where
     * it was: the switcher's cards couldn't be reached past the second.)
     */
    static final class Trap extends FrameLayout {
        Trap(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        public View focusSearch(View focused, int direction) {
            View next = focused != null && inside(focused)
                    ? android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction)
                    : super.focusSearch(focused, direction);
            if (next != null && inside(next)) return next;
            Sound.bump();
            return focused;
        }

        boolean inside(View v) {
            for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == this) return true;
            return false;
        }
    }

    // ---- shared pieces ------------------------------------------------------------------------------------

    /** A row in a list whose focus is the gliding platter (the words keep their colour on it). */
    static LinearLayout row(Context c, Ui.Tone t, Glide glide, float h) {
        LinearLayout row = Ui.row(c);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Ui.px(12), 0, Ui.px(12), 0);
        row.setMinimumHeight(Ui.px(h));
        row.setOnFocusChangeListener((v, has) -> {
            paint((LinearLayout) v, has, t);
            if (has) {
                glide.to(v);
                Sound.tick();
            } else {
                v.post(() -> {
                    ViewGroup g = (ViewGroup) glide.getParent();
                    if (g != null && g.findFocus() == null) glide.hide();
                });
            }
        });
        return row;
    }

    static void paint(LinearLayout row, boolean focused, Ui.Tone t) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View ch = row.getChildAt(i);
            Object role = ch.getTag();
            if (ch instanceof TextView) {
                boolean sub = "sub".equals(role);
                ((TextView) ch).setTextColor(focused ? (sub ? t.focusSub : t.focusInk) : (sub ? t.ink3 : t.ink));
            } else if (ch instanceof ImageView && ((ImageView) ch).getDrawable() instanceof Glyph && !"keep".equals(role)) {
                ((Glyph) ((ImageView) ch).getDrawable()).setColor(focused ? t.focusInk : t.ink2);
            }
        }
    }

    static ImageView glyph(Context c, String name, int color) {
        ImageView g = new ImageView(c);
        g.setImageDrawable(Glyph.of(name, color));
        return g;
    }

    /** A FrameLayout holding a column of rows and the fill that glides behind them. */
    static final class Rows {
        final FrameLayout frame;
        final LinearLayout list;
        final Glide glide;

        Rows(Context c, Ui.Tone t, float radius) {
            frame = new FrameLayout(c);
            glide = new Glide(c, t.focus, 0, radius, 0);
            frame.addView(glide, Ui.fill());
            list = Ui.column(c);
            frame.addView(list, new FrameLayout.LayoutParams(-1, -2));
        }
    }

    // ---- a menu: rows hanging from the bar (or rising from the dock) --------------------------------------

    static final class Row {
        final String glyph, title, detail;
        final Runnable action;
        boolean check;

        Row(String glyph, String title, String detail, Runnable action) {
            this.glyph = glyph;
            this.title = title;
            this.detail = detail;
            this.action = action;
        }

        Row checked(boolean on) {
            check = on;
            return this;
        }
    }

    static final class Menu extends Sheet {
        final List<Row> rows;
        final float x, y, w;
        final boolean up;
        private final List<View> views = new ArrayList<>();

        /** x, y: the menu's top-left (or, rising, its bottom-left) in design px. */
        Menu(Desk a, List<Row> rows, float x, float y, float w, boolean up) {
            super(a);
            this.rows = rows;
            this.x = x;
            this.y = y;
            this.w = w;
            this.up = up;
        }

        /** A row's height: 40 at text size M, growing with the words. */
        private final float rowH = Ui.boxf(40, 15);

        @Override
        void build() {
            float h = 16 + rows.size() * rowH;
            float top = up ? y - h : y;
            // wider with bigger words (and still on the screen)
            float ww = w * (1f + 0.7f * Math.max(0f, Ui.fs - 1f));
            float xx = Math.max(Ui.EDGE, Math.min(x, 1280 - Ui.EDGE - ww));
            top = Math.max(8, Math.min(top, 712 - h));
            pane = new Pane(c, true, xx, top, ww, h, 20);
            pane.overApp(a.overApp());
            Rows list = new Rows(c, t, 12);
            list.frame.setPadding(Ui.px(8), Ui.px(8), Ui.px(8), Ui.px(8));
            boolean anyGlyph = false;
            for (Row r : rows) anyGlyph |= r.glyph != null;
            for (Row r : rows) {
                LinearLayout row = row(c, t, list.glide, rowH);
                if (r.glyph != null) row.addView(glyph(c, r.glyph, t.ink2), Ui.ll(18, 18, 0, 0));
                else if (anyGlyph) row.addView(new View(c), Ui.ll(18, 1));   // words line up under the others'
                TextView title = Ui.line(c, 15, t.ink, 400, r.title);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
                tl.leftMargin = Ui.px(anyGlyph ? 10 : 0);
                row.addView(title, tl);
                if (r.detail != null) {
                    TextView d = Ui.line(c, 13, t.ink3, 400, r.detail);
                    d.setTag("sub");
                    row.addView(d, Ui.ll(-1, -1, 10, 0));
                }
                if (r.check) row.addView(glyph(c, "check", t.ink2), Ui.ll(16, 16, 10, 0));
                paint(row, false, t);
                row.setOnClickListener(v -> {
                    a.dismiss(this);
                    if (r.action != null) r.action.run();
                });
                list.list.addView(row, Ui.ll(-2, rowH));
                views.add(row);
            }
            pane.addView(list.frame, Ui.fill());
            content = list.frame;
            root.addView(pane, pane.params());
            pane.bake(c);
        }

        @Override
        void animateIn() {
            if (!up) {
                super.animateIn();
                return;
            }
            Sound.pop();
            pane.setPivotX(Ui.pxf(w / 2));
            pane.setPivotY(pane.h * Ui.s);
            pane.setTranslationY(Ui.pxf(10));
            pane.setScaleX(.94f);
            pane.setScaleY(.94f);
            pane.setAlpha(0f);
            Spring.to(pane, DynamicAnimation.TRANSLATION_Y, 0f, Spring.PANEL);
            Spring.scale(pane, 1f, Spring.PANEL);
            Spring.to(pane, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
        }

        @Override
        void animateOut(Runnable end) {
            if (!up) {
                super.animateOut(end);
                return;
            }
            closing = true;
            Sound.close();
            Spring.stop(pane);
            pane.animate().translationY(Ui.pxf(8)).scaleX(.96f).scaleY(.96f).alpha(0f).setDuration(Ui.ms(160)).setInterpolator(Ui.EIN).withEndAction(end).start();
        }

        @Override
        View firstFocus() {
            return views.isEmpty() ? null : views.get(0);
        }
    }
}
