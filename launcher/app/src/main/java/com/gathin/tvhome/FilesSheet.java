package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Files: USB drives, downloads, photos, videos and music, in one window that grows out of its dock icon.
 * Sidebar on the left (Favourites, Locations), a toolbar with where you are, a grid of big previews.
 * OK opens a folder or plays a file in the app that handles it; Back goes up a folder, then closes.
 */
final class FilesSheet extends Sheet {
    static final float X = 140, Y = 58, W = 1000, H = 530, SB = 232;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final List<View> sideRows = new ArrayList<>();
    private final List<Files.Place> sidePlaces = new ArrayList<>();
    private Files.Place place;
    private final List<File> stack = new ArrayList<>();
    private final List<Files.Entry> all = new ArrayList<>(), shown = new ArrayList<>();
    private TextView title, status, empty;
    private EditText filter;
    private RecyclerView grid;
    private Adapter adapter;
    private int load;

    FilesSheet(Desk a) {
        super(a);
        window = true;
    }

    @Override
    void build() {
        pane = new Pane(c, true, X, Y, W, H, Ui.PANEL_R);
        pane.overApp(a.overApp());
        FrameLayout in = new FrameLayout(c);
        // sidebar
        LinearLayout side = Ui.column(c);
        side.setPadding(Ui.px(10), Ui.px(12), Ui.px(10), Ui.px(12));
        side.setClipToPadding(false);   // a row grows a little on focus: the padding mustn't cut it
        side.setBackgroundColor(t.hover);
        side.addView(head("Favourites"));
        for (Files.Place p : Files.favourites(c)) addSide(side, p);
        side.addView(head("Locations"));
        for (Files.Place p : Files.locations(c)) addSide(side, p);
        in.addView(side, Ui.at(0, 0, SB, H));
        View rule = new View(c);
        rule.setBackgroundColor(t.hair);
        in.addView(rule, Ui.at(SB - 1, 0, 1, H));
        // toolbar
        LinearLayout bar = Ui.row(c);
        bar.setPadding(Ui.px(20), 0, Ui.px(16), 0);
        // where you are, as a path: Back walks up it
        title = Ui.line(c, 20, t.ink, 600, "");
        title.setLetterSpacing(-0.01f);
        title.setEllipsize(TextUtils.TruncateAt.START);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        filter = Ui.field(c, 13, "Search", 10, true);
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c1) { }
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c1) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        bar.addView(filter, Ui.ll(190, Ui.boxf(34, 13), 12, 0));
        in.addView(bar, Ui.at(SB, 0, W - SB, 60));
        View rule2 = new View(c);
        rule2.setBackgroundColor(t.hair);
        in.addView(rule2, Ui.at(SB, 59, W - SB, 1));
        // grid
        grid = Audit.secret(new RecyclerView(c));   // someone's photos and file names
        grid.setLayoutManager(new GridLayoutManager(c, 5));
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.px(16), 0, Ui.px(16));
        grid.setItemAnimator(null);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        in.addView(grid, Ui.at(SB + 14, 60, 700, H - 100));
        empty = Ui.text(c, 15, t.ink3, 400);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        in.addView(empty, Ui.at(SB, 60, W - SB, H - 100));
        // status line
        LinearLayout st = Ui.row(c);
        st.setPadding(Ui.px(20), 0, Ui.px(20), 0);
        status = Ui.line(c, 13, t.ink3, 400, "");
        st.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        in.addView(st, Ui.at(SB, H - Ui.boxf(40, 13), W - SB, Ui.boxf(40, 13)));
        View rule3 = new View(c);
        rule3.setBackgroundColor(t.hair);
        in.addView(rule3, Ui.at(SB, H - 40, W - SB, 1));
        pane.addView(in, Ui.fill());
        root.addView(pane, pane.params());
        pane.bake(c);
        // start on a USB drive when one is in, else what phones sent (if anything), else Recents
        Files.Place start = sidePlaces.size() > 1 && Show.all(c).isEmpty() ? sidePlaces.get(1) : sidePlaces.get(0);
        for (Files.Place p : sidePlaces) if (p.key.startsWith("usb:")) { start = p; break; }
        go(start);
    }

    private TextView head(String s) {
        TextView v = Ui.line(c, 13, t.ink3, 600, s);
        v.setPadding(Ui.px(10), Ui.px(14), Ui.px(10), Ui.px(6));
        return v;
    }

    private void addSide(LinearLayout side, Files.Place p) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.px(10), 0, Ui.px(10), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.addView(glyph(c, p.glyph, t.ink2), Ui.ll(18, 18));
        TextView l = Ui.line(c, 15, t.ink, 400, p.title);
        row.addView(l, Ui.ll(-1, -1, 10, 0));
        Ui.focusRing(row, 10);
        row.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        row.setOnClickListener(v -> { Sound.pop(); go(p); });
        side.addView(row, Ui.ll(-2, Ui.boxf(36, 14)));
        sideRows.add(row);
        sidePlaces.add(p);
    }

    private void markSide() {
        for (int i = 0; i < sideRows.size(); i++) {
            LinearLayout r = (LinearLayout) sideRows.get(i);
            boolean cur = sidePlaces.get(i) == place;
            if (cur) {
                GradientDrawable g = Ui.fillRound(t.night ? Ui.a(0xFFFFFF, .09f) : Ui.a(0xFFFFFF, .60f), 10);
                g.setStroke(Math.max(1, Ui.px(1)), t.hair);
                Ui.rest(r, g);
            } else {
                Ui.rest(r, null);
            }
            ((TextView) r.getChildAt(1)).setTypeface(Ui.face(c, cur ? 600 : 400));
            ((Glyph) ((ImageView) r.getChildAt(0)).getDrawable()).setColor(cur ? t.ink : t.ink2);
        }
    }

    private void go(Files.Place p) {
        place = p;
        stack.clear();
        markSide();
        reload(true);
    }

    private File dir() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void reload(boolean focusGrid) {
        final int my = ++load;
        File d = dir();
        StringBuilder path = new StringBuilder(place.title);
        for (File f : stack) path.append("  ›  ").append(f.getName());
        title.setText(path);
        status.setText("");
        Files.IO.execute(() -> {
            List<Files.Entry> list = Files.list(c, place, d);
            String free = Files.free(c, d != null ? d : place.root);
            ui.post(() -> {
                if (my != load || closing) return;
                all.clear();
                all.addAll(list);
                filter.setText("");
                applyFilter();
                status.setText((all.size() == 1 ? "1 item" : all.size() + " items") + (free.isEmpty() ? "" : " · " + free));
                if (focusGrid && !shown.isEmpty()) grid.post(() -> {
                    RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
                    if (h != null) h.itemView.requestFocus();
                });
            });
        });
    }

    private void applyFilter() {
        String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Files.Entry e : all) if (q.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(e);
        adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        empty.setText(all.isEmpty() ? ("phones".equals(place.key) ? "Nothing sent yet.\nOn your phone's Glass remote: Apps ▸ Show on the TV. Photos and videos land here, and on the screen at once."
                : place.media != 0 ? "Nothing here yet. Videos, photos and music on a USB drive show up here." : "This folder is empty")
                : "Nothing matches");
    }

    @Override
    View firstFocus() {
        return sideRows.isEmpty() ? null : sideRows.get(sidePlaces.indexOf(place));
    }

    @Override
    boolean onBack() {
        if (filter.hasFocus() && filter.getText().length() > 0) {
            filter.setText("");
            return true;
        }
        if (!stack.isEmpty()) {
            stack.remove(stack.size() - 1);
            reload(true);
            return true;
        }
        return false;
    }

    @Override
    void onClosed() {
        ui.removeCallbacksAndMessages(null);
        load++;
    }

    private void open(Files.Entry e, View from) {
        if ("folder".equals(e.kind)) {
            stack.add(e.file);
            reload(true);
            return;
        }
        if ("photo".equals(e.kind) || "video".equals(e.kind)) {
            // Glass's own viewer, with the photos and videos of this place: ← → through them at once
            List<File> media = new ArrayList<>();
            int at = 0;
            for (Files.Entry x : shown) {
                if (!"photo".equals(x.kind) && !"video".equals(x.kind)) continue;
                if (x == e) at = media.size();
                media.add(x.file);
            }
            ShowActivity.open(c, media, at);
            return;
        }
        Files.open(c, e.file, false);
    }

    private void options(Files.Entry e, View from) {
        if ("folder".equals(e.kind)) return;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("play", "Open", null, () -> open(e, from)));
        rows.add(new Row("apps", "Open with…", null, () -> Files.open(c, e.file, true)));
        if (Files.sent(c, e.file)) rows.add(new Row("trash", "Delete", null, () -> confirmDelete(e, from)));
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        float fx = loc[0] / Ui.s, fy = loc[1] / Ui.s;
        a.show(new Menu(a, rows, Math.min(fx, 1280 - 16 - 220), fy + 100, 220, false), from);
    }

    /** Delete, asked once more (the safe answer first). Only what a phone sent: Glass's own folder. */
    private void confirmDelete(Files.Entry e, View from) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("close", "Keep it", null, null));
        rows.add(new Row("trash", "Delete from the TV", null, () -> {
            boolean ok = e.file.delete();
            Sound.toggle(ok);
            if (!ok) Toast.makeText(c, "Couldn't delete it", Toast.LENGTH_SHORT).show();
            reload(true);
        }));
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        float fx = loc[0] / Ui.s, fy = loc[1] / Ui.s;
        a.show(new Menu(a, rows, Math.min(fx, 1280 - 16 - 240), fy + 100, 240, false), from);
    }

    // ---- the grid -------------------------------------------------------------------------------------------

    private final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout frame;
        final ImageView pic;
        final TextView name, meta;
        Files.Entry entry;

        Holder(LinearLayout v) {
            super(v);
            frame = (FrameLayout) v.getChildAt(0);
            pic = (ImageView) frame.getChildAt(0);
            name = (TextView) v.getChildAt(1);
            meta = (TextView) v.getChildAt(2);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout v = Ui.column(c);
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            v.setFocusable(true);
            v.setClickable(true);
            v.setLongClickable(true);
            FrameLayout frame = new FrameLayout(c);
            frame.setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6));
            ImageView pic = new ImageView(c);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setClipToOutline(true);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.pxf(10));
                }
            });
            frame.addView(pic, new FrameLayout.LayoutParams(Ui.px(96), Ui.px(72)));
            v.addView(frame, Ui.ll(108, 84));
            TextView name = Ui.line(c, 15, t.ink, 400, "");
            name.setTag("clip-ok");   // file names are long: cut at rest, they scroll into view on focus
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setPadding(Ui.px(8), Ui.px(2), Ui.px(8), Ui.px(2));
            name.setMaxWidth(Ui.px(136));
            v.addView(name, Ui.ll(-1, -1, 0, 8));
            TextView meta = Ui.line(c, 13, t.ink3, 400, "");
            v.addView(meta, Ui.ll(-1, -1, 0, 2));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.px(140), Ui.px(150));
            v.setLayoutParams(lp);
            Holder h = new Holder(v);
            v.setOnFocusChangeListener((x, has) -> focus(h, has));
            v.setOnClickListener(x -> { if (h.entry != null) open(h.entry, x); });
            v.setOnLongClickListener(x -> { if (h.entry != null) options(h.entry, x); return true; });
            return h;
        }

        @Override
        public void onBindViewHolder(Holder h, int i) {
            Files.Entry e = shown.get(i);
            h.entry = e;
            h.name.setText(e.name);
            h.meta.setText(e.meta);
            h.pic.setImageBitmap(tile(e.kind));
            focus(h, h.itemView.hasFocus());
            if ("photo".equals(e.kind) || "video".equals(e.kind)) {
                String key = e.file.getPath();
                Bitmap cached = thumbs.get(key);
                if (cached != null) {
                    h.pic.setImageBitmap(cached);
                } else {
                    Files.IO.execute(() -> {
                        Bitmap b = Files.thumb(c, e);
                        if (b == null) return;
                        ui.post(() -> {
                            thumbs.put(key, b);
                            if (h.entry == e) h.pic.setImageBitmap(b);
                        });
                    });
                }
            }
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    private void focus(Holder h, boolean has) {
        if (has) Sound.tick();
        h.frame.setPivotX(Ui.pxf(54));
        h.frame.setPivotY(Ui.pxf(84));
        Spring.scale(h.frame, has ? 1.06f : 1f, Spring.FOCUS);
        h.frame.setBackground(has ? Ui.platter(16) : null);   // the focus platter, no ring
        h.frame.setElevation(has ? Ui.pxf(8) : 0);
        h.name.setBackground(null);
        h.name.setTextColor(t.ink);
        h.name.setTypeface(Ui.face(c, has ? 600 : 400));
    }

    // ---- the boards' tiles for folders and files without a picture -------------------------------------------

    private final java.util.Map<String, Bitmap> tiles = new java.util.HashMap<>();

    private Bitmap tile(String kind) {
        Bitmap b = tiles.get(kind);
        if (b != null) return b;
        int w = Ui.px(96), h = Ui.px(72);
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.scale(w / 96f, h / 72f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if ("folder".equals(kind)) {
            p.setColor(0xFF5FD6C5);
            cv.drawRoundRect(new RectF(4, 4, 42, 24), 6, 6, p);
            p.setShader(new LinearGradient(0, 12, 0, 70, 0xFF7EE3D5, 0xFF3CC9B6, Shader.TileMode.CLAMP));
            cv.drawRoundRect(new RectF(4, 12, 92, 70), 10, 10, p);
            p.setShader(null);
            p.setColor(Ui.a(0xFFFFFF, .5f));
            cv.drawRect(14, 12.5f, 82, 13.5f, p);
        } else {
            int[] cols;
            float[] at = null;
            String g = null;
            int gc = 0xF2FFFFFF;
            switch (kind) {
                case "video": cols = new int[]{0xFFF4C7A1, 0xFFE6866A, 0xFF5B6EA8}; at = new float[]{0f, .55f, 1f}; g = "play"; break;
                case "photo": cols = new int[]{0xFFA6D3F2, 0xFF7FB2D9, 0xFF3E7F6A}; at = new float[]{0f, .5f, 1f}; g = "photo"; break;
                case "music": cols = new int[]{0xFFF7A8C2, 0xFFB24C79}; g = "music"; break;
                default: cols = new int[]{0xFFFFFFFF, 0xFFE9ECF2}; g = "doc"; gc = 0xFF8A93A3;
            }
            double ang = Math.toRadians(160);
            float ux = (float) Math.sin(ang), uy = (float) -Math.cos(ang);
            float len = Math.abs(96 * ux) + Math.abs(72 * uy);
            p.setShader(new LinearGradient(48 - ux * len / 2, 36 - uy * len / 2, 48 + ux * len / 2, 36 + uy * len / 2, cols, at, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, 96, 72, p);
            Glyph gl = Glyph.of(g, gc, 2f);
            gl.setBounds(36, 24, 60, 48);
            gl.draw(cv);
        }
        tiles.put(kind, b);
        return b;
    }
}
