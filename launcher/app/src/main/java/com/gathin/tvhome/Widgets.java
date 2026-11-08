package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Widgets: glass cards on the desktop (and quiet chips on the screen saver): the time, the weather, what's playing,
 * this month, and photos from your phone. Settings ▸ Backgrounds & savers ▸ Widgets turns each on for each place.
 *
 * On the desktop they are things you use, as on an iPhone: focus reaches them (↑ from the dock under them, ↓ from
 * the bar), OK does the obvious thing (plays or pauses the music, opens the forecast, the clock, the photos), and
 * holding OK offers the rest: Move (then ← → ↑ ↓ carry it, OK puts it down), Small · Medium · Large, Remove. Each
 * remembers where it is and how big. Sizes are those of iPhone widgets: small 150 square, medium 316 x 150, large
 * 316 square (design px), with words that follow the Text size as far as a card of that size allows.
 *
 * The weather is the only one that goes out to the internet, and only while it's on: the forecast comes from
 * open-meteo.com for the town you chose (or, until you choose one, the town of the TV's internet address, from
 * get.geojs.io), at most every 30 minutes.
 */
final class Widgets {
    private Widgets() {
    }

    /** {id, name, what it shows}. */
    static final String[][] ALL = {
            {"clock", "Clock", "The time and the date"},
            {"weather", "Weather", "The weather where you are (from the internet)"},
            {"playing", "Now playing", "What an app is playing, with play and pause"},
            {"calendar", "Calendar", "Today and this month"},
            {"photos", "Photos", "Photos sent from your phone"}};

    static final int S = 0, M = 1, L = 2;
    static final String[] SIZES = {"Small", "Medium", "Large"};
    static final float GAP = 16, LEFT = 40, TOP = 70, RIGHT = 1240, BOTTOM = 566, RAD = 22;

    static float w(int size) {
        return size == S ? 150 : 316;
    }

    static float h(int size) {
        return size == L ? 316 : 150;
    }

    // ---- which, where, how big -------------------------------------------------------------------------------

    static String key(String place) {
        return "widgets_" + place;
    }

    static List<String> on(Context c, String place) {
        if (Sample.on && Sample.size >= 0 && "desktop".equals(place)) {   // a picture of one size: every widget at it
            return new ArrayList<>(Sample.size == L ? Arrays.asList("clock", "weather", "playing")
                    : Arrays.asList("clock", "weather", "playing", "calendar", "photos"));
        }
        String s = Glass.prefs(c).getString(key(place), "");
        List<String> out = new ArrayList<>();
        for (String id : s.split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    static boolean on(Context c, String place, String id) {
        return on(c, place).contains(id);
    }

    static void set(Context c, String place, String id, boolean on) {
        List<String> now = on(c, place);
        now.remove(id);
        if (on) now.add(id);
        List<String> ordered = new ArrayList<>();
        for (String[] w : ALL) if (now.contains(w[0])) ordered.add(w[0]);
        Glass.prefs(c).edit().putString(key(place), TextUtils.join(",", ordered)).apply();
    }

    static int size(Context c, String id) {
        if (Sample.on && Sample.size >= 0) return Sample.size;
        int d = "calendar".equals(id) ? L : M;
        return Math.max(S, Math.min(L, Glass.prefs(c).getInt("wsize_" + id, d)));
    }

    /** {x, y} in design px, or null when it hasn't been placed yet. */
    static float[] pos(Context c, String id) {
        if (Sample.on && Sample.size >= 0) return null;   // wherever there's room, top left first
        String s = Glass.prefs(c).getString("wpos_" + id, null);
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1])};
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void keep(Context c, String id, float x, float y, int size) {
        if (Sample.on && Sample.size >= 0) return;   // a picture's layout is never saved over the owner's
        Glass.prefs(c).edit().putString("wpos_" + id, Math.round(x) + "," + Math.round(y)).putInt("wsize_" + id, size).apply();
    }

    /** The words inside a card follow the Text size, as far as a card of fixed size allows. */
    static float wf() {
        return Math.max(0.9f, Math.min(1.12f, Ui.fs));
    }

    private static TextView tx(Context c, float size, int color, int weight, String s) {
        TextView v = Ui.line(c, size, color, weight, s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(size) * wf());
        return v;
    }

