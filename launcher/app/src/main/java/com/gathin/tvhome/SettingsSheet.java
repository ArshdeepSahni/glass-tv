package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings: one window, changes apply as you go. Sections on the left (focus picks one), its rows on the right:
 * choices change with ← →, pictures (backgrounds, screen savers) too, links open with OK. Back from the rows returns
 * to the sections; Back again closes. The search box at the top finds any row in any section (Glass's own and the
 * TV's: "wallpaper", "bluetooth", "keyboard", "update"...), as you type, with Glass's keyboard or from a phone.
 *
 * Focus is the bright platter (no rings), and wherever something rebuilds the desktop (a new look, text size or
 * background), Settings opens again on the same row.
 */
final class SettingsSheet extends Sheet {
    static final float X = 140, Y = 52, W = 1000, H = 560;
    /** The sections' column: wider with bigger words, so "Backgrounds & savers" fits at every Text size. */
    private final float SB = 252 * (1f + 0.6f * Math.max(0f, Ui.fs - 1f));
    static final String[][] SECTIONS = {
            {"display", "sun", "Display & text"}, {"looks", "sparkle", "Backgrounds & savers"}, {"sound", "volume", "Sound"},
            {"network", "wifi", "Network & Bluetooth"}, {"inputs", "input", "Inputs"}, {"apps", "apps", "Apps"},
            {"speed", "sliders", "Speed & memory"}, {"remote", "remote", "Remote & keyboard"}, {"power", "moon", "Sleep & power"}, {"assistant", "assist", "Assistant"},
            {"glass", "swap", "Glass & original home"}, {"tv", "tv", "TV settings"}, {"about", "info", "About this TV"}};
    static final String GLASS_IME = "com.gathin.tvhome/.GlassKeyboard";
    static final String GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";
    static final String[] CASTING = {"com.google.android.apps.mediashell", "com.homwee.mmp.screenshare", "com.homwee.multiscreenshare"};

    private final String startAt;
    private String pendingRow;   // the row to open on (after the desktop was rebuilt), used once
    private final List<View> sideRows = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private int section = -1;
    private TextView heading;
    private LinearLayout group;
    private ScrollView scroll;
    private EditText search;
    private String query = "";
    private boolean indexing;
    private int indexSection;
    private final List<String[]> index = new ArrayList<>();   // {section, label, sub}
    private long btOffAsked;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    /** startAt: a section, or "section:Row label" to open on that row (after the desktop was rebuilt). */
    SettingsSheet(Desk a, String startAt) {
        super(a);
        window = true;
        String s = startAt, r = null;
        if (startAt != null && startAt.contains(":")) {
            s = startAt.substring(0, startAt.indexOf(':'));
            r = startAt.substring(startAt.indexOf(':') + 1);
        }
        this.startAt = s;
        this.pendingRow = r;
    }

    @Override
    void build() {
        pane = new Pane(c, true, X, Y, W, H, Ui.PANEL_R);
        pane.overApp(a.overApp());
        FrameLayout in = new FrameLayout(c);
        LinearLayout side = Ui.column(c);
        side.setBackgroundColor(t.hover);
        side.setPadding(Ui.px(16), Ui.px(18), Ui.px(16), Ui.px(14));
        search = Ui.field(c, 14, "Search settings", 10, true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int b, int cn) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int cn) { }
            @Override public void afterTextChanged(Editable e) { find(e.toString()); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            hideKeyboard();
            if (!rows.isEmpty()) rows.get(0).requestFocus();
            return true;
        });
        side.addView(search, Ui.ll(-2, Ui.boxf(36, 14)));
        // the sections scroll when a big Text size makes them taller than the window
        ScrollView sideScroll = new ScrollView(c);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setClipToPadding(false);
        sideScroll.setPadding(0, Ui.px(12), 0, Ui.px(4));
        LinearLayout sections = Ui.column(c);
        sideScroll.addView(sections);
        side.addView(sideScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            LinearLayout r = Ui.row(c);
            r.setPadding(Ui.px(10), 0, Ui.px(10), 0);
            r.setFocusable(true);
            r.setClickable(true);
            FrameLayout chip = new FrameLayout(c);
            chip.setBackground(Ui.fillRound(t.field, 7));
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(Ui.px(14), Ui.px(14));
            gl.gravity = Gravity.CENTER;
            chip.addView(glyph(c, SECTIONS[i][1], t.ink2), gl);
            r.addView(chip, Ui.ll(24, 24));
            r.addView(Ui.line(c, 14, t.ink, 400, SECTIONS[i][2]), Ui.ll(-1, -1, 10, 0));
            Ui.focusRing(r, 10);
            Ui.lift(r, 1f, 2);
            r.setOnFocusChangeListener((v, has) -> {
                if (!has) return;
                Sound.tick();
                if (!query.isEmpty()) search.setText("");
                show(index);
            });
            r.setOnClickListener(v -> focusRows());
            sections.addView(r, Ui.ll(-2, Ui.boxf(36, 14), 0, i == 0 ? 0 : 2));
            sideRows.add(r);
        }
        in.addView(side, Ui.at(0, 0, SB, H));
        View rule = new View(c);
        rule.setBackgroundColor(t.hair);
        in.addView(rule, Ui.at(SB - 1, 0, 1, H));
        LinearLayout right = Ui.column(c);
        right.setPadding(0, Ui.px(22), 0, 0);
        heading = Ui.line(c, 24, t.ink, 600, "");
        heading.setLetterSpacing(-0.02f);
        heading.setPadding(Ui.px(24), 0, Ui.px(24), Ui.px(16));
        right.addView(heading);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.px(4), 0, Ui.px(24));
        group = Ui.column(c);
        android.graphics.drawable.GradientDrawable gb = Ui.fillRound(t.field, 16);
        gb.setStroke(Math.max(1, Ui.px(1)), t.hair);
        group.setBackground(gb);
        group.setClipChildren(false);
        FrameLayout pad = new FrameLayout(c);
        pad.setClipChildren(false);
        pad.setPadding(Ui.px(24), Ui.px(2), Ui.px(24), Ui.px(2));
        pad.addView(group, new FrameLayout.LayoutParams(-1, -2));
        scroll.addView(pad);
        right.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));   // the rest of the height, not all of it
        in.addView(right, Ui.at(SB, 0, W - SB, H));
        pane.addView(in, Ui.fill());
        root.addView(pane, pane.params());
        pane.bake(c);
        int start = 0;
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i][0].equals(startAt)) start = i;
        show(start);
        Updates.listen(updatesChanged);
    }

    @Override
    View firstFocus() {
        return sideRows.get(Math.max(0, section));
    }

    /** Into the rows: the row Settings was asked to open on (the first time only), else the first. The scroll
     *  follows the focused row by itself. */
    void focusRows() {
        if (rows.isEmpty()) return;
        View target = rows.get(0);
        if (pendingRow != null && section >= 0 && SECTIONS[section][0].equals(startAt)) {
            for (View r : rows) if (pendingRow.equals(r.getContentDescription())) target = r;
        }
        pendingRow = null;
        target.requestFocus();
    }

    @Override
    boolean onBack() {
        if (search.hasFocus() && !query.isEmpty()) {
            search.setText("");
            return true;
        }
        for (View r : rows) if (r.hasFocus()) {
            if (!query.isEmpty()) search.requestFocus(); else sideRows.get(section).requestFocus();
            return true;
        }
        return false;
    }

    @Override
    void onClosed() {
        hideKeyboard();
        h.removeCallbacksAndMessages(null);
        Updates.unlisten(updatesChanged);
    }

    private void hideKeyboard() {
        InputMethodManager imm = c.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    @Override
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int dir = k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            for (View r : rows) {
                if (!r.hasFocus()) continue;
                Object tag = r.getTag();
                if (tag instanceof Stepper) {
                    ((Stepper) tag).step(dir);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (!query.isEmpty()) search.requestFocus(); else sideRows.get(section).requestFocus();
                    return true;
                }
                return true;   // → on a link or a fact: nothing to the right
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                for (View s : sideRows) if (s.hasFocus()) { focusRows(); return true; }
                if (search.hasFocus() && search.getSelectionEnd() >= search.length() && !rows.isEmpty()) {
                    rows.get(0).requestFocus();
                    return true;
                }
            }
        }
        // ↑ from the first row goes up to the search field (nothing is above it on the right)
        if (k == KeyEvent.KEYCODE_DPAD_UP && !rows.isEmpty() && rows.get(0).hasFocus()) {
            search.requestFocus();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN && search.hasFocus() && !query.isEmpty() && !rows.isEmpty()) {
            hideKeyboard();
            rows.get(0).requestFocus();
            return true;
        }
        return false;
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    private interface Pick {
        void apply(int i);
    }

    /** Anything that ← → changes: a choice, a gallery, a level. */
    private interface Stepper {
        void step(int d);
    }

    private final class Choice implements Stepper {
        final String[] opts;
        final Pick pick;
        final LinearLayout chips;
        int index;

        Choice(String[] opts, int index, Pick pick) {
            this.opts = opts;
            this.index = index;
            this.pick = pick;
            chips = Ui.row(c);
            chips.setPadding(Ui.px(3), Ui.px(3), Ui.px(3), Ui.px(3));
            chips.setBackground(Ui.fillRound(t.night ? Ui.a(0x000000, .22f) : Ui.a(0x16181D, .06f), 10));
            for (String o : opts) {
                TextView ch = Ui.line(c, 13, t.ink2, 400, o);
                ch.setGravity(Gravity.CENTER);
                ch.setPadding(Ui.px(12), 0, Ui.px(12), 0);
                chips.addView(ch, Ui.ll(-1, Ui.boxf(26, 13)));
            }
            paint();
        }

        @Override
        public void step(int d) {
            int i = Math.max(0, Math.min(opts.length - 1, index + d));
            if (i == index) {
                Sound.bump();
                return;
            }
            index = i;
            paint();
            Sound.step();
            pick.apply(i);
        }

        void set(int i) {
            index = i;
            paint();
        }

        void paint() {
            for (int k = 0; k < chips.getChildCount(); k++) {
                TextView ch = (TextView) chips.getChildAt(k);
                boolean on = k == index;
                android.graphics.drawable.GradientDrawable chip = Ui.fillRound(t.night ? 0xFF55555F : 0xFFFFFFFF, 7);
                ch.setBackground(on ? chip : null);
                ch.setElevation(on ? Ui.pxf(1.5f) : 0);
                ch.setTextColor(on ? t.ink : t.ink2);
                ch.setTypeface(Ui.face(c, on ? 600 : 400));
            }
        }
    }

    /** A level (volume, brightness): the value and a thin bar, ← → step. */
    private final class Level implements Stepper {
        final TextView value;
        final View bar;
        final java.util.function.IntUnaryOperator step;   // dir → the new value (0–100), or -1 at the end

        Level(int now, java.util.function.IntUnaryOperator step) {
            this.step = step;
            value = Ui.line(c, 15, t.ink2, 500, now + "");
            bar = new View(c);
            set(now);
        }

        @Override
        public void step(int d) {
            int v = step.applyAsInt(d);
            if (v < 0) {
                Sound.bump();
                return;
            }
            Sound.step();
            set(v);
        }

        void set(int v) {
            value.setText(String.valueOf(v));
            bar.setPivotX(0);
            bar.setScaleX(Math.max(0.02f, v / 100f));
        }
    }

    private LinearLayout shell(String label, String sub) {
        if (group.getChildCount() > 0) {
            View hair = new View(c);
            hair.setBackgroundColor(t.hair);
            LinearLayout.LayoutParams hl = Ui.ll(-2, 1);
            hl.leftMargin = Ui.px(16);
            hl.rightMargin = Ui.px(16);
            group.addView(hair, hl);
        }
        LinearLayout r = Ui.row(c);
        r.setPadding(Ui.px(16), Ui.px(12), Ui.px(16), Ui.px(12));
        r.setMinimumHeight(Ui.px(54));
        r.setFocusable(true);
        r.setClickable(true);
        r.setContentDescription(label);
        LinearLayout words = Ui.column(c);
        words.addView(Ui.line(c, 15, t.ink, 500, label));
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(c, 13, t.ink3, 400);
            s.setText(sub);
            s.setMaxLines(2);
            words.addView(s, Ui.ll(-1, -1, 0, 3));
        }
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.rightMargin = Ui.px(16);
        r.addView(words, wl);
        Ui.focusRing(r, 12);
        Ui.lift(r, 1f, t.night ? 2 : 6);
        r.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        group.addView(r, Ui.ll(-2, -1));
        rows.add(r);
        if (indexing) index.add(new String[]{String.valueOf(indexSection), label, sub == null ? "" : sub});
        return r;
    }

    private Choice choice(String label, String sub, String[] opts, int index, Pick pick) {
        LinearLayout r = shell(label, sub);
        Choice ch = new Choice(opts, index, pick);
        r.addView(ch.chips);
        r.setTag(ch);
        return ch;
    }

    private void onOff(String label, String sub, boolean on, Pick pick) {
        choice(label, sub, new String[]{"On", "Off"}, on ? 0 : 1, pick);
    }

    private Level level(String label, String sub, int now, java.util.function.IntUnaryOperator step) {
        LinearLayout r = shell(label, sub);
        Level lv = new Level(now, step);
        FrameLayout track = new FrameLayout(c);
        track.setBackground(Ui.fillRound(t.night ? Ui.a(0xFFFFFF, .12f) : Ui.a(0x16181D, .10f), 3));
        lv.bar.setBackground(Ui.fillRound(t.ink, 3));
        track.addView(lv.bar, new FrameLayout.LayoutParams(-1, -1));
        track.setClipToOutline(true);
        r.addView(track, Ui.ll(150, 6));
        lv.value.setGravity(Gravity.END);
        r.addView(lv.value, Ui.ll(44, -1, 10, 0));
        r.addView(glyph(c, "chev_l", t.ink3), Ui.ll(14, 14, 8, 0));
        r.addView(glyph(c, "chev_r", t.ink3), Ui.ll(14, 14, 2, 0));
        r.setTag(lv);
        return lv;
    }

    private void link(String label, String sub, String value, Runnable go) {
        LinearLayout r = shell(label, sub);
        if (value != null && !value.isEmpty()) r.addView(Ui.line(c, 15, t.ink2, 400, value));
        r.addView(glyph(c, "chev_r", t.ink3), Ui.ll(16, 16, 6, 0));
        r.setOnClickListener(x -> {
            if (indexing) return;
            Sound.pop();
            go.run();
        });
    }

    private void info(String label, String value) {
        LinearLayout r = shell(label, null);
        r.addView(Ui.line(c, 15, t.ink2, 400, value));
    }

    /** Pictures in a row (backgrounds, screen savers): ← → choose, the chosen one marked. */
    private final class Gallery implements Stepper {
        final String[][] items;
        final Pick pick;
        final List<FrameLayout> cards = new ArrayList<>();
        final TextView name;
        int index;

        Gallery(LinearLayout row, String[][] items, int index, Pick pick, java.util.function.Function<String, Bitmap> thumbs) {
            this.items = items;
            this.index = index;
            this.pick = pick;
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.START);
            LinearLayout strip = Ui.row(c);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            // every card in view at every Text size (the column is narrower when the sections' column is wider)
            final float cardW = Math.min(100, (W - SB - 80 - 8 * (items.length - 1)) / items.length);
            for (int i = 0; i < items.length; i++) {
                FrameLayout card = new FrameLayout(c);
                ImageView img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(0xFF000000);
                card.addView(img, Ui.fill());
                card.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(10));
                    }
                });
                card.setClipToOutline(true);
                final String id = items[i][0];
                if ("photos".equals(id)) Audit.secret(card);   // someone's own photo
                if ("night".equals(id)) {   // the Night saver: a dim clock on black
                    TextView dim = Ui.line(c, 18, 0x66FFB37A, 300, "21:40");
                    dim.setGravity(Gravity.CENTER);
                    card.addView(dim, Ui.fill());
                } else if (!indexing) {   // (building the search index: no pictures needed)
                    Ui.BG.execute(() -> {
                        Bitmap b = thumbs == null ? null : thumbs.apply(id);
                        img.post(() -> img.setImageBitmap(b));
                    });
                }
                strip.addView(card, Ui.ll(cardW, cardW * 0.56f, i == 0 ? 0 : 8, 0));
                cards.add(card);
            }
            row.addView(strip, Ui.ll(-1, -1, 0, 10));
            name = Ui.line(c, 13, t.ink2, 500, "");
            row.addView(name, Ui.ll(-1, -1, 0, 8));
            paint();
        }

        @Override
        public void step(int d) {
            int i = Math.max(0, Math.min(items.length - 1, index + d));
            if (i == index) {
                Sound.bump();
                return;
            }
            index = i;
            paint();
            Sound.step();
            pick.apply(i);
        }

        void paint() {
            for (int k = 0; k < cards.size(); k++) {
                FrameLayout card = cards.get(k);
                boolean on = k == index;
                android.graphics.drawable.GradientDrawable edge = new android.graphics.drawable.GradientDrawable();
                edge.setCornerRadius(Ui.pxf(10));
                edge.setColor(0);
                edge.setStroke(Math.round(Ui.pxf(on ? 3 : 1)), on ? (t.night ? 0xFFFFFFFF : 0xFFFFFFFF) : Ui.a(0xFFFFFF, .25f));
                card.setForeground(edge);
                card.setElevation(on ? Ui.pxf(6) : Ui.pxf(1));
                card.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).alpha(on ? 1f : .78f).setDuration(Ui.ms(180)).setInterpolator(Ui.OUT).start();
            }
            name.setText(items[index][1] + (items[index].length > 2 ? " · " + items[index][2] : "") + "   ← →");
        }
    }

    private Gallery gallery(String label, String sub, String[][] items, int index, Pick pick, java.util.function.Function<String, Bitmap> thumbs) {
        LinearLayout r = shell(label, sub);
        LinearLayout col = Ui.column(c);
        // the words, then the pictures under them (the row stacks)
        View words = r.getChildAt(0);
        r.removeView(words);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.START);
        r.addView(words, new LinearLayout.LayoutParams(-1, -2));
        r.addView(col, new LinearLayout.LayoutParams(-1, -2));
        r.setClipChildren(false);
        col.setClipChildren(false);
        Gallery g = new Gallery(col, items, index, pick, thumbs);
        r.setTag(g);
        return g;
    }

    // ---- search ---------------------------------------------------------------------------------------------

    /** Every row of every section, once per opening (the sections are built off screen, nothing runs). */
    private void buildIndex() {
        if (!index.isEmpty()) return;
        LinearLayout keep = group;
        List<View> keepRows = new ArrayList<>(rows);
        indexing = true;
        for (int i = 0; i < SECTIONS.length; i++) {
            group = Ui.column(c);
            rows.clear();
            indexSection = i;
            try {
                fill(SECTIONS[i][0]);
            } catch (RuntimeException ignored) {
                // a section that can't be read right now: its rows just aren't found
            }
        }
        indexing = false;
        group = keep;
        rows.clear();
        rows.addAll(keepRows);
    }

    private static final String[][] ALSO = {
            {"wallpaper", "background"}, {"theme", "appearance"}, {"dark", "appearance"}, {"light mode", "appearance"},
            {"night mode", "appearance"}, {"font", "text size"}, {"wifi", "wi-fi"}, {"internet", "wi-fi"}, {"bt", "bluetooth"},
            {"headphones", "bluetooth"}, {"speaker", "bluetooth"}, {"remote", "pair"}, {"saver", "screen saver"},
            {"screensaver", "screen saver"}, {"widget", "widgets"}, {"clock", "widgets"}, {"weather", "widgets"},
            {"launcher", "home screen"}, {"original", "home screen"}, {"stock", "home screen"}, {"cast", "casting"},
            {"mirror", "casting"}, {"chromecast", "casting"}, {"airplay", "casting"}, {"typing", "keyboard"},
            {"gboard", "keyboard"}, {"update", "system update"}, {"reset", "storage & reset"}, {"factory", "storage & reset"},
            {"developer", "developer options"}, {"adb", "developer options"}, {"debugging", "developer options"},
            {"hdmi", "inputs"}, {"source", "inputs"}, {"timer", "sleep timer"}, {"brightness", "brightness"},
            {"backlight", "brightness"}, {"volume", "volume"}, {"language", "language"}, {"time zone", "date & time"},
            {"subtitles", "captions"}, {"uninstall", "manage apps"}, {"storage", "storage"}, {"memory", "memory"},
            {"phone", "phone"}, {"helper", "phone remote helper"}, {"animation", "wake animation"},
            {"upgrade", "check for updates"}, {"new version", "check for updates"}, {"version", "glass"},
            {"slow", "free up memory"}, {"lag", "free up memory"}, {"speed", "free up memory"}, {"fast", "free up memory"},
            {"bloat", "free up memory"}, {"ram", "free up memory"}, {"background", "in the background"}};

    private void find(String q) {
        query = q.trim().toLowerCase(Locale.getDefault());
        if (query.isEmpty()) {
            int s = Math.max(0, section);
            section = -1;
            show(s);
            return;
        }
        buildIndex();
        List<String> terms = new ArrayList<>();
        terms.add(query);
        for (String[] a2 : ALSO) if (a2[0].startsWith(query) || query.startsWith(a2[0])) terms.add(a2[1]);
        heading.setText("“" + q.trim() + "”");
        group.removeAllViews();
        rows.clear();
        scroll.scrollTo(0, 0);
        int found = 0;
        for (String[] e : index) {
            int sec = Integer.parseInt(e[0]);
            String hay = (e[1] + " " + e[2] + " " + SECTIONS[sec][2]).toLowerCase(Locale.getDefault());
            boolean hit = false;
            for (String term : terms) if (hay.contains(term)) hit = true;
            if (!hit || found >= 24) continue;
            found++;
            final String label = e[1];
            link(label, SECTIONS[sec][2] + (e[2].isEmpty() ? "" : " · " + e[2]), null, () -> {
                search.setText("");
                hideKeyboard();
                section = -1;
                show(sec);
                for (View r : rows) {
                    if (label.equals(r.getContentDescription())) {
                        r.requestFocus();
                        return;
                    }
                }
                focusRows();
            });
        }
        if (found == 0) {
            TextView none = Ui.text(c, 15, t.ink3, 400);
            none.setText("Nothing called that. Try “sound”, “wallpaper”, “bluetooth”, “keyboard”…");
            none.setPadding(Ui.px(18), Ui.px(18), Ui.px(18), Ui.px(18));
            group.addView(none);
        }
        for (int k = 0; k < sideRows.size(); k++) paintSide(k, false);
    }

    // ---- sections -------------------------------------------------------------------------------------------

    private void paintSide(int k, boolean cur) {
        LinearLayout r = (LinearLayout) sideRows.get(k);
        if (cur) {
            android.graphics.drawable.GradientDrawable g = Ui.fillRound(t.night ? Ui.a(0xFFFFFF, .09f) : Ui.a(0xFFFFFF, .60f), 10);
            g.setStroke(Math.max(1, Ui.px(1)), t.hair);
            Ui.rest(r, g);
        } else {
            Ui.rest(r, null);
        }
        ((TextView) r.getChildAt(1)).setTypeface(Ui.face(c, cur ? 600 : 400));
    }

    private void show(int i) {
        if (i == section && query.isEmpty()) return;
        section = i;
        for (int k = 0; k < sideRows.size(); k++) paintSide(k, k == i);
        heading.setText(SECTIONS[i][2]);
        group.removeAllViews();
        rows.clear();
        scroll.scrollTo(0, 0);
        fill(SECTIONS[i][0]);
    }

    private void again(String row) {
        int s = section;
        section = -1;
        show(s);
        if (row != null) for (View r : rows) if (row.equals(r.getContentDescription())) { r.requestFocus(); return; }
    }

    private void toast(String s) {
        if (!indexing) Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    private void fill(String key) {
        ContentResolver cr = c.getContentResolver();
        SharedPreferences g = Glass.prefs(c);
        switch (key) {
            case "display": {
                String[] looks = {"auto", "light", "dark"};
                String cur = g.getString(GlassHomeActivity.APPEARANCE, "auto");
                int li = 0;
                for (int k = 0; k < looks.length; k++) if (looks[k].equals(cur)) li = k;
                choice("Appearance", "Auto follows the time of day: light by day, dark from 6 pm", new String[]{"Auto", "Light", "Dark"}, li, k -> {
                    g.edit().putString(GlassHomeActivity.APPEARANCE, looks[k]).apply();
                    a.lookChanged("settings:display:Appearance");
                });
                if (Backlight.works(c)) {
                    level("Brightness", Backlight.about(c), Backlight.get(c), d -> Backlight.step(c, d) ? Backlight.get(c) : -1);
                }
                float[] sizes = {0.85f, 1f, 1.15f, 1.3f};
                float now = Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1f);
                int si = 0;
                for (int k = 1; k < sizes.length; k++) if (Math.abs(sizes[k] - now) < Math.abs(sizes[si] - now)) si = k;
                choice("Text size", "Every app, not just Glass", new String[]{"S", "M", "L", "XL"}, si, k -> {
                    try { Settings.System.putFloat(cr, Settings.System.FONT_SCALE, sizes[k]); } catch (RuntimeException ignored) { }
                    a.fontScaleChanged();
                });
                choice("Motion", "How much things move", new String[]{"Full", "Reduced"}, g.getBoolean(GlassHomeActivity.REDUCED, false) ? 1 : 0, k -> {
                    g.edit().putBoolean(GlassHomeActivity.REDUCED, k == 1).apply();
                    Ui.reduced = k == 1;
                });
                onOff("Wake animation", "The glass and its sound when the TV comes on", g.getBoolean(Wake.PREF, true),
                        k -> g.edit().putBoolean(Wake.PREF, k == 0).apply());
                link("Show it now", "Plays the wake animation", null, () -> {
                    a.dismiss(this);
                    GlassKeys.start(c, new Intent(c, GlassHomeActivity.class).putExtra(GlassHomeActivity.EXTRA_OPEN, "wake"));
                });
                break;
            }
            case "looks": {
                // Light and Dark each keep their own: the pictures below are chosen for the look picked here
                final boolean edit = looksFor != null ? looksFor : t.night;
                choice("Choosing for", "Light and Dark each keep their own background and screen saver", new String[]{"Light", "Dark"},
                        edit ? 1 : 0, k -> {
                            looksFor = k == 1;
                            h.post(() -> again("Choosing for"));
                        });
                gallery("Background", edit ? "Dark's background (its night light)" : "Light's background (its day light)", Looks.WALLS,
                        Looks.index(Looks.WALLS, Looks.wall(c, edit)), k -> {
                            Looks.setWall(c, edit, Looks.WALLS[k][0]);
                            if (edit == t.night) {   // the look on screen: the desktop follows
                                h.removeCallbacks(applyLook);
                                h.postDelayed(applyLook, 900);
                            }
                        }, id -> Looks.thumb(c, id, edit));
                gallery("Screen saver", edit ? "Dark's screen saver" : "Light's screen saver", Looks.SAVERS, Looks.index(Looks.SAVERS, Looks.saver(c, edit)),
                        k -> Looks.setSaver(c, edit, Looks.SAVERS[k][0]), id -> Looks.saverThumb(c, id, edit));
                link("Try the screen saver", "Any key ends it", null, () -> GlassKeys.start(c, new Intent(c, ClockActivity.class).putExtra(ClockActivity.PREVIEW, true)));
                int[] mins = {5, 10, 15, 30, 60};
                int curOff = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 15 * 60 * 1000), mi = 2;
                for (int k = 0; k < mins.length; k++) if (Math.abs(mins[k] * 60_000 - curOff) < 30_000) mi = k;
                choice("Screen saver starts after", "With no remote press", new String[]{"5m", "10m", "15m", "30m", "1h"}, mi, k -> {
                    try {
                        Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, mins[k] * 60_000);
                    } catch (RuntimeException e) {
                        toast("The TV wouldn't change this");
                    }
                });
                for (String[] w : Widgets.ALL) {
                    onOff(w[1] + " on the desktop", w[2], Widgets.on(c, "desk", w[0]), k -> {
                        Widgets.set(c, "desk", w[0], k == 0);
                        h.removeCallbacks(applyWidgets);
                        h.postDelayed(applyWidgets, 700);
                    });
                }
                link("Weather location", "Where the weather widget's forecast is for", Widgets.Weather.placeName(c) != null ? Widgets.Weather.placeName(c) : "Automatic", () -> {
                    a.dismiss(this);
                    a.show(new WeatherSheet(a, true), null);
                });
                for (String[] w : Widgets.ALL) {
                    if ("clock".equals(w[0])) continue;   // every screen saver shows the time
                    onOff(w[1] + " on the screen saver", w[2], Widgets.on(c, "saver", w[0]), k -> Widgets.set(c, "saver", w[0], k == 0));
                }
                break;
            }
            case "sound": {
                AudioManager am = c.getSystemService(AudioManager.class);
                if (am != null) {
                    int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    level("Volume", "Hold the remote's volume key to go faster", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max, d -> {
                        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int next = Math.max(0, Math.min(max, v + d * Math.max(1, max / 50)));
                        if (next == v) return -1;
                        GlassKeys.ownChange();
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                        return next * 100 / max;
                    });
                }
                choice("Sounds", "Soft clicks as you move and open things", new String[]{"On", "Off"}, Sound.enabled() ? 0 : 1,
                        k -> Sound.setEnabled(c, k == 0));
                int[] v = {0, 10, 12, 15, 20};
                int cur = g.getInt(Glass.NIGHT_CEILING, 0), ci = 0;
                for (int k = 0; k < v.length; k++) if (v[k] == cur) ci = k;
                choice("Night volume", "After 11 pm the volume stops here", new String[]{"Off", "10", "12", "15", "20"}, ci,
                        k -> g.edit().putInt(Glass.NIGHT_CEILING, v[k]).apply());
                link("Where the sound plays", "TV speakers, headphones, a speaker", null, () -> {
                    a.dismiss(this);
                    a.show(new ControlsSheet(a, "sound", "controls"), null);
                });
                nativeLink("TV sound", "Sound modes, outputs, surround", Settings.ACTION_SOUND_SETTINGS);
                break;
            }
            case "network": {
                boolean online = Glass.online(c), connected = Glass.connected(c);
                String ssid = Net.current(c);
                info("Internet", online ? (ssid != null ? "Connected · " + ssid : "Connected") : connected ? "No internet" : "Offline");
                link("Wi-Fi", "Choose a network, join with a password", Net.on(c) ? "On" : "Off", () -> {
                    a.dismiss(this);
                    a.show(new ControlsSheet(a, "wifi", "controls"), null);
                });
                choice("Bluetooth", "Headphones, speakers, gamepads, the remote", new String[]{"On", "Off"}, Bt.on() ? 0 : 1, k -> {
                    boolean want = k == 0;
                    if (!want && Bt.remoteConnected() && SystemClock.uptimeMillis() - btOffAsked > 4000) {
                        btOffAsked = SystemClock.uptimeMillis();
                        toast("The remote uses Bluetooth. Press → again to turn it off (the phone and the TV's buttons still work).");
                        h.post(() -> again("Bluetooth"));
                        return;
                    }
                    btOffAsked = 0;
                    if (!Bt.set(want)) toast("The TV wouldn't change Bluetooth just now");
                });
                link("Bluetooth devices", "Connect headphones or a speaker", null, () -> {
                    a.dismiss(this);
                    a.show(new ControlsSheet(a, "bluetooth", "controls"), null);
                });
                nativeLink("Pair a remote or accessory", "The TV's own pairing", SystemSettings.PAIR);
                nativeLink("Network settings", "Ethernet, proxy, IP settings", Settings.ACTION_WIRELESS_SETTINGS);
                break;
            }
            case "inputs": {
                List<GlassInputs.Input> ins = GlassInputs.list(c);
                if (ins.isEmpty()) info("Inputs", "None found");
                for (GlassInputs.Input in : ins) {
                    String state = in.on ? "On" : in.empty ? "Nothing connected" : null;
                    link(in.title, in.title.equals(in.port) ? state : in.port + (state == null ? "" : " · " + state), "Switch",
                            () -> GlassInputs.open(c, in.id));
                }
                break;
            }
            case "apps": {
                link("All apps", "Every app on this TV", null, () -> a.openApps());
                int open = Running.quick(c).size();
                link("Close apps in the background", open == 0 ? "None open" : open + " open: frees memory for the one you use",
                        null, () -> {
                            for (String p : Running.quick(c)) Running.close(c, p, null);
                            toast("Closed");
                            h.postDelayed(() -> { a.runningChanged(); again("Close apps in the background"); }, 600);
                        });
                link("Dock", "Hold OK on an app to move, close or remove it", "Reset", () -> a.resetDock());
                nativeLink("Manage apps", "Storage, permissions, uninstall", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                break;
            }
            case "speed": {
                ActivityManager am = c.getSystemService(ActivityManager.class);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                if (am != null) am.getMemoryInfo(mi);
                link("Free up memory", "Closes the apps waiting in the background. " + (mi.availMem >> 20) + " MB of "
                        + (mi.totalMem >> 20) + " MB free now", null, this::freeMemory);
                float scale = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
                choice("Animations", "How quickly the TV's windows open and close. Glass's own motion stays as it is",
                        new String[]{"Normal", "Quick"}, scale < 0.75f ? 1 : 0, k -> setAnimations(k == 1 ? 0.5f : 1f));
                // apps that may keep running after you leave them (each holds memory the one you watch could use)
                List<String[]> apps = backgroundApps();
                if (!apps.isEmpty()) subhead("Apps in the background");
                if (!indexing) loadBackground(apps);
                for (String[] app : apps) {
                    final String pkg = app[0];
                    Boolean may = BG.get(pkg);
                    boolean on = may == null || may;
                    choice(app[1], on ? "Keeps running in the background after you leave it" : "Runs only while it's open",
                            new String[]{"In background", "Only open"}, on ? 0 : 1, k -> setBackground(pkg, k == 0));
                }
                // the TV's own extras: off frees their memory and the TV's start (never uninstalled; restore brings them back)
                boolean any = false;
                for (String[] e : Extras.ALL) {
                    if (!installed(e[0])) continue;
                    if (!any) subhead("The TV's extras");
                    any = true;
                    final String pkg = e[0];
                    onOff(e[1], e[2], enabled(pkg), k -> setExtra(pkg, e[1], k == 0));
                }
                break;
            }
            case "remote": {
                List<String[]> kb = keyboards();
                String curIme = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
                int ki = 0;
                String[] names = new String[kb.size()];
                for (int k = 0; k < kb.size(); k++) {
                    names[k] = kb.get(k)[1];
                    if (kb.get(k)[0].equals(curIme)) ki = k;
                }
                if (kb.size() > 1) {
                    choice("Keyboard", "Glass's is made for the remote: big keys, rows that wrap, words as you type", names, ki,
                            k -> setKeyboard(kb.get(k)[0]));
                }
                int phones = Phones.list(c).size();
                link("Phone", "Your phone as a remote, a keyboard, a way to send photos", phones == 0 ? "Connect" : phones + " connected", () -> {
                    a.dismiss(this);
                    a.show(new PhoneSheet(a), null);
                });
                String ks = Keys.status();
                String kv = "ready".equals(ks) ? "Ready" : "asking".equals(ks) ? "Answer on the TV" : "declined".equals(ks) ? "Not allowed · OK to ask again"
                        : "off".equals(ks) ? "Needs network debugging" : "Starting…";
                link("Phone remote helper", "Lets a phone press keys inside apps, and Glass close apps. The TV asks once: tick “Always allow”, then OK.",
                        kv, () -> {
                            Keys.askAgain(c);
                            toast("If the TV asks “Allow USB debugging?” for glass@tv: tick Always allow, then OK");
                            h.postDelayed(() -> again("Phone remote helper"), 2500);
                        });
                break;
            }
            case "power": {
                int[] sleep = {0, 30, 60, 90, 120};
                int left = SleepTimer.minutesLeft(), slI = 0;
                for (int k = 0; k < sleep.length; k++) if (sleep[k] > 0 && left > 0 && Math.abs(sleep[k] - left) < 15) slI = k;
                choice("Sleep timer", "Turn off after", new String[]{"Off", "30m", "1h", "1½h", "2h"}, slI, k -> {
                    if (sleep[k] == 0) SleepTimer.cancel(); else SleepTimer.set(sleep[k]);
                });
                // Android's "sleep" setting: the TV goes off this long after the last remote press (the saver comes first)
                int[] idle = {60 * 60 * 1000, 2 * 60 * 60 * 1000, 4 * 60 * 60 * 1000, -1};
                int cur = Settings.Secure.getInt(cr, "sleep_timeout", -1), ii = 3;
                for (int k = 0; k < idle.length; k++) if (idle[k] == cur) ii = k;
                choice("Turn off when idle", "After this long with no remote press (the screen saver comes on first)",
                        new String[]{"1h", "2h", "4h", "Never"}, ii, k -> {
                            try {
                                Settings.Secure.putInt(cr, "sleep_timeout", idle[k]);
                            } catch (RuntimeException e) {
                                toast("The TV wouldn't change this");
                            }
                        });
                if (Glass.stockAvailable(c)) {
                    String[] v = {"ask", "glass", "original"};
                    String on = g.getString(Glass.ON_POWER, "ask");
                    int oi = 0;
                    for (int k = 0; k < v.length; k++) if (v[k].equals(on)) oi = k;
                    choice("On power-on", "Glass or the TV's original home", new String[]{"Ask", "Glass", "Original"}, oi, k ->
                            g.edit().putString(Glass.ON_POWER, v[k]).putInt(Glass.STREAK, 0)
                                    .putString(Glass.MODE, k == 2 ? "original" : k == 1 ? "glass" : g.getString(Glass.MODE, "glass")).apply());
                }
                break;
            }
            case "assistant": {
                boolean glass = Assist.owns(c);
                choice("Assistant button", "What the remote's assistant button opens", new String[]{"Glass", "Google"}, glass ? 0 : 1, k -> {
                    if (!Assist.set(c, k == 0)) toast("Needs the installer's permission");
                });
                boolean reads = Screen.reads(c);
                choice("Reads the screen", "When you ask, what's on screen goes with your question", new String[]{"On", "Off"}, reads ? 0 : 1, k ->
                        Glass.prefs(c).edit().putBoolean(Screen.PREF, k == 0).apply());
                link("Ask something", "Or press the assistant button", null, () -> a.ask(null));
                break;
            }
            case "glass": {
                link("Use the TV's original home", "Android TV as it shipped. Glass's tile in its apps row brings Glass back.", null, () -> {
                    List<Row> r = new ArrayList<>();
                    r.add(new Row("swap", "Switch to the original home", null, () -> Homes.toOriginal(c, (ok, why) -> {
                        if (!ok) toast("helper".equals(why) ? "First allow Glass's helper on the TV (Remote & keyboard ▸ Phone remote helper)"
                                : "The TV wouldn't switch just now");
                    })));
                    r.add(new Row("close", "Stay on Glass", null, null));
                    a.stack(new Menu(a, r, X + W - 340, Y + 160, 300, false), group.findFocus());
                });
                boolean casting = enabled(CASTING[0]);
                choice("Casting from phones", "Chromecast (the cast button in YouTube, Netflix, Chrome) and screen mirroring from Android phones. Uses some memory.",
                        new String[]{"On", "Off"}, casting ? 0 : 1, k -> setCasting(k == 0));
                link("Glass " + Updates.installedName(c, c.getPackageName()), "Updates are in About this TV", null, () -> {
                    section = -1;
                    for (int k = 0; k < SECTIONS.length; k++) if ("about".equals(SECTIONS[k][0])) show(k);
                    for (View r : rows) if ("Check for updates".equals(r.getContentDescription())) r.requestFocus();
                });
                break;
            }
            case "tv": {
                for (String[] n : SystemSettings.NATIVE) {
                    if (SystemSettings.exact(c, n[2]) == null) continue;   // this TV doesn't have that page
                    nativeLink(n[0], n[1], n[2]);
                }
                break;
            }
            case "about": {
                updates();
                info("Model", (Build.MANUFACTURER + " " + Build.MODEL).trim());
                info("Android", Build.VERSION.RELEASE);
                try {
                    StatFs st = new StatFs(Environment.getDataDirectory().getPath());
                    info("Storage", Formatter.formatShortFileSize(c, st.getAvailableBytes()) + " free of " + Formatter.formatShortFileSize(c, st.getTotalBytes()));
                } catch (RuntimeException ignored) {
                }
                ActivityManager am = c.getSystemService(ActivityManager.class);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    info("Memory", Formatter.formatShortFileSize(c, mi.availMem) + " free of " + Formatter.formatShortFileSize(c, mi.totalMem));
                }
                nativeLink("Everything about this TV", "Status, legal, the TV's name", Settings.ACTION_DEVICE_INFO_SETTINGS);
                break;
            }
        }
    }

    /** One of the TV's own pages, exactly that page (never the settings' first screen instead). */
    private void nativeLink(String label, String sub, String action) {
        Intent exact = SystemSettings.exact(c, action);
        if (exact == null && !Settings.ACTION_WIRELESS_SETTINGS.equals(action)) return;
        link(label, sub, "TV", () -> {
            Intent i = exact != null ? exact : SystemSettings.intent(c, action);
            try {
                c.startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                toast("This TV doesn't have that page");
            }
        });
    }

    // ---- Glass's updates (About this TV) -----------------------------------------------------------------------

    private TextView updLine;   // the words under the update row, changed in place while it downloads
    private String updPhase;

    /** Glass's version, Check for updates, and when a newer Glass is out: Install, with what's new. */
    private void updates() {
        String ph = Updates.phase;
        Updates.Release r = Updates.found;
        boolean newer = r != null && r.newer();
        String as = Updates.installedName(c, Updates.ASSISTANT);
        info("Glass", Updates.installedName(c, c.getPackageName()) + (as != null ? "  ·  Assistant " + as : ""));
        String sub = !Updates.ready() ? "Updates come from the computer that set Glass up: ./glass update"
                : "checking".equals(ph) || "current".equals(ph) || "failed".equals(ph) && !newer ? Updates.text
                : "Glass looks for a new version only when you ask";
        link("Check for updates", sub, null, () -> Updates.check(c));
        TextView under = subOf(rows.get(rows.size() - 1));
        if (newer) {
            String notes = String.join("  ·  ", r.notes);
            boolean going = "downloading".equals(ph) || "installing".equals(ph) || "failed".equals(ph);
            link("Install Glass " + r.version, going ? Updates.text : notes.isEmpty() ? "Glass and the assistant, as one update" : notes,
                    Updates.size(r.bytes()), () -> Updates.install(c));
            under = subOf(rows.get(rows.size() - 1));
            if (under != null && !going) {   // what's new, in two lines at most (all of it is on the phone's TV tab)
                under.setEllipsize(android.text.TextUtils.TruncateAt.END);
                under.setTag("clip-ok");
            }
        }
        if (!indexing) {
            updLine = under;
            updPhase = ph;
        }
    }

    private static TextView subOf(View row) {
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildAt(0) instanceof LinearLayout) {
            LinearLayout w = (LinearLayout) ((LinearLayout) row).getChildAt(0);
            if (w.getChildCount() > 1 && w.getChildAt(1) instanceof TextView) return (TextView) w.getChildAt(1);
        }
        return null;
    }

    /** The update moved on: its words change in place; a new step (found, installing, done) redraws the rows. */
    private final Runnable updatesChanged = () -> {
        if (closing || section < 0 || !"about".equals(SECTIONS[section][0]) || !query.isEmpty()) return;
        String ph = Updates.phase;
        if (ph.equals(updPhase) && updLine != null && updLine.isAttachedToWindow()) {
            updLine.setText(Updates.text);
            return;
        }
        String keep = null;
        for (View r : rows) if (r.hasFocus() && r.getContentDescription() != null) keep = r.getContentDescription().toString();
        int s = section;
        section = -1;
        show(s);
        if (keep == null) return;   // the sections (or the search) had the focus: they keep it
        View to = null, check = null, install = null;
        for (View r : rows) {
            String d = String.valueOf(r.getContentDescription());
            if (d.equals(keep)) to = r;
            if (d.equals("Check for updates")) check = r;
            if (d.startsWith("Install Glass")) install = r;
        }
        if ("available".equals(ph) && "Check for updates".equals(keep) && install != null) to = install;   // found one: OK installs it
        if (to == null) to = check;
        if (to != null) to.requestFocus();
    };

    private final Runnable applyLook = () -> a.lookChanged("settings:looks:Background");
    private Boolean looksFor;   // Backgrounds & savers: choosing for Light (false) or Dark (true); null: the look on screen
    private final Runnable applyWidgets = () -> a.widgetsChanged();

    private String focusedLabel() {
        for (View r : rows) if (r.hasFocus() && r.getContentDescription() != null) return r.getContentDescription().toString();
        return "Background";
    }

    // ---- speed & memory ---------------------------------------------------------------------------------------

    /** Whether each app may run in the background, as the TV's key helper last said (Android's own switch). */
    private static final java.util.Map<String, Boolean> BG = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean bgLoading;

    /** A quiet label between rows (not focusable). */
    private void subhead(String text) {
        TextView t1 = Ui.line(c, 13, t.ink3, 500, text);
        t1.setPadding(Ui.px(16), Ui.px(group.getChildCount() == 0 ? 12 : 18), Ui.px(16), Ui.px(4));
        group.addView(t1, Ui.ll(-2, -1));
    }

    /** {package, name} of the apps on the TV's apps row that may be kept from the background (not Android's, Google's
     *  services or assistant, or Glass), by name. */
    private List<String[]> backgroundApps() {
        android.content.pm.PackageManager pm = c.getPackageManager();
        java.util.TreeMap<String, String[]> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (Extras.backgroundProtected(pkg) || Extras.known(pkg)) continue;
            String name = String.valueOf(ri.loadLabel(pm));
            byName.put(name + " " + pkg, new String[]{pkg, name});
        }
        return new ArrayList<>(byName.values());
    }

    /** What the helper says about each app, once per Glass start; the section follows when it's what you're in. */
    private void loadBackground(List<String[]> apps) {
        boolean all = true;
        for (String[] a1 : apps) if (!BG.containsKey(a1[0])) all = false;
        if (all || bgLoading || !"ready".equals(Keys.status())) return;
        bgLoading = true;
        new Thread(() -> {
            boolean changed = false;
            try {
                for (String[] a1 : apps) {
                    Boolean may = Keys.background(c, a1[0]);
                    if (may == null) break;
                    if (!may.equals(BG.put(a1[0], may))) changed = true;
                }
            } finally {
                bgLoading = false;
            }
            if (!changed) return;
            h.post(() -> {
                // shown again only while the sections column has the focus: never pulled from under a row you're on
                View f = root.findFocus();
                if (section >= 0 && "speed".equals(SECTIONS[section][0]) && (f == null || !isInside(f, group))) {
                    int s1 = section;
                    section = -1;
                    show(s1);
                }
            });
        }, "glass-speed").start();
    }

    private static boolean isInside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private boolean installed(String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The helper must be up for these: it asks the TV once, and the row goes back to how it was meanwhile. */
    private boolean helperUp(String row) {
        if ("ready".equals(Keys.status())) return true;
        Keys.askAgain(c);
        toast("First allow Glass's helper on the TV (Remote & keyboard ▸ Phone remote helper)");
        h.post(() -> again(row));
        return false;
    }

    private void freeMemory() {
        if (!helperUp("Free up memory")) return;
        final ActivityManager am = c.getSystemService(ActivityManager.class);
        new Thread(() -> {
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo(), after = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(before);
            boolean ok = Keys.freeMemory(c);
            SystemClock.sleep(1500);
            if (am != null) am.getMemoryInfo(after);
            long freed = Math.max(0, (after.availMem - before.availMem) >> 20);
            h.post(() -> {
                toast(!ok ? "The TV wouldn't close its apps just now"
                        : freed > 0 ? "Freed " + freed + " MB: " + (after.availMem >> 20) + " MB free" : "Nothing was waiting in the background");
                again("Free up memory");
            });
        }, "glass-free").start();
    }

    private void setAnimations(float scale) {
        try {
            Settings.Global.putFloat(c.getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE, scale);
            Settings.Global.putFloat(c.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
        } catch (RuntimeException e) {
            toast("Needs the installer's permission");
            h.post(() -> again("Animations"));
        }
    }

    private void setBackground(String pkg, boolean allow) {
        String row = null;
        for (String[] a1 : backgroundApps()) if (a1[0].equals(pkg)) row = a1[1];
        if (!helperUp(row)) return;
        final String label = row;
        new Thread(() -> {
            boolean ok = Keys.setBackground(c, pkg, allow);
            if (ok) BG.put(pkg, allow);
            h.post(() -> {
                if (!ok) toast("The TV wouldn't change that just now");
                again(label);
            });
        }, "glass-speed").start();
    }

    private void setExtra(String pkg, String name, boolean on) {
        if (!helperUp(name)) return;
        new Thread(() -> {
            boolean ok = Keys.enable(c, pkg, on);
            h.post(() -> {
                toast(!ok ? "The TV wouldn't change " + name + " just now" : name + (on ? " is on" : " is off"));
                again(name);
            });
        }, "glass-speed").start();
    }

    // ---- keyboard, casting ------------------------------------------------------------------------------------

    /** {id, name} for Glass's keyboard and the TV's others (Gboard first). */
    private List<String[]> keyboards() {
        List<String[]> out = new ArrayList<>();
        InputMethodManager imm = c.getSystemService(InputMethodManager.class);
        if (imm == null) return out;
        for (android.view.inputmethod.InputMethodInfo im : imm.getInputMethodList()) {
            String id = im.getId();
            String name = GLASS_IME.equals(id) ? "Glass" : id.equals(GBOARD) ? "Gboard" : String.valueOf(im.loadLabel(c.getPackageManager()));
            name = shortName(name);
            if (GLASS_IME.equals(id)) out.add(0, new String[]{id, name}); else out.add(new String[]{id, name});
        }
        return out;
    }

    /** A keyboard's name short enough for a choice chip, without cutting a word: "Virtual Remote Keyboard" → "Virtual Remote". */
    static String shortName(String name) {
        String n = name.trim().replaceAll("(?i)\\s+(keyboard|input|input method|ime|service)$", "").trim();
        if (n.length() <= 15) return n;
        StringBuilder b = new StringBuilder();
        for (String w : n.split("\\s+")) {
            if (b.length() + w.length() + (b.length() > 0 ? 1 : 0) > 15) break;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.length() > 0 ? b.toString() : n.substring(0, 15);
    }

    private void setKeyboard(String id) {
        ContentResolver cr = c.getContentResolver();
        try {
            String on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
            if (on == null) on = "";
            if (!(":" + on + ":").contains(":" + id + ":")) on = on.isEmpty() ? id : on + ":" + id;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, on);
            Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, id);
        } catch (RuntimeException e) {
            toast("Needs the installer's permission");
        }
    }

    private boolean enabled(String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    private void setCasting(boolean on) {
        if (!"ready".equals(Keys.status())) {
            Keys.askAgain(c);
            toast("First allow Glass's helper on the TV (Remote & keyboard ▸ Phone remote helper)");
            h.post(() -> again("Casting from phones"));
            return;
        }
        new Thread(() -> {
            boolean ok = true;
            for (String p : CASTING) {
                try {
                    c.getPackageManager().getApplicationInfo(p, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                } catch (Exception missing) {
                    continue;
                }
                ok &= Keys.enable(c, p, on);
            }
            final boolean done = ok;
            h.post(() -> toast(done ? (on ? "Casting is on: look for this TV in your phone's cast button" : "Casting is off") : "The TV wouldn't change casting just now"));
        }, "glass-casting").start();
    }
}
