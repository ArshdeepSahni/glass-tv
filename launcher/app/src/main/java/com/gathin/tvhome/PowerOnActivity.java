package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Glass II power-on chooser: Glass or Original, with the last choice preselected and a 5-second countdown, so
 * doing nothing is a choice too. Three identical picks in a row and it stops asking (Glass Settings > On power-on
 * brings it back). Original is the Android TV home as it shipped, kept on the read-only system partition, so
 * having both costs no storage; switching is a preference, not a reinstall.
 */
public final class PowerOnActivity extends Activity {
    private static final int SECONDS = 5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float s;
    private int left = SECONDS;
    private TextView countdown;
    private View glassCard, originalCard;
    private boolean lastWasOriginal;

    /** Should power-on ask, given the setting and the streak? */
    static boolean shouldAsk(Context ctx) {
        SharedPreferences prefs = Glass.prefs(ctx);
        String on = prefs.getString(Glass.ON_POWER, "ask");
        return "ask".equals(on) && prefs.getInt(Glass.STREAK, 0) < 3 && Glass.stockAvailable(ctx);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private int px(float v) { return Math.round(v * s); }

    private TextView text(float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1280f;
        lastWasOriginal = Glass.original(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x21FF8C46, 0x00000000});
        g.setGradientRadius(px(450));
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(px(900), px(900), Gravity.CENTER));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView hello = text(64, 0xFFF4EFE9, Glass.serif(this));
        hello.setText(greeting());
        col.addView(hello);
        countdown = text(18, 0xFF8F867D, Glass.sans(this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(16);
        col.addView(countdown, clp);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        glassCard = card("Glass", null, false);
        originalCard = card("Original", "Android TV, as it shipped", true);
        cards.addView(glassCard, new LinearLayout.LayoutParams(px(330), px(180)));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(px(330), px(180));
        olp.leftMargin = px(24);
        cards.addView(originalCard, olp);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsLp.topMargin = px(58);
        col.addView(cards, cardsLp);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        (lastWasOriginal ? originalCard : glassCard).requestFocus();
        updateCountdown();
        handler.postDelayed(tick, 1000);
    }

    private static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "Good morning.";
        if (h >= 12 && h < 17) return "Good afternoon.";
        if (h >= 17 && h < 23) return "Good evening.";
        return "Hello.";
    }

    private View card(String title, String sub, boolean original) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setPadding(px(26), px(26), px(26), px(26));
        TextView t = text(44, original ? 0xFFA79E94 : 0xFFF4EFE9, Glass.serif(this));
        t.setGravity(Gravity.START);
        t.setText(title);
        c.addView(t);
        c.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        if (sub != null) {
            TextView u = text(15, 0xFF6F675F, Glass.sans(this));
            u.setGravity(Gravity.START);
            u.setText(sub);
            c.addView(u);
        }
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(px(18));
        rest.setColor(0xFF0B0A09);
        rest.setStroke(Math.max(1, px(1)), 0xFF2A2420);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(18));
        on.setColor(0xFF14110F);
        on.setStroke(px(2), 0xFFF4EFE9);
        c.setBackground(rest);
        c.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : rest);
            t.setTextColor(has ? 0xFFF4EFE9 : 0xFFA79E94);
            if (has) updateCountdown();
        });
        c.setOnClickListener(v -> choose(original));
        return c;
    }

    private boolean focusedOriginal() {
        return originalCard != null && originalCard.hasFocus();
    }

    private void updateCountdown() {
        if (countdown != null) countdown.setText("Opening " + (focusedOriginal() ? "Original" : "Glass") + " in " + left);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            left--;
            if (left <= 0) { choose(focusedOriginal()); return; }
            updateCountdown();
            handler.postDelayed(this, 1000);
        }
    };

    private void choose(boolean original) {
        handler.removeCallbacks(tick);
        SharedPreferences prefs = Glass.prefs(this);
        int streak = original == lastWasOriginal ? prefs.getInt(Glass.STREAK, 0) + 1 : 1;
        prefs.edit().putString(Glass.MODE, original ? "original" : "glass").putInt(Glass.STREAK, streak).apply();
        // Switched off while watching an HDMI device or Live TV: Glass goes straight back to it, not to Home.
        String liveTv = Config.load(this).liveTvPackage;
        if (!original && liveTv != null && liveTv.equals(Glass.front()) && Glass.open(this, liveTv)) {
            finish();
            return;
        }
        Intent home = original
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(Glass.STOCK_HOME)
                : new Intent(this, GlassHomeActivity.class);
        try { startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException e) {
            startActivity(new Intent(this, GlassHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // any key stops the countdown: the person is choosing
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
            handler.removeCallbacks(tick);
            if (countdown != null) countdown.setText("OK to open");
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) { choose(focusedOriginal()); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