    private static TextView big(Context c, float size, int color) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(size) * wf());
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setLetterSpacing(-0.02f);
        try {
            v.setTypeface(c.getResources().getFont(R.font.outfit_light));
        } catch (RuntimeException e) {
            v.setTypeface(Ui.face(c, 400));
        }
        return v;
    }

    // ---- the desktop's board ------------------------------------------------------------------------------------

    static Board board(Context c, Desk desk) {
        List<String> ids = on(c, "desk");
        if (ids.isEmpty()) return null;
        return new Board(c, desk, ids);
    }

    static final class Card {
        final String id;
        int size;
        float x, y;
        Pane pane;
        final List<Runnable> refreshers = new ArrayList<>();

        Card(String id) {
            this.id = id;
        }

        float right() {
            return x + w(size);
        }

        float bottom() {
            return y + h(size);
        }

        boolean hits(float ox, float oy, float ow, float oh) {
            return ox < right() + GAP && ox + ow + GAP > x && oy < bottom() + GAP && oy + oh + GAP > y;
        }
    }

    static final class Board {
        final FrameLayout view;
        final List<Card> cards = new ArrayList<>();
        private final Context c;
        private final Desk desk;
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean live;
        private Card moving;
        private TextView hint;
        private final Runnable mediaChanged = this::refreshPlaying;

        Board(Context c, Desk desk, List<String> ids) {
            this.c = c;
            this.desk = desk;
            view = new FrameLayout(c);
            view.setClipChildren(false);
            // placed ones first, then the rest wherever there's room (top left first, down then across)
            List<String> later = new ArrayList<>();
            for (String id : ids) {
                float[] p = pos(c, id);
                Card k = new Card(id);
                k.size = size(c, id);
                if (p == null) {
                    later.add(id);
                    continue;
                }
                k.x = clampX(p[0], k.size);
                k.y = clampY(p[1], k.size);
                if (free(k.x, k.y, k.size, null)) cards.add(k);
                else later.add(id);
            }
            for (String id : later) {
                Card k = new Card(id);
                k.size = size(c, id);
                float[] spot = room(k.size, null, LEFT, TOP);
                if (spot == null && k.size != S) {
                    k.size = S;
                    spot = room(S, null, LEFT, TOP);
                }
                if (spot == null) continue;   // no room left on the desktop
                k.x = spot[0];
                k.y = spot[1];
                cards.add(k);
            }
            for (Card k : cards) build(k);
        }

        private float clampX(float x, int size) {
            return Math.max(LEFT, Math.min(RIGHT - w(size), x));
        }

        private float clampY(float y, int size) {
            return Math.max(TOP, Math.min(BOTTOM - h(size), y));
        }

        private boolean free(float x, float y, int size, Card except) {
            if (x < LEFT - 0.5f || y < TOP - 0.5f || x + w(size) > RIGHT + 0.5f || y + h(size) > BOTTOM + 0.5f) return false;
            for (Card o : cards) if (o != except && o.hits(x, y, w(size), h(size))) return false;
            return true;
        }

        /** The free spot for a card of this size nearest to (nx, ny), on an 8 px grid; null when there's none. */
        private float[] room(int size, Card except, float nx, float ny) {
            float best = Float.MAX_VALUE;
            float[] at = null;
            for (float y = TOP; y + h(size) <= BOTTOM + 0.5f; y += 8) {
                for (float x = LEFT; x + w(size) <= RIGHT + 0.5f; x += 8) {
                    if (!free(x, y, size, except)) continue;
                    float d = (x - nx) * (x - nx) + (y - ny) * (y - ny) * 1.4f;
                    if (d < best) {
                        best = d;
                        at = new float[]{x, y};
                    }
                }
            }
            return at;
        }

        private void build(Card k) {
            if (k.pane != null) view.removeView(k.pane);
            k.refreshers.clear();
            Pane p = new Pane(c, true, k.x, k.y, w(k.size), h(k.size), RAD);
            View inner = content(c, k.id, k.size, Ui.t, false, k.refreshers);
            p.addView(inner, Ui.fill());
            p.setFocusable(true);
            p.setClickable(true);
            p.setLongClickable(true);
            p.setContentDescription(name(k.id));
            p.setForeground(Ui.veil(RAD));
            Ui.lift(p, 1.035f, 10);
            p.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
            p.setOnClickListener(v -> act(k, v));
            p.setOnLongClickListener(v -> {
                menu(k, v);
                return true;
            });
            view.addView(p, p.params());
            p.bake(c);
            k.pane = p;
            for (Runnable r : k.refreshers) r.run();
        }

        void start() {
            if (live) return;
            live = true;
            Media.listen(mediaChanged);
            tick.run();
        }

        void stop() {
            live = false;
            Media.unlisten(mediaChanged);
            h.removeCallbacks(tick);
            h.removeCallbacks(fast);
            if (moving != null) endMove(true);
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                for (Card k : cards) for (Runnable r : k.refreshers) r.run();
                if (live) h.postDelayed(this, 30_000);
                h.removeCallbacks(fast);
                if (live && has("playing")) h.post(fast);
            }
        };

        /** Now playing keeps up: what's playing every 3 s, its progress every second. */
        private final Runnable fast = new Runnable() {
            private int n;

            @Override
            public void run() {
                if (!live) return;
                if (n++ % 3 == 0) Media.fresh(c, 2500);
                refreshPlaying();
                h.postDelayed(this, 1000);
            }
        };

        private boolean has(String id) {
            for (Card k : cards) if (k.id.equals(id)) return true;
            return false;
        }

        private void refreshPlaying() {
            for (Card k : cards) if (k.id.equals("playing")) for (Runnable r : k.refreshers) r.run();
        }

        /** The card under a dock icon spanning x0..x1 (design px), lowest first; null when none is above it. */
        View above(float x0, float x1) {
            Card best = null;
            for (Card k : cards) {
                if (k.x < x1 && k.right() > x0 && (best == null || k.bottom() > best.bottom())) best = k;
            }
            return best == null ? null : best.pane;
        }

        // ---- OK, and holding OK -------------------------------------------------------------------------------

        private void act(Card k, View v) {
            Sound.pop();
            switch (k.id) {
                case "playing":
                    if (!playPause(c)) menu(k, v);
                    h.postDelayed(() -> Media.fresh(c, 0), 350);
                    break;
                case "weather":
                    desk.show(new WeatherSheet(desk, false), v);
                    break;
                case "clock":
                    GlassKeys.start(c, new Intent(c, ClockActivity.class).putExtra(ClockActivity.PREVIEW, true));
                    break;
                case "photos": {
                    List<File> p = Show.photos(c);
                    if (p.isEmpty()) Toast.makeText(c, "Send photos from your phone's Glass remote", Toast.LENGTH_SHORT).show();
                    else ShowActivity.open(c, p, 0);
                    break;
                }
                default:
                    menu(k, v);
            }
        }

        private void menu(Card k, View v) {
            List<Sheet.Row> rows = new ArrayList<>();
            switch (k.id) {
                case "playing": {
                    Media.Now n = Media.best();
                    if (n != null) {
                        rows.add(new Sheet.Row(n.playing() ? "pause" : "play", n.playing() ? "Pause" : "Play", null, () -> playPause(c)));
                        rows.add(new Sheet.Row("next", "Next", null, () -> mediaKey(c, KeyEvent.KEYCODE_MEDIA_NEXT)));
                        rows.add(new Sheet.Row("prev", "Previous", null, () -> mediaKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
                        CharSequence app = Sample.on ? Sample.APP : Glass.label(c, n.pkg);
                        rows.add(new Sheet.Row("apps", "Open " + (app != null ? app : "the app"), null, () -> desk.launch(n.pkg, v)));
                    }
                    break;
                }
                case "weather":
                    rows.add(new Sheet.Row("location", "Change location", null, () -> desk.show(new WeatherSheet(desk, true), v)));
                    break;
                case "photos":
                    rows.add(new Sheet.Row("photo", "Show the photos", null, () -> act(k, v)));
                    break;
                case "clock":
                    rows.add(new Sheet.Row("clock", "Show the clock", null, () -> act(k, v)));
                    break;
                default:
                    break;
            }
            rows.add(new Sheet.Row("move", "Move", null, () -> startMove(k)));
            for (int s = S; s <= L; s++) {
                final int size = s;
                rows.add(new Sheet.Row(s == S ? "resize" : null, SIZES[s], null, () -> resize(k, size)).checked(k.size == s));
            }
            rows.add(new Sheet.Row("minus", "Remove widget", null, () -> remove(k)));
            rows.add(new Sheet.Row("grid", "Edit widgets…", null, () -> desk.openSettings("looks:Clock on the desktop")));
            float mw = Ui.boxf(232, 15) + 8;
            float mx = Math.max(Ui.EDGE, Math.min(1280 - Ui.EDGE - mw, k.x + w(k.size) / 2 - mw / 2));
            float mh = 16 + rows.size() * Ui.boxf(40, 15);
            float my = k.bottom() + 12 + mh > 704 ? Math.max(56, k.y - 12 - mh) : k.bottom() + 12;
            desk.show(new Sheet.Menu(desk, rows, mx, my, mw, false), v);
        }

        // ---- moving and resizing -----------------------------------------------------------------------------

        boolean editing() {
            return moving != null;
        }

        /** Home while a widget is being moved: it stays where it is now. */
        void done() {
            if (moving != null) endMove(true);
        }

        private void startMove(Card k) {
            moving = k;
            k.pane.requestFocus();
            Sound.pop();
            k.pane.animate().scaleX(1.06f).scaleY(1.06f).translationZ(Ui.pxf(18)).setDuration(Ui.ms(180)).setInterpolator(Ui.OUT).start();
            k.pane.setForeground(Ui.ring(t().night ? 0xE6FFFFFF : 0xCC16181D, 2, RAD));
            if (hint == null) {
                hint = Ui.line(c, 14, t().ink, 500, "← → ↑ ↓ move it   ·   OK puts it down");
                hint.setGravity(Gravity.CENTER);
                hint.setPadding(Ui.px(16), 0, Ui.px(16), 0);
                GradientDrawable g = Ui.fillRound(t().glass2, 18);
                g.setStroke(Math.max(1, Ui.px(1)), t().edge);
                hint.setBackground(g);
                hint.setElevation(Ui.pxf(20));
            }
            if (hint.getParent() == null) view.addView(hint, Ui.at(0, 0, -1, Ui.boxf(36, 14)));
            placeHint();
        }

        private Ui.Tone t() {
            return Ui.t;
        }

        private void placeHint() {
            if (hint == null || moving == null) return;
            hint.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            float hw = hint.getMeasuredWidth() / Ui.s, hh = Ui.boxf(36, 14);
            float hx = Math.max(Ui.EDGE, Math.min(1280 - Ui.EDGE - hw, moving.x + w(moving.size) / 2 - hw / 2));
            float hy = moving.bottom() + 14 + hh > 600 ? moving.y - 14 - hh : moving.bottom() + 14;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hint.getLayoutParams();
            lp.leftMargin = Ui.px(hx);
            lp.topMargin = Ui.px(hy);
            hint.setLayoutParams(lp);
        }

        /** Keys while a card is being moved: arrows carry it (past any card in the way), OK or Back puts it down. */
        boolean key(KeyEvent e) {
            if (moving == null) return false;
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) endMove(true);
                return true;
            }
            int dx = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            int dy = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            if (dx == 0 && dy == 0) return true;
            float step = e.getRepeatCount() > 6 ? 32 : 16;
            Card m = moving;
            float nx = m.x, ny = m.y;
            boolean placed = false;
            for (int i = 1; i <= 80; i++) {
                float tx = clampX(m.x + dx * step * i, m.size), ty = clampY(m.y + dy * step * i, m.size);
                if (tx == nx && ty == ny && i > 1) break;   // stuck at the edge
                if (free(tx, ty, m.size, m)) {
                    nx = tx;
                    ny = ty;
                    placed = true;
                    break;
                }
                nx = tx;
                ny = ty;
            }
            if (!placed || (nx == m.x && ny == m.y)) {
                Sound.bump();
                return true;
            }
            Sound.tick();
            float ox = m.x, oy = m.y;
            m.x = nx;
            m.y = ny;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) m.pane.getLayoutParams();
            lp.leftMargin = Ui.px(m.x);
            lp.topMargin = Ui.px(m.y);
            m.pane.setLayoutParams(lp);
            if (!Ui.reduced) {   // it glides there (the layout moved at once; the offset eases out)
                m.pane.setTranslationX(Ui.pxf(ox - m.x));
                m.pane.setTranslationY(Ui.pxf(oy - m.y));
                m.pane.animate().translationX(0).translationY(0).setDuration(Ui.ms(140)).setInterpolator(Ui.OUT).start();
            }
            placeHint();
            return true;
        }

        private void endMove(boolean keepIt) {
            Card m = moving;
            moving = null;
            if (hint != null && hint.getParent() != null) view.removeView(hint);
            if (m == null) return;
            m.pane.setForeground(Ui.veil(RAD));
            m.pane.animate().scaleX(m.pane.isFocused() ? 1.035f : 1f).scaleY(m.pane.isFocused() ? 1.035f : 1f).translationZ(0)
                    .setDuration(Ui.ms(180)).setInterpolator(Ui.OUT).start();
            if (keepIt) {
                Sound.pop();
                keep(c, m.id, m.x, m.y, m.size);
                m.pane.bake(c);
            }
        }

        private void resize(Card k, int size) {
            if (size == k.size) return;
            float cx = k.x + w(k.size) / 2, cy = k.y;
            float nx = clampX(cx - w(size) / 2, size), ny = clampY(cy, size);
            float[] at = free(nx, ny, size, k) ? new float[]{nx, ny} : room(size, k, nx, ny);
            if (at == null) {
                Sound.bump();
                Toast.makeText(c, "No room for a " + SIZES[size].toLowerCase(Locale.ROOT) + " widget: move or remove one first", Toast.LENGTH_SHORT).show();
                return;
            }
            k.size = size;
            k.x = at[0];
            k.y = at[1];
            keep(c, k.id, k.x, k.y, k.size);
            build(k);
            k.pane.requestFocus();
            Sound.pop();
        }

        private void remove(Card k) {
            set(c, "desk", k.id, false);
            cards.remove(k);
            if (k.pane != null) view.removeView(k.pane);
            Sound.close();
            desk.focusDock();
        }
    }

    static String name(String id) {
        for (String[] w : ALL) if (w[0].equals(id)) return w[1];
        return id;
    }

    // ---- play / pause, anywhere --------------------------------------------------------------------------------

    /** A media key to whatever is playing (Android routes it to the app's session; no special access needed). */
    static boolean mediaKey(Context c, int code) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return false;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        return true;
    }

    /** Play or pause what's playing; false when nothing is (or was). */
    static boolean playPause(Context c) {
        Media.Now n = Media.best();
        if (n == null) return false;
        return mediaKey(c, n.playing() ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    // ---- the screen saver's row ---------------------------------------------------------------------------

    static Strip strip(Context c, String place, boolean dark) {
        List<String> ids = on(c, place);
        ids.remove("clock");   // every screen saver shows the time already
        if (ids.isEmpty()) return null;
        return new Strip(c, ids);
    }

    static final class Strip {
        final LinearLayout view;
        private final List<Runnable> refreshers = new ArrayList<>();
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean live;

        Strip(Context c, List<String> ids) {
            view = Ui.row(c);
            view.setGravity(Gravity.BOTTOM);
            for (String id : ids) {
                int size = "playing".equals(id) ? M : S;
                FrameLayout chip = new FrameLayout(c);
                GradientDrawable g = Ui.fillRound(Ui.a(0x101014, .46f), RAD);
                g.setStroke(Math.max(1, Ui.px(1)), Ui.a(0xFFFFFF, .12f));
                chip.setBackground(g);
                chip.setClipToOutline(true);
                chip.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(RAD));
                    }
                });
                chip.addView(content(c, id, size, Ui.NIGHT, true, refreshers), Ui.fill());
                view.addView(chip, Ui.ll(w(size), h(size), view.getChildCount() == 0 ? 0 : 14, 0));
            }
        }

        void start() {
            if (live) return;
            live = true;
            tick.run();
        }

        void stop() {
            live = false;
            h.removeCallbacks(tick);
        }

        private final Runnable tick = new Runnable() {
            private int n;

            @Override
            public void run() {
                if (n++ % 10 == 0) for (Runnable r : refreshers) r.run();
                else Media.fresh(view.getContext(), 2500);
                if (live) h.postDelayed(this, 3_000);
            }
        };
    }

    // ---- what each widget shows ---------------------------------------------------------------------------

    static View content(Context c, String id, int size, Ui.Tone t, boolean saver, List<Runnable> refreshers) {
        switch (id) {
            case "clock": return clock(c, size, t);
            case "weather": return weather(c, size, t, refreshers);
            case "playing": return playing(c, size, t, refreshers);
            case "calendar": return calendar(c, size, t);
            case "photos": return photos(c, size, t, refreshers);
            default: return new View(c);
        }
    }

    private static TextClock clockText(Context c, String pattern12, String pattern24, float size, int color, boolean light, int weight) {
        TextClock v = new TextClock(c);
        v.setFormat12Hour(pattern12);
        v.setFormat24Hour(pattern24);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(size) * wf());
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        if (light) {
            v.setLetterSpacing(-0.02f);
            try {
                v.setTypeface(c.getResources().getFont(R.font.outfit_light));
            } catch (RuntimeException e) {
                v.setTypeface(Ui.face(c, 400));
            }
        } else {
            v.setTypeface(Ui.face(c, weight));
        }
        return v;
    }

    private static String best(String skeleton) {
        return DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
    }

    private static View clock(Context c, int size, Ui.Tone t) {
        LinearLayout col = Ui.column(c);
        if (size == S) {
            col.setPadding(Ui.px(16), Ui.px(14), Ui.px(16), Ui.px(14));
            col.addView(clockText(c, "h:mm", "HH:mm", 44, t.ink, true, 0));
            col.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
            col.addView(clockText(c, "EEEE", "EEEE", 13, t.ink, false, 600));
            col.addView(clockText(c, best("dMMMM"), best("dMMMM"), 13, t.ink2, false, 500), Ui.ll(-1, -1, 0, 2));
            return col;
        }
        if (size == M) {
            col.setPadding(Ui.px(20), Ui.px(16), Ui.px(20), Ui.px(16));
            col.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(clockText(c, "h:mm", "HH:mm", 58, t.ink, true, 0));
            col.addView(clockText(c, best("EEEEdMMMM"), best("EEEEdMMMM"), 15, t.ink2, false, 500), Ui.ll(-1, -1, 2, 8));
            return col;
        }
        col.setPadding(Ui.px(20), Ui.px(20), Ui.px(20), Ui.px(20));
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
        TextClock time = clockText(c, "h:mm", "HH:mm", 92, t.ink, true, 0);
        time.setGravity(Gravity.CENTER);
        col.addView(time, Ui.ll(-2, -1));
        TextClock ampm = clockText(c, "a", "", 15, t.ink3, false, 600);
        ampm.setGravity(Gravity.CENTER);
        col.addView(ampm, Ui.ll(-2, -1, 0, 4));
        TextClock date = clockText(c, best("EEEEdMMMM"), best("EEEEdMMMM"), 16, t.ink2, false, 500);
        date.setGravity(Gravity.CENTER);
        col.addView(date, Ui.ll(-2, -1, 0, 10));
        col.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
        Weather.Now w = Weather.now(c);
        if (w != null && w.sunset != null) {
            TextView sun = tx(c, 13, t.ink3, 500, Weather.sunLine(w));
            sun.setGravity(Gravity.CENTER);
            col.addView(sun, Ui.ll(-2, -1));
        }
        return col;
    }

    // weather ---------------------------------------------------------------------------------------------------

    private static View weather(Context c, int size, Ui.Tone t, List<Runnable> refreshers) {
        LinearLayout outer = Ui.column(c);
        outer.setPadding(Ui.px(16), Ui.px(14), Ui.px(16), Ui.px(14));
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.TOP);
        LinearLayout now = Ui.column(c);
        TextView place = Audit.secret(tx(c, 13, t.ink, 600, "Weather"));
        TextView temp = big(c, size == S ? 36 : 40, t.ink);
        temp.setText("—");
        LinearLayout cond = Ui.row(c);
        ImageView icon = new ImageView(c);
        cond.addView(icon, Ui.ll(16, 16));
        TextView what = tx(c, 13, t.ink2, 500, "");
        cond.addView(what, Ui.ll(-1, -1, 6, 0));
        TextView hilo = tx(c, 13, t.ink2, 500, "");
        now.addView(place);
        now.addView(temp, Ui.ll(-1, -1, 0, 2));
        now.addView(cond, Ui.ll(-1, -1, 0, 4));
        now.addView(hilo, Ui.ll(-1, -1, 0, 2));
        top.addView(now, size == S ? new LinearLayout.LayoutParams(-1, -2) : Ui.ll(118, -1));
        LinearLayout hours = null;
        if (size != S) {
            hours = Ui.row(c);
            hours.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, -2, 1f);
            hl.leftMargin = Ui.px(10);
            top.addView(hours, hl);
        }
        outer.addView(top, Ui.ll(-2, -1));
        LinearLayout days = null;
        if (size == L) {
            View hair = new View(c);
            hair.setBackgroundColor(t.hair);
            outer.addView(hair, Ui.ll(-2, 1, 0, 12));
            days = Ui.column(c);
            outer.addView(days, Ui.ll(-2, -1, 0, 6));
        }
        final LinearLayout fh = hours, fd = days;
        Runnable r = () -> {
            Weather.Now w = Weather.now(c);
            if (w == null) {
                temp.setText("—");
                place.setText(Weather.placeName(c) != null ? Weather.placeName(c) : "Weather");
                what.setText(Glass.online(c) ? "Finding the weather…" : "Offline");
                icon.setImageDrawable(Glyph.of("cloud", t.ink2));
                hilo.setText("");
                return;
            }
            place.setText(w.place != null ? w.place : "Weather");
            temp.setText(w.temp + "°");
            what.setText(w.text);
            icon.setImageDrawable(Glyph.of(w.glyph, t.ink));
            hilo.setText("H:" + w.hi + "°  L:" + w.lo + "°");
            if (fh != null) {
                fh.removeAllViews();
                for (int i = 0; i < Math.min(4, w.hours.size()); i++) {
                    Weather.Hour hr = w.hours.get(i);
                    LinearLayout cell = Ui.column(c);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView at = tx(c, 13, t.ink2, 600, hr.label);
                    at.setGravity(Gravity.CENTER);
                    cell.addView(at, Ui.ll(-2, -1));
                    ImageView g = new ImageView(c);
                    g.setImageDrawable(Glyph.of(hr.glyph, t.ink));
                    cell.addView(g, Ui.ll(20, 20, 0, 8));
                    TextView tt = tx(c, 13, t.ink, 600, hr.temp + "°");
                    tt.setGravity(Gravity.CENTER);
                    cell.addView(tt, Ui.ll(-2, -1, 0, 8));
                    fh.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
                }
            }
            if (fd != null) {
                fd.removeAllViews();
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (Weather.Day d : w.days) {
                    lo = Math.min(lo, d.lo);
                    hi = Math.max(hi, d.hi);
                }
                for (int i = 0; i < Math.min(4, w.days.size()); i++) {
                    Weather.Day d = w.days.get(i);
                    LinearLayout row = Ui.row(c);
                    TextView name = tx(c, 13, t.ink, 600, d.label);
                    row.addView(name, Ui.ll(58, -1));
                    ImageView g = new ImageView(c);
                    g.setImageDrawable(Glyph.of(d.glyph, t.ink));
                    row.addView(g, Ui.ll(18, 18, 2, 0));
                    TextView l = tx(c, 13, t.ink2, 500, d.lo + "°");
                    l.setGravity(Gravity.END);
                    row.addView(l, Ui.ll(40, -1, 6, 0));
                    row.addView(range(c, t, d.lo, d.hi, lo, hi), new LinearLayout.LayoutParams(0, Ui.px(4), 1f) {{
                        leftMargin = Ui.px(10);
                        rightMargin = Ui.px(10);
                    }});
                    TextView hh = tx(c, 13, t.ink, 600, d.hi + "°");
                    row.addView(hh, Ui.ll(36, -1));
                    fd.addView(row, Ui.ll(-2, 38));
                }
            }
        };
        refreshers.add(r);
        return outer;
    }

    /** A day's temperatures as a bar on the week's range (cool to warm, as on an iPhone). */
    private static View range(Context c, Ui.Tone t, int lo, int hi, int min, int max) {
        FrameLayout track = new FrameLayout(c);
        track.setBackground(Ui.fillRound(t.hover, 2));
        View fill = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF5AC8FA, 0xFF9AD66B, 0xFFFFCC00, 0xFFFF9500});
        g.setCornerRadius(Ui.pxf(2));
        fill.setBackground(g);
        float span = Math.max(1, max - min);
        track.addOnLayoutChangeListener((v, l, tp, rt, b, ol, ot, orr, ob) -> {
            int wdt = rt - l;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            int x0 = Math.round(wdt * (lo - min) / span), x1 = Math.round(wdt * (hi - min) / span);
            if (lp.leftMargin != x0 || lp.width != Math.max(Ui.px(4), x1 - x0)) {
                lp.leftMargin = x0;
                lp.width = Math.max(Ui.px(4), x1 - x0);
                fill.post(() -> fill.setLayoutParams(lp));
            }
        });
        track.addView(fill, new FrameLayout.LayoutParams(Ui.px(4), -1));
        return track;
    }

    // now playing ---------------------------------------------------------------------------------------------

    private static View playing(Context c, int size, Ui.Tone t, List<Runnable> refreshers) {
        if (size == S) return playingSmall(c, t, refreshers);
        LinearLayout outer = Ui.column(c);
        outer.setPadding(Ui.px(14), Ui.px(14), Ui.px(16), Ui.px(14));
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        float artSize = size == L ? 132 : 122;
        ImageView art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(14));
            }
        });
        art.setClipToOutline(true);
        art.setBackground(Ui.fillRound(t.hover, 14));
        Audit.secret(art);
        top.addView(art, Ui.ll(artSize, artSize));
        LinearLayout words = Ui.column(c);
        TextView app = tx(c, 13, t.ink3, 700, "");
        app.setLetterSpacing(0.06f);
        TextView title = Audit.secret(tx(c, 16, t.ink, 600, "Nothing playing"));
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView artist = Audit.secret(tx(c, 13, t.ink2, 500, ""));
        words.addView(app);
        words.addView(title, Ui.ll(-1, -1, 0, 4));
        words.addView(artist, Ui.ll(-1, -1, 0, 3));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.leftMargin = Ui.px(14);
        top.addView(words, wl);
        outer.addView(top, Ui.ll(-2, -1));
        // progress (and, on the large card, the buttons it answers to)
        FrameLayout bar = new FrameLayout(c);
        bar.setBackground(Ui.fillRound(t.hover, 2));
        View done = new View(c);
        done.setBackground(Ui.fillRound(t.ink2, 2));
        bar.addView(done, new FrameLayout.LayoutParams(0, -1));
        LinearLayout times = Ui.row(c);
        TextView el = tx(c, 13, t.ink3, 600, "");
        TextView left = tx(c, 13, t.ink3, 600, "");
        left.setGravity(Gravity.END);
        times.addView(el, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout buttons = null;
        ImageView pp = null;
        if (size == L) {
            outer.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
            outer.addView(bar, Ui.ll(-2, 4));
            outer.addView(times, Ui.ll(-2, -1, 0, 6));
            buttons = Ui.row(c);
            buttons.setGravity(Gravity.CENTER);
            ImageView prev = new ImageView(c);
            prev.setImageDrawable(Glyph.of("prev", t.ink2));
            pp = new ImageView(c);
            ImageView next = new ImageView(c);
            next.setImageDrawable(Glyph.of("next", t.ink2));
            buttons.addView(prev, Ui.ll(24, 24));
            buttons.addView(pp, Ui.ll(30, 30, 40, 0));
            buttons.addView(next, Ui.ll(24, 24, 40, 0));
            outer.addView(buttons, Ui.ll(-2, -1, 0, 10));
        } else {
            words.addView(bar, Ui.ll(-2, 4, 0, 12));
            words.addView(times, Ui.ll(-2, -1, 0, 5));
        }
        final ImageView fpp = pp;
        final String[] shownArt = {null};
        Runnable r = () -> {
            Media.Now n = Media.best();
            if (n == null) {
                app.setText("");
                title.setText("Nothing playing");
                artist.setText("From any app");
                art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                art.setImageDrawable(Glyph.of("music", t.ink3));
                art.setPadding(Ui.px(34), Ui.px(34), Ui.px(34), Ui.px(34));
                shownArt[0] = null;
                bar.setVisibility(View.INVISIBLE);
                times.setVisibility(View.INVISIBLE);
                if (fpp != null) fpp.setImageDrawable(Glyph.of("play", t.ink3));
                return;
            }
            CharSequence an = Sample.on ? Sample.APP : Glass.label(c, n.pkg);
            app.setText(an != null ? an.toString().toUpperCase(Locale.getDefault()) : "");
            title.setText(n.title);
            artist.setText(n.artist != null ? n.artist : n.album != null ? n.album : "");
            String key = n.pkg + "|" + n.title + "|" + (Media.art(n.pkg) != null ? Media.art(n.pkg).getGenerationId() : 0);
            if (!key.equals(shownArt[0])) {
                shownArt[0] = key;
                Bitmap b = Media.art(n.pkg);
                art.setPadding(0, 0, 0, 0);
                if (b != null) {
                    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    art.setImageBitmap(b);
                } else {
                    art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    art.setImageBitmap(AppIcons.of(c, n.pkg, Ui.px(artSize)));
                }
            }
            boolean timed = n.dur > 0;
            bar.setVisibility(timed ? View.VISIBLE : View.INVISIBLE);
            times.setVisibility(timed ? View.VISIBLE : View.INVISIBLE);
            if (timed) {
                long p = n.position();
                el.setText(Media.clock(p));
                left.setText("−" + Media.clock(n.dur - p));
                int bw = bar.getWidth();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) done.getLayoutParams();
                int want = Math.round(bw * Math.min(1f, p / (float) n.dur));
                if (lp.width != want) {
                    lp.width = want;
                    done.setLayoutParams(lp);
                }
            }
            if (fpp != null) fpp.setImageDrawable(Glyph.of(n.playing() ? "pause" : "play", t.ink));
        };
        refreshers.add(r);
        return outer;
    }

    private static View playingSmall(Context c, Ui.Tone t, List<Runnable> refreshers) {
        FrameLayout f = Audit.secret(new FrameLayout(c));
        ImageView art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(art, Ui.fill());
        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{Ui.a(0x000000, .66f), Ui.a(0x000000, .0f)}));
        f.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.px(96), Gravity.BOTTOM));
        LinearLayout words = Ui.column(c);
        words.setPadding(Ui.px(14), 0, Ui.px(14), Ui.px(12));
        TextView title = tx(c, 14, 0xFFFFFFFF, 600, "");
        TextView artist = tx(c, 13, Ui.a(0xFFFFFF, .75f), 500, "");
        words.addView(title);
        words.addView(artist, Ui.ll(-1, -1, 0, 2));
        f.addView(words, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        ImageView state = new ImageView(c);
        state.setBackground(Ui.oval(Ui.a(0x000000, .35f)));
        state.setPadding(Ui.px(6), Ui.px(6), Ui.px(6), Ui.px(6));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.px(28), Ui.px(28), Gravity.TOP | Gravity.END);
        sl.topMargin = Ui.px(10);
        sl.rightMargin = Ui.px(10);
        f.addView(state, sl);
        Runnable r = () -> {
            Media.Now n = Media.best();
            if (n == null) {
                art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                art.setImageDrawable(Glyph.of("music", t.ink3));
                art.setPadding(Ui.px(50), Ui.px(36), Ui.px(50), Ui.px(64));
                scrim.setVisibility(View.GONE);
                title.setTextColor(t.ink);
                artist.setTextColor(t.ink2);
                title.setText("Nothing playing");
                artist.setText("");
                state.setVisibility(View.GONE);
                return;
            }
            Bitmap b = Media.art(n.pkg);
            art.setPadding(0, 0, 0, 0);
            if (b != null) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageBitmap(b);
                scrim.setVisibility(View.VISIBLE);
                title.setTextColor(0xFFFFFFFF);
                artist.setTextColor(Ui.a(0xFFFFFF, .75f));
            } else {
                art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                art.setImageBitmap(AppIcons.of(c, n.pkg, Ui.px(64)));
                art.setPadding(0, 0, 0, Ui.px(36));
                scrim.setVisibility(View.GONE);
                title.setTextColor(t.ink);
                artist.setTextColor(t.ink2);
            }
            title.setText(n.title);
            artist.setText(n.artist != null ? n.artist : "");
            state.setVisibility(View.VISIBLE);
            state.setImageDrawable(Glyph.of(n.playing() ? "pause" : "play", 0xFFFFFFFF));
        };
        refreshers.add(r);
        return f;
    }

    // calendar --------------------------------------------------------------------------------------------------

    private static int red(Ui.Tone t) {
        return t.night ? 0xFFFF6961 : 0xFFE5372B;
    }

    private static View calendar(Context c, int size, Ui.Tone t) {
        Calendar cal = Calendar.getInstance();
        if (size == S) return today(c, t, cal, 16);
        if (size == M) {
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            row.addView(today(c, t, cal, 16), Ui.ll(124, -2));
            View g = month(c, t, cal, 12.5f, false);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, -1, 1f);
            gl.rightMargin = Ui.px(12);
            gl.topMargin = Ui.px(14);
            gl.bottomMargin = Ui.px(10);
            row.addView(g, gl);
            return row;
        }
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.px(16), Ui.px(14), Ui.px(16), Ui.px(12));
        TextView month = tx(c, 15, red(t), 700, DateFormat.format("MMMM", cal).toString().toUpperCase(Locale.getDefault()));
        month.setLetterSpacing(0.04f);
        col.addView(month);
        col.addView(month(c, t, cal, 13, true), new LinearLayout.LayoutParams(-1, 0, 1f) {{
            topMargin = Ui.px(8);
        }});
        return col;
    }

    private static View today(Context c, Ui.Tone t, Calendar cal, float pad) {
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.px(pad), Ui.px(14), Ui.px(8), Ui.px(14));
        TextView day = tx(c, 13, red(t), 700, DateFormat.format("EEEE", cal).toString().toUpperCase(Locale.getDefault()));
        day.setLetterSpacing(0.04f);
        col.addView(day);
        TextView num = big(c, 56, t.ink);
        num.setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
        col.addView(num, Ui.ll(-1, -1, 0, 2));
        col.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
        col.addView(tx(c, 13, t.ink2, 500, DateFormat.format(best("MMMMyyyy"), cal).toString()));
        return col;
    }

    /** This month as a grid, today ringed; `heads` adds the weekday letters. */
    private static View month(Context c, Ui.Tone t, Calendar cal, float size, boolean heads) {
        LinearLayout col = Ui.column(c);
        int today = cal.get(Calendar.DAY_OF_MONTH);
        int first = cal.getFirstDayOfWeek();
        if (heads) {
            LinearLayout hr = Ui.row(c);
            String[] names = new java.text.DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
            for (int i = 0; i < 7; i++) {
                int d = (first - 1 + i) % 7 + 1;
                TextView n = tx(c, 13, t.ink3, 600, names[d].substring(0, 1).toUpperCase(Locale.getDefault()));
                n.setGravity(Gravity.CENTER);
                hr.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            col.addView(hr, Ui.ll(-2, -1, 0, 0));
        }
        Calendar m = (Calendar) cal.clone();
        m.set(Calendar.DAY_OF_MONTH, 1);
        int lead = (m.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        int days = m.getActualMaximum(Calendar.DAY_OF_MONTH);
        int weeks = (lead + days + 6) / 7;
        int cell = 0;
        LinearLayout week = null;
        float dot = size * (heads ? 1.9f : 1.55f) * wf();   // today's ring fits its row (six weeks in a medium card)
        for (int i = 0; i < weeks * 7; i++) {
            if (cell % 7 == 0) {
                week = Ui.row(c);
                col.addView(week, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
            int day = i - lead + 1;
            boolean real = day >= 1 && day <= days;
            boolean me = day == today;
            TextView d = tx(c, size, me ? (t.night ? 0xFF0E0E10 : 0xFFFFFFFF) : t.ink2, me ? 700 : 500, real ? String.valueOf(day) : "");
            d.setGravity(Gravity.CENTER);
            FrameLayout box = new FrameLayout(c);
            if (me) d.setBackground(Ui.oval(red(t)));
            box.addView(d, new FrameLayout.LayoutParams(Ui.px(dot), Ui.px(dot), Gravity.CENTER));
            week.addView(box, new LinearLayout.LayoutParams(0, -1, 1f));
            cell++;
        }
        return col;
    }

    // photos ------------------------------------------------------------------------------------------------------

    private static View photos(Context c, int size, Ui.Tone t, List<Runnable> refreshers) {
        FrameLayout f = Audit.secret(new FrameLayout(c));
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, Ui.fill());
        TextView none = Ui.text(c, 13, t.ink2, 500);
        none.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(13) * wf());
        none.setGravity(Gravity.CENTER);
        none.setPadding(Ui.px(18), 0, Ui.px(18), 0);
        none.setText("Photos you send from your phone show here");
        f.addView(none, Ui.fill());
        TextView chip = null;
        if (size != S) {
            chip = tx(c, 13, 0xFFFFFFFF, 600, "");
            chip.setPadding(Ui.px(10), Ui.px(5), Ui.px(10), Ui.px(5));
            chip.setBackground(Ui.fillRound(Ui.a(0x000000, .38f), 11));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            cl.leftMargin = Ui.px(12);
            cl.bottomMargin = Ui.px(12);
            f.addView(chip, cl);
        }
        final TextView fchip = chip;
        final int[] at = {0};
        final int pw = Ui.px(w(size)), ph = Ui.px(h(size));
        Runnable r = () -> {
            List<File> p = Show.photos(c);
            none.setVisibility(p.isEmpty() ? View.VISIBLE : View.GONE);
            if (fchip != null) {
                fchip.setVisibility(p.isEmpty() ? View.GONE : View.VISIBLE);
                fchip.setText(p.size() == 1 ? "1 photo from a phone" : p.size() + " photos from phones");
            }
            if (p.isEmpty()) {
                img.setImageDrawable(null);
                return;
            }
            File file = p.get(at[0]++ % p.size());
            Ui.BG.execute(() -> {
                Bitmap b = Show.thumb(file, pw, ph);
                img.post(() -> {
                    img.setAlpha(0f);
                    img.setImageBitmap(b);
                    img.animate().alpha(1f).setDuration(Ui.ms(600)).start();
                });
            });
        };
        refreshers.add(r);
        return f;
    }

    // ---- the weather ----------------------------------------------------------------------------------------

    static final class Weather {
        private Weather() {
        }

        static final class Hour {
            String label, glyph;
            int temp;
        }

        static final class Day {
            String label, glyph;
            int hi, lo;
        }

        static final class Now {
            int temp, hi, lo;
            String text, glyph, place, sunrise, sunset;
            final List<Hour> hours = new ArrayList<>();
            final List<Day> days = new ArrayList<>();
        }

        private static final long EVERY = 30 * 60 * 1000L;
        private static volatile boolean fetching;

        /** The chosen place {name, lat, lon, country}, or null for "where the TV's internet address says". */
        static String[] place(Context c) {
            String s = Glass.prefs(c).getString("wx_place", null);
            if (s == null) return null;
            try {
                JSONObject o = new JSONObject(s);
                return new String[]{o.getString("name"), o.getString("lat"), o.getString("lon"), o.optString("cc", "")};
            } catch (Exception e) {
                return null;
            }
        }

        static String placeName(Context c) {
            if (Sample.on) return Sample.PLACE;
            String[] p = place(c);
            return p == null ? null : p[0];
        }

        /** Chooses a place (null: automatic again) and fetches its weather now. */
        static void setPlace(Context c, String name, double lat, double lon, String country) {
            SharedPreferences.Editor e = Glass.prefs(c).edit();
            if (name == null) e.remove("wx_place");
            else {
                try {
                    e.putString("wx_place", new JSONObject().put("name", name).put("lat", String.valueOf(lat)).put("lon", String.valueOf(lon))
                            .put("cc", country == null ? "" : country).toString());
                } catch (Exception ignored) {
                }
            }
            e.remove("wx_json").putLong("wx_at", 0).apply();
            fetching = false;
            fetch(c.getApplicationContext(), null);
        }

        /** The last weather (at most 3 hours old), fetching a newer one in the background when it's due. */
        static Now now(Context c) {
            if (Sample.on) return Sample.weather(c);
            SharedPreferences p = Glass.prefs(c);
            long at = p.getLong("wx_at", 0);
            String json = p.getString("wx_json", null);
            if (System.currentTimeMillis() - at > EVERY && !fetching && Glass.online(c)) fetch(c.getApplicationContext(), null);
            if (json == null || System.currentTimeMillis() - at > 3 * 60 * 60 * 1000L) return null;
            try {
                return parse(new JSONObject(json));
            } catch (Exception e) {
                return null;
            }
        }

        private static Now parse(JSONObject o) throws Exception {
            Now n = new Now();
            n.temp = o.getInt("t");
            n.hi = o.getInt("hi");
            n.lo = o.getInt("lo");
            n.text = o.getString("text");
            n.glyph = o.getString("glyph");
            n.place = o.optString("place", null);
            n.sunrise = o.optString("rise", null);
            n.sunset = o.optString("set", null);
            JSONArray hs = o.optJSONArray("hours");
            if (hs != null) for (int i = 0; i < hs.length(); i++) {
                JSONObject x = hs.getJSONObject(i);
                Hour hr = new Hour();
                hr.label = x.getString("l");
                hr.glyph = x.getString("g");
                hr.temp = x.getInt("t");
                n.hours.add(hr);
            }
            JSONArray ds = o.optJSONArray("days");
            if (ds != null) for (int i = 0; i < ds.length(); i++) {
                JSONObject x = ds.getJSONObject(i);
                Day d = new Day();
                d.label = x.getString("l");
                d.glyph = x.getString("g");
                d.hi = x.getInt("hi");
                d.lo = x.getInt("lo");
                n.days.add(d);
            }
            return n;
        }

        static String sunLine(Now w) {
            return "Sunrise " + w.sunrise + "  ·  Sunset " + w.sunset;
        }

        interface Done {
            void done(boolean ok);
        }

        /** Fetches the forecast for the chosen place (or the internet address's town); `done` on the main thread. */
        static void fetch(Context c, Done done) {
            fetching = true;
            new Thread(() -> {
                boolean ok = false;
                try {
                    String[] chosen = place(c);
                    String lat, lon, place, cc;
                    if (chosen != null) {
                        place = chosen[0];
                        lat = chosen[1];
                        lon = chosen[2];
                        cc = chosen[3];
                    } else {
                        JSONObject geo = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
                        lat = geo.getString("latitude");
                        lon = geo.getString("longitude");
                        place = geo.optString("city", null);
                        cc = geo.optString("country_code", "");
                    }
                    boolean f = Arrays.asList("US", "LR", "MM").contains(cc);
                    JSONObject fc = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=5"
                            + (f ? "&temperature_unit=fahrenheit" : "")));
                    JSONObject cur = fc.getJSONObject("current");
                    JSONObject daily = fc.getJSONObject("daily");
                    int code = cur.getInt("weather_code");
                    boolean day = cur.optInt("is_day", 1) == 1;
                    JSONObject out = new JSONObject();
                    out.put("t", Math.round(cur.getDouble("temperature_2m")));
                    out.put("hi", Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)));
                    out.put("lo", Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)));
                    out.put("text", text(code));
                    out.put("glyph", glyph(code, day));
                    if (place != null && !place.isEmpty() && !"null".equals(place)) out.put("place", place);
                    out.put("rise", hm(c, daily.getJSONArray("sunrise").getString(0)));
                    out.put("set", hm(c, daily.getJSONArray("sunset").getString(0)));
                    // the next five hours, from the hour after now (the forecast's own clock: the place's time)
                    JSONObject hourly = fc.getJSONObject("hourly");
                    JSONArray ht = hourly.getJSONArray("time"), htemp = hourly.getJSONArray("temperature_2m"), hcode = hourly.getJSONArray("weather_code");
                    JSONArray hday = hourly.optJSONArray("is_day");
                    String nowT = cur.getString("time");
                    JSONArray hours = new JSONArray();
                    for (int i = 0; i < ht.length() && hours.length() < 5; i++) {
                        String tt = ht.getString(i);
                        if (tt.compareTo(nowT) <= 0) continue;
                        boolean d = hday == null || hday.optInt(i, 1) == 1;
                        hours.put(new JSONObject().put("l", hourLabel(c, tt)).put("g", glyph(hcode.getInt(i), d)).put("t", Math.round(htemp.getDouble(i))));
                    }
                    out.put("hours", hours);
                    JSONArray dt = daily.getJSONArray("time"), dcode = daily.getJSONArray("weather_code");
                    JSONArray dmax = daily.getJSONArray("temperature_2m_max"), dmin = daily.getJSONArray("temperature_2m_min");
                    JSONArray days = new JSONArray();
                    for (int i = 0; i < dt.length() && i < 5; i++) {
                        days.put(new JSONObject().put("l", i == 0 ? "Today" : dayLabel(dt.getString(i))).put("g", glyph(dcode.getInt(i), true))
                                .put("hi", Math.round(dmax.getDouble(i))).put("lo", Math.round(dmin.getDouble(i))));
                    }
                    out.put("days", days);
                    Glass.prefs(c).edit().putString("wx_json", out.toString()).putLong("wx_at", System.currentTimeMillis()).apply();
                    ok = true;
                } catch (Exception e) {
                    // offline or the service is down: try again in a while
                    Glass.prefs(c).edit().putLong("wx_at", System.currentTimeMillis() - EVERY + 5 * 60 * 1000L).apply();
                } finally {
                    fetching = false;
                }
                final boolean fok = ok;
                if (done != null) new Handler(Looper.getMainLooper()).post(() -> done.done(fok));
            }, "glass-weather").start();
        }

        /** "06:12" (the forecast's ISO time) as the TV tells time: "6:12 AM" or "06:12". */
        private static String hm(Context c, String iso) {
            try {
                int t = iso.indexOf('T');
                String[] p = iso.substring(t + 1).split(":");
                int hh = Integer.parseInt(p[0]), mm = Integer.parseInt(p[1]);
                Calendar k = Calendar.getInstance();
                k.set(Calendar.HOUR_OF_DAY, hh);
                k.set(Calendar.MINUTE, mm);
                return DateFormat.getTimeFormat(c).format(k.getTime());
            } catch (Exception e) {
                return iso;
            }
        }

        private static String hourLabel(Context c, String iso) {
            try {
                int hh = Integer.parseInt(iso.substring(iso.indexOf('T') + 1, iso.indexOf('T') + 3));
                if (DateFormat.is24HourFormat(c)) return String.format(Locale.ROOT, "%02d", hh);
                return (hh % 12 == 0 ? 12 : hh % 12) + (hh < 12 ? "am" : "pm");
            } catch (Exception e) {
                return "";
            }
        }

        private static String dayLabel(String isoDate) {
            try {
                java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                return new java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(in.parse(isoDate));
            } catch (Exception e) {
                return "";
            }
        }

        /** Places matching what was typed: {name, region · country, lat, lon, country code}. Network: call off the UI. */
        static List<String[]> search(String q) {
            List<String[]> out = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                        + URLEncoder.encode(q, "UTF-8")));
                JSONArray r = o.optJSONArray("results");
                if (r == null) return out;
                for (int i = 0; i < r.length(); i++) {
                    JSONObject p = r.getJSONObject(i);
                    String region = p.optString("admin1", "");
                    String country = p.optString("country", "");
                    String where = region.isEmpty() ? country : country.isEmpty() ? region : region + ", " + country;
                    out.add(new String[]{p.getString("name"), where, String.valueOf(p.getDouble("latitude")), String.valueOf(p.getDouble("longitude")),
                            p.optString("country_code", "")});
                }
            } catch (Exception ignored) {
                // offline: nothing found
            }
            return out;
        }

        private static String get(String url) throws Exception {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(6000);
            h.setReadTimeout(8000);
            h.setRequestProperty("User-Agent", "Glass-TV");
            try (InputStream in = h.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) {
                    b.write(buf, 0, n);
                    if (b.size() > 256 * 1024) break;
                }
                return b.toString("UTF-8");
            } finally {
                h.disconnect();
            }
        }

        /** WMO weather codes, in a few plain words. */
        static String text(int code) {
            if (code == 0) return "Clear";
            if (code <= 2) return "Partly cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code >= 61 && code <= 67) return "Rain";
            if (code >= 71 && code <= 77) return "Snow";
            if (code >= 80 && code <= 82) return "Showers";
            if (code == 85 || code == 86) return "Snow showers";
            if (code >= 95) return "Thunderstorm";
            return "Weather";
        }

        static String glyph(int code, boolean day) {
            if (code == 0) return day ? "sun" : "moon";
            if (code <= 2) return day ? "sun_cloud" : "cloud";
            if (code == 45 || code == 48) return "fog";
            if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return "rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snow";
            if (code >= 95) return "storm";
            return "cloud";
        }
    }
}
