package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search: one box for apps, settings, files and questions. Results arrive as you type, grouped (Top hit,
 * Settings, Files, Ask); the focused row inverts. OK on the keyboard opens the top hit. Rows 48 high at radius
 * 18 (10 in from the 28 panel), one 17 px size for every result.
 */
final class SearchSheet extends Sheet {
    static final float X = 320, Y = 88, W = 640, H = 446;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText field;
    private LinearLayout list;
    private Glide glide;
    private final List<View> rows = new ArrayList<>();
    private Runnable top;
    private int generation;

    SearchSheet(Desk a) {
        super(a);
        barKey = "search";
    }

    @Override
    void build() {
        View dim = new View(c);
        dim.setBackgroundColor(Ui.a(0x000000, .06f));
        root.addView(dim, Ui.fill());
        pane = new Pane(c, true, X, Y, W, H, Ui.PANEL_R);
        pane.overApp(a.overApp());
        LinearLayout col = Ui.column(c);
        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.px(20), 0, Ui.px(20), 0);
        head.addView(glyph(c, "search", t.ink2), Ui.ll(22, 22));
        field = new EditText(c);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(24) * Ui.fs);
        field.setTypeface(Ui.face(c, 400));
        field.setTextColor(t.ink);
        field.setHintTextColor(t.ink3);
        field.setHint("Search apps, settings, files — or ask");
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnEditorActionListener((v, id, ev) -> {
            if (top != null) top.run();
            return true;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(search);
                ui.postDelayed(search, 140);
            }
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, -2, 1f);
        fl.leftMargin = Ui.px(12);
        head.addView(field, fl);
        col.addView(head, Ui.ll(-2, Ui.boxf(63, 24)));
        View hair = new View(c);
        hair.setBackgroundColor(t.hair);
        col.addView(hair, Ui.ll(-2, 1));
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        Rows rows = new Rows(c, t, 18);
        rows.frame.setPadding(Ui.px(10), Ui.px(4), Ui.px(10), Ui.px(10));
        list = rows.list;
        glide = rows.glide;
        scroll.addView(rows.frame);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));   // what's left under the field
        pane.addView(col, Ui.fill());
        dropX = a.barCenter("search");
        root.addView(pane, pane.params());
        pane.bake(c);
        run("");
    }

    @Override
    View firstFocus() {
        return field;
    }

    void showKeyboard() {
        field.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 160);
    }

    @Override
    void onClosed() {
        ui.removeCallbacksAndMessages(null);
        InputMethodManager imm = c.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    @Override
    boolean onKey(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && field.hasFocus() && !rows.isEmpty()) {
            rows.get(0).requestFocus();
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && !rows.isEmpty() && rows.get(0).hasFocus()) {
            field.requestFocus();
            return true;
        }
        return false;
    }

    private final Runnable search = () -> run(field.getText().toString().trim());

    // ---- results --------------------------------------------------------------------------------------------

    private static final class Hit {
        final String section, title, kind;
        final View icon;
        final Runnable go;

        Hit(String section, String title, String kind, View icon, Runnable go) {
            this.section = section;
            this.title = title;
            this.kind = kind;
            this.icon = icon;
            this.go = go;
        }
    }

    private void run(String q) {
        final int gen = ++generation;
        List<Hit> hits = new ArrayList<>();
        String lq = q.toLowerCase(Locale.ROOT);
        // apps: prefix matches first; with nothing typed, the ones used most recently
        List<String[]> apps = a.allApps();
        List<String[]> found = new ArrayList<>();
        if (q.isEmpty()) {
            for (String[] r : LastOpened.recent(c, 6)) for (String[] app : apps) if (app[1].equals(r[0]) && found.size() < 4) found.add(app);
        } else {
            for (String[] app : apps) if (app[0].toLowerCase(Locale.ROOT).startsWith(lq)) found.add(app);
            for (String[] app : apps) if (!found.contains(app) && app[0].toLowerCase(Locale.ROOT).contains(lq)) found.add(app);
        }
        int n = 0;
        for (String[] app : found) {
            if (n++ >= 4) break;
            String section = q.isEmpty() ? "Recent" : n == 1 ? "Top hit" : "Apps";
            hits.add(new Hit(section, app[0], "App", appIcon(app[1]), () -> a.launch(app[1], null)));
        }
        if (!q.isEmpty()) {
            int s = 0;
            for (Object[] st : settings()) {
                if (s >= 3) break;
                String title = (String) st[1];
                if (title.toLowerCase(Locale.ROOT).contains(lq) || ((String) st[3]).contains(lq)) {
                    s++;
                    hits.add(new Hit("Settings", title, "Settings", chip((String) st[0]), (Runnable) st[2]));
                }
            }
        }
        if (!q.isEmpty()) hits.add(new Hit("Ask", "Ask Glass “" + q + "”", "", askTile(), () -> a.ask(q)));
        show(hits, q);
        if (q.length() >= 2) {
            Ui.BG.execute(() -> {
                List<String[]> files = files(q);
                ui.post(() -> {
                    if (gen != generation || files.isEmpty() || closing) return;
                    List<Hit> more = new ArrayList<>(hits);
                    int at = more.size() - 1;   // before "Ask"
                    for (String[] f : files) {
                        File file = new File(f[1]);
                        more.add(at++, new Hit("Files", f[0], f[2], chip(f[3]), () -> a.openFile(file)));
                    }
                    show(more, q);
                });
            });
        }
    }

    private void show(List<Hit> hits, String q) {
        View had = null;
        for (View r : rows) if (r.hasFocus()) had = r;
        int focusIndex = had == null ? -1 : rows.indexOf(had);
        list.removeAllViews();
        rows.clear();
        glide.hide();
        top = hits.isEmpty() ? null : hits.get(0).go;
        String section = null;
        for (Hit h : hits) {
            if (!h.section.equals(section)) {
                section = h.section;
                TextView head = Ui.line(c, 13, t.ink3, 500, section);
                head.setPadding(Ui.px(14), Ui.px(12), Ui.px(14), Ui.px(4));
                list.addView(head);
            }
            LinearLayout row = Sheet.row(c, t, glide, 48);
            row.setPadding(Ui.px(14), 0, Ui.px(14), 0);
            h.icon.setTag("keep");
            row.addView(h.icon, Ui.ll(28, 28));
            TextView title = Ui.line(c, 17, t.ink, 400, h.title);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = Ui.px(12);
            row.addView(title, tl);
            TextView kind = Ui.line(c, 13, t.ink3, 400, h.kind);
            kind.setTag("sub");
            row.addView(kind);
            paint(row, false, t);
            View.OnFocusChangeListener base = row.getOnFocusChangeListener();
            row.setOnFocusChangeListener((v, has) -> {
                base.onFocusChange(v, has);
                title.setTypeface(Ui.face(c, has ? 600 : 400));
            });
            row.setOnClickListener(v -> {
                a.dismiss(this);
                h.go.run();
            });
            list.addView(row, Ui.ll(-2, Ui.boxf(48, 15)));
            rows.add(row);
        }
        if (focusIndex >= 0 && !rows.isEmpty()) rows.get(Math.min(focusIndex, rows.size() - 1)).requestFocus();
        else if (!field.hasFocus() && !rows.isEmpty()) rows.get(0).requestFocus();
    }

    private View appIcon(String pkg) {
        ImageView v = new ImageView(c);
        v.setImageBitmap(AppIcons.of(c, pkg, Ui.px(28)));
        return v;
    }

    private View chip(String glyph) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(Ui.fillRound(t.hover, 8));
        ImageView g = glyph(c, glyph, t.ink2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.px(17), Ui.px(17));
        lp.gravity = Gravity.CENTER;
        f.addView(g, lp);
        return f;
    }

    private View askTile() {
        FrameLayout f = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFB27A, 0xFFE86A4C});
        g.setCornerRadius(Ui.pxf(8));
        f.setBackground(g);
        ImageView gl = glyph(c, "assist", 0xFFFFFFFF);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.px(17), Ui.px(17));
        lp.gravity = Gravity.CENTER;
        f.addView(gl, lp);
        return f;
    }

    /** {glyph, title, action, extra words} */
    private List<Object[]> settings() {
        List<Object[]> s = new ArrayList<>();
        s.add(new Object[]{"sun", "Picture brightness", (Runnable) () -> a.openControls(), "backlight dim bright screen"});
        s.add(new Object[]{"text", "Text size", (Runnable) () -> a.openSettings("display"), "font bigger smaller"});
        s.add(new Object[]{"sun", "Appearance", (Runnable) () -> a.openSettings("display"), "dark light night day theme"});
        s.add(new Object[]{"wifi", "Wi-Fi", (Runnable) () -> a.openSystem(Settings.ACTION_WIFI_SETTINGS), "network internet"});
        s.add(new Object[]{"bt", "Bluetooth", (Runnable) () -> a.openSystem(Settings.ACTION_BLUETOOTH_SETTINGS), "remote headphones speaker"});
        s.add(new Object[]{"moon", "Sleep timer", (Runnable) () -> a.openControls(), "turn off later"});
        s.add(new Object[]{"volume", "Night volume", (Runnable) () -> a.openControls(), "quiet loud ceiling"});
        s.add(new Object[]{"input", "Inputs", (Runnable) () -> a.openInputs(), "hdmi source av"});
        s.add(new Object[]{"power", "Turn off when idle", (Runnable) () -> a.openSettings("power"), "standby sleep"});
        s.add(new Object[]{"assist", "Assistant button", (Runnable) () -> a.openSettings("assistant"), "google voice mic"});
        s.add(new Object[]{"tv", "About this TV", (Runnable) () -> a.openSettings("about"), "model version storage"});
        s.add(new Object[]{"sliders", "Android settings", (Runnable) () -> a.openSystem(Settings.ACTION_SETTINGS), "system all"});
        return s;
    }

    /** {name, path, where, glyph} — files whose names contain the words, newest first. */
    private List<String[]> files(String q) {
        List<String[]> out = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        Uri u = MediaStore.Files.getContentUri("external");
        String[] cols = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor k = cr.query(u, cols, MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{"%" + q + "%"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            while (k != null && k.moveToNext() && out.size() < 3) {
                String name = k.getString(0), path = k.getString(1);
                if (name == null || path == null) continue;
                int type = k.getInt(2);
                String g = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? "film" : type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ? "photo"
                        : type == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ? "music" : "doc";
                out.add(new String[]{name, path, Files.where(c, path), g});
            }
        } catch (RuntimeException ignored) {
            // no storage permission yet: files simply don't appear
        }
        return out;
    }
}
