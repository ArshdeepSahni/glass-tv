package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
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

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Control Center. Everything works right here; only what Android keeps for its own settings sends you there:
 *   main       Wi-Fi ›, Bluetooth ›, Sleep (OK steps the timer), Night volume (OK steps the ceiling),
 *              Volume and Brightness (the TV's backlight, or else the picture dimmed) with ← →,
 *              Text size and Appearance (Light · Auto · Dark) with ← →, Phone ›
 *   wifi       the switch, the networks in reach, joining one (password typed here). With Location off, a row that
 *              turns on its TV-only kind first. Android 10 and later keep the switch and joining for the TV's
 *              own Wi-Fi page, which those open.
 *   bluetooth  the switch, your devices; OK sends the TV's sound to headphones or a speaker, or brings it back.
 *              Turned on here, it says "Turning on…" until it is, and if it never comes (a TV whose Bluetooth keeps
 *              stopping), that a restart fixes it, with Restart the TV
 * Back from a page puts focus back on whatever opened it; a page's last action sits at the panel's foot.
 *   sound      volume, where the sound plays, the night ceiling (the bar's volume item opens this one)
 * Pages slide like cards; whatever has focus rises a little on a soft shadow, a row on the bright platter, a tile with
 * a sheen of light over it (no rings). Back from a page puts focus back on what opened it. Same panel over a film
 * (dark tinted glass).
 */
final class ControlsSheet extends Sheet {
    static final float W = 404;
    // heights follow the Text size (Ui.boxf): at M they are the boards' 64 · 76 · 52 · 56, and the panel is 512 high
    private static final float GAP = 8;
    private final float TILE = Ui.boxf(64, 28), SLIDE = Ui.boxf(76, 13), SEG = Ui.boxf(52, 13), LINK = Ui.boxf(56, 28),
            HEAD = Ui.boxf(52, 17), ROW = Ui.boxf(44, 15), FOOTH = Ui.boxf(40, 15);
    private final float H = 12 + 2 * TILE + 2 * SLIDE + 2 * SEG + LINK + 6 * GAP + 12;
    private final float FOOT = H - 12 - FOOTH;   // a page's last action, at the panel's foot
    private static final int[] SLEEP = {0, 30, 60, 90, 120};
    private static final int[] CEILING = {0, 10, 12, 15};
    private static final float[] TEXT = {0.85f, 1f, 1.15f, 1.3f};

    private final String start, key;
    private FrameLayout stage;
    private View page;
    private String current;
    private final List<View> focusables = new ArrayList<>();
    private Slider volume, picture;
    private Seg textSize, look;
    private String cameFrom;                 // the page Back just left: focus goes back to what opened it
    private final java.util.Map<String, View> openers = new java.util.HashMap<>();
    private long btOffAsked;
    private Tile wifiT, btT, sleepT, nightT;
    private TextView outputValue;
    private Net.Ap joining;
    private EditText password;

    private String focusKey;   // after the desktop is rebuilt (a new look, a new text size): focus back on that

    ControlsSheet(Desk a, String page, String key) {
        super(a);
        this.start = page;
        this.key = key;
        barKey = key;
    }

    /** Opens with focus on "look" (Appearance) or "text" (Text size), where it was before the desktop was rebuilt. */
    ControlsSheet focusOn(String what) {
        focusKey = what;
        return this;
    }

    /** Brightness shows wherever Glass can change it: the TV's backlight, or the picture dimmed (Backlight, Dim).
     *  Only without Glass's helper is that row where the sound goes. */
    static boolean pictureWorks(android.content.Context c) {
        return Backlight.works(c);
    }

    @Override
    void build() {
        float x = 1280 - Ui.EDGE - W, y = a.panelTop();
        pane = new Pane(c, true, x, y, W, H, Ui.PANEL_R);
        pane.overApp(a.overApp());
        stage = new FrameLayout(c);
        stage.setClipChildren(true);
        pane.addView(stage, Ui.fill());
        content = stage;
        dropX = a.barCenter(key);
        root.addView(pane, pane.params());
        pane.bake(c);
        go(start, 0);
    }

    @Override
    View firstFocus() {
        if ("look".equals(focusKey) && look != null) return look.v;
        if ("text".equals(focusKey) && textSize != null) return textSize.v;
        return focusables.isEmpty() ? null : focusables.get(0);
    }

    /** Shows a page; dir: 0 in place, 1 forward (slides in from the right), -1 back. */
    private void go(String name, int dir) {
        View old = page;
        cameFrom = dir < 0 ? current : null;
        focusables.clear();
        openers.clear();
        current = name;
        FrameLayout p = new FrameLayout(c);
        p.setClipChildren(false);
        switch (name) {
            case "wifi": wifi(p); break;
            case "bluetooth": bluetooth(p); break;
            case "sound": sound(p); break;
            default: main(p);
        }
        stage.addView(p, Ui.fill());
        page = p;
        if (dir != 0 && old != null) {
            float d = Ui.pxf(W) * 0.35f * dir;
            p.setTranslationX(d);
            p.setAlpha(0f);
            Spring.to(p, DynamicAnimation.TRANSLATION_X, 0f, Spring.PANEL);
            Spring.to(p, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
            old.animate().translationX(-d).alpha(0f).setDuration(Ui.ms(160)).withEndAction(() -> stage.removeView(old)).start();
            Sound.tick();
        } else if (old != null) {
            stage.removeView(old);
        }
        View first = cameFrom != null && openers.get(cameFrom) != null ? openers.get(cameFrom) : focusables.isEmpty() ? null : focusables.get(0);
        if ("look".equals(focusKey) && look != null) first = look.v;
        if ("text".equals(focusKey) && textSize != null) first = textSize.v;
        focusKey = null;
        if (first != null) {
            final View f = first;
            f.post(f::requestFocus);
        }
    }

    /** Focus, Glass's way: a tile (it has a fill) gets a sheen and rises; a row gets the platter and rises a hair. */
    private void focusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        boolean tile = v.getBackground() != null;
        Ui.focusRing(v, tile ? 16 : 12);
        if (tile) Ui.lift(v, 1.035f, 8); else Ui.lift(v, 1f, 2);
        v.setOnFocusChangeListener((x, has) -> {
            if (has) Sound.tick();
        });
        focusables.add(v);
    }

    // ---- pages ----------------------------------------------------------------------------------------------

    private void main(FrameLayout p) {
        wifiT = new Tile("wifi", "Wi-Fi");
        btT = new Tile("bt", "Bluetooth");
        sleepT = new Tile("moon", "Sleep");
        nightT = new Tile("volume", "Night volume");
        float y = 12;
        p.addView(wifiT.v, Ui.at(12, y, 186, TILE));
        p.addView(btT.v, Ui.at(206, y, 186, TILE));
        y += TILE + GAP;
        p.addView(sleepT.v, Ui.at(12, y, 186, TILE));
        p.addView(nightT.v, Ui.at(206, y, 186, TILE));
        y += TILE + GAP;
        volume = new Slider("volume", "Volume");
        p.addView(volume.v, Ui.at(12, y, 380, SLIDE));
        y += SLIDE + GAP;
        if (pictureWorks(c)) {
            picture = new Slider("sun", "Brightness");
            p.addView(picture.v, Ui.at(12, y, 380, SLIDE));
        } else {
            picture = null;
            LinearLayout out = link("Sound output", outputName(), () -> go("sound", 1));
            outputValue = (TextView) ((LinearLayout) out.getChildAt(0)).getChildAt(1);
            p.addView(out, Ui.at(12, y, 380, SLIDE));
            openers.put("sound", out);
        }
        y += SLIDE + GAP;
        textSize = new Seg("Text size", new String[]{"S", "M", "L", "XL"});
        p.addView(textSize.v, Ui.at(12, y, 380, SEG));
        y += SEG + GAP;
        look = new Seg("Appearance", new String[]{"Light", "Auto", "Dark"});
        p.addView(look.v, Ui.at(12, y, 380, SEG));
        y += SEG + GAP;
        final float linkY = y;
        int phones = Phones.list(c).size();
        LinearLayout phone = link("Phone", phones == 0 ? "Connect your phone" : phones == 1 ? "1 phone connected" : phones + " phones connected", () -> {
            Desk d = a;
            d.dismiss(this);
            d.show(new PhoneSheet(d), null);
        });
        p.addView(phone, Ui.at(12, linkY, 380, LINK));
        openers.put("wifi", wifiT.v);
        openers.put("bluetooth", btT.v);
        wifiT.v.setOnClickListener(v -> go("wifi", 1));
        btT.v.setOnClickListener(v -> go("bluetooth", 1));
        sleepT.v.setOnClickListener(v -> {
            int now = SleepTimer.minutesLeft(), next = 0;
            for (int m : SLEEP) if (m > now + 1) { next = m; break; }
            if (next == 0) SleepTimer.cancel(); else SleepTimer.set(next);
            Sound.toggle(next != 0);
            refresh();
        });
        nightT.v.setOnClickListener(v -> {
            SharedPreferences g = Glass.prefs(c);
            int cur = g.getInt(Glass.NIGHT_CEILING, 0), next = 0;
            for (int i = 0; i < CEILING.length; i++) if (CEILING[i] == cur) next = CEILING[(i + 1) % CEILING.length];
            g.edit().putInt(Glass.NIGHT_CEILING, next).apply();
            Sound.toggle(next != 0);
            refresh();
        });
        // focus starts on Volume, the thing people come here for most
        focusables.remove(volume.v);
        focusables.add(0, volume.v);
        refresh();
    }

    private LinearLayout header(String title, boolean back) {
        LinearLayout h = Ui.row(c);
        h.setPadding(Ui.px(12), 0, Ui.px(14), 0);
        if (back) h.addView(glyph(c, "chev_l", t.ink2), Ui.ll(20, 20));
        TextView tv = Ui.line(c, 17, t.ink, 600, title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Ui.px(back ? 6 : 2);
        h.addView(tv, tl);
        return h;
    }

    private void wifi(FrameLayout p) {
        boolean on = Net.on(c);
        LinearLayout head = header("Wi-Fi", "main".equals(start) || !"wifi".equals(start));
        Switch sw = new Switch(on);
        head.addView(sw.v);
        focusable(head);
        head.setOnClickListener(v -> {
            boolean want = !Net.on(c);
            // Android 10 and later keep the Wi-Fi switch for their own settings: there it opens the TV's Wi-Fi page
            if (!Net.inPlace() || !Net.set(c, want)) {
                a.openSystem(Settings.ACTION_WIFI_SETTINGS);
                return;
            }
            Sound.toggle(want);
            sw.set(want);
            v.postDelayed(() -> go("wifi", 0), 1500);
        });
        p.addView(head, Ui.at(12, 12, 380, HEAD));
        if (joining != null) {
            joinForm(p);
            return;
        }
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = Ui.column(c);
        scroll.addView(list);
        if (on && !Net.locationOn(c)) {
            // Android names the networks around (and the one the TV is on) to apps only while Location is on
            LinearLayout r = Ui.row(c);
            r.setPadding(Ui.px(12), 0, Ui.px(12), 0);
            r.addView(glyph(c, "location", t.ink2), Ui.ll(18, 18));
            TextView name = Ui.line(c, 15, t.ink, 500, "Show the networks");
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
            nl.leftMargin = Ui.px(12);
            r.addView(name, nl);
            r.addView(Ui.line(c, 13, t.ink3, 400, "Turns on Location"), Ui.ll(-1, -1, 8, 0));
            focusable(r);
            r.setOnClickListener(v -> {
                if (Net.turnOnLocation(c)) {
                    Sound.toggle(true);
                    go("wifi", 0);
                } else {
                    a.openSystem(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                }
            });
            list.addView(r, Ui.ll(-2, ROW));
            list.addView(note("Android lists nearby Wi-Fi only while Location is on. Glass turns on the TV-only kind, "
                    + "so nothing is sent to Google."));
        } else if (on) {
            Net.scan(c);
            List<Net.Ap> aps = Net.list(c);
            if (aps.isEmpty()) list.addView(note("Looking for networks…"));
            int n = 0;
            for (Net.Ap ap : aps) {
                if (n++ >= 8) break;
                LinearLayout r = Ui.row(c);
                r.setPadding(Ui.px(12), 0, Ui.px(12), 0);
                String g = Net.bars(ap.level) >= 3 ? "wifi" : Net.bars(ap.level) == 2 ? "wifi2" : "wifi1";
                r.addView(glyph(c, g, ap.current ? t.ink : t.ink2), Ui.ll(18, 18));
                TextView name = Ui.line(c, 15, t.ink, ap.current ? 600 : 400, ap.ssid);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
                nl.leftMargin = Ui.px(12);
                r.addView(name, nl);
                if (ap.current) r.addView(Ui.line(c, 13, t.ink3, 400, "Connected"), Ui.ll(-1, -1, 8, 0));
                else if (ap.saved) r.addView(Ui.line(c, 13, t.ink3, 400, "Saved"), Ui.ll(-1, -1, 8, 0));
                if (ap.secured) r.addView(glyph(c, "lock", t.ink3), Ui.ll(14, 14, 8, 0));
                focusable(r);
                r.setOnClickListener(v -> {
                    if (ap.current) return;
                    if (!Net.inPlace()) {   // Android 10 and later join networks in their own settings
                        a.openSystem(Settings.ACTION_WIFI_SETTINGS);
                        return;
                    }
                    if (ap.secured && !ap.saved) {
                        joining = ap;
                        go("wifi", 0);
                        return;
                    }
                    boolean ok = Net.join(c, ap, null);
                    Sound.toggle(ok);
                    toast(ok ? "Joining " + ap.ssid + "…" : "Couldn't join " + ap.ssid);
                    v.postDelayed(() -> go("wifi", 0), 2500);
                });
                list.addView(r, Ui.ll(-2, ROW));
            }
        } else {
            list.addView(note("Wi-Fi is off. OK on the switch above turns it on."));
        }
        p.addView(scroll, Ui.at(12, 12 + HEAD + 4, 380, FOOT - 12 - HEAD - 12));
        LinearLayout more = link("Network settings", null, () -> a.openSettings("network"));
        p.addView(more, Ui.at(12, FOOT, 380, FOOTH));
    }

    private void joinForm(FrameLayout p) {
        TextView label = Ui.line(c, 13, t.ink2, 400, "Password for " + joining.ssid);
        p.addView(label, Ui.at(24, 84, 356, -1));
        password = new EditText(c);
        password.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(17) * Ui.fs);
        password.setTypeface(Ui.face(c, 400));
        password.setTextColor(t.ink);
        password.setHintTextColor(t.ink3);
        password.setHint("At least 8 characters");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        android.graphics.drawable.GradientDrawable fb = Ui.fillRound(t.field, 12);
        fb.setStroke(Math.max(1, Ui.px(1)), t.hair);
        password.setBackground(fb);
        password.setPadding(Ui.px(14), 0, Ui.px(14), 0);
        password.setOnEditorActionListener((v, id, ev) -> {
            String pw = password.getText().toString();
            Net.Ap ap = joining;
            boolean ok = Net.join(c, ap, pw);
            Sound.toggle(ok);
            toast(ok ? "Joining " + ap.ssid + "…" : "That password needs 8 characters or more");
            if (ok) {
                hideKeyboard();
                joining = null;
                password.postDelayed(() -> go("wifi", 0), 2500);
            }
            return true;
        });
        Ui.focusRing(password, 12);
        p.addView(password, Ui.at(12, 108, 380, Ui.boxf(48, 17)));
        focusables.add(password);
        password.post(() -> {
            password.requestFocus();
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(password, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = c.getSystemService(InputMethodManager.class);
        if (imm != null && password != null) imm.hideSoftInputFromWindow(password.getWindowToken(), 0);
    }

    /** Bluetooth was turned on here and is still coming on until then (uptime); after it, if it isn't on, it failed. */
    private static long btWaitUntil;
    private static boolean btFailed;
    private static final long BT_WAIT_MS = 12_000;
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView btNote;

    /** Watches Bluetooth coming on after the switch: the page again once it's on, or once it clearly didn't come. */
    private final Runnable btWatch = new Runnable() {
        @Override
        public void run() {
            if (!"bluetooth".equals(current) || btNote == null || !btNote.isAttachedToWindow()) return;
            if (Bt.on()) {
                btWaitUntil = 0;
                btFailed = false;
                Sound.toggle(true);
                go("bluetooth", 0);
            } else if (SystemClock.uptimeMillis() >= btWaitUntil) {
                btWaitUntil = 0;
                btFailed = true;
                Sound.bump();
                go("bluetooth", 0);
            } else {
                UI.postDelayed(this, 600);
            }
        }
    };

    /** "waiting" (turned on here, still coming), "failing" (it doesn't come on), "on" or "off". */
    private String btState() {
        if (Bt.on()) {
            btWaitUntil = 0;
            btFailed = false;
            return "on";
        }
        if (btWaitUntil != 0 && SystemClock.uptimeMillis() < btWaitUntil) return "waiting";
        if (btWaitUntil != 0) {
            btWaitUntil = 0;
            btFailed = true;
        }
        return btFailed || Bt.stuck(c) ? "failing" : "off";
    }

    private void bluetooth(FrameLayout p) {
        String st = btState();
        boolean on = "on".equals(st);
        LinearLayout head = header("Bluetooth", "main".equals(start) || !"bluetooth".equals(start));
        Switch sw = new Switch(on || "waiting".equals(st));
        head.addView(sw.v);
        focusable(head);
        head.setOnClickListener(v -> {
            if ("waiting".equals(btState())) {
                Sound.bump();
                return;
            }
            boolean want = !Bt.on();
            // the remote may talk Bluetooth: turning it off asks twice, so it isn't done by a stray press
            if (!want && Bt.remoteConnected() && SystemClock.uptimeMillis() - btOffAsked > 4000) {
                btOffAsked = SystemClock.uptimeMillis();
                Sound.bump();
                toast("The remote uses Bluetooth. Press OK again to turn it off (the phone and the TV's own buttons still work).");
                return;
            }
            btOffAsked = 0;
            if (!Bt.set(want)) {
                toast("The TV wouldn't change Bluetooth just now");
                return;
            }
            Sound.toggle(want);
            sw.set(want);
            if (want) {
                // it takes a few seconds, and on some TVs it never comes: the page says which
                btWaitUntil = SystemClock.uptimeMillis() + BT_WAIT_MS;
                btFailed = false;
                go("bluetooth", 0);
            } else {
                v.postDelayed(() -> go("bluetooth", 0), 1600);
            }
        });
        p.addView(head, Ui.at(12, 12, 380, HEAD));
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = Ui.column(c);
        scroll.addView(list);
        if ("waiting".equals(st)) {
            btNote = note("Turning on…");
            list.addView(btNote);
            UI.removeCallbacks(btWatch);
            UI.postDelayed(btWatch, 600);
        } else if ("failing".equals(st)) {
            btNote = note("Bluetooth isn't starting on this TV, so a Bluetooth remote has no microphone. "
                    + "Restarting the TV fixes it.");
            list.addView(btNote);
            if (HomeKeyService.canOpenPowerMenu()) {
                LinearLayout r = Ui.row(c);
                r.setPadding(Ui.px(12), 0, Ui.px(12), 0);
                r.addView(glyph(c, "power", t.ink2), Ui.ll(18, 18));
                TextView name = Ui.line(c, 15, t.ink, 500, "Restart the TV");
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
                nl.leftMargin = Ui.px(12);
                r.addView(name, nl);
                focusable(r);
                r.setOnClickListener(v -> HomeKeyService.openPowerMenu());
                list.addView(r, Ui.ll(-2, ROW));
            }
        } else {
            btNote = null;
        }
        List<Bt.Dev> devs = Bt.devices();
        if (devs.isEmpty() && btNote == null) list.addView(note(on ? "No devices yet. Pair headphones or a speaker below." : "Bluetooth is off."));
        for (Bt.Dev d : devs) {
            LinearLayout r = Ui.row(c);
            r.setPadding(Ui.px(12), 0, Ui.px(12), 0);
            String g = d.remote ? "remote" : d.audio ? (d.name.toLowerCase(java.util.Locale.ROOT).contains("bud") || d.name.toLowerCase(java.util.Locale.ROOT).contains("head") ? "headphones" : "speaker") : "bt";
            r.addView(glyph(c, g, d.connected ? t.ink : t.ink2), Ui.ll(18, 18));
            TextView name = Ui.line(c, 15, t.ink, d.connected ? 600 : 400, d.name);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
            nl.leftMargin = Ui.px(12);
            r.addView(name, nl);
            TextView state = Ui.line(c, 13, t.ink3, 400, d.remote ? "Remote" : d.connected ? "Connected" : "Not connected");
            r.addView(state, Ui.ll(-1, -1, 8, 0));
            focusable(r);
            if (d.audio) r.setOnClickListener(v -> {
                state.setText(d.connected ? "Disconnecting…" : "Connecting…");
                Bt.audio(c, d.device, !d.connected, ok -> v.post(() -> {
                    Sound.toggle(ok && !d.connected);
                    if (!ok) toast("Couldn't reach " + d.name);
                    v.postDelayed(() -> go("bluetooth", 0), 1800);
                }));
            });
            list.addView(r, Ui.ll(-2, ROW));
        }
        p.addView(scroll, Ui.at(12, 12 + HEAD + 4, 380, FOOT - 12 - HEAD - 12));
        LinearLayout pair = link("Pair a new device", null, () -> a.openSystem(SystemSettings.PAIR));
        p.addView(pair, Ui.at(12, FOOT, 380, FOOTH));
    }

    private void sound(FrameLayout p) {
        LinearLayout head = header("Sound", !"sound".equals(start));
        focusable(head);
        head.setOnClickListener(v -> { if (!"sound".equals(start)) go("main", -1); });
        p.addView(head, Ui.at(12, 12, 380, HEAD));
        volume = new Slider("volume", "Volume");
        p.addView(volume.v, Ui.at(12, 12 + HEAD + 4, 380, SLIDE));
        focusables.remove(head);
        focusables.add(0, volume.v);
        focusables.add(head);
        TextView out = Ui.line(c, 13, t.ink3, 600, "Plays on");
        out.setPadding(Ui.px(14), 0, 0, 0);
        p.addView(out, Ui.at(12, 12 + HEAD + 4 + SLIDE + GAP, 380, -1));
        LinearLayout list = Ui.column(c);
        List<Bt.Dev> audio = new ArrayList<>();
        for (Bt.Dev d : Bt.devices()) if (d.audio) audio.add(d);
        boolean any = false;
        for (Bt.Dev d : audio) any |= d.connected;
        list.addView(outputRow("tv", "TV speakers", !any, null), Ui.ll(-2, ROW));
        for (Bt.Dev d : audio) list.addView(outputRow("headphones", d.name, d.connected, d), Ui.ll(-2, ROW));
        float listY = 12 + HEAD + 4 + SLIDE + GAP + Ui.boxf(16, 13) + 4;   // under "Plays on"
        p.addView(list, Ui.at(12, listY, 380, FOOT - listY - 8));
        int ceil = Glass.prefs(c).getInt(Glass.NIGHT_CEILING, 0);
        LinearLayout night = link("Night volume", ceil > 0 ? ceil + " after 11 pm" : "Off", null);
        night.setOnClickListener(v -> {
            SharedPreferences g = Glass.prefs(c);
            int cur = g.getInt(Glass.NIGHT_CEILING, 0), next = 0;
            for (int i = 0; i < CEILING.length; i++) if (CEILING[i] == cur) next = CEILING[(i + 1) % CEILING.length];
            g.edit().putInt(Glass.NIGHT_CEILING, next).apply();
            Sound.toggle(next != 0);
            ((TextView) ((LinearLayout) night.getChildAt(0)).getChildAt(1)).setText(next > 0 ? next + " after 11 pm" : "Off");
        });
        p.addView(night, Ui.at(12, FOOT, 380, FOOTH));
        refresh();
    }

    private LinearLayout outputRow(String g, String name, boolean on, Bt.Dev d) {
        LinearLayout r = Ui.row(c);
        r.setPadding(Ui.px(12), 0, Ui.px(12), 0);
        r.addView(glyph(c, g, on ? t.ink : t.ink2), Ui.ll(18, 18));
        TextView n = Ui.line(c, 15, t.ink, on ? 600 : 400, name);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
        nl.leftMargin = Ui.px(12);
        r.addView(n, nl);
        if (on) r.addView(glyph(c, "check", t.ink), Ui.ll(16, 16, 8, 0));
        focusable(r);
        r.setOnClickListener(v -> {
            if (on) return;
            if (d != null) {
                Bt.audio(c, d.device, true, ok -> v.post(() -> { Sound.toggle(ok); v.postDelayed(() -> go("sound", 0), 1500); }));
            } else {
                // back to the TV's speakers: let go of whichever Bluetooth device has the sound
                for (Bt.Dev x : Bt.devices()) if (x.audio && x.connected) Bt.audio(c, x.device, false, ok -> v.post(() -> go("sound", 0)));
            }
        });
        return r;
    }

    private String outputName() {
        for (Bt.Dev d : Bt.devices()) if (d.audio && d.connected) return d.name;
        return "TV speakers";
    }

    private TextView note(String s) {
        TextView v = Ui.text(c, 13, t.ink3, 400);
        v.setText(s);
        v.setPadding(Ui.px(14), Ui.px(10), Ui.px(14), Ui.px(10));
        return v;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** A row that goes somewhere: title (and value) on the left, a chevron on the right. */
    private LinearLayout link(String title, String value, Runnable go) {
        LinearLayout r = Ui.row(c);
        r.setPadding(Ui.px(14), 0, Ui.px(12), 0);
        r.setBackground(Ui.fillRound(t.hover, 16));
        LinearLayout words = Ui.column(c);
        words.addView(Ui.line(c, value == null ? 15 : 13, t.ink, value == null ? 500 : 600, title));
        if (value != null) words.addView(Ui.line(c, 15, t.ink2, 400, value), Ui.ll(-1, -1, 0, 4));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(glyph(c, "chev_r", t.ink3), Ui.ll(18, 18));
        focusable(r);
        if (go != null) r.setOnClickListener(v -> go.run());
        return r;
    }

    void refresh() {
        if ("main".equals(current)) {
            boolean wifiOn = Net.on(c);
            String ssid = Net.current(c);
            wifiT.set(wifiOn, !wifiOn ? "Off" : ssid != null ? ssid : Glass.connected(c) ? "Connected" : "On");
            String bt = btState();
            boolean btOn = "on".equals(bt);
            boolean any = false;
            for (Bt.Dev d : Bt.devices()) any |= d.audio && d.connected;
            btT.set(btOn, "waiting".equals(bt) ? "Turning on…" : "failing".equals(bt) ? "Not starting" : !btOn ? "Off" : any ? "Headphones" : "On");
            int left = SleepTimer.minutesLeft();
            sleepT.set(left > 0, left > 0 ? "In " + left + " min" : "Off");
            int ceil = Glass.prefs(c).getInt(Glass.NIGHT_CEILING, 0);
            nightT.set(ceil > 0, ceil > 0 ? ceil + " after 11 pm" : "Off");
            if (outputValue != null) outputValue.setText(outputName());
            ContentResolver cr = c.getContentResolver();
            if (picture != null) picture.set(Backlight.get(c) / 100f);
            float fsNow = Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1f);
            int best = 0;
            for (int i = 1; i < TEXT.length; i++) if (Math.abs(TEXT[i] - fsNow) < Math.abs(TEXT[best] - fsNow)) best = i;
            if (textSize != null) textSize.set(best);
            if (look != null) {
                String ap = Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto");
                look.set("light".equals(ap) ? 0 : "dark".equals(ap) ? 2 : 1);
            }
        }
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null && volume != null) volume.set(am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
    }

    @Override
    boolean onBack() {
        if (joining != null) {
            hideKeyboard();
            joining = null;
            go("wifi", -1);
            return true;
        }
        if (!"main".equals(current) && "main".equals(start)) {
            go("main", -1);
            return true;
        }
        return false;
    }

    @Override
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        int dir = k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (volume != null && volume.v.hasFocus()) {
            AudioManager am = c.getSystemService(AudioManager.class);
            GlassKeys.ownChange();
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            Sound.step();
            refresh();
            if (a.bar() != null) a.bar().refresh();
            return true;
        }
        if (picture != null && picture.v.hasFocus()) {
            if (Backlight.step(c, dir)) Sound.step(); else Sound.bump();
            refresh();
            return true;
        }
        if (textSize != null && textSize.v.hasFocus()) {
            int i = Math.max(0, Math.min(TEXT.length - 1, textSize.index + dir));
            if (i != textSize.index) {
                try {
                    Settings.System.putFloat(c.getContentResolver(), Settings.System.FONT_SCALE, TEXT[i]);
                } catch (RuntimeException ignored) {
                }
                textSize.set(i);
                Sound.step();
                a.fontScaleChanged();
            } else {
                Sound.bump();
            }
            return true;
        }
        if (look != null && look.v.hasFocus()) {
            int i = Math.max(0, Math.min(2, look.index + dir));
            if (i == look.index) {
                Sound.bump();
                return true;
            }
            look.set(i);
            Sound.step();
            Glass.prefs(c).edit().putString(GlassHomeActivity.APPEARANCE, i == 0 ? "light" : i == 2 ? "dark" : "auto").apply();
            // the whole desktop takes the new look in a moment, and Control Center opens again on it
            look.v.postDelayed(() -> a.lookChanged("controls:look"), Ui.reduced ? 0 : 260);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT && !"main".equals(current) && "main".equals(start) && joining == null) {
            View f = stage.findFocus();
            if (f != null && focusables.indexOf(f) == 0) {
                go("main", -1);
                return true;
            }
        }
        return false;
    }

    @Override
    void onClosed() {
        hideKeyboard();
    }

    // ---- pieces ---------------------------------------------------------------------------------------------

    private final class Tile {
        final LinearLayout v;
        final FrameLayout chip;
        final ImageView glyph;
        final TextView title, sub;

        Tile(String g, String name) {
            v = Ui.row(c);
            boolean big = Ui.fs > 1.1f;   // big words: a little less around them, so "Night volume" still fits
            v.setPadding(Ui.px(big ? 10 : 12), 0, Ui.px(big ? 8 : 12), 0);
            v.setBackground(Ui.fillRound(t.hover, 16));
            chip = new FrameLayout(c);
            glyph = new ImageView(c);
            glyph.setImageDrawable(Glyph.of(g, t.ink));
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(Ui.px(18), Ui.px(18));
            gl.gravity = Gravity.CENTER;
            chip.addView(glyph, gl);
            v.addView(chip, Ui.ll(big ? 32 : 36, big ? 32 : 36));
            LinearLayout words = Ui.column(c);
            title = Ui.line(c, 15, t.ink, 600, name);
            sub = Audit.secret(Ui.line(c, 13, t.ink3, 400, ""));
            words.addView(title);
            words.addView(sub, Ui.ll(-1, -1, 0, 2));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
            wl.leftMargin = Ui.px(big ? 9 : 12);
            v.addView(words, wl);
            focusable(v);
        }

        void set(boolean on, String s) {
            v.setBackground(Ui.fillRound(on ? t.sel : t.hover, 16));
            chip.setBackground(Ui.oval(on ? t.selChip : t.field));
            ((Glyph) glyph.getDrawable()).setColor(on ? t.selink : t.ink);
            title.setTextColor(on ? t.selink : t.ink);
            sub.setTextColor(on ? t.selSub : t.ink3);
            sub.setText(s);
        }
    }

    private final class Slider {
        final LinearLayout v;
        final View fillBar;
        final FrameLayout track;

        Slider(String g, String name) {
            v = Ui.column(c);
            v.setPadding(Ui.px(14), Ui.px(12), Ui.px(14), Ui.px(12));
            v.setBackground(Ui.fillRound(t.hover, 16));
            v.addView(Ui.line(c, 13, t.ink, 600, name));
            track = new FrameLayout(c);
            android.graphics.drawable.GradientDrawable bg = Ui.fillRound(t.night ? Ui.a(0xFFFFFF, .10f) : Ui.a(0x16181D, .10f), 14);
            bg.setStroke(Math.max(1, Ui.px(1)), t.hair);
            track.setBackground(bg);
            track.setClipToOutline(true);
            fillBar = new View(c);
            fillBar.setBackgroundColor(t.night ? 0xFFE9E9EE : 0xFFFFFFFF);
            fillBar.setPivotX(0);
            track.addView(fillBar, new FrameLayout.LayoutParams(-1, -1));
            ImageView gl = new ImageView(c);
            gl.setImageDrawable(Glyph.of(g, 0xFF5B6070));
            track.addView(gl, Ui.at(10, 5, 18, 18));
            v.addView(track, Ui.ll(-2, 28, 0, 8));
            focusable(v);
        }

        void set(float f) {
            float to = Math.max(0f, Math.min(1f, f));
            if (fillBar.getScaleX() == 1f && fillBar.getWidth() == 0) fillBar.setScaleX(to);
            else Spring.to(fillBar, DynamicAnimation.SCALE_X, to, Spring.FOCUS);
        }
    }

    private final class Seg {
        final LinearLayout v;
        final LinearLayout chips;
        int index;

        Seg(String label, String[] opts) {
            v = Ui.row(c);
            v.setPadding(Ui.px(14), 0, Ui.px(10), 0);
            v.setBackground(Ui.fillRound(t.hover, 16));
            TextView l = Ui.line(c, 13, t.ink, 600, label);
            v.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
            chips = Ui.row(c);
            chips.setPadding(Ui.px(3), Ui.px(3), Ui.px(3), Ui.px(3));
            chips.setBackground(Ui.fillRound(t.field, 8));
            for (String o : opts) {
                TextView ch = Ui.line(c, 13, t.ink2, 400, o);
                ch.setGravity(Gravity.CENTER);
                ch.setPadding(Ui.px(11), 0, Ui.px(11), 0);
                chips.addView(ch, Ui.ll(-1, Ui.boxf(26, 13)));
            }
            v.addView(chips);
            focusable(v);
        }

        void set(int i) {
            index = i;
            for (int k = 0; k < chips.getChildCount(); k++) {
                TextView ch = (TextView) chips.getChildAt(k);
                boolean on = k == i;
                ch.setBackground(on ? Ui.fillRound(t.sel, 6) : null);
                ch.setTextColor(on ? t.selink : t.ink2);
                ch.setTypeface(Ui.face(c, on ? 600 : 400));
            }
        }
    }

    /** A small switch: a pill with a knob that slides on the FOCUS spring. */
    private final class Switch {
        final FrameLayout v;
        final View knob;

        Switch(boolean on) {
            v = new FrameLayout(c);
            knob = new View(c);
            knob.setElevation(Ui.pxf(1));
            v.addView(knob, Ui.at(3, 3, 20, 20));
            v.setLayoutParams(Ui.ll(44, 26));
            paint(on);
            knob.setTranslationX(on ? Ui.pxf(18) : 0);
        }

        void set(boolean on) {
            paint(on);
            Spring.to(knob, DynamicAnimation.TRANSLATION_X, on ? Ui.pxf(18) : 0f, Spring.FOCUS);
        }

        private void paint(boolean on) {
            // the monochrome accent: ink when on, a quiet track when off
            v.setBackground(Ui.fillRound(on ? t.sel : (t.night ? Ui.a(0xFFFFFF, .20f) : Ui.a(0x16181D, .18f)), 13));
            knob.setBackground(Ui.oval(on ? t.selink : 0xFFFFFFFF));
        }
    }
}
