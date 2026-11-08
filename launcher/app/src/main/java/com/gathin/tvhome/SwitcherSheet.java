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
 * Hold Home: the apps that are open, newest first, on glass. Inside an app it opens over the app (Hud: the app stays
 * as it is underneath); on the desktop, over the desktop. The app on screen comes first and the one before it is
 * already chosen, so a hold and OK goes back to it. ← → choose (every card, however many), OK opens, ↑ closes the
 * app (its card leaves for the top, as a card flicked away), hold OK for its menu, Back closes. The chosen card rises
 * on the focus platter, with room around it in the glass so nothing of it is cut.
 *
 * Only apps that are really open are here (Running.recent): with the key helper up, the ones whose process is still
 * there; without it, the ones in front in the last 3 hours. Never one closed since, never one from days ago.
 */
final class SwitcherSheet extends Sheet {
    /** At most this many cards: they fit across the screen at every Text size. */
    static final int MAX = 7;
    /** Card width, gap between cards, and the glass around them (room for the chosen card's rise and shadow). */
    private static final float CW = 150, GAP = 14, PAD = 26, ICON = 96;
    private final List<View> cards = new ArrayList<>();
    private final List<String> pkgs = new ArrayList<>();
    private LinearLayout row;
    private TextView hint;
    private String onScreen;

    SwitcherSheet(Desk a) {
        super(a);
        overlay = true;
    }

    @Override
    void build() {
        boolean over = a.overApp();
        View dim = new View(c);
        dim.setBackgroundColor(Ui.a(0x000000, over ? .34f : .10f));
        root.addView(dim, Ui.fill());
        onScreen = Glass.selfInFront() ? null : Glass.front();
        List<String[]> recent = Running.recent(c, MAX);
        // the card grows with the words (Text size); the glass leaves PAD all round it
        float nameH = Ui.boxf(20, 15), agoH = Ui.boxf(17, 13);
        float ch = 20 + ICON + 12 + nameH + 3 + agoH + 16;
        int n = Math.max(1, recent.size());
        float w = n * CW + (n - 1) * GAP + 2 * PAD, h = ch + 2 * PAD;
        float x = (1280 - w) / 2, y = (720 - h) / 2;
        pane = new Pane(c, over, x, y, w, h, 36);
        pane.overApp(over);
        FrameLayout holder = new FrameLayout(c);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        row = Ui.row(c);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(Ui.px(PAD), Ui.px(PAD), Ui.px(PAD), Ui.px(PAD));
        holder.addView(row, Ui.fill());
        if (recent.isEmpty()) empty(ch);
        long now = System.currentTimeMillis();
        for (String[] r : recent) {
            String pkg = r[0];
            long when;
            try {
                when = Long.parseLong(r[1]);
            } catch (NumberFormatException e) {
                when = now;
            }
            LinearLayout card = Ui.column(c);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(Ui.px(8), Ui.px(20), Ui.px(8), Ui.px(16));
            card.setClipChildren(false);
            card.setClipToPadding(false);
            card.setFocusable(true);
            card.setClickable(true);
            card.setLongClickable(true);
            ImageView icon = new ImageView(c);
            icon.setImageBitmap(AppIcons.of(c, pkg, Ui.px(ICON)));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(ICON * 0.235f));
                }
            });
            icon.setElevation(Ui.pxf(2));
            card.addView(icon, Ui.ll(ICON, ICON));
            CharSequence l = Glass.label(c, pkg);
            TextView name = Ui.line(c, 15, t.ink, 500, l == null ? pkg : l.toString());
            name.setGravity(Gravity.CENTER);
            name.setTag("clip-ok");   // a long name scrolls into view while its card is chosen (Ui.marquee)
            card.addView(name, Ui.ll(-2, nameH, 0, 12));
            boolean live = pkg.equals(onScreen);
            String ago = live ? "On screen" : now - when < DateUtils.MINUTE_IN_MILLIS ? "Just now"
                    : DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
            TextView sub = Ui.line(c, 13, t.ink3, 400, ago);
            sub.setGravity(Gravity.CENTER);
            card.addView(sub, Ui.ll(-2, agoH, 0, 3));
            Ui.focusRing(card, 22);
            Ui.lift(card, 1.05f, 8);
            card.setOnFocusChangeListener((v, has) -> {
                name.setTypeface(Ui.face(c, has ? 600 : 500));
                if (has) Sound.tick();
            });
            card.setOnClickListener(v -> {
                a.dismiss(this);
                if (!pkg.equals(onScreen)) a.launch(pkg, icon);   // the one on screen: just back to it, as it was
            });
            card.setOnLongClickListener(v -> {
                menu(pkg, v);
                return true;
            });
            row.addView(card, Ui.ll(CW, ch, cards.isEmpty() ? 0 : GAP, 0));
            cards.add(card);
            pkgs.add(pkg);
        }
        pane.addView(holder, Ui.fill());
        root.addView(pane, pane.params());
        pane.bake(c);
        hint = Ui.text(c, 13, over ? 0xFFFFFFFF : t.ink, 400);
        hint.setAlpha(over ? 0.85f : 0.7f);
        hint.setGravity(Gravity.CENTER);
        hint.setText(recent.isEmpty() ? "Hold Home" : "← → choose · OK open · ↑ close the app · hold OK for more");
        root.addView(hint, Ui.at(0, y + h + 16, 1280, -1));
    }

    private void empty(float ch) {
        TextView none = Ui.text(c, 15, t.ink2, 400);
        none.setText("Apps you open will line up here");
        none.setGravity(Gravity.CENTER);
        none.setFocusable(true);
        row.addView(none, Ui.ll(CW, ch));
        cards.add(none);
        pkgs.add(null);
    }

    @Override
    View firstFocus() {
        if (cards.isEmpty()) return null;
        // the one before the app on screen: hold Home, OK, and you're back in it
        if (cards.size() > 1 && onScreen != null && onScreen.equals(pkgs.get(0))) return cards.get(1);
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
        return true;   // ↑ never leaves the cards
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
        if (pkg.equals(onScreen)) onScreen = null;
        Running.close(c, pkg, a::runningChanged);
        card.animate().translationY(-Ui.pxf(70)).alpha(0f).setDuration(Ui.ms(220)).setInterpolator(Ui.EIN).withEndAction(() -> {
            row.removeView(card);
            if (!cards.isEmpty()) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cards.get(0).getLayoutParams();
                lp.leftMargin = 0;
                cards.get(0).setLayoutParams(lp);
            } else {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                empty(lp.height / Ui.s);
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
        float y = Math.min(720 - 110, loc[1] / Ui.s + card.getHeight() / Ui.s - 24);
        a.stack(new Menu(a, rows, x, y, 200, false), card);
    }
}
