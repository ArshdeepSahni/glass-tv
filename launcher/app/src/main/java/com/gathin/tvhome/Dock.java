package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The dock: your apps on the left, Glass's own places (Files, Settings, All apps) right of a hairline, on clear
 * glass. Focus breathes: the focused icon grows to 76 px about its centre on a soft shadow (it stays inside the
 * glass: no ring, nothing hangs over the edge), its neighbours step 14 px aside, and the glass grows sideways to
 * make room (its edges move; the scene inside stays put), with the app's name floating above. All on the FOCUS
 * spring, so racing along keeps its speed. A short dash under an icon means the app is open in the background.
 * ← at the first icon and → at the last stay in the dock (a soft bump); only ↑ leaves it, for the bar.
 *
 * Geometry (design px): icons 64 on 12 px gaps, 16 px padding, a 24 px gap around the hairline, dock 96 high
 * at y 612 (12 px from the bottom), radius 31 (concentric with the icons' 15).
 */
final class Dock extends FrameLayout {
    interface Host {
        void dockOpen(Item it, View cell);

        void dockMenu(Item it, View cell);

        /** ↑ from a cell: true when the host moved focus (a widget above it, or the bar). */
        default boolean dockUp(View cell) {
            return false;
        }
    }

    static final class Item {
        final String pkg;      // a package, or AppIcons.FILES / SETTINGS / ALL_APPS
        final String label;

        Item(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        boolean app() {
            return !pkg.startsWith(":");
        }
    }

    static final float DS = 64, DF = 76, GAP = 12, PAD = 16, SEP = 24, SPREAD = 14, TOP = 612, H = 96, R = 31, LIFT = 0;
    static final float FOCUS_TOP = TOP + PAD + (DS - DF) / 2 - LIFT;   // 622: grown about its centre, inside the glass

    private final Host host;
    private final List<Item> items = new ArrayList<>();
    private final List<View> cells = new ArrayList<>(), dashes = new ArrayList<>();
    private final List<Float> slots = new ArrayList<>();
    private int nLeft;
    private Pane glass;
    private View sep;
    private FrameLayout label;
    private TextView labelText;
    private int focused = -1;
    private float x0, width, inset = SPREAD;
    private SpringAnimation widen;

    Dock(Context c, Host host) {
        super(c);
        this.host = host;
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setItems(List<Item> apps) {
        removeAllViews();
        items.clear();
        cells.clear();
        dashes.clear();
        slots.clear();
        focused = -1;
        items.addAll(apps);
        nLeft = apps.size();
        items.add(new Item(AppIcons.FILES, "Files"));
        items.add(new Item(AppIcons.SETTINGS, "Settings"));
        items.add(new Item(AppIcons.ALL_APPS, "All apps"));
        int n = items.size();
        width = n * DS + (n - 2) * GAP + SEP + 2 * PAD;
        if (nLeft == 0) width -= SEP - GAP;
        x0 = (1280 - width) / 2f;
        float x = x0 + PAD;
        float sepX = 0;
        for (int i = 0; i < n; i++) {
            slots.add(x);
            x += DS;
            if (i == nLeft - 1) {
                sepX = x + SEP / 2;
                x += SEP;
            } else {
                x += GAP;
            }
        }
        Context c = getContext();
        glass = new Pane(c, false, x0 - SPREAD, TOP, width + 2 * SPREAD, H, R);
        addView(glass, glass.params());
        glass.setElevation(Ui.pxf(4));
        glass.bake(c);
        inset = SPREAD;
        setInset(SPREAD);
        sep = new View(c);
        sep.setBackgroundColor(Ui.t.sep);
        above(sep, 5);
        if (nLeft > 0) addView(sep, Ui.at(sepX, TOP + 24, 1, H - 48));
        int big = Ui.px(DF);
        for (int i = 0; i < n; i++) {
            final int index = i;
            Item it = items.get(i);
            FrameLayout cell = new FrameLayout(c);
            ImageView icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.FIT_XY);
            icon.setImageBitmap(AppIcons.of(c, it.pkg, big));
            cell.addView(icon, Ui.fill());
            final float rr = Ui.pxf(DS * 0.235f);
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rr);
                    o.setAlpha(v.isFocused() ? 1f : 0f);   // a shadow only under the lifted icon (ten cost a frame)
                }
            });
            cell.setElevation(Ui.pxf(5));   // above the glass (4) in z, so the glass never covers it
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setLongClickable(true);
            cell.setPivotX(Ui.pxf(DS / 2));
            cell.setPivotY(Ui.pxf(DS / 2));
            Audit.focusScale(cell, DF / DS);
            cell.setOnFocusChangeListener((v, has) -> onCellFocus(index, has));
            // the dock's ends hold focus (and ↓ goes nowhere): only ↑ leaves, for the bar
            cell.setOnKeyListener((v, code, ev) -> {
                if (code == android.view.KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == android.view.KeyEvent.ACTION_DOWN && host.dockUp(v)) return true;
                boolean edge = code == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && index == 0
                        || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && index == items.size() - 1;
                if (!edge) return false;
                if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    Sound.bump();
                    nudge(v, code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0);
                }
                return true;
            });
            cell.setOnClickListener(v -> {
                Sound.pop();
                press(v, () -> host.dockOpen(it, v));
            });
            cell.setOnLongClickListener(v -> {
                if (it.app()) host.dockMenu(it, v);
                return true;
            });
            cell.setContentDescription(it.label);
            addView(cell, Ui.at(slots.get(i), TOP + PAD, DS, DS));
            cells.add(cell);
            View dash = new View(c);
            dash.setBackground(Ui.fillRound(Ui.t.ink2, 2));
            dash.setVisibility(INVISIBLE);
            above(dash, 5);
            addView(dash, Ui.at(slots.get(i) + DS / 2 - 8, TOP + H - 7, 16, 3));
            dashes.add(dash);
        }
        // the name, floating above the dock
        label = new FrameLayout(c);
        labelText = Ui.line(c, 15, Ui.t.ink, 500, "");
        labelText.setGravity(Gravity.CENTER);
        labelText.setPadding(Ui.px(12), 0, Ui.px(12), 0);
        pill(labelText);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, Ui.box(30, 15));
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        label.addView(labelText, lp);
        label.setAlpha(0f);
        label.setElevation(Ui.pxf(13));
        addView(label, Ui.at(0, TOP - 10 - Ui.boxf(30, 15), 240, Ui.boxf(30, 15)));
    }

    /** A push against the dock's end: the icon leans that way a touch and comes back. */
    private void nudge(View v, int dir) {
        if (Ui.reduced || dir == 0) return;
        float base = v.getTranslationX();
        v.animate().translationX(base + Ui.pxf(5) * dir).setDuration(Ui.ms(70)).setInterpolator(Ui.OUT)
                .withEndAction(() -> Spring.to(v, DynamicAnimation.TRANSLATION_X, base, Spring.FOCUS)).start();
    }

    /** Above the glass in z, without casting a shadow of its own. */
    private static void above(View v, float z) {
        v.setOutlineProvider(null);
        v.setElevation(Ui.pxf(z));
    }

    private static void pill(TextView v) {
        android.graphics.drawable.GradientDrawable g = Ui.fillRound(Ui.t.glass2, 10);
        g.setStroke(Math.max(1, Ui.px(1)), Ui.t.edge);
        v.setBackground(g);
    }

    void setRunning(Collection<String> pkgs) {
        for (int i = 0; i < items.size(); i++) {
            dashes.get(i).setVisibility(items.get(i).app() && pkgs.contains(items.get(i).pkg) ? VISIBLE : INVISIBLE);
        }
    }

    View first() {
        return cells.isEmpty() ? null : cells.get(0);
    }

    View cellFor(String pkg) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(pkg)) return cells.get(i);
        return null;
    }

    /** The centre of a cell in design px: where its window grows from. */
    float[] center(View cell) {
        int i = cells.indexOf(cell);
        if (i < 0) return new float[]{640, TOP + H / 2};
        return new float[]{slots.get(i) + DS / 2, TOP + PAD + DS / 2};
    }

    boolean hasFocusInside() {
        for (View v : cells) if (v.hasFocus()) return true;
        return false;
    }

    /** OK presses the icon in a little, and it springs back as whatever it opens starts to open. */
    private void press(View v, Runnable then) {
        float sc = cells.indexOf(v) == focused ? DF / DS : 1f;
        Spring.scale(v, sc * 0.9f, Spring.FOCUS);
        v.postDelayed(() -> {
            Spring.scale(v, sc, Spring.PANEL);
            then.run();
        }, Ui.reduced ? 0 : 90);
    }

    private void onCellFocus(int i, boolean has) {
        if (has) {
            boolean entering = focused < 0;
            focused = i;
            arrange(entering);
        } else {
            post(() -> {
                if (!hasFocusInside() && focused == i) {
                    focused = -1;
                    arrange(false);
                }
            });
        }
    }

    /** The glass's sides, `in` design px in from the widest the dock gets. */
    private void setInset(float in) {
        inset = in;
        float W = Ui.pxf(width + 2 * SPREAD);
        glass.shape(Ui.pxf(in), 0, W - Ui.pxf(in), Ui.pxf(H), Ui.pxf(R));
    }

    /** Lays every icon where the current focus wants it, on the FOCUS spring. */
    private void arrange(boolean entering) {
        if (focused >= 0) Sound.tick();
        for (int j = 0; j < cells.size(); j++) {
            float tx = focused < 0 ? 0 : j < focused ? -SPREAD : j > focused ? SPREAD : 0;
            boolean f = j == focused;
            View cell = cells.get(j);
            Spring.to(cell, DynamicAnimation.TRANSLATION_X, Ui.pxf(tx), Spring.FOCUS);
            Spring.to(cell, DynamicAnimation.TRANSLATION_Y, f ? -Ui.pxf(LIFT) : 0f, Spring.FOCUS);
            Spring.scale(cell, f ? DF / DS : 1f, Spring.FOCUS);
            cell.setElevation(Ui.pxf(f ? 12 : 5));
            cell.invalidateOutline();
            Spring.to(dashes.get(j), DynamicAnimation.TRANSLATION_X, Ui.pxf(tx), Spring.FOCUS);
        }
        float stx = focused < 0 ? 0 : focused < nLeft ? SPREAD : -SPREAD;
        Spring.to(sep, DynamicAnimation.TRANSLATION_X, Ui.pxf(stx), Spring.FOCUS);
        float target = focused < 0 ? SPREAD : 0;
        if (widen != null) widen.cancel();
        widen = Spring.value(inset, target, Spring.FOCUS, this::setInset, null);
        if (focused < 0) {
            Spring.to(label, DynamicAnimation.ALPHA, 0f, Spring.SOFT);
            return;
        }
        float cx = slots.get(focused) + DS / 2;
        float lx = Ui.pxf(cx - 120) - label.getLeft();
        labelText.setText(items.get(focused).label);
        if (entering || label.getAlpha() < 0.05f) {
            Spring.stop(label);
            label.setTranslationX(lx);
            label.setTranslationY(Ui.pxf(6));
        }
        Spring.to(label, DynamicAnimation.TRANSLATION_X, lx, Spring.FOCUS);
        Spring.to(label, DynamicAnimation.TRANSLATION_Y, 0f, Spring.FOCUS);
        Spring.to(label, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
    }

    Item item(View cell) {
        int i = cells.indexOf(cell);
        return i < 0 ? null : items.get(i);
    }

    List<Item> apps() {
        return new ArrayList<>(items.subList(0, nLeft));
    }
}
