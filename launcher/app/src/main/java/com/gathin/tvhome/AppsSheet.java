package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * All apps: every app on the TV over a frosted veil, seven to a row, A to Z. Typing (Up from the first row)
 * narrows it. Hold OK on an app to keep it in the dock or take it out.
 */
final class AppsSheet extends Sheet {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> all = new ArrayList<>(), shown = new ArrayList<>();
    private EditText field;
    private RecyclerView grid;
    private Adapter adapter;
    private ImageView veil;
    private static final int COLS = 7;

    AppsSheet(Desk a) {
        super(a);
        overlay = true;
        barKey = "apps";
    }

    @Override
    void build() {
        veil = new ImageView(c);
        veil.setScaleType(ImageView.ScaleType.FIT_XY);
        veil.setBackgroundColor(t.night ? 0xFF15151C : 0xFFDCE3EB);
        root.addView(veil, Ui.fill());
        View tint = new View(c);
        tint.setBackgroundColor(t.night ? Ui.a(0x0A0A0E, .35f) : Ui.a(0xFFFFFF, .18f));
        root.addView(tint, Ui.fill());
        final boolean n = t.night;
        Ui.BG.execute(() -> {
            try {
                Bitmap wall = Wall.get(c.getApplicationContext(), n);
                Bitmap b = Lens.veil(wall, 28 * Ui.s, 1.3f);
                ui.post(() -> veil.setImageBitmap(b));
            } catch (OutOfMemoryError | RuntimeException ignored) {
            }
        });
        field = Ui.field(c, 15, "Search apps", 12, true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c1) { }
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c1) { }
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        float fh = Ui.boxf(40, 15);
        root.addView(field, Ui.at(470, 92 - fh / 2, 340, fh));
        all.addAll(a.allApps());
        shown.addAll(all);
        grid = new RecyclerView(c);
        grid.setLayoutManager(new GridLayoutManager(c, COLS));
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.px(8), 0, Ui.px(40));
        grid.setItemAnimator(null);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        float gy = 92 + fh / 2 + 18;
        root.addView(grid, Ui.at(115, gy, 1050, 720 - gy));
    }

    @Override
    View firstFocus() {
        return null;
    }

    void focusFirst() {
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
            if (h != null) h.itemView.requestFocus();
            else field.requestFocus();
        });
    }

    @Override
    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && field.hasFocus()) {
            focusFirst();
            return true;
        }
        // ↑ from anywhere on the first row reaches the search field (not only below it)
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && !field.hasFocus()) {
            View f = grid.getFocusedChild();
            if (f != null && grid.getChildAdapterPosition(f) >= 0 && grid.getChildAdapterPosition(f) < COLS) {
                field.requestFocus();
                return true;
            }
        }
        return false;
    }

    @Override
    boolean onBack() {
        if (field.getText().length() > 0) {
            field.setText("");
            focusFirst();
            return true;
        }
        return false;
    }

    @Override
    void onClosed() {
        ui.removeCallbacksAndMessages(null);
    }

    private void filter() {
        String q = field.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (String[] app : all) if (q.isEmpty() || app[0].toLowerCase(Locale.ROOT).contains(q)) shown.add(app);
        adapter.notifyDataSetChanged();
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        String pkg;

        Holder(LinearLayout v) {
            super(v);
            icon = (ImageView) v.getChildAt(0);
            name = (TextView) v.getChildAt(1);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout cell = Ui.column(c);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, Ui.px(16), 0, 0);
            // the icon grows past its own box on focus: nothing around it may cut it (a group clips at its padding)
            cell.setClipToPadding(false);
            cell.setClipChildren(false);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setLongClickable(true);
            ImageView icon = new ImageView(c);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(88 * 0.235f));
                }
            });
            cell.addView(icon, Ui.ll(88, 88));
            Audit.focusScale(icon, 1.1f);
            TextView name = Ui.line(c, 15, t.ink, 500, "");
            name.setTag("clip-ok");   // a long name is cut short at rest, and scrolls into view on focus (as on Apple TV)
            name.setGravity(Gravity.CENTER);
            name.setMaxWidth(Ui.px(140));
            name.setShadowLayer(Ui.pxf(6), 0, Ui.pxf(1), Ui.a(0x000000, .25f));
            cell.addView(name, Ui.ll(-1, -1, 0, 10));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.px(150), Ui.px(16 + 88 + 10 + 14) + Math.round(name.getTextSize() * 1.3f));
            lp.bottomMargin = Ui.px(14);
            cell.setLayoutParams(lp);
            Holder h = new Holder(cell);
            cell.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? Ui.platter(20) : null);   // the focus platter, no ring
                h.name.setTypeface(Ui.face(c, has ? 600 : 500));
                if (has) Sound.tick();
                h.icon.setPivotX(Ui.pxf(44));
                h.icon.setPivotY(Ui.pxf(50));
                h.icon.setElevation(has ? Ui.pxf(8) : 0);
                Spring.scale(h.icon, has ? 1.1f : 1f, Spring.FOCUS);
            });
            cell.setOnClickListener(v -> {
                if (h.pkg == null) return;
                a.dismiss(AppsSheet.this);
                a.launch(h.pkg, h.icon);
            });
            cell.setOnLongClickListener(v -> {
                if (h.pkg != null) a.appMenu(h.pkg, v);
                return true;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(Holder h, int i) {
            String[] app = shown.get(i);
            h.pkg = app[1];
            h.name.setText(app[0]);
            h.icon.setImageBitmap(AppIcons.of(c, app[1], Ui.px(88)));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
