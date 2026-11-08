package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Glass II Inputs: a row of cards that drops from the top of the screen (Up is where the hand went), one per
 * input, named the way the owner knows it. A switched-on device is lit and focused first; an empty port is drawn
 * dashed but still reachable, so a device plugged in a minute ago is one press away. Translucent over the picture.
 */
public final class GlassInputsActivity extends Activity {
    private float s;

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
        Glass.Palette p = Glass.palette(this);
        FrameLayout root = new FrameLayout(this);
        View shade = new View(this);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{p.ground, p.ground, 0x000A0B0E});
        shade.setBackground(fade);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(330)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<GlassInputs.Input> inputs = GlassInputs.list(this);
        // Live TV (antenna / cable) as the last real card
        String liveTv = Config.load(this).liveTvPackage;
        View first = null, lit = null;
        for (GlassInputs.Input in : inputs) {
            View card = card(p, in.port, in.on ? "● on" : in.empty ? "nothing connected" : "standby",
                    in.title, in.on, in.empty, v -> { GlassInputs.open(this, in.id); finish(); });
            if (first == null) first = card;
            if (in.on && lit == null) lit = card;
            row.addView(card, cardLp(row.getChildCount()));
        }
        if (liveTv != null && !liveTv.isEmpty() && (getPackageManager().getLeanbackLaunchIntentForPackage(liveTv) != null
                || getPackageManager().getLaunchIntentForPackage(liveTv) != null)) {
            View card = card(p, "Antenna", "", "Live TV", false, false, v -> { Glass.open(this, liveTv); finish(); });
            if (first == null) first = card;
            row.addView(card, cardLp(row.getChildCount()));
        }
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200));
        rlp.leftMargin = px(64);
        rlp.rightMargin = px(64);
        rlp.topMargin = px(52);
        root.addView(row, rlp);
        setContentView(root);
        View target = lit != null ? lit : first;
        if (target != null) target.post(target::requestFocus);
    }

    private LinearLayout.LayoutParams cardLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (index > 0) lp.leftMargin = px(20);
        return lp;
    }

    private View card(Glass.Palette p, String port, String state, String title, boolean on, boolean empty, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setPadding(px(24), px(24), px(24), px(24));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView portV = text(14, empty ? 0xFF4D4640 : 0xFF9A928A, Glass.sans(this));
        portV.setText(port);
        TextView stateV = text(14, on ? 0xFF3DDC84 : 0xFF6F675F, Glass.sans(this));
        stateV.setText(state);
        stateV.setGravity(Gravity.END);
        top.addView(portV, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(stateV);
        c.addView(top);
        View spacer = new View(this);
        c.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView t = text(40, empty ? 0xFF4D4640 : on ? p.ink : 0xFFC9C0B6, Glass.serif(this));
        t.setText(empty ? "Nothing" : title);
        c.addView(t);

        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(px(16));
        rest.setColor(empty ? 0xFF0B0A09 : 0xFF0F0E0C);
        if (empty) rest.setStroke(Math.max(1, px(1)), 0xFF26221E, px(6), px(4));
        else rest.setStroke(Math.max(1, px(1)), 0xFF26221E);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(16));
        focus.setColor(0xFF15130F);
        focus.setStroke(px(2), p.ink);
        c.setBackground(rest);
        c.setOnFocusChangeListener((v, has) -> v.setBackground(has ? focus : rest));
        c.setOnClickListener(click);
        return c;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_HOME) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
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
