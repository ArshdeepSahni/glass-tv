package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Glass II chord compass: hold Home anywhere and four actions sit on the D-pad's four directions. The app behind
 * keeps playing (translucent, own task). Up: inputs. Right: resume the app before this one. Down: ask. Left (or
 * OK): everything. Late at night Left is "sleep in 30" instead, the thing you want at that hour. Back closes.
 */
public final class ChordActivity extends Activity {
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
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private String resumeTarget() {
        return Glass.selfInFront() ? Glass.front() : Glass.previous();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = getResources().getDisplayMetrics().widthPixels / 1280f;
        Glass.Palette p = Glass.palette(this);
        FrameLayout root = new FrameLayout(this);
        // the picture stays, but well back: a deep veil, darkest at the centre where the words are
        root.setBackgroundColor(0xC708090B);
        View vignette = new View(this);
        GradientDrawable v = new GradientDrawable();
        v.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        v.setColors(new int[]{0xB308090B, 0x0008090B});
        v.setGradientRadius(px(520));
        vignette.setBackground(v);
        root.addView(vignette, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // centre: the Home mark, a white disc with a faint ring (board: 80 px at 600,320; ring 12 px)
        View ring = new View(this);
        GradientDrawable rd = new GradientDrawable();
        rd.setShape(GradientDrawable.OVAL);
        rd.setColor(0x12F4EFE9);
        ring.setBackground(rd);
        root.addView(ring, at(588, 308, 104, 104));
        ImageView home = new ImageView(this);
        home.setImageResource(R.drawable.ic_home_glass);
        home.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(p.ink);
        home.setBackground(disc);
        root.addView(home, at(600, 320, 80, 80));

        String device = null;
        GlassInputs.Input on = GlassInputs.deviceOn(this);
        if (on != null) device = on.title + " is on";
        String resume = resumeTarget();
        CharSequence resumeLabel = resume == null ? null : Glass.label(this, resume);
        boolean night = Glass.lateNight();
        int line = 0xFF4A443F;

        // Up (board: column 440..840, top 128): title, sub, then a 64 px line fading down toward the disc
        LinearLayout up = column(Gravity.CENTER_HORIZONTAL);
        up.addView(title("Inputs", p.ink));
        up.addView(sub(device != null ? device : "HDMI · Live TV", 0xFF9A928A), gap(6));
        up.addView(spaced(rule(1, 64, new int[]{0x009A928A, line}, GradientDrawable.Orientation.TOP_BOTTOM), 8, 0));
        root.addView(up, at(340, 112, 600, -2));

        // Right (board: from x 708, top 322): a 64 px line, then the words
        LinearLayout right = row();
        right.addView(rule(64, 1, new int[]{line, 0x009A928A}, GradientDrawable.Orientation.LEFT_RIGHT));
        LinearLayout rw = column(Gravity.START);
        rw.addView(title("Resume", p.ink));
        rw.addView(sub(resumeLabel != null ? resumeLabel.toString() : "nothing yet", 0xFF9A928A), gap(4));
        right.addView(rw, side(18));
        root.addView(right, at(708, 316, 520, -2));

        // Down (board: column 440..840, top 420): an accent line, then Ask
        LinearLayout down = column(Gravity.CENTER_HORIZONTAL);
        down.addView(rule(1, 64, new int[]{0xFFFF8A4C, 0x00FF8A4C}, GradientDrawable.Orientation.TOP_BOTTOM));
        down.addView(title("Ask", 0xFFFF8A4C), gap(8));
        down.addView(sub("release to listen", 0xFFC9A27F), gap(6));
        root.addView(down, at(340, 420, 600, -2));

        // Left (board: right edge at x 572, top 322): the words, then a line toward the disc
        LinearLayout left = row();
        left.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout lw = column(Gravity.END);
        lw.addView(title(night ? "Sleep in 30" : "Everything", p.ink));
        lw.addView(sub(night ? "OK for everything" : "sleep · settings · apps", 0xFF9A928A), gap(4));
        left.addView(lw);
        left.addView(spaced(rule(64, 1, new int[]{0x009A928A, line}, GradientDrawable.Orientation.LEFT_RIGHT), 0, 18));
        root.addView(left, at(52, 316, 520, -2));
        setContentView(root);

        // the arms open out from the disc: 160 ms, nothing more
        View[] arms = {up, right, down, left};
        float[][] from = {{0, 16}, {-16, 0}, {0, -16}, {16, 0}};
        for (int i = 0; i < arms.length; i++) {
            arms[i].setAlpha(0f);
            arms[i].setTranslationX(px(from[i][0]));
            arms[i].setTranslationY(px(from[i][1]));
            arms[i].animate().alpha(1f).translationX(0).translationY(0).setDuration(160).setStartDelay(20L * i).start();
        }
    }

    /** Absolute placement in board pixels (1280 x 720), scaled to the screen; -2 = wrap content. */
    private FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(w),
                h < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(h));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private LinearLayout column(int gravity) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(gravity);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView title(String t, int color) {
        TextView v = text(44, color, Glass.serif(this));
        // Instrument Serif draws past its own line box: keep the font padding so nothing is clipped
        v.setIncludeFontPadding(true);
        v.setText(t);
        return v;
    }

    private TextView sub(String t, int color) {
        TextView v = text(16, color, Glass.sans(this));
        v.setText(t);
        return v;
    }

    private View rule(int w, int h, int[] colors, GradientDrawable.Orientation o) {
        View r = new View(this);
        r.setBackground(new GradientDrawable(o, colors));
        r.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, px(w)), Math.max(1, px(h))));
        return r;
    }

    private View spaced(View v, int top, int left) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.topMargin = px(top);
        lp.leftMargin = px(left);
        return v;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(top);
        return lp;
    }

    private LinearLayout.LayoutParams side(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(left);
        return lp;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                start(new Intent(this, GlassInputsActivity.class));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                String target = resumeTarget();
                if (target == null || !Glass.open(this, target)) Toast.makeText(this, "Nothing to resume yet", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN:
                ask(this);
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (Glass.lateNight() && HomeKeyService.current() != null) {
                    SleepTimer.set(30);
                    Toast.makeText(this, "Sleeping in 30 minutes", Toast.LENGTH_SHORT).show();
                    finish();
                    return true;
                }
                start(new Intent(this, PaletteActivity.class));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                start(new Intent(this, PaletteActivity.class));
                return true;
            case KeyEvent.KEYCODE_HOME:
                finish();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** Build 2: the system assistant. Build 3 replaces this with Glass Assistant when it is installed. */
    static void ask(Context ctx) {
        Intent glass = ctx.getPackageManager().getLaunchIntentForPackage(GlassKeys.ASSISTANT);
        Intent open = glass != null ? glass : new Intent(Intent.ACTION_ASSIST);
        try {
            ctx.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(ctx, "No assistant available", Toast.LENGTH_SHORT).show();
        }
    }

    private void start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) { }
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
