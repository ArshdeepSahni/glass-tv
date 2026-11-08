package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import io.nayuki.qrcodegen.QrCode;

/**
 * Controls › Phone: connect a phone to the TV. A code to scan (it opens Glass in the phone's browser, served by the
 * TV on the home Wi-Fi), the same as six digits for a phone without a camera, and the phones already connected.
 * While this is open, phones may ask to join; the TV then asks "Allow this phone?" (Allow, below).
 */
final class PhoneSheet extends Sheet {
    static final float X = 280, Y = 86, W = 720, H = 470;

    private static PhoneSheet open;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ImageView qr;
    private TextView countdown, address;
    private LinearLayout digits, right, phonesPage;
    private TextView phonesButton;
    private String shownToken;

    PhoneSheet(Desk a) {
        super(a);
        window = true;
        ox = 1084;
        oy = 17;
    }

    @Override
    void build() {
        pane = new Pane(c, true, X, Y, W, H, Ui.PANEL_R);
        pane.overApp(a.overApp());
        FrameLayout in = new FrameLayout(c);

        // the code to scan
        FrameLayout card = new FrameLayout(c);
        GradientDrawable white = Ui.fillRound(Color.WHITE, 20);
        card.setBackground(white);
        card.setElevation(Ui.pxf(4));
        qr = new ImageView(c);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ql = new FrameLayout.LayoutParams(Ui.px(252), Ui.px(252));
        ql.gravity = Gravity.CENTER;
        card.addView(qr, ql);
        in.addView(card, Ui.at(28, 28, 272, 272));
        countdown = Ui.line(c, 13, t.ink2, 400, "");
        countdown.setGravity(Gravity.CENTER);
        in.addView(countdown, Ui.at(28, 312, 272, 18));

        // how
        right = Ui.column(c);
        TextView title = Ui.line(c, 24, t.ink, 600, "Connect your phone");
        title.setLetterSpacing(-0.02f);
        right.addView(title);
        TextView sub = Ui.text(c, 15, t.ink2, 400);
        sub.setText("Glass opens in your phone’s browser — nothing to install.");
        right.addView(sub, Ui.ll(-2, -1, 0, 6));
        LinearLayout steps = Ui.column(c);
        String[] s = {"Point your phone’s camera here", "Tap <b>Connect</b> on your phone", "Allow it here, on the TV"};
        for (int i = 0; i < s.length; i++) {
            LinearLayout row = Ui.row(c);
            TextView n = Ui.line(c, 13, t.ink, 600, String.valueOf(i + 1));
            n.setGravity(Gravity.CENTER);
            GradientDrawable chip = Ui.fillRound(t.hover, 14);
            chip.setStroke(Math.max(1, Ui.px(1)), t.hair);
            n.setBackground(chip);
            row.addView(n, Ui.ll(28, 28));
            TextView w = Ui.text(c, 17, t.ink, 400);   // two lines when the words are big, never cut
            w.setMaxLines(2);
            w.setText(Html.fromHtml(s[i], Html.FROM_HTML_MODE_LEGACY));
            row.addView(w, new LinearLayout.LayoutParams(0, -2, 1f) {{ leftMargin = Ui.px(12); }});
            row.setMinimumHeight(Ui.px(40));
            steps.addView(row, Ui.ll(-2, -1, 0, i == 0 ? 0 : 4));
        }
        right.addView(steps, Ui.ll(-2, -1, 0, 18));
        View rule = new View(c);
        rule.setBackgroundColor(t.hair);
        right.addView(rule, Ui.ll(-2, 1, 0, 20));
        address = Ui.text(c, 13, t.ink2, 400);
        right.addView(address, Ui.ll(-2, -1, 0, 16));
        digits = Ui.row(c);
        right.addView(digits, Ui.ll(-1, Ui.boxf(44, 17), 0, 10));
        in.addView(right, Ui.at(332, 30, W - 332 - 32, H - 30 - 90));

        // the phones already connected (Phones page), shown instead of the steps
        phonesPage = Ui.column(c);
        phonesPage.setVisibility(View.GONE);
        in.addView(phonesPage, Ui.at(332, 30, W - 332 - 32, H - 30 - 90));

        // along the bottom
        TextView note = Ui.text(c, 13, t.ink2, 400);
        note.setText("Phones work only on this home Wi‑Fi. Remove one any time from Phones here.");
        in.addView(note, Ui.at(28, H - 22 - 44, 330, 44));
        note.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout buttons = Ui.row(c);
        buttons.setClipChildren(false);   // the buttons rise on focus
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        phonesButton = button("Phones", false);
        phonesButton.setOnClickListener(v -> togglePhones());
        buttons.addView(phonesButton, Ui.ll(-1, Ui.boxf(44, 15)));
        TextView done = button("Done", true);
        done.setOnClickListener(v -> a.dismiss(this));
        buttons.addView(done, Ui.ll(-1, Ui.boxf(44, 15), 10, 0));
        in.addView(buttons, Ui.at(W - 28 - 360, H - 22 - Ui.boxf(44, 15), 360, Ui.boxf(44, 15)));
        content = in;
        pane.addView(in, Ui.fill());
        root.addView(pane, pane.params());
        pane.bake(c);
        refresh();
        open = this;
        Phones.setAsker(this::allow);
        h.post(tick);
    }

