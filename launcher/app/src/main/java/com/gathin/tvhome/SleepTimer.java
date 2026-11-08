package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Glass: a sleep timer that runs inside HomeKeyService, so it can show its last minute over whatever is playing
 * (an accessibility overlay needs no "draw over apps" permission) and put the TV in standby with the service's
 * GLOBAL_ACTION_LOCK_SCREEN. Set from the Palette; OK during the last minute adds 15 minutes; any other key is
 * left alone, so a film keeps its controls. Nothing is scheduled with alarms: the service holds the timer, and a
 * service that is unbound (the TV off at the wall) has nothing left to do.
 */
final class SleepTimer {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final long WARN_MS = 60 * 1000L;
    private static long endsAt;            // SystemClock.elapsedRealtime(), 0 when off
    private static View overlay;
    private static TextView countdown;

    private SleepTimer() {
    }

    /** Minutes left, or 0 when no timer is set. */
    static int minutesLeft() {
        if (endsAt == 0) return 0;
        long left = endsAt - SystemClock.elapsedRealtime();
        return left <= 0 ? 0 : (int) Math.ceil(left / 60000.0);
    }

    /** Sets (or replaces) the timer; 0 cancels it. Needs the helper bound, which the Palette checks first. */
    static void set(int minutes) {
        cancel();
        if (minutes <= 0) return;
        endsAt = SystemClock.elapsedRealtime() + minutes * 60 * 1000L;
        handler.postDelayed(warn, Math.max(0, minutes * 60 * 1000L - WARN_MS));
    }

    static void cancel() {
        endsAt = 0;
        handler.removeCallbacks(warn);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(sleep);
        hide();
    }

    /** OK while the last-minute line is up: 15 more minutes. True when the key was taken. */
    static boolean handleKey(KeyEvent event) {
        if (overlay == null) return false;
        if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_CENTER && event.getKeyCode() != KeyEvent.KEYCODE_ENTER) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) set(15);
        return true;
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (countdown == null) return;
            long left = Math.max(0, endsAt - SystemClock.elapsedRealtime());
            countdown.setText(String.format("%d:%02d", left / 60000, (left / 1000) % 60));
            handler.postDelayed(this, 1000);
        }
    };

    private static final Runnable sleep = () -> {
        HomeKeyService s = HomeKeyService.current();
        hide();
        endsAt = 0;
        if (s != null && Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
    };

    private static final Runnable warn = () -> {
        HomeKeyService s = HomeKeyService.current();
        if (s == null) { cancel(); return; }
        show(s);
        handler.post(tick);
        handler.postDelayed(sleep, WARN_MS);
    };

    private static void show(AccessibilityService s) {
        if (overlay != null) return;
        float d = s.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(s);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(12 * d);
        box.setPadding(pad + pad / 3, pad, pad + pad / 3, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0A0B0E);
        bg.setStroke(Math.round(d), 0xFF22252B);
        bg.setCornerRadius(10 * d);
        box.setBackground(bg);

        TextView label = new TextView(s);
        label.setText("Sleeping in ");
        label.setTextColor(0xFFF2F3F5);
        label.setTextSize(18);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(label);

        countdown = new TextView(s);
        countdown.setTextColor(0xFFF5B000);
        countdown.setTextSize(18);
        countdown.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(countdown);

        TextView more = new TextView(s);
        more.setText("OK · 15 more minutes");
        more.setTextColor(0xFFC9CDD2);
        more.setTextSize(14);
        int mp = Math.round(6 * d);
        more.setPadding(pad, mp, pad, mp);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFF1C2026);
        pill.setStroke(Math.round(1.5f * d), Color.WHITE);
        pill.setCornerRadius(8 * d);
        more.setBackground(pill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(pad + pad / 2);
        box.addView(more, lp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.BOTTOM | Gravity.START;
        wlp.x = Math.round(48 * d / 1.5f);   // the TV-safe corner at this density
        wlp.y = Math.round(27 * d / 1.5f);
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.addView(box, wlp);
            overlay = box;
        } catch (RuntimeException e) {
            overlay = null;
            countdown = null;
        }
    }

    private static void hide() {
        handler.removeCallbacks(tick);
        HomeKeyService s = HomeKeyService.current();
        if (overlay != null && s != null) {
            WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
            try { wm.removeView(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null;
        countdown = null;
    }
}
