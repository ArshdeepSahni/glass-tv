package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * The remote's assistant button, Glass's way. On these TVs the button never reaches an app: the remote's own service
 * opens Google's voice screen directly. So the moment Google's screen appears, by the kind of screen it is:
 *   an activity (a MediaTek TV): Glass covers it with its own listening glass and closes it as soon as it has the
 *     TV's focus (before Google hears or answers anything), and Glass's assistant listens instead (the remote's
 *     microphone answers any app that listens there).
 *   a window over the apps (a Xiaomi): the remote's service hands the button and its sound to Google's app alone,
 *     and Google's voice session keeps that sound for as long as it lives, window or not. Glass notes the TV
 *     (GOOGLE_ONLY), stops Google at once (Android's Force stop, through Glass's own key), which frees the remote's
 *     microphone, and Glass's assistant listens through the TV's speech recognizer. After a stop, the TV's next
 *     press comes as an activity of Google's, which goes the same way on such a TV.
 * What's on the TV is noted first (Screen), before any window goes over it.
 * Press the button again while Glass listens: what it heard so far is sent.
 * Settings > Assistant > Assistant button: Google leaves the button to Google entirely.
 */
final class Takeover {
    private Takeover() {
    }

    private static final String TAG = "GlassKeys";
    static final String GOOGLE = "com.google.android.katniss";
    /** Seen on this TV: Google's voice screen is a window over the apps, and the remote's mic button and sound reach
     *  only Google's app: Google is stopped at once whenever its voice screen comes up (googleAppeared). */
    static final String GOOGLE_ONLY = "voice_button_only";
    private static final long QUIET_AFTER_MS = 1500;

    private static boolean active;
    private static long quietUntil;
    private static Handler h;
    private static HomeKeyService svc;
    private static FrameLayout window;
    private static TextView words;
    private static View bead;
    private static long began;

    /** Whether the button belongs to Glass (Settings > Assistant > Assistant button, kept by Glass: Assist). */
    static boolean glassOwnsButton(HomeKeyService s) {
        return Assist.owns(s);
    }

    /**
     * A window of Google's assistant came up (HomeKeyService's window events). overlay: it isn't an activity but a
     * window over the apps (a Xiaomi's), whose remote hands the button and its sound to Google's app alone.
     */
    static void googleAppeared(HomeKeyService s, boolean overlay) {
        long now = SystemClock.uptimeMillis();
        if (active || now < quietUntil || !glassOwnsButton(s)) return;
        if (s.getPackageManager().getLaunchIntentForPackage(GlassKeys.ASSISTANT) == null) return;
        if (overlay) Glass.prefs(s).edit().putBoolean(GOOGLE_ONLY, true).apply();
        boolean googleOnly = Glass.prefs(s).getBoolean(GOOGLE_ONLY, false);
        // where only Google's app hears the remote, Glass needs its own key to stop Google: without it, Google answers
        if (googleOnly && !Keys.everAllowed(s)) return;
        active = true;
        svc = s;
        if (h == null) h = new Handler(Looper.getMainLooper());
        began = now;
        Hud.closeAll();
        // what's on the TV, before the assistant's own window goes over it
        final android.content.Context app = s.getApplicationContext();
        Ui.BG.execute(() -> Screen.snapshot(app));
        cover();
        Sound.glass();
        if (googleOnly) {
            // Google's voice session keeps the remote's sound while it lives, window or not: it's stopped at once,
            // which frees the remote's microphone, and Glass's assistant listens through the TV's speech recognizer
            Log.i(TAG, "takeover: Google's voice screen covered; Google stops, Glass listens");
            stopGoogle(Takeover::handOver);
        } else {
            h.postDelayed(Takeover::dismissGoogle, 60);
            Log.i(TAG, "takeover: Google's voice screen covered");
        }
    }

    /** Google's assistant stopped as Android's own Force stop does (through Glass's own key, LocalAdb: it never asks
     *  on the TV), before it answers anything; its recognizer comes back by itself when Glass's assistant asks for it. */
    private static void stopGoogle(Runnable then) {
        new Thread(() -> {
            String out;
            try {
                out = LocalAdb.quiet("am force-stop " + GOOGLE + "; echo stopped");
            } catch (Exception e) {
                out = e.getClass().getSimpleName();
            }
            Log.i(TAG, "takeover: Google " + (out.contains("stopped") ? "stopped" : "not stopped (" + out.trim() + ")")
                    + " after " + (SystemClock.uptimeMillis() - began) + " ms");
            h.post(then);
        }, "glass-takeover").start();
    }

    /** Google's screen goes as soon as it holds the TV's focus: a Back any earlier would land in the app (a film). */
    private static void dismissGoogle() {
        if (!active) return;
        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo r = activeGoogle();
        if (r != null) {
            logShape(r);
            r.recycle();
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            Log.i(TAG, "takeover: Google's screen closed after " + (now - began) + " ms");
            handOver();
            return;
        }
        if (now - began > 1500) {   // it never took the focus: leave it be, Glass listens all the same
            Log.i(TAG, "takeover: Google's screen never took the focus");
            handOver();
            return;
        }
        h.postDelayed(Takeover::dismissGoogle, 40);
    }

