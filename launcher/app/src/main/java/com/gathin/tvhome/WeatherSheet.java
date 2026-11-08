package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The weather, and where it's for. The top says what it's like now and the next days; under it, the place: the town
 * you choose (type its name, with the remote's keyboard or your phone, and pick it from what's found), or
 * Automatic (the town of the TV's internet address, which can be a city away). Opened from the weather widget.
 */
final class WeatherSheet extends Sheet {
    static final float W = 640, H = 500;
    private final boolean searchFirst;
    private final Handler h = new Handler(Looper.getMainLooper());
    private EditText field;
    private LinearLayout results, forecast;
    private TextView status, placeLine;
    private final List<View> rows = new ArrayList<>();
    private Glide glide;
    private int asked;

    WeatherSheet(Desk a, boolean searchFirst) {
        super(a);
        this.searchFirst = searchFirst;
        window = true;
        ox = 640;
        oy = 360;
    }

    @Override
    void build() {
        float x = (1280 - W) / 2, y = (720 - H) / 2 + 8;
        pane = new Pane(c, true, x, y, W, H, Ui.PANEL_R);
        pane.overApp(a.overApp());
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.px(28), Ui.px(24), Ui.px(28), Ui.px(20));
        // now
        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = Ui.column(c);
        placeLine = Audit.secret(Ui.line(c, 22, t.ink, 600, "Weather"));
        words.addView(placeLine);
        status = Ui.line(c, 14, t.ink2, 500, "");
        words.addView(status, Ui.ll(-1, -1, 0, 4));
        head.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(head, Ui.ll(-2, -1));
        forecast = Ui.row(c);
        col.addView(forecast, Ui.ll(-2, -1, 0, 14));
        View hair = new View(c);
        hair.setBackgroundColor(t.hair);
        col.addView(hair, Ui.ll(-2, 1, 0, 18));
        TextView where = Ui.line(c, 13, t.ink3, 600, "LOCATION");
        where.setLetterSpacing(0.06f);
        col.addView(where, Ui.ll(-1, -1, 0, 16));
        field = Ui.field(c, 16, "Type a town or city", 12, true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int n) { }
            @Override public void afterTextChanged(Editable e) {
                h.removeCallbacks(look);
                h.postDelayed(look, 450);
            }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            h.removeCallbacks(look);
            look.run();
            hideKeyboard();
            if (rows.size() > 1) rows.get(1).requestFocus();
            return true;
        });
        col.addView(field, Ui.ll(-2, Ui.boxf(44, 16), 0, 10));
        ScrollView sc = new ScrollView(c);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        sc.setPadding(0, Ui.px(8), 0, Ui.px(8));
        FrameLayout frame = new FrameLayout(c);
        glide = new Glide(c, t.focus, 0, 12, 0);
        frame.addView(glide, Ui.fill());
        results = Audit.secret(Ui.column(c));
        frame.addView(results, new FrameLayout.LayoutParams(-1, -2));
        sc.addView(frame);
        col.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        pane.addView(col, Ui.fill());
        content = col;
        root.addView(pane, pane.params());
        pane.bake(c);
        showNow();
        list(null);
        if (Widgets.Weather.now(c) == null) Widgets.Weather.fetch(c.getApplicationContext(), ok -> { if (!closing) showNow(); });
    }

    @Override
    View firstFocus() {
        return searchFirst || rows.isEmpty() ? field : rows.get(0);
    }

    private void showNow() {
        Widgets.Weather.Now w = Widgets.Weather.now(c);
        String chosen = Widgets.Weather.placeName(c);
        if (w == null) {
            placeLine.setText(chosen != null ? chosen : "Weather");
            status.setText(Glass.online(c) ? "Finding the weather…" : "Offline: the weather comes back with the internet");
            forecast.removeAllViews();
            return;
        }
        placeLine.setText((w.place != null ? w.place : "Weather") + "  " + w.temp + "°");
        status.setText(w.text + "  ·  H:" + w.hi + "°  L:" + w.lo + "°" + (chosen == null ? "  ·  Automatic" : ""));
        forecast.removeAllViews();
        for (int i = 0; i < Math.min(5, w.days.size()); i++) {
            Widgets.Weather.Day d = w.days.get(i);
            LinearLayout cell = Ui.column(c);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView n = Ui.line(c, 13, t.ink2, 600, d.label);
            n.setGravity(Gravity.CENTER);
            cell.addView(n, Ui.ll(-2, -1));
            ImageView g = new ImageView(c);
            g.setImageDrawable(Glyph.of(d.glyph, t.ink));
            cell.addView(g, Ui.ll(22, 22, 0, 8));
            TextView tt = Ui.line(c, 14, t.ink, 600, d.hi + "°  " + d.lo + "°");
            tt.setGravity(Gravity.CENTER);
            cell.addView(tt, Ui.ll(-2, -1, 0, 8));
            forecast.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
    }

    private final Runnable look = () -> {
        String q = field.getText().toString().trim();
        if (q.length() < 2) {
            list(null);
            return;
        }
        final int my = ++asked;
        Ui.BG.execute(() -> {
            List<String[]> found = Widgets.Weather.search(q);
            h.post(() -> {
                if (my == asked && !closing) list(found);
            });
        });
    };

    /** Automatic first, then what was found (or a word on why nothing is there). */
    private void list(List<String[]> found) {
        results.removeAllViews();
        rows.clear();
        boolean auto = Widgets.Weather.placeName(c) == null;
        row("location", "Automatic", "The town of the TV's internet address", auto, () -> choose(null));
        String chosen = Widgets.Weather.placeName(c);
        if (found == null && chosen != null) row("check", chosen, "Chosen", true, null);
        if (found != null && found.isEmpty()) {
            TextView none = Ui.text(c, 14, t.ink3, 400);
            none.setText(Glass.online(c) ? "No town called that. Try another spelling." : "Offline: searching needs the internet.");
            none.setPadding(Ui.px(14), Ui.px(12), Ui.px(14), Ui.px(4));
            results.addView(none);
        }
        if (found != null) for (String[] p : found) row(null, p[0], p[1], false, () -> choose(p));
    }

    private void row(String glyph, String title, String sub, boolean checked, Runnable act) {
        LinearLayout r = Sheet.row(c, t, glide, Ui.boxf(52, 15));
        r.setPadding(Ui.px(14), Ui.px(8), Ui.px(14), Ui.px(8));
        ImageView g = glyph(c, glyph != null ? glyph : "location", t.ink2);
        if (glyph == null) g.setAlpha(0f);   // keeps the words lined up
        r.addView(g, Ui.ll(18, 18));
        LinearLayout w = Ui.column(c);
        TextView tt = Ui.line(c, 15, t.ink, 500, title);
        w.addView(tt);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.line(c, 13, t.ink3, 400, sub);
            s.setTag("sub");
            w.addView(s, Ui.ll(-1, -1, 0, 2));
        }
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.leftMargin = Ui.px(12);
        r.addView(w, wl);
        if (checked) r.addView(glyph(c, "check", t.ink2), Ui.ll(16, 16, 10, 0));
        Sheet.paint(r, false, t);
        r.setOnClickListener(v -> {
            if (act != null) act.run();
        });
        results.addView(r, Ui.ll(-2, -1));
        rows.add(r);
    }

    private void choose(String[] p) {
        Sound.pop();
        if (p == null) Widgets.Weather.setPlace(c, null, 0, 0, null);
        else Widgets.Weather.setPlace(c, p[0], Double.parseDouble(p[2]), Double.parseDouble(p[3]), p[4]);
        field.setText("");
        status.setText("Finding the weather…");
        forecast.removeAllViews();
        placeLine.setText(p == null ? "Automatic" : p[0]);
        list(null);
        // the forecast arrives in a moment: the sheet and the desktop's widget show it
        h.postDelayed(new Runnable() {
            int n;

            @Override
            public void run() {
                if (closing) return;
                if (Widgets.Weather.now(c) != null) {
                    showNow();
                    a.widgetsChanged();
                } else if (n++ < 20) h.postDelayed(this, 500);
            }
        }, 600);
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    @Override
    boolean onKey(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && field.hasFocus() && !rows.isEmpty()) {
            hideKeyboard();
            rows.get(0).requestFocus();
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && !rows.isEmpty() && rows.get(0).hasFocus()) {
            field.requestFocus();
            return true;
        }
        return false;
    }

    @Override
    boolean onBack() {
        if (field.hasFocus() && field.length() > 0) {
            field.setText("");
            return true;
        }
        return false;
    }

    private void hideKeyboard() {
        InputMethodManager imm = c.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    @Override
    void onClosed() {
        hideKeyboard();
        h.removeCallbacksAndMessages(null);
        asked++;
    }
}
