package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The menu bar. No band behind it: words and glyphs sit straight on the wallpaper with a faint wash at the very
 * top. Only what does something: left, the Glass menu, what's in front, Inputs; right, Wi-Fi, Sound, Search,
 * Control Center, the assistant, the time. A quiet lozenge glides to whichever item has focus (24 px rhythm);
 * the item whose panel is open keeps its lozenge.
 */
final class Bar extends FrameLayout {
    interface Host {
        void barOpen(String key, View item);
    }

    private final Host host;
    private final Map<String, View> items = new HashMap<>();
    private final TextView context, time, volume;
    private final ImageView wifi, volumeGlyph;
    private final Glide glide;
    private String open;

    Bar(Context c, Host host) {
        super(c);
        this.host = host;
        Ui.Tone t = Ui.t;
        final float IH = Ui.boxf(26, 15);   // an item's height: its lozenge grows with the words
        View wash = new View(c);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                t.night ? new int[]{Ui.a(0x000000, .30f), 0} : new int[]{Ui.a(0xFFFFFF, .24f), 0x00FFFFFF}));
        addView(wash, Ui.at(0, 0, 1280, 52));
        glide = new Glide(c, t.lozenge, 0, 8, 0);
        addView(glide, Ui.fill());

        // left: the mark's lozenge starts 8 px in, so the mark itself sits on the 16 px edge
        LinearLayout left = Ui.row(c);
        left.addView(item("mark", "mark", 18, null), Ui.ll(-1, IH));
        context = shadowed(Ui.text(c, 15, t.ink, 600));
        context.setText("Home");
        left.addView(context, Ui.ll(-1, -1, 14, 0));
        left.addView(item("inputs", null, 0, "Inputs"), Ui.ll(-1, IH, 14, 0));
        addView(left, Ui.at(Ui.EDGE - 8, 4, -1, IH));

        // right: lozenges 8 apart (glyph to glyph 24), then the time 16 further
        LinearLayout right = Ui.row(c);
        right.addView(item("wifi", "wifi", 16, null), Ui.ll(-1, IH));
        View vol = item("volume", "volume", 16, "14");
        right.addView(vol, Ui.ll(-1, IH, 8, 0));
        right.addView(item("search", "search", 16, null), Ui.ll(-1, IH, 8, 0));
        right.addView(item("controls", "controls", 16, null), Ui.ll(-1, IH, 8, 0));
        right.addView(item("assist", "assist", 16, null), Ui.ll(-1, IH, 8, 0));
        time = shadowed(Ui.text(c, 15, t.ink, 500));
        time.setLetterSpacing(-0.005f);
        time.setFontFeatureSettings("tnum");
        right.addView(time, Ui.ll(-1, -1, 16, 0));
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, Ui.px(IH));
        rl.gravity = Gravity.TOP | Gravity.END;
        rl.topMargin = Ui.px(4);
        rl.rightMargin = Ui.px(Ui.EDGE);
        addView(right, rl);

        wifi = (ImageView) ((Holder) items.get("wifi").getTag()).glyph;
        volumeGlyph = (ImageView) ((Holder) vol.getTag()).glyph;
        volume = ((Holder) vol.getTag()).label;
        refresh();
    }

    private static final class Holder {
        View glyph;
        TextView label;
    }

    private TextView shadowed(TextView v) {
        v.setShadowLayer(Ui.pxf(8), 0, Ui.pxf(1), Ui.t.night ? Ui.a(0x000000, .35f) : Ui.a(0xFFFFFF, .35f));
        return v;
    }

    private View item(String key, String glyph, int size, String label) {
        Context c = getContext();
        LinearLayout l = Ui.row(c);
        l.setPadding(Ui.px(8), 0, Ui.px(8), 0);
        GradientDrawable openTint = Ui.fillRound(Ui.t.lozenge, 8);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_selected, -android.R.attr.state_focused}, openTint);
        l.setBackground(bg);
        l.setFocusable(true);
        l.setClickable(true);
        Holder h = new Holder();
        if (glyph != null) {
            ImageView g = new ImageView(c);
            if ("mark".equals(glyph)) {
                // the mark is the glass itself, small (ray traced: brand/tv_assets.py), not a drawn glyph
                g.setImageResource(R.drawable.glass_mark);
                g.setScaleType(ImageView.ScaleType.FIT_CENTER);
            } else {
                g.setImageDrawable(Glyph.of(glyph, Ui.t.ink));
            }
            l.addView(g, Ui.ll(size, size));
            h.glyph = g;
        }
        if (label != null) {
            TextView t = shadowed(Ui.text(c, 15, Ui.t.ink, 400));
            t.setLetterSpacing(-0.005f);
            t.setText(label);
            l.addView(t, Ui.ll(-1, -1, glyph != null ? 6 : 0, 0));
            h.label = t;
        }
        l.setTag(h);
        l.setOnClickListener(v -> host.barOpen(key, v));
        l.setOnFocusChangeListener((v, has) -> {
            if (has) {
                glide.to(v);
                Sound.tick();
            } else {
                v.post(() -> {
                    if (findFocus() == null) glide.hide();
                });
            }
        });
        items.put(key, l);
        return l;
    }

    View item(String key) {
        return items.get(key);
    }

    /** The item nearest a point across the screen (design px), for ↑ from below. */
    View nearest(float x) {
        View best = null;
        float d = Float.MAX_VALUE;
        int[] loc = new int[2];
        for (View v : items.values()) {
            if (v.getVisibility() != VISIBLE || !v.isFocusable() || v.getWidth() == 0) continue;
            v.getLocationOnScreen(loc);
            float cx = (loc[0] + v.getWidth() / 2f) / Ui.s;
            if (Math.abs(cx - x) < d) {
                d = Math.abs(cx - x);
                best = v;
            }
        }
        return best;
    }

    void setContext(String s) {
        context.setText(s);
    }

    /** The open item keeps its lozenge while its panel is up. */
    void setOpen(String key) {
        if (open != null && items.get(open) != null) items.get(open).setSelected(false);
        open = key;
        if (key != null && items.get(key) != null) items.get(key).setSelected(true);
    }

    void refresh() {
        Context c = getContext();
        Date now = new Date();
        String day = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now);
        String clock = DateFormat.is24HourFormat(c) ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                : new SimpleDateFormat("h:mm a", Locale.getDefault()).format(now);
        time.setText(day + "  " + clock);
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = v == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC);
            volume.setText(String.valueOf(v));
            volumeGlyph.setImageDrawable(Glyph.of(muted ? "mute" : "volume", Ui.t.ink));
        }
        boolean online = Glass.connected(c);
        wifi.setImageDrawable(Glyph.of(online ? "wifi" : "wifi_off", Ui.t.ink));
        wifi.setAlpha(online ? 1f : 0.55f);
    }
}