    @Override
    View firstFocus() {
        return ((LinearLayout) phonesButton.getParent()).getChildAt(1);
    }

    @Override
    boolean onBack() {
        if (phonesPage.getVisibility() == View.VISIBLE) {
            togglePhones();
            return true;
        }
        return false;
    }

    @Override
    void onClosed() {
        h.removeCallbacksAndMessages(null);
        if (open == this) open = null;
        Phones.setAsker(null);
        Phones.closing();
    }

    private TextView button(String label, boolean primary) {
        TextView b = Ui.line(c, 15, primary ? t.selink : t.ink, primary ? 600 : 500, label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.px(20), 0, Ui.px(20), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = Ui.fillRound(primary ? t.sel : t.hover, 14);
        if (!primary) bg.setStroke(Math.max(1, Ui.px(1)), t.hair);
        b.setBackground(bg);
        Ui.focusRing(b, 14);
        Ui.lift(b, 1.05f, 6);
        b.setOnFocusChangeListener((v, has) -> {
            if (has) Sound.tick();
        });
        return b;
    }

    /** The code, the link and the digits; a fresh code when this one is about to run out. */
    private void refresh() {
        Phones.Session s = Phones.open();
        String link = PhoneServer.link(c, s.token);
        if (!s.token.equals(shownToken)) {
            shownToken = s.token;
            qr.setImageDrawable(link == null ? null : qrDrawable(link));
            digits.removeAllViews();
            for (int i = 0; i < s.code.length(); i++) {
                TextView d = Ui.line(c, 24, t.ink, 600, s.code.substring(i, i + 1));
                d.setGravity(Gravity.CENTER);
                GradientDrawable g = Ui.fillRound(t.field, 10);
                g.setStroke(Math.max(1, Ui.px(1)), t.hair);
                d.setBackground(g);
                digits.addView(d, Ui.ll(34, 44, i == 0 ? 0 : (i == 3 ? 16 : 6), 0));
            }
        }
        String home = PhoneServer.link(c, null);
        address.setText(home == null ? "The TV isn’t on Wi‑Fi right now — connect it first."
                : Html.fromHtml("No camera? On the phone, open <b>" + home.replace("http://", "") + "</b> and enter", Html.FROM_HTML_MODE_LEGACY));
        int n = Phones.list(c).size();
        phonesButton.setText(n == 0 ? "Phones" : "Phones (" + n + ")");
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Phones.Session s = Phones.open();
            long left = Math.max(0, (s.until - SystemClock.elapsedRealtime()) / 1000);
            if (!s.token.equals(shownToken)) refresh();
            long shown = ((left + 14) / 15) * 15;   // in steps of 15 s: the screen stays still in between
            String text = String.format(Locale.ENGLISH, "A new code in %d:%02d", shown / 60, shown % 60);
            if (!text.contentEquals(countdown.getText())) countdown.setText(text);
            h.postDelayed(this, 1000);
        }
    };

