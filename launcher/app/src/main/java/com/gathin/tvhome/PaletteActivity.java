package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Glass II "Everything": hold Home, then Left (or OK). A panel slides in from the left over whatever is playing:
 * sleep chips, quick switches (mute, guest curtain, simple size), and the ways out (Settings, Wi-Fi, All apps,
 * the Original home). Typing a letter turns the panel into a search over every app.
 */
public final class PaletteActivity extends Activity {
    private static final int[] SLEEP = {15, 30, 60, 90};
    private float s;
    private Glass.Palette p;
    private LinearLayout panel;
    private final StringBuilder query = new StringBuilder();

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
        t.setSingleLine(true);
        return t;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1280f;
        p = Glass.palette(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x5508090B);
        root.setOnClickListener(v -> finish());
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF0C0B0A);
        panel.setPadding(px(64), px(48), px(48), px(40));
        panel.setElevation(px(24));
        root.addView(panel, new FrameLayout.LayoutParams(px(560), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        render();
    }

    private void render() {
        panel.removeAllViews();
        TextView search = text(20, query.length() > 0 ? p.ink : 0xFF6F675F, Glass.sans(this));
        search.setText(query.length() > 0 ? "“" + query + "”" : "Type a letter…");
        search.setPadding(0, 0, 0, px(14));
        panel.addView(search);
        View rule = new View(this);
        rule.setBackgroundColor(0xFF26221E);
        panel.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        if (query.length() > 0) { renderSearch(); return; }

        head("Sleep", 26);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        boolean helper = HomeKeyService.current() != null;
        int left = SleepTimer.minutesLeft();
        View firstChip = null;
        if (left > 0) {
            View c = chip("Cancel · " + left + " min left", true, v -> { SleepTimer.cancel(); finish(); });
            chips.addView(c);
            firstChip = c;
        } else {
            for (int m : SLEEP) {
                View c = chip(m == 30 ? "30 min" : String.valueOf(m), m == 30, v -> {
                    if (!helper) { Toast.makeText(this, "Needs the Home key helper", Toast.LENGTH_SHORT).show(); return; }
                    SleepTimer.set(m);
                    Toast.makeText(this, "Sleeping in " + m + " minutes", Toast.LENGTH_SHORT).show();
                    finish();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (chips.getChildCount() > 0) lp.leftMargin = px(10);
                chips.addView(c, lp);
                if (m == 30) firstChip = c;
            }
        }
        panel.addView(chips);

        head("Quick", 26);
        AudioManager audio = getSystemService(AudioManager.class);
        boolean muted = audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC);
        row("Mute", muted ? "on" : "off", v -> {
            if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
            finish();
        });
        boolean guest = Glass.guest(this);
        row("Guest curtain", guest ? "on" : "off", v -> {
            Glass.prefs(this).edit().putBoolean(Glass.GUEST, !guest).apply();
            home();
        });
        boolean simple = Glass.simple(this);
        row("Simple size", simple ? "on" : "off", v -> { Glass.setSimple(this, !simple); home(); });

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout ways = new LinearLayout(this);
        ways.setOrientation(LinearLayout.HORIZONTAL);
        ways.addView(way("Settings", v -> start(new Intent(this, GlassSettingsActivity.class))));
        ways.addView(way("Wi-Fi", v -> start(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS))));
        ways.addView(way("All apps", v -> start(new Intent(this, GlassAllAppsActivity.class))));
        boolean original = Glass.original(this);
        if (Glass.stockAvailable(this)) ways.addView(way(original ? "Glass home" : "Original home", v -> {
            Glass.prefs(this).edit().putString(Glass.MODE, original ? "glass" : "original").apply();
            home();
        }));
        panel.addView(ways);
        if (firstChip != null) firstChip.post(firstChip::requestFocus);
    }

    private void renderSearch() {
        String q = query.toString().toLowerCase(Locale.ROOT);
        List<String[]> hits = new ArrayList<>();
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            CharSequence l = Glass.label(this, pkg);
            if (l != null && l.toString().toLowerCase(Locale.ROOT).contains(q)) hits.add(new String[]{l.toString(), pkg});
        }
        Collections.sort(hits, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        View first = null;
        for (String[] h : hits) {
            View r = row(h[0], "", v -> { Glass.open(this, h[1]); finish(); });
            if (first == null) first = r;
        }
        if (hits.isEmpty()) {
            TextView none = text(18, 0xFF6F675F, Glass.sans(this));
            none.setText("No app matches · Back clears");
            none.setPadding(0, px(20), 0, 0);
            panel.addView(none);
        }
        if (first != null) first.post(first::requestFocus);
    }

    private void head(String label, int top) {
        TextView h = text(12, 0xFF6F675F, Glass.sans(this));
        h.setLetterSpacing(0.14f);
        h.setText(label.toUpperCase(Locale.ROOT));
        h.setPadding(0, px(top), 0, px(10));
        panel.addView(h);
    }

    private View chip(String label, boolean strong, View.OnClickListener click) {
        TextView c = text(18, strong ? p.ink : 0xFF9A928A, strong ? Glass.sansMedium(this) : Glass.sans(this));
        c.setText(label);
        c.setPadding(px(16), px(10), px(16), px(10));
        c.setFocusable(true);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(px(10));
        rest.setStroke(Math.max(1, px(1)), 0xFF26221E);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(10));
        on.setColor(0xFFF4EFE9);
        c.setBackground(rest);
        c.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : rest);
            c.setTextColor(has ? 0xFF0C0B0A : strong ? p.ink : 0xFF9A928A);
        });
        c.setOnClickListener(click);
        return c;
    }

    private View row(String label, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(px(14), px(10), px(14), px(10));
        TextView l = text(22, 0xFFC9C0B6, Glass.sans(this));
        l.setText(label);
        TextView v = text(16, 0xFF6F675F, Glass.sans(this));
        v.setText(value);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(v);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(10));
        on.setColor(0xFF1A1715);
        on.setStroke(Math.max(1, Math.round(1.5f * s)), p.ink);
        r.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? on : null);
            l.setTextColor(has ? p.ink : 0xFFC9C0B6);
        });
        r.setOnClickListener(click);
        panel.addView(r);
        return r;
    }

    private View way(String label, View.OnClickListener click) {
        TextView w = text(18, 0xFF9A928A, Glass.sans(this));
        w.setText(label);
        w.setFocusable(true);
        w.setPadding(px(8), px(8), px(8), px(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(14);
        w.setLayoutParams(lp);
        w.setOnFocusChangeListener((v, has) -> w.setTextColor(has ? p.ink : 0xFF9A928A));
        w.setOnClickListener(click);
        return w;
    }

    private void home() {
        start(new Intent(this, GlassHomeActivity.class));
    }

    private void start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && query.length() > 0) { query.setLength(0); render(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DEL && query.length() > 0) { query.setLength(query.length() - 1); render(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && getCurrentFocus() != null
                && !(getCurrentFocus().getParent() instanceof LinearLayout
                && ((LinearLayout) getCurrentFocus().getParent()).getOrientation() == LinearLayout.HORIZONTAL)) {
            finish();   // Right from the panel's edge closes it, the way it came
            return true;
        }
        int ch = event.getUnicodeChar();
        if (ch != 0 && Character.isLetterOrDigit(ch)) { query.append((char) ch); render(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
