package com.gathin.tvhome;

import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Hold Home: your open apps on clear glass, newest first, the one before the current already chosen so a hold and
 * OK goes back. ← → choose, OK opens, ↑ closes the app (it leaves for the top, as a card flicked away), hold OK for
 * its menu, Back closes. The chosen card rises on the focus platter. Cards 150 × 180 at radius 20 (16 in from 36).
 */
final class SwitcherSheet extends Sheet {
    private final List<View> cards = new ArrayList<>();
    private final List<String> pkgs = new ArrayList<>();
    private LinearLayout row;
    private TextView hint;

    SwitcherSheet(Desk a) {
        super(a);
        overlay = true;
    }

    @Override
    void build() {
        View dim = new View(c);
        dim.setBackgroundColor(Ui.a(0x000000, .10f));
        root.addView(dim, Ui.fill());
        List<String[]> recent = new ArrayList<>();
        String front = Glass.front();
        for (String[] r : LastOpened.recent(c, 12)) {
            if (recent.size() >= 6) break;
            if (c.getPackageManager().getLeanbackLaunchIntentForPackage(r[0]) == null) continue;   // TV apps only
            if (r[0].equals(c.getPackageName())) continue;
            try {
                if (Running.closed(c, r[0], Long.parseLong(r[1]))) continue;   // closed here since: it left
            } catch (NumberFormatException ignored) {
            }
            recent.add(r);
        }
        int n = Math.max(1, recent.size());
        float w = n * 150 + (n - 1) * 12 + 32, h = 180 + 32;
        float x = (1280 - w) / 2, y = (720 - h) / 2;
        pane = new Pane(c, false, x, y, w, h, 36);
        FrameLayout holder = new FrameLayout(c);
        holder.setClipChildren(false);
        row = Ui.row(c);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setPadding(Ui.px(16), Ui.px(16), Ui.px(16), Ui.px(16));
        holder.addView(row, Ui.fill());
        if (recent.isEmpty()) empty();
        long now = System.currentTimeMillis();
        for (int i = 0; i < recent.size(); i++) {
            String pkg = recent.get(i)[0];
            long when = Long.parseLong(recent.get(i)[1]);
            LinearLayout card = Ui.column(c);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(0, Ui.px(18), 0, 0);
            card.setFocusable(true);
            card.setClickable(true);
            card.setLongClickable(true);
            ImageView icon = new ImageView(c);
            icon.setImageBitmap(AppIcons.of(c, pkg, Ui.px(96)));
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(96 * 0.235f));
                }
            });
            icon.setElevation(Ui.pxf(2));
            card.addView(icon, Ui.ll(96, 96));
            CharSequence l = Glass.label(c, pkg);
            TextView name = Ui.line(c, 15, t.ink, 500, l == null ? pkg : l.toString());
            card.addView(name, Ui.ll(-1, -1, 0, 12));
            boolean live = pkg.equals(front) && !Glass.selfInFront();
            String ago = live ? "Open now" : now - when < DateUtils.MINUTE_IN_MILLIS ? "Just now"
                    : DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
            card.addView(Ui.line(c, 13, t.ink3, 400, ago), Ui.ll(-1, -1, 0, 6));
            Ui.focusRing(card, 20);
            Ui.lift(card, 1.05f, 10);
            card.setOnFocusChangeListener((v, has) -> {
                name.setTypeface(Ui.face(c, has ? 600 : 500));
                if (has) Sound.tick();
            });
            card.setOnClickListener(v -> {
                a.dismiss(this);
                a.launch(pkg, icon);
            });
            card.setOnLongClickListener(v -> {
                menu(pkg, v);
                return true;
            });
            row.addView(card, Ui.ll(150, 180, cards.isEmpty() ? 0 : 12, 0));
            cards.add(card);
            pkgs.add(pkg);
        }
        pane.addView(holder, Ui.fill());
        root.addView(pane, pane.params());
        pane.bake(c);
        hint = Ui.text(c, 13, t.ink, 400);
        hint.setAlpha(0.7f);
        hint.setGravity(Gravity.CENTER);
        hint.setText(recent.isEmpty() ? "Hold Home" : "← → choose · OK open · ↑ close the app · hold OK for more");
        root.addView(hint, Ui.at(0, y + h + 16, 1280, -1));
    }

    private void empty() {
        TextView none = Ui.text(c, 15, t.ink2, 400);
        none.setText("Apps you open will line up here");
        none.setGravity(Gravity.CENTER);
        none.setFocusable(true);
        row.addView(none, Ui.ll(150, 180));
        cards.add(none);
        pkgs.add(null);
    }

    @Override
    View firstFocus() {
        if (cards.isEmpty()) return null;
        // the one before what's in front: hold Home, OK, and you're back
        String front = Glass.front();
        if (cards.size() > 1 && front != null && !Glass.selfInFront() && front.equals(pkgs.get(0))) return cards.get(1);
        return cards.get(0);
    }

    @Override
    boolean onKey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP || e.getRepeatCount() > 0) return false;
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).hasFocus() && pkgs.get(i) != null) {
                close(i);
                return true;
            }
        }
        return false;
    }

    /** The app closes; its card leaves for the top and the others close the gap, focus on the next one. */
    private void close(int i) {
        String pkg = pkgs.get(i);
        View card = cards.get(i);
        Sound.close();
        cards.remove(i);
        pkgs.remove(i);
        card.setFocusable(false);
        int next = Math.min(i, cards.size() - 1);
        if (next >= 0) cards.get(next).requestFocus();
        Running.close(c, pkg, a::runningChanged);
        card.animate().translationY(-Ui.pxf(70)).alpha(0f).setDuration(Ui.ms(220)).setInterpolator(Ui.EIN).withEndAction(() -> {
            row.removeView(card);
            if (!cards.isEmpty()) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cards.get(0).getLayoutParams();
                lp.leftMargin = 0;
                cards.get(0).setLayoutParams(lp);
            } else {
                empty();
                hint.setText("Hold Home");
                cards.get(0).requestFocus();
            }
        }).start();
        CharSequence l = Glass.label(c, pkg);
        hint.setText((l == null ? "The app" : l) + " is closed");
    }

    private void menu(String pkg, View card) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("close", "Close app", null, () -> {
            int i = cards.indexOf(card);
            if (i >= 0) close(i);
        }));
        rows.add(new Row("info", "App info", null, () -> {
            a.dismiss(this);
            a.openAppInfo(pkg);
        }));
        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        float x = Math.max(Ui.EDGE, Math.min(1280 - Ui.EDGE - 200, loc[0] / Ui.s - 25));
        float y = loc[1] / Ui.s + 150;
        a.stack(new Menu(a, rows, x, y, 200, false), card);
    }
}