    private void togglePhones() {
        boolean show = phonesPage.getVisibility() != View.VISIBLE;
        phonesPage.removeAllViews();
        if (show) {
            TextView title = Ui.line(c, 24, t.ink, 600, "Phones");
            title.setLetterSpacing(-0.02f);
            phonesPage.addView(title);
            java.util.List<Phones.Phone> all = Phones.list(c);
            if (all.isEmpty()) {
                phonesPage.addView(Ui.line(c, 15, t.ink2, 400, "No phones yet."), Ui.ll(-2, -1, 0, 10));
            }
            View first = null;
            int pi = 0;
            for (Phones.Phone p : all) {
                LinearLayout row = Ui.row(c);
                LinearLayout words = Ui.column(c);
                words.addView(Ui.line(c, 17, t.ink, 500, Sample.on ? Sample.phone(pi++) : p.name));
                words.addView(Ui.line(c, 13, t.ink2, 400, seen(p.seen)), Ui.ll(-1, -1, 0, 2));
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView remove = button("Remove", false);
                remove.setOnClickListener(v -> {
                    Phones.remove(c, p.id);
                    Sound.toggle(false);
                    togglePhones();
                    togglePhones();
                    refresh();
                });
                row.addView(remove, Ui.ll(-1, Ui.boxf(40, 15)));
                phonesPage.addView(row, Ui.ll(-2, Ui.boxf(56, 28), 0, 10));
                if (first == null) first = remove;
            }
            right.setVisibility(View.GONE);
            phonesPage.setVisibility(View.VISIBLE);
            (first != null ? first : phonesButton).requestFocus();
        } else {
            phonesPage.setVisibility(View.GONE);
            right.setVisibility(View.VISIBLE);
            phonesButton.requestFocus();
        }
    }

    private static String seen(long at) {
        long m = (System.currentTimeMillis() - at) / 60_000L;
        if (m < 2) return "Used just now";
        if (m < 60) return "Used " + m + " min ago";
        if (m < 48 * 60) return "Used " + (m / 60) + " h ago";
        return "Used " + (m / 1440) + " days ago";
    }

