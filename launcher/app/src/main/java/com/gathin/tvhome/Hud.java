package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Glass over whatever is playing, drawn by the remote helper (an accessibility overlay: the app underneath keeps
 * playing, nothing is paused or restarted). The remote's own buttons land here instead of the TV's native UI:
 *   Volume / Mute    the volume HUD over the TV's own bar (bottom centre), gone after the TV's bar is
 *   Menu, Settings   Control Center
 *   Input            the sources, as a glass menu
 * Over a film the glass is dark tinted frost; over the desktop it is the desktop's own live glass.
 */
final class Hud implements Desk {
    private static Hud live;
    private final HomeKeyService svc;
    private final WindowManager wm;
    private Root root;
    private final List<Sheet> sheets = new ArrayList<>();

    private Hud(HomeKeyService svc) {
        this.svc = svc;
        wm = svc.getSystemService(WindowManager.class);
    }

    static Hud of(HomeKeyService s) {
        if (live == null || live.svc != s) live = new Hud(s);
        return live;
    }

    static void closeAll() {
        Hud h = live;
        if (h == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (Sheet s : new ArrayList<>(h.sheets)) h.dismiss(s);
            h.hidePill(true);
        });
    }

    static boolean open() {
        return live != null && !live.sheets.isEmpty();
    }

    private Ui.Tone tone() {
        if (GlassHomeActivity.showing()) return Ui.night(Glass.prefs(svc).getString(GlassHomeActivity.APPEARANCE, "auto")) ? Ui.NIGHT : Ui.DAY;
        return Ui.NIGHT;
    }

    /** Builds with the overlay's own look, without disturbing the desktop's. */
    private void withTone(Runnable r) {
        Ui.Tone saved = Ui.t;
        Ui.t = tone();
        try {
            r.run();
        } finally {
            Ui.t = saved;
        }
    }

    // ---- the window for panels ------------------------------------------------------------------------------

    private final class Root extends FrameLayout {
        Root(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int k = e.getKeyCode();
            Sheet top = sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (top != null && !top.closing && top.onKey(e)) return true;
                if (k == KeyEvent.KEYCODE_BACK) return true;
            } else if (e.getAction() == KeyEvent.ACTION_UP && k == KeyEvent.KEYCODE_BACK) {
                if (!e.isCanceled() && top != null && !top.onBack()) dismiss(top);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    private void ensureWindow() {
        if (root != null) return;
        root = new Root(svc);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, lp);
            Ui.marquee(root);   // words cut short scroll into view while focused, over apps too
        } catch (RuntimeException e) {
            root = null;
        }
    }

    private void dropWindow() {
        if (root == null || !sheets.isEmpty()) return;
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
        }
        root = null;
    }

    void controls() {
        Sheet top = sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
        if (top instanceof ControlsSheet) {
            dismiss(top);
            return;
        }
        withTone(() -> show(new ControlsSheet(this, "main", "controls"), null));
    }

    void inputs() {
        Sheet top = sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
        if (top instanceof Sheet.Menu) {
            dismiss(top);
            return;
        }
        withTone(() -> {
            List<Sheet.Row> rows = new ArrayList<>();
            for (GlassInputs.Input in : GlassInputs.list(svc)) {
                String detail = in.on ? "On" : in.empty ? null : in.title.equals(in.port) ? null : in.port;
                rows.add(new Sheet.Row(in.hdmi ? "input" : "tv", in.title, detail, () -> GlassInputs.open(svc, in.id)));
            }
            String liveTv = Config.load(svc).liveTvPackage;
            if (liveTv != null && !liveTv.isEmpty() && svc.getPackageManager().getLeanbackLaunchIntentForPackage(liveTv) != null) {
                rows.add(new Sheet.Row("live", "Live TV", null, () -> Glass.open(svc, liveTv)));
            }
            rows.add(new Sheet.Row("home", "Home", null, () -> GlassKeys.home(svc)));
            Sheet.Menu m = new Sheet.Menu(this, rows, 1280 - Ui.EDGE - 300, panelTop(), 300, false);
            m.dropX = 1280 - Ui.EDGE - 150;
            show(m, null);
        });
    }

    /** Hold Home inside an app: the open apps over it (the app stays as it is underneath). Held again: away. */
    void switcher() {
        Sheet top = sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
        if (top instanceof SwitcherSheet) {
            dismiss(top);
            return;
        }
        withTone(() -> show(new SwitcherSheet(this), null));
    }

    @Override
    public void show(Sheet s, View anchor) {
        for (Sheet o : new ArrayList<>(sheets)) dismiss(o);
        put(s, anchor);
    }

    /** Over what's open (a card's menu over the switcher): what's under it stays, and gets the focus back after. */
    @Override
    public void stack(Sheet s, View anchor) {
        put(s, anchor);
    }

    private void put(Sheet s, View anchor) {
        ensureWindow();
        if (root == null) return;
        Ui.Tone saved = Ui.t;
        Ui.t = s.t;
        try {
            s.anchor = anchor;
            s.attach(root);
        } finally {
            Ui.t = saved;
        }
        sheets.add(s);
        s.animateIn();
        View f = s.firstFocus();
        if (f != null) f.post(f::requestFocus);
    }

    @Override
    public void dismiss(Sheet s) {
        if (s == null || s.closing || !sheets.contains(s)) return;
        sheets.remove(s);
        s.onClosed();
        final View r = s.root;
        s.animateOut(() -> {
            if (root != null) root.removeView(r);
            dropWindow();
        });
        Sheet top = sheets.isEmpty() ? null : sheets.get(sheets.size() - 1);
        if (top != null && !top.closing) {   // focus back where it was in the sheet under it
            View back = s.anchor;
            if (back != null && back.isAttachedToWindow() && back.isShown() && top.root.inside(back)) back.requestFocus();
            else {
                View f = top.firstFocus();
                if (f != null) f.requestFocus();
            }
        }
    }

    // ---- the volume HUD -------------------------------------------------------------------------------------
    //
    // Android TV hands Vol+ and Vol- to the system before any app can see them: it changes the volume and shows
    // its own bar at the bottom centre (a rule of Android TV; only root could change it). So Glass follows the
    // volume instead of the keys, and its HUD sits exactly over the TV's bar, a little larger and opaque, for as
    // long as the TV's bar lives (about 3.5 s after the last change). The TV's bar is never seen.

    /** The TV's own bar on this firmware: x 437-841, y 668 to the bottom edge. Ours covers it with a margin. */
    static final float HW = 444, HV = 66, HX = (1280 - HW) / 2, HY = 720 - HV, HM = 36;
    static final long HUD_MS = 4200;
    private FrameLayout hudWindow;
    private Pane hud;
    private View hudFill;
    private TextView hudNumber;
    private ImageView hudGlyph;
    private boolean hudNight;
    private final Runnable hide = () -> hidePill(false);

    /** Shows (or updates) the HUD with the level now; `ceiling`: the night ceiling just held it back. */
    void volume(boolean ceiling) {
        AudioManager am = svc.getSystemService(AudioManager.class);
        if (am == null) return;
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        boolean muted = v == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC);
        boolean night = tone().night;
        if (hudWindow != null && night != hudNight) hidePill(true);   // the look changed under it: build afresh
        if (hudWindow == null) withTone(this::buildPill);
        if (hudWindow == null) return;
        hud.overApp(!GlassHomeActivity.showing());   // the desktop's glass, or opaque frost over a film
        Ui.Tone t = tone();
        hudGlyph.setImageDrawable(Glyph.of(muted ? "mute" : "volume", t.ink));
        hudNumber.setText(muted ? "Muted" : ceiling ? v + " \u00b7 night" : String.valueOf(v));
        float to = muted ? 0f : v / (float) max;
        Spring.to(hudFill, DynamicAnimation.SCALE_X, Math.max(0.0001f, to), Spring.FOCUS);
        hud.animate().cancel();   // caught while fading: it comes back instead of vanishing under the press
        hud.setAlpha(1f);         // opaque at once: the TV's bar must never show through
        if (hud.getTranslationY() != 0f) Spring.to(hud, DynamicAnimation.TRANSLATION_Y, 0f, Spring.PANEL);
        hudWindow.removeCallbacks(hide);
        hudWindow.postDelayed(hide, HUD_MS);
    }

    private void buildPill() {
        Ui.Tone t = Ui.t;
        hudNight = t.night;
        boolean over = !GlassHomeActivity.showing();
        hudWindow = new FrameLayout(svc);
        hudWindow.setClipChildren(false);
        // glass taller than the window: its foot runs off the bottom edge, so only the top corners are rounded
        hud = new Pane(svc, true, HM, HM, HW, HV + 34, 26);
        hud.overApp(over);
        hud.solid(true);
        FrameLayout in = new FrameLayout(svc);
        hudGlyph = new ImageView(svc);
        in.addView(hudGlyph, Ui.at(24, 22, 22, 22));
        FrameLayout track = new FrameLayout(svc);
        track.setBackground(Ui.fillRound(t.night ? Ui.a(0xFFFFFF, .16f) : Ui.a(0x16181D, .12f), 3));
        track.setClipToOutline(true);
        hudFill = new View(svc);
        hudFill.setBackground(Ui.fillRound(t.ink, 3));
        hudFill.setPivotX(0);
        hudFill.setScaleX(0.0001f);
        track.addView(hudFill, new FrameLayout.LayoutParams(-1, -1));
        in.addView(track, Ui.at(62, 30, HW - 62 - 84, 6));
        hudNumber = Ui.line(svc, 17, t.ink, 600, "");
        hudNumber.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        hudNumber.setFontFeatureSettings("tnum");
        in.addView(hudNumber, Ui.at(HW - 84, 0, 62, HV));
        hud.addView(in, Ui.fill());
        hudWindow.addView(hud, hud.params());
        hud.bake(svc);
        hud.setTranslationY(Ui.pxf(10));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Ui.px(HW + 2 * HM), Ui.px(HV + HM),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Ui.px(HX - HM);
        lp.y = Ui.px(HY - HM);
        lp.setTitle("Glass volume");
        try {
            wm.addView(hudWindow, lp);
        } catch (RuntimeException e) {
            hudWindow = null;
        }
    }

    private void hidePill(boolean now) {
        if (hudWindow == null) return;
        FrameLayout w = hudWindow;
        w.removeCallbacks(hide);
        Runnable gone = () -> {
            try {
                wm.removeView(w);
            } catch (RuntimeException ignored) {
            }
            if (hudWindow == w) hudWindow = null;
        };
        if (now) {
            gone.run();
            return;
        }
        Spring.stop(hud);
        hud.animate().alpha(0f).translationY(Ui.pxf(8)).setDuration(Ui.ms(220)).setInterpolator(Ui.EIN).withEndAction(gone).start();
    }

    // ---- a phone's pointer --------------------------------------------------------------------------------------

    private FrameLayout pointWindow;
    private View pointDot, pointRing;
    private WindowManager.LayoutParams pointLp;
    private float px = -1, py = -1;   // where it is (screen pixels, its centre)
    private static final float PS = 64;   // the window, design px; the dot is 22 in the middle of it
    private final Runnable pointFade = () -> {
        if (pointWindow == null) return;
        pointWindow.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(Ui.ms(320)).setInterpolator(Ui.EIN).start();
        pointWindow.postDelayed(this::dropPointer, 2500);
    };

    /**
     * A phone's finger on its touchpad, shown on the TV: "s" it comes (fading in where it last was, else the middle),
     * "m" it moves by dx, dy (the phone's pixels, scaled), "e" the finger lifted (it fades a moment later), "c" a tap
     * there (Point & tap: a real tap through the key helper). Main thread.
     */
    void pointer(String phase, float dx, float dy) {
        int sw = svc.getResources().getDisplayMetrics().widthPixels, sh = svc.getResources().getDisplayMetrics().heightPixels;
        if (px < 0) {
            px = sw / 2f;
            py = sh / 2f;
        }
        if (pointWindow == null) buildPointer();
        if (pointWindow == null) return;
        pointWindow.removeCallbacks(pointFade);
        switch (phase) {
            case "s":
                pointWindow.animate().cancel();
                pointWindow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.ms(160)).setInterpolator(Ui.OUT).start();
                break;
            case "m": {
                float gain = 2.4f * Ui.s;
                px = Math.max(0, Math.min(sw - 1, px + dx * gain));
                py = Math.max(0, Math.min(sh - 1, py + dy * gain));
                place();
                if (pointWindow.getAlpha() < 1f) pointWindow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.ms(120)).start();
                break;
            }
            case "c":
                pointRing.setScaleX(0.6f);
                pointRing.setScaleY(0.6f);
                pointRing.setAlpha(0.9f);
                pointRing.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(Ui.ms(380)).setInterpolator(Ui.OUT).start();
                final float x = px, y = py;
                new Thread(() -> Keys.tap(svc, x, y), "glass-tap").start();
                pointWindow.postDelayed(pointFade, 1600);
                break;
            default:   // "e": the finger lifted
                pointWindow.postDelayed(pointFade, 900);
        }
    }

    private void place() {
        if (pointWindow == null || pointLp == null) return;
        pointLp.x = Math.round(px - Ui.pxf(PS / 2));
        pointLp.y = Math.round(py - Ui.pxf(PS / 2));
        try {
            wm.updateViewLayout(pointWindow, pointLp);
        } catch (RuntimeException ignored) {
        }
    }

    private void buildPointer() {
        pointWindow = new FrameLayout(svc);
        pointWindow.setClipChildren(false);
        pointRing = new View(svc);
        android.graphics.drawable.GradientDrawable ring = Ui.oval(0);
        ring.setStroke(Math.round(Ui.pxf(2)), 0xFFFFFFFF);
        pointRing.setBackground(ring);
        pointRing.setAlpha(0f);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.px(40), Ui.px(40), Gravity.CENTER);
        pointWindow.addView(pointRing, rl);
        // the dot: white glass with a dark hairline, so it shows on anything, on a soft shadow
        pointDot = new View(svc);
        android.graphics.drawable.GradientDrawable dot = Ui.oval(Ui.a(0xFFFFFF, .92f));
        dot.setStroke(Math.max(1, Ui.px(1.5f)), Ui.a(0x000000, .35f));
        pointDot.setBackground(dot);
        pointDot.setElevation(Ui.pxf(6));
        pointDot.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.px(22), Ui.px(22), Gravity.CENTER);
        pointWindow.addView(pointDot, dl);
        pointWindow.setAlpha(0f);
        pointWindow.setScaleX(0.7f);
        pointWindow.setScaleY(0.7f);
        pointLp = new WindowManager.LayoutParams(Ui.px(PS), Ui.px(PS),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        pointLp.gravity = Gravity.TOP | Gravity.START;
        pointLp.x = Math.round(px - Ui.pxf(PS / 2));
        pointLp.y = Math.round(py - Ui.pxf(PS / 2));
        pointLp.setTitle("Glass pointer");
        try {
            wm.addView(pointWindow, pointLp);
        } catch (RuntimeException e) {
            pointWindow = null;
        }
    }

    private void dropPointer() {
        if (pointWindow == null) return;
        try {
            wm.removeView(pointWindow);
        } catch (RuntimeException ignored) {
        }
        pointWindow = null;
    }

    // ---- a note (a reminder the assistant set) ------------------------------------------------------------------

    private FrameLayout noteWindow;

    /** A glass note at the top right over anything, for 12 s: a reminder's words. */
    void note(String title, String text) {
        note(title, text, "clock");
    }

    /** The same with another glyph (an update: "check"). */
    void note(String title, String text, String glyph) {
        dropNote();
        Ui.Tone saved = Ui.t;
        Ui.t = tone();
        try {
            Ui.Tone t = Ui.t;
            boolean over = !GlassHomeActivity.showing();
            float hh = Ui.boxf(18, 13), bh = Ui.boxf(24, 17);
            float w = 420 + 60 * Math.max(0f, Ui.fs - 1f), h = 14 + hh + 2 + bh + 14, M = 32;
            noteWindow = new FrameLayout(svc);
            noteWindow.setClipChildren(false);
            Pane p = new Pane(svc, true, M, M, w, h, 22);
            p.overApp(over);
            p.solid(over);
            FrameLayout in = new FrameLayout(svc);
            ImageView gl = new ImageView(svc);
            gl.setImageDrawable(Glyph.of(glyph, t.ink));
            in.addView(gl, Ui.at(20, h / 2 - 11, 22, 22));
            TextView head = Ui.line(svc, 13, t.ink2, 600, title);
            in.addView(head, Ui.at(56, 14, w - 76, hh));
            TextView body = Ui.line(svc, 17, t.ink, 500, text);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            in.addView(body, Ui.at(56, 14 + hh + 2, w - 76, bh));
            p.addView(in, Ui.fill());
            noteWindow.addView(p, p.params());
            p.bake(svc);
            p.setAlpha(0f);
            p.setTranslationY(-Ui.pxf(10));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Ui.px(w + 2 * M), Ui.px(h + 2 * M),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Ui.px(1280 - Ui.EDGE - w - M);
            lp.y = Ui.px((over ? 24 : Ui.PANEL_Y) - M);
            lp.setTitle("Glass note");
            wm.addView(noteWindow, lp);
            Spring.to(p, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
            Spring.to(p, DynamicAnimation.TRANSLATION_Y, 0f, Spring.PANEL);
            final FrameLayout mine = noteWindow;
            mine.postDelayed(() -> {
                if (noteWindow == mine) dropNote();
            }, 12_000);
        } catch (RuntimeException e) {
            noteWindow = null;
        } finally {
            Ui.t = saved;
        }
    }

    private void dropNote() {
        FrameLayout w = noteWindow;
        noteWindow = null;
        if (w == null) return;
        w.animate().alpha(0f).setDuration(Ui.ms(220)).withEndAction(() -> {
            try {
                wm.removeView(w);
            } catch (RuntimeException ignored) {
            }
        }).start();
    }

    /** An open Control Center catches up with a change made elsewhere (the remote's Vol+/-). */
    void refreshControls() {
        for (Sheet s : sheets) if (s instanceof ControlsSheet && !s.closing) ((ControlsSheet) s).refresh();
    }

    // ---- Desk -----------------------------------------------------------------------------------------------

    @Override public Context context() { return svc; }
    @Override public float barCenter(String key) { return 1280 - Ui.EDGE - ControlsSheet.W / 2; }
    @Override public float panelTop() { return GlassHomeActivity.showing() ? Ui.PANEL_Y : 24; }
    @Override public boolean overApp() { return !GlassHomeActivity.showing(); }
    @Override public Bar bar() { return null; }

    @Override
    public void openSystem(String action) {
        closeAll();
        try {
            svc.startActivity(SystemSettings.intent(svc, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    @Override public void openControls() { controls(); }
    @Override public void openInputs() { inputs(); }

    @Override
    public void openSettings(String section) {
        closeAll();
        GlassKeys.open(svc, "settings" + (section != null ? ":" + section : ""));
    }

    @Override
    public void openSearch() {
        closeAll();
        GlassKeys.open(svc, "search");
    }

    @Override
    public void openApps() {
        closeAll();
        GlassKeys.open(svc, "apps");
    }

    @Override
    public void launch(String pkg, View from) {
        closeAll();
        Glass.open(svc, pkg, null);
    }

    @Override public void openFile(File f) { Files.open(svc, f, false); }

    @Override
    public void ask(String q) {
        closeAll();
        Intent i = svc.getPackageManager().getLaunchIntentForPackage(GlassKeys.ASSISTANT);
        if (i == null) return;
        GlassKeys.start(svc, GlassKeys.assistant(svc, i));
    }

    @Override
    public void openAppInfo(String pkg) {
        closeAll();
        try {
            svc.startActivity(SystemSettings.appInfo(svc, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    @Override public List<String[]> allApps() { return GlassHomeActivity.apps(svc); }
    @Override public void lookChanged() { }
    @Override public void fontScaleChanged() { }
    @Override public void resetDock() { }
    @Override public void appMenu(String pkg, View from) { }
}