    /** Glass's assistant, listening (or, already open, told the button was pressed again). */
    private static void handOver() {
        active = false;
        quietUntil = SystemClock.uptimeMillis() + QUIET_AFTER_MS;
        Intent ask = svc.getPackageManager().getLaunchIntentForPackage(GlassKeys.ASSISTANT);
        if (ask != null) {
            GlassKeys.assistant(svc, ask);
            ask.putExtra("button", true);
            final Intent go = ask;
            h.postDelayed(() -> GlassKeys.start(svc, go), 90);   // after Google's screen has gone
        }
        h.postDelayed(Takeover::uncover, 520);
    }

    /** Google's window, only if it is the one holding the TV's focus. */
    private static AccessibilityNodeInfo activeGoogle() {
        try {
            AccessibilityNodeInfo r = svc.getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && GOOGLE.contentEquals(r.getPackageName())) return r;
            if (r != null) r.recycle();
        } catch (RuntimeException ignored) {
            // nothing readable
        }
        return null;
    }

    /** The layout of Google's screen, for tuning (ids, kinds and sizes; never words). */
    private static void logShape(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> texts = new ArrayList<>();
        StringBuilder shape = new StringBuilder();
        collect(root, texts, shape, 0);
        for (AccessibilityNodeInfo n : texts) n.recycle();
        String sh = shape.toString();
        Log.i(TAG, "google screen: " + (sh.length() > 900 ? sh.substring(0, 900) : sh));
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> texts, StringBuilder shape, int depth) {
        if (n == null || depth > 30 || texts.size() > 60) return;
        if (!n.isVisibleToUser()) return;
        CharSequence t = n.getText();
        String id = n.getViewIdResourceName();
        CharSequence cls = n.getClassName();
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        shape.append(id != null ? id.substring(id.indexOf('/') + 1) : "-").append(':')
                .append(cls != null ? cls.toString().substring(cls.toString().lastIndexOf('.') + 1) : "?").append(':')
                .append(t != null ? t.length() : 0).append('@').append(b.left).append(',').append(b.top).append(',').append(b.width()).append('x').append(b.height()).append(' ');
        if (t != null && t.length() > 0 && !n.isEditable()) texts.add(AccessibilityNodeInfo.obtain(n));
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch;
            try {
                ch = n.getChild(i);
            } catch (RuntimeException e) {
                continue;
            }
            if (ch == null) continue;
            collect(ch, texts, shape, depth + 1);
            ch.recycle();
        }
    }

    // ---- Glass's listening glass ---------------------------------------------------------------------------------

    private static void cover() {
        WindowManager wm = svc.getSystemService(WindowManager.class);
        if (wm == null) return;
        Ui.Tone saved = Ui.t;
        boolean night = Ui.night(Glass.prefs(svc).getString(GlassHomeActivity.APPEARANCE, "auto"));
        Ui.t = night ? Ui.NIGHT : Ui.DAY;   // the desktop's own look
        try {
            window = new FrameLayout(svc);
            window.setClipChildren(false);
            // the TV goes quiet under Glass's own frosted wallpaper, dense enough that Google's screen is never seen
            ImageView veil = new ImageView(svc);
            veil.setScaleType(ImageView.ScaleType.FIT_XY);
            veil.setBackgroundColor(night ? 0xFF0A0C14 : 0xFFE6EAF0);
            Bitmap frost = Pane.frostNow(night);
            if (frost != null) veil.setImageDrawable(new BitmapDrawable(svc.getResources(), frost));
            veil.setAlpha(0f);
            window.addView(veil, Ui.fill());
            // the capsule, where Glass's assistant will be a moment later
            Pane cap = new Pane(svc, true, 800, 42, 464, 76, 38);
            cap.bake(svc);
            FrameLayout in = new FrameLayout(svc);
            bead = new View(svc);
            bead.setBackground(Ui.oval(0xFFFF7A45));
            in.addView(bead, Ui.at(26, 26, 24, 24));
            words = Ui.text(svc, 22, Ui.t.ink, 500);
            words.setSingleLine(true);
            words.setEllipsize(TextUtils.TruncateAt.START);
            words.setGravity(Gravity.CENTER_VERTICAL);
            words.setText("Listening…");
            words.setAlpha(.7f);
            in.addView(words, Ui.at(70, 24, 464 - 70 - 22, 28));
            cap.addView(in, Ui.fill());
            window.addView(cap, cap.params());
            cap.setAlpha(0f);
            cap.setScaleX(.9f);
            cap.setScaleY(.9f);
            cap.setPivotX(Ui.pxf(464 - 38));
            cap.setPivotY(Ui.pxf(38));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("Glass listening");
            wm.addView(window, lp);
            veil.animate().alpha(.96f).setDuration(Ui.ms(160)).start();
            Spring.to(cap, DynamicAnimation.ALPHA, 1f, Spring.SOFT);
            Spring.scale(cap, 1f, Spring.PANEL);
            breathe();
        } catch (RuntimeException e) {
            window = null;
        } finally {
            Ui.t = saved;
        }
    }

    private static void breathe() {
        if (bead == null || window == null) return;
        float to = bead.getScaleX() > 1f ? .86f : 1.14f;
        bead.animate().scaleX(to).scaleY(to).setDuration(520).withEndAction(Takeover::breathe).start();
    }

    private static void uncover() {
        FrameLayout w = window;
        window = null;
        words = null;
        bead = null;
        if (w == null) return;
        WindowManager wm = svc.getSystemService(WindowManager.class);
        w.animate().alpha(0f).setDuration(Ui.ms(240)).withEndAction(() -> {
            try {
                if (wm != null) wm.removeView(w);
            } catch (RuntimeException ignored) {
            }
        }).start();
    }

    static boolean active() {
        return active;
    }

}