    static BitmapDrawable qrDrawable(String text) {
        QrCode q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 2, n = q.size + border * 2;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) if (q.getModule(x, y)) b.setPixel(x + border, y + border, 0xFF0E0E10);
        }
        BitmapDrawable d = new BitmapDrawable(null, b);
        d.setFilterBitmap(false);   // crisp squares at any size
        d.setAntiAlias(false);
        return d;
    }

    // ---- "Allow this phone?" ---------------------------------------------------------------------------------

    /** A phone asked to join while this is open: ask here (this window gives way to the question). */
    private void allow(Phones.Request r) {
        Desk d = a;
        d.dismiss(this);
        d.show(new Allow(d, r), null);
    }

    /** A phone asked to join with the window closed (within its short grace): ask on whatever is on screen. */
    static void askAnywhere(Context c, Phones.Request r) {
        if (GlassHomeActivity.showing()) {
            Allow.pending = r;
            GlassKeys.open(c, "phone-allow");
            return;
        }
        HomeKeyService svc = HomeKeyService.current();
        if (svc == null) {
            Phones.decide(c, r, false);
            return;
        }
        Hud hud = Hud.of(svc);
        hud.show(new Allow(hud, r), null);
    }

    static final class Allow extends Sheet {
        static final float X = 360, Y = 118, W = 560, H = 424;
        static Phones.Request pending;
        private final Phones.Request r;
        private final Handler h = new Handler(Looper.getMainLooper());
        private TextView allowB;
        private boolean answered;

        Allow(Desk a, Phones.Request r) {
            super(a);
            window = true;
            ox = 640;
            oy = 330;
            this.r = r;
        }

        @Override
        void build() {
            pane = new Pane(c, true, X, Y, W, H, Ui.PANEL_R);
            pane.overApp(a.overApp());
            FrameLayout in = new FrameLayout(c);
            FrameLayout disc = new FrameLayout(c);
            GradientDrawable dg = Ui.fillRound(t.field, 28);
            dg.setStroke(Math.max(1, Ui.px(1)), t.hair);
            disc.setBackground(dg);
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(Ui.px(26), Ui.px(26));
            gl.gravity = Gravity.CENTER;
            disc.addView(glyph(c, "phone", t.ink), gl);
            in.addView(disc, Ui.at(28, 28, 56, 56));
            TextView title = Ui.line(c, 24, t.ink, 600, "Allow this phone?");
            title.setLetterSpacing(-0.02f);
            in.addView(title, Ui.at(100, 30, W - 128, 30));
            in.addView(Ui.line(c, 15, t.ink2, 400, lead(r.name) + " scanned the code."), Ui.at(100, 62, W - 128, 20));
            LinearLayout codeCard = Ui.column(c);
            codeCard.setPadding(Ui.px(16), Ui.px(14), Ui.px(16), Ui.px(14));
            GradientDrawable cg = Ui.fillRound(t.field, 16);
            cg.setStroke(Math.max(1, Ui.px(1)), t.hair);
            codeCard.setBackground(cg);
            codeCard.addView(Ui.line(c, 13, t.ink2, 400, "It shows this code — check it matches"));
            TextView code = Ui.line(c, 28, t.ink, 600, r.code);
            code.setLetterSpacing(0.06f);
            codeCard.addView(code, Ui.ll(-2, -1, 0, 4));
            in.addView(codeCard, Ui.at(28, 116, W - 56, 86));
            LinearLayout can = Ui.column(c);
            can.addView(Ui.line(c, 13, t.ink2, 400, "On this home Wi‑Fi, it will be able to"));
            String[][] items = {{"remote", "Use it as a remote and keyboard"}, {"assist", "Talk to Glass"}, {"film", "See what’s playing"}};
            for (String[] it : items) {
                LinearLayout row = Ui.row(c);
                row.addView(glyph(c, it[0], t.ink2), Ui.ll(18, 18));
                row.addView(Ui.line(c, 15, t.ink, 400, it[1]), Ui.ll(-1, -1, 10, 0));
                can.addView(row, Ui.ll(-2, Ui.boxf(30, 15)));
            }
            in.addView(can, Ui.at(28, 222, W - 56, 120));
            LinearLayout buttons = Ui.row(c);
            buttons.setClipChildren(false);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            TextView no = pill("Not now", false);
            no.setOnClickListener(v -> answer(false));
            buttons.addView(no, Ui.ll(-1, Ui.boxf(44, 15)));
            allowB = pill("Allow", true);
            allowB.setOnClickListener(v -> answer(true));
            buttons.addView(allowB, Ui.ll(128, Ui.boxf(44, 15), 10, 0));
            in.addView(buttons, Ui.at(28, H - 24 - Ui.boxf(44, 15), W - 56, Ui.boxf(44, 15)));
            content = in;
            pane.addView(in, Ui.fill());
            root.addView(pane, pane.params());
            pane.bake(c);
            Sound.glass();
            h.postDelayed(() -> answer(false), 60_000L);   // the phone gave up waiting by now
        }

        private TextView pill(String label, boolean primary) {
            TextView b = Ui.line(c, 15, primary ? t.selink : t.ink, primary ? 600 : 500, label);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.px(20), 0, Ui.px(20), 0);
            b.setFocusable(true);
            b.setClickable(true);
            GradientDrawable bg = Ui.fillRound(primary ? t.sel : t.hover, 14);
            if (!primary) bg.setStroke(Math.max(1, Ui.px(1)), t.hair);
            b.setBackground(bg);
            Ui.focusRing(b, 14);
            Ui.lift(b, 1.05f, 6);
            b.setOnFocusChangeListener((v, has) -> {
                if (has) Sound.tick();
            });
            return b;
        }

        /** "An Android phone · Chrome", "An iPhone · Safari", "A phone". */
        private static String lead(String name) {
            String n = name == null || name.isEmpty() || "Phone".equals(name) ? "phone" : name;
            char first = Character.toLowerCase(n.charAt(0));
            return ("aeiou".indexOf(first) >= 0 ? "An " : "A ") + n;
        }

        @Override
        View firstFocus() {
            return allowB;
        }

        @Override
        boolean onBack() {
            answer(false);
            return true;
        }

        @Override
        void onClosed() {
            h.removeCallbacksAndMessages(null);
            if (!answered) Phones.decide(c, r, false);
        }

        private void answer(boolean yes) {
            if (answered) return;
            answered = true;
            Phones.decide(c, r, yes);
            if (yes) Sound.toggle(true);
            a.dismiss(this);
            if (yes) {
                HomeKeyService svc = HomeKeyService.current();
                if (svc != null) Hud.of(svc).note("Phone connected", r.name);
            }
        }
    }
}
