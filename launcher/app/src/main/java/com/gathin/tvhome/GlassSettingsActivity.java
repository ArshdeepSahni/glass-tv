package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Glass II Settings: everything changed more than once a year, on one page. Left / Right changes a value in place;
 * nothing opens another screen. Text size and idle-off are Android's own settings (they apply to every app) and
 * need the WRITE_SETTINGS grant the installer gives; Motion needs WRITE_SECURE_SETTINGS, also granted over ADB.
 * Without a grant the row says so instead of failing silently.
 */
public final class GlassSettingsActivity extends Activity {
    private float s;
    private Glass.Palette p;
    private TextView previewBig, previewSmall;

    private interface Choice {
        String[] labels();
        int current();
        /** Applies option i; returns false when the TV refused it. */
        boolean apply(int i);
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
        t.setSingleLine(true);
        return t;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1280f;
        p = Glass.palette(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(p.ground);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(64, p.ink, Glass.serif(this));
        title.setText("Settings");
        left.addView(title);
        FrameLayout preview = new FrameLayout(this);
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(px(16));
        pb.setColor(0xFF14110F);
        pb.setStroke(Math.max(1, px(1)), 0xFF26221E);
        preview.setBackground(pb);
        previewBig = text(34, p.ink, Glass.sansMedium(this));
        previewBig.setText("Netflix");
        previewSmall = text(17, p.dim, Glass.sans(this));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        bl.leftMargin = px(24);
        bl.bottomMargin = px(56);
        preview.addView(previewBig, bl);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        sl.leftMargin = px(24);
        sl.bottomMargin = px(26);
        preview.addView(previewSmall, sl);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(240));
        plp.topMargin = px(24);
        left.addView(preview, plp);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(px(400), ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = px(64);
        llp.topMargin = px(48);
        root.addView(left, llp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = px(528);
        rlp.rightMargin = px(64);
        rlp.topMargin = px(58);
        rlp.bottomMargin = px(32);
        root.addView(scroll, rlp);
        setContentView(root);

        ContentResolver cr = getContentResolver();
        SharedPreferences g = Glass.prefs(this);

        head(list, "Looks", 0);
        View first = row(list, "Text size", new Choice() {
            final float[] v = {0.9f, 1.0f, 1.15f, 1.3f};
            public String[] labels() { return new String[]{"Small", "Default", "Large", "Largest"}; }
            public int current() { return nearest(v, Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1f)); }
            public boolean apply(int i) {
                try { return Settings.System.putFloat(cr, Settings.System.FONT_SCALE, v[i]); }
                catch (RuntimeException e) { return false; }
            }
        }, "every app");
        row(list, "Light follows the day", new Choice() {
            public String[] labels() { return new String[]{"Off", "On"}; }
            public int current() { return g.getBoolean(Glass.LIGHT_FOLLOWS_DAY, true) ? 1 : 0; }
            public boolean apply(int i) { g.edit().putBoolean(Glass.LIGHT_FOLLOWS_DAY, i == 1).apply(); return true; }
        }, "morning · afternoon · evening · night");
        row(list, "Motion", new Choice() {
            final float[] v = {0f, 0.5f, 1f};
            public String[] labels() { return new String[]{"Off", "Quick", "Standard"}; }
            public int current() { return nearest(v, Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)); }
            public boolean apply(int i) {
                try {
                    Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, v[i]);
                    Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, v[i]);
                    Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, v[i]);
                    return true;
                } catch (RuntimeException e) { return false; }
            }
        }, "how fast screens open");

        head(list, "Sound", 16);
        row(list, "Night volume ceiling", new Choice() {
            final int[] v = {0, 10, 12, 15};
            public String[] labels() { return new String[]{"Off", "10 after 11 pm", "12 after 11 pm", "15 after 11 pm"}; }
            public int current() { int c = g.getInt(Glass.NIGHT_CEILING, 0); for (int i = 0; i < v.length; i++) if (v[i] == c) return i; return 0; }
            public boolean apply(int i) { g.edit().putInt(Glass.NIGHT_CEILING, v[i]).apply(); return true; }
        }, "held by the remote helper");

        head(list, "Power", 16);
        row(list, "Turn off when idle", new Choice() {
            final int[] v = {60 * 60 * 1000, 2 * 60 * 60 * 1000, 4 * 60 * 60 * 1000, Integer.MAX_VALUE};
            public String[] labels() { return new String[]{"After 1 h", "After 2 h", "After 4 h", "Never"}; }
            public int current() {
                int c = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 4 * 60 * 60 * 1000);
                int best = 2; for (int i = 0; i < v.length; i++) if (v[i] == c) best = i; return best;
            }
            public boolean apply(int i) {
                try { return Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, v[i]); }
                catch (RuntimeException e) { return false; }
            }
        }, "with no remote press");
        if (Glass.stockAvailable(this)) row(list, "On power-on, open", new Choice() {
            final String[] v = {"ask", "glass", "original"};
            public String[] labels() { return new String[]{"Ask", "Glass", "Original"}; }
            public int current() { String c = g.getString(Glass.ON_POWER, "ask"); for (int i = 0; i < v.length; i++) if (v[i].equals(c)) return i; return 0; }
            public boolean apply(int i) {
                g.edit().putString(Glass.ON_POWER, v[i]).putInt(Glass.STREAK, 0)
                        .putString(Glass.MODE, i == 2 ? "original" : i == 1 ? "glass" : g.getString(Glass.MODE, "glass")).apply();
                return true;
            }
        }, "Glass or the original home");

        LinearLayout all = rowShell(list, "All TV settings", "→");
        all.setOnClickListener(v -> {
            try { startActivity(SystemSettings.intent(this, Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException ignored) { }
        });
        first.post(first::requestFocus);
    }

    private static int nearest(float[] v, float x) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (Math.abs(v[i] - x) < Math.abs(v[best] - x)) best = i;
        return best;
    }

    private void head(LinearLayout list, String label, int top) {
        TextView h = text(12, p.faint, Glass.sans(this));
        h.setLetterSpacing(0.14f);
        h.setText(label.toUpperCase(Locale.ROOT));
        h.setPadding(px(18), px(top), 0, px(6));
        list.addView(h);
    }

    private LinearLayout rowShell(LinearLayout list, String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(px(18), px(12), px(18), px(12));
        TextView l = text(20, p.ink, Glass.sans(this));
        l.setText(label);
        TextView v = text(18, p.dim, Glass.sans(this));
        v.setText(value);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(v);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(px(12));
        on.setColor(0xFF1A1715);
        on.setStroke(Math.max(1, Math.round(1.5f * s)), p.ink);
        r.setOnFocusChangeListener((view, has) -> view.setBackground(has ? on : null));
        list.addView(r);
        return r;
    }

    private View row(LinearLayout list, String label, Choice c, String note) {
        String[] labels = c.labels();
        final int[] at = {c.current()};
        LinearLayout r = rowShell(list, label, labels[at[0]]);
        TextView value = (TextView) r.getChildAt(1);
        r.setOnFocusChangeListener((view, has) -> {
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(px(12));
            on.setColor(0xFF1A1715);
            on.setStroke(Math.max(1, Math.round(1.5f * s)), p.ink);
            view.setBackground(has ? on : null);
            value.setText(has ? "◀  " + labels[at[0]] + "  ▶" : labels[at[0]]);
            value.setTextColor(has ? p.ink : p.dim);
            if (has) preview(label, labels[at[0]], note);
        });
        r.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step == 0) return false;
            int next = at[0] + step;
            if (next < 0 || next >= labels.length) return true;
            if (!c.apply(next)) {
                Toast.makeText(this, "The TV refused this one — the installer grants it once over ADB", Toast.LENGTH_LONG).show();
                return true;
            }
            at[0] = next;
            value.setText("◀  " + labels[next] + "  ▶");
            preview(label, labels[next], note);
            return true;
        });
        return r;
    }

    private void preview(String label, String value, String note) {
        if ("Text size".equals(label)) {
            float scale = "Small".equals(value) ? 0.9f : "Large".equals(value) ? 1.15f : "Largest".equals(value) ? 1.3f : 1f;
            previewBig.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * s * scale);
            previewBig.setText("Netflix");
        } else {
            previewBig.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * s);
            previewBig.setText(value);
        }
        previewSmall.setText(label + " · " + note);
    }
}
