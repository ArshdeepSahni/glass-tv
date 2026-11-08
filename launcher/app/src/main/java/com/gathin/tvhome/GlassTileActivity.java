package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Glass's tile in a launcher's apps row. With Glass as the home it simply opens the desktop. With the TV's original
 * home in charge (Settings ▸ Glass & original home), it's the way back: "Back to Glass?", Switch or Stay.
 */
public final class GlassTileActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Glass.original(this)) {
            GlassKeys.start(this, new Intent(this, GlassHomeActivity.class));
            finish();
            return;
        }
        Ui.init(this);
        Sound.init(this);
        Ui.Tone saved = Ui.t;
        Ui.t = Ui.NIGHT;   // built in the dark look, without changing the desktop's (Ui.t is shared)
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050508);
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x33FF9A5C, 0x00000000});
        g.setGradientRadius(Ui.pxf(460));
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(Ui.px(920), Ui.px(920), Gravity.CENTER));
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.glass_mark);
        col.addView(mark, Ui.ll(88, 88));
        TextView title = Ui.text(this, 44, 0xFFF4F2EF, 400);
        title.setTypeface(Glass.serif(this));
        title.setText("Back to Glass?");
        title.setGravity(Gravity.CENTER);
        col.addView(title, Ui.ll(-1, -1, 0, 22));
        TextView sub = Ui.text(this, 16, Ui.a(0xF4F2EF, .62f), 400);
        sub.setText("Glass becomes the TV's home again. The original home stays on the TV, turned off.");
        sub.setGravity(Gravity.CENTER);
        sub.setMaxWidth(Ui.px(560));
        col.addView(sub, Ui.ll(-1, -1, 0, 12));
        LinearLayout buttons = Ui.row(this);
        TextView yes = button("Switch to Glass", true);
        TextView no = button("Stay here", false);
        buttons.addView(yes, Ui.ll(-1, Ui.boxf(48, 16)));
        buttons.addView(no, Ui.ll(-1, Ui.boxf(48, 16), 16, 0));
        col.addView(buttons, Ui.ll(-1, -1, 0, 36));
        root.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Ui.t = saved;
        setContentView(root);
        Ui.marquee(root);
        yes.setOnClickListener(v -> {
            Sound.pop();
            v.setEnabled(false);
            Homes.toGlass(this, (ok, why) -> {
                if (!ok) Toast.makeText(this, "Couldn't switch just now", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
        no.setOnClickListener(v -> finish());
        yes.requestFocus();
    }

    private TextView button(String label, boolean primary) {
        TextView b = Ui.line(this, 16, primary ? 0xFF0E0E10 : 0xFFF2F2F5, 600, label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.px(26), 0, Ui.px(26), 0);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = Ui.fillRound(primary ? 0xFFF2F2F5 : Ui.a(0xFFFFFF, .10f), 24);
        if (!primary) bg.setStroke(Math.max(1, Ui.px(1)), Ui.a(0xFFFFFF, .16f));
        b.setBackground(bg);
        Ui.focusRing(b, 24);
        Ui.lift(b, 1.06f, 8);
        b.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        return b;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
