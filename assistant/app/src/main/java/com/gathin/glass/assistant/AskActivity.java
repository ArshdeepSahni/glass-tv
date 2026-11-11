package com.gathin.glass.assistant;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Glass's assistant as a conversation. One frosted sheet down the right of the screen, so whatever is playing stays
 * in view: who you're talking to and what it can see at the top, the conversation in the middle (your words in the
 * selection ink on the right, Glass's as plain type on the glass, what it did as receipts, its questions back with
 * replies to pick, a pick to watch, follow-up questions), and at the bottom the row where you talk: the lens wakes
 * while it listens, your words run along the row, OK (or the assistant button again) sends. The conversation is
 * kept for the evening (Talk); New chat clears it.
 *
 * It pours from the bar's assistant item and folds back into it. Over the desktop it is the desktop's live glass;
 * over an app, dense dark frost.
 */
public final class AskActivity extends Activity {
    // design px: the sheet hangs down the right on the 16 px edge, 504 wide (the text column is 464)
    static final float CW = 504, CX = 1280 - 16 - CW, CR = 28, GX = 1116, HEAD = 69, ROW = 56, LIFT = 8;
    static final String DESK = "content://com.gathin.tvhome.desk";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Brain brain;
    private Talk talk;
    private boolean desk, closing, busy, listening;
    /** Asked without the TV's context (the installer's own test questions): nothing of the screen is sent. */
    private boolean bare;
    private float py, ph;

    private FrameLayout root, inner;
    private Pane pane;
    private TextView sees, newChat;
    private ScrollView scroll;
    private LinearLayout feed;
    private View thinking, nextView;
    private ValueAnimator dots;

    private FrameLayout row;
    private Lamp lamp;
    private TextView rowText, rowHint, keys;
    private EditText typed;
    private SpeechRecognizer speech;
    private String partial;
    private float level;
    private ValueAnimator breathe;
    private volatile CountDownLatch tvReady = new CountDownLatch(0);

    // ---- start ----------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle st) {
        super.onCreate(st);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> AssistEntry.check(app), "assist-entry").start();
        Ui.init(this);
        Sound.init(this);
        brain = new Brain(this);
        talk = Talk.load(this);
        bare = getIntent().getBooleanExtra("bare", false);
        if (getIntent().getBooleanExtra("fresh", false)) talk.clear(this);
        Bundle front = ask("front", null);
        if (front != null) Sound.setEnabled(this, front.getBoolean("sounds", true));
        desk = front != null && front.getBoolean("desk", false);
        boolean night = front != null ? front.getBoolean("night", Ui.night("auto")) : Ui.night("auto");
        Ui.tone(desk ? night : true);
        readContext(getIntent(), front);
        py = desk ? 42 : 24;
        ph = desk ? 612 - 8 - 42 : 720 - 48;
        build(front);
        for (Talk.Turn t : talk.turns) render(t, false, talk.turns.indexOf(t) == talk.turns.size() - 1);
        loadTv();
        if (desk) ask("assist", "open");
        appear();
        String q = getIntent().getStringExtra("q");
        if (q != null && !q.trim().isEmpty()) {
            ui.postDelayed(() -> send(q.trim(), null), Ui.ms(420));
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && SpeechRecognizer.isRecognitionAvailable(this)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            ui.postDelayed(this::listen, Ui.ms(380));
        }
    }

    /** Asks the desktop (DeskProvider) something; null when it can't be reached. */
    private Bundle ask(String method, String arg) {
        try {
            return getContentResolver().call(Uri.parse(DESK), method, arg, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void readContext(Intent i, Bundle front) {
        String w = i.getStringExtra("watching");
        String wt = i.getStringExtra("watching_title"), wa = i.getStringExtra("watching_app");
        if (w == null && front != null && front.getString("front") != null && !desk) {
            wt = front.getString("title");
            wa = front.getString("app");
        }
        brain.watchingTitle = wt;
        brain.watchingApp = wa;
    }

    /** What's on the TV: the desktop reads the screen once, now, while the app is still under the sheet. */
    private void loadTv() {
        if (bare) {
            tvReady = new CountDownLatch(0);
            return;
        }
        CountDownLatch l = new CountDownLatch(1);
        tvReady = l;
        new Thread(() -> {
            try {
                Bundle cx = ask("context", null);
                if (cx != null) {
                    brain.context(cx);
                    String s = cx.getString("sees");
                    if (s != null) ui.post(() -> {
                        if (sees != null) sees.setText("Sees " + s);
                    });
                }
            } finally {
                l.countDown();
            }
        }, "glass-tv").start();
    }

    // ---- the sheet ------------------------------------------------------------------------------------------

    private void build(Bundle front) {
        Ui.Tone t = Ui.t;
        root = new FrameLayout(this);
        pane = new Pane(this, true, CX, py, CW, ph, CR);
        pane.overApp(!desk);
        pane.bake(this);
        inner = new FrameLayout(this);

        // where you talk (first in the tree: when what had the remote goes away, the remote lands here)
        row = new FrameLayout(this);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnFocusChangeListener((v, has) -> {
            if (has) Sound.tick();
            paintRow();
        });
        row.setOnClickListener(v -> {
            if (listening) sendNow();
            else if (!busy) listen();
        });
        lamp = new Lamp(this, 30);
        row.addView(lamp, Ui.at(6, 6, 44, 44));
        rowText = Ui.text(this, 17, t.ink3, 500);
        rowText.setSingleLine(true);
        rowText.setEllipsize(TextUtils.TruncateAt.START);
        rowText.setGravity(Gravity.CENTER_VERTICAL);
        rowText.setText("Ask Glass…");
        row.addView(rowText, Ui.at(56, 0, CW - 24 - 56 - 160, ROW));
        typed = new EditText(this);
        typed.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(17) * Ui.fs);
        typed.setTypeface(Ui.face(this, 500));
        typed.setTextColor(t.ink);
        typed.setHintTextColor(t.ink3);
        typed.setHint("Type to Glass");
        typed.setBackground(null);
        typed.setPadding(0, 0, 0, 0);
        typed.setSingleLine(true);
        typed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        typed.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        typed.setVisibility(View.GONE);
        typed.setOnEditorActionListener((v, id, ev) -> {
            String q = typed.getText().toString().trim();
            hideTyping();
            if (!q.isEmpty()) send(q, null);
            return true;
        });
        row.addView(typed, Ui.at(56, 12, CW - 24 - 56 - 110, 32));
        rowHint = Ui.text(this, 13, t.ink3, 400);
        rowHint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        rowHint.setSingleLine(true);
        row.addView(rowHint, Ui.at(CW - 24 - 52 - 130, 0, 120, ROW));
        keys = new TextView(this);
        Glyph kg = Glyph.of("text", t.ink2, 2f);
        kg.setBounds(0, 0, Ui.px(18), Ui.px(18));
        keys.setCompoundDrawables(kg, null, null, null);
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(Ui.px(9), 0, 0, 0);
        keys.setContentDescription("Type instead");
        pill(keys, 500, 10, t.ink2);
        keys.setOnClickListener(v -> showTyping());
        row.addView(keys, Ui.at(CW - 24 - 10 - 36, 10, 36, 36));
        inner.addView(row, Ui.at(12, ph - ROW - 12, CW - 24, ROW));
        hint();
        paintRow();

        // who, and what it can see
        Lamp mark = new Lamp(this, 28);
        inner.addView(mark, Ui.at(10, 14, 44, 44));
        inner.addView(Ui.line(this, 17, t.ink, 600, "Glass"), Ui.at(56, 16, 240, 20));
        String app = front != null ? front.getString("app") : null;
        sees = Ui.line(this, 13, t.ink2, 400, desk || app == null ? "Sees the desktop" : "Sees " + app);
        Glyph sg = Glyph.of(desk ? "clock" : "film", t.ink2, 2.2f);
        sg.setBounds(0, 0, Ui.px(13), Ui.px(13));
        sees.setCompoundDrawables(sg, null, null, null);
        sees.setCompoundDrawablePadding(Ui.px(6));
        inner.addView(sees, Ui.at(56, 39, CW - 56 - 128, 17));
        newChat = Ui.line(this, 13, t.ink2, 600, "New chat");
        newChat.setGravity(Gravity.CENTER);
        newChat.setPadding(Ui.px(12), 0, Ui.px(12), 0);
        pill(newChat, 600, 10, t.ink2);
        newChat.setOnClickListener(v -> newChat());
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(32));
        nl.gravity = Gravity.TOP | Gravity.END;
        nl.topMargin = Ui.px(20);
        nl.rightMargin = Ui.px(18);
        inner.addView(newChat, nl);
        View hair = new View(this);
        hair.setBackgroundColor(t.hair);
        inner.addView(hair, Ui.at(0, 68, CW, 1));

        // the conversation: newest at the bottom, older above, scrolling as it grows; the older end fades
        // under the header instead of being cut. 8 px of room each side for whatever the remote lifts.
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setPadding(0, Ui.px(10), 0, Ui.px(10));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.px(30));
        feed = Ui.column(this);
        feed.setGravity(Gravity.BOTTOM);
        feed.setPadding(Ui.px(LIFT), 0, Ui.px(LIFT), 0);
        feed.setClipChildren(false);
        scroll.addView(feed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inner.addView(scroll, Ui.at(20 - LIFT, HEAD, CW - 40 + 2 * LIFT, ph - HEAD - ROW - 24));

        pane.addView(inner, Ui.fill());
        root.addView(pane, pane.params());
        setContentView(root);
        Ui.marquee(root);
        row.post(row::requestFocus);
    }

    /**
     * One look for everything the remote can land on, so where the remote is never needs finding: at rest, light
     * glass with a hairline; under the remote, the one dark pill on screen, lifted a little. `weight` stays put
     * (a heavier face on focus would shift the row it sits in).
     */
    private void pill(TextView v, int weight, float radius, int restInk) {
        Ui.Tone t = Ui.t;
        v.setFocusable(true);
        v.setClickable(true);
        v.setTypeface(Ui.face(this, weight));
        Runnable paint = () -> {
            boolean has = v.isFocused();
            GradientDrawable g = Ui.fillRound(has ? t.sel : t.hover, radius);
            if (!has) g.setStroke(Math.max(1, Ui.px(1)), t.hair);
            v.setBackground(g);
            v.setTextColor(has ? t.selink : restInk);
            Drawable d = v.getCompoundDrawables()[0];
            if (d instanceof Glyph) ((Glyph) d).setColor(has ? t.selink : restInk);
        };
        paint.run();
        v.setOnFocusChangeListener((x, has) -> {
            if (has) Sound.tick();
            paint.run();
            float k = has ? 1.05f : 1f;
            v.animate().scaleX(k).scaleY(k).setDuration(Ui.ms(has ? 200 : 140)).setInterpolator(Ui.OUT).start();
        });
    }

    /** Before taking away a view the remote may be on: the remote goes to the row first (never to New chat). */
    private void spare(View leaving) {
        if (leaving != null && leaving.hasFocus()) row.requestFocus();
    }

    private void paintRow() {
        Ui.Tone t = Ui.t;
        GradientDrawable g = Ui.fillRound(t.field, 16);
        if (row.hasFocus() && row.isFocused()) g.setStroke(Math.round(Ui.pxf(2.5f)), t.ink);
        else g.setStroke(Math.max(1, Ui.px(1)), t.hair);
        row.setBackground(g);
    }

    private void hint() {
        rowHint.setText(busy ? "" : listening ? "OK to send" : "OK to talk");
    }

    // ---- the conversation, drawn --------------------------------------------------------------------------

    /** Adds a turn to the sheet. `latest`: follow-up questions are only shown for the newest turn. */
    private void render(Talk.Turn t, boolean animate, boolean latest) {
        View v;
        switch (t.who) {
            case "you": v = you(t.text); break;
            case "glass": v = says(t.text); break;
            case "done": v = receipts(t.items); break;
            case "ask": v = question(t); break;
            case "pick":
                if (t.pick == null) return;
                v = card(t.pick);
                break;
            case "next":
                if (!latest) return;
                v = chips(t.items, label -> send(label, null), null);
                nextView = v;
                break;
            default: return;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = feed.getChildCount() == 0 ? 0 : Ui.px(14);
        feed.addView(v, lp);
        if (animate) {
            v.setAlpha(0f);
            v.setTranslationY(Ui.pxf(10));
            v.animate().alpha(1f).translationY(0).setDuration(Ui.ms(320)).setInterpolator(Ui.OUT).start();
        }
        scrollDown();
    }

    /** To the newest end, once what was just added has its size (before that, the old height would stop short). */
    private void scrollDown() {
        feed.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                feed.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                scroll.smoothScrollTo(0, feed.getHeight());   // ScrollView stops at its last line
            }
        });
        feed.requestLayout();
    }

    private View you(String text) {
        Ui.Tone t = Ui.t;
        FrameLayout holder = new FrameLayout(this);
        TextView b = Ui.text(this, 17, t.ink, 500);
        b.setText(text);
        b.setMaxWidth(Ui.px(360));
        b.setLineSpacing(Ui.pxf(24) - b.getPaint().getFontMetricsInt(null), 1f);
        b.setPadding(Ui.px(14), Ui.px(10), Ui.px(14), Ui.px(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.lozenge);
        float r = Ui.pxf(18), tail = Ui.pxf(6);
        g.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});
        b.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        holder.addView(b, lp);
        return holder;
    }

    private TextView says(String text) {
        TextView v = Ui.text(this, 17, Ui.t.ink, 400);
        v.setText(text);
        v.setLineSpacing(Ui.pxf(25) - v.getPaint().getFontMetricsInt(null), 1f);
        return v;
    }

    private View receipts(List<String> items) {
        Ui.Tone t = Ui.t;
        Flow f = new Flow(this, 6, 6);
        for (String s : items) {
            boolean failed = s.startsWith("✕ ");
            TextView r = Ui.line(this, 13, t.ink2, 600, failed ? s.substring(2) : s);
            Glyph g = Glyph.of(failed ? "info" : "check", t.ink2, 2.4f);
            g.setBounds(0, 0, Ui.px(14), Ui.px(14));
            r.setCompoundDrawables(g, null, null, null);
            r.setCompoundDrawablePadding(Ui.px(6));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.px(10), 0, Ui.px(10), 0);
            r.setBackground(Ui.fillRound(t.hover, 9));
            f.addView(r, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(28)));
        }
        return f;
    }

    interface Pick {
        void picked(String label);
    }

    /** Replies to pick with the remote; `chosen`: the one already picked (then none can be picked again). */
    private Flow chips(List<String> labels, Pick pick, String chosen) {
        Ui.Tone t = Ui.t;
        Flow f = new Flow(this, 8, 8);
        for (String l : labels) {
            TextView c = Ui.line(this, 15, t.ink, 500, l);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.px(14), 0, Ui.px(14), 0);
            boolean done = chosen != null;
            boolean mine = l.equals(chosen);
            if (done) {
                GradientDrawable g = Ui.fillRound(mine ? t.lozenge : 0, 12);
                if (!mine) g.setStroke(Math.max(1, Ui.px(1)), t.hair);
                c.setBackground(g);
                c.setTextColor(mine ? t.ink : t.ink3);
                if (mine) {
                    c.setTypeface(Ui.face(this, 600));
                    Glyph ok = Glyph.of("check", t.ink, 2.4f);
                    ok.setBounds(0, 0, Ui.px(15), Ui.px(15));
                    c.setCompoundDrawables(ok, null, null, null);
                    c.setCompoundDrawablePadding(Ui.px(6));
                    c.setPadding(Ui.px(11), 0, Ui.px(14), 0);
                }
            } else {
                pill(c, 500, 12, t.ink);
                c.setOnClickListener(v -> {
                    Sound.pop();
                    pick.picked(l);
                });
            }
            f.addView(c, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(38)));
        }
        return f;
    }

    private View question(Talk.Turn q) {
        LinearLayout col = Ui.column(this);
        col.setClipChildren(false);
        col.addView(says(q.text), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View c = chips(q.items, label -> pick(q, label), q.picked);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.px(10);
        col.addView(c, lp);
        col.setTag(q);
        return col;
    }

    private View card(JSONObject p) {
        Ui.Tone t = Ui.t;
        LinearLayout box = Ui.column(this);
        box.setClipChildren(false);
        box.setPadding(Ui.px(14), Ui.px(14), Ui.px(14), Ui.px(14));
        GradientDrawable bg = Ui.fillRound(t.hover, 16);
        bg.setStroke(Math.max(1, Ui.px(1)), t.hair);
        box.setBackground(bg);
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageDrawable(art(p.optString("app")));
        art.setClipToOutline(true);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(12));
            }
        });
        top.addView(art, Ui.ll(132, 74));
        LinearLayout words = Ui.column(this);
        TextView title = Ui.text(this, 20, t.ink, 600);
        title.setText(p.optString("title"));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLetterSpacing(-0.015f);
        words.addView(title);
        String meta = p.optString("meta", "");
        String app = p.optString("app", "");
        TextView m = Ui.line(this, 13, t.ink2, 400, meta + (meta.isEmpty() || app.isEmpty() || meta.contains(app) ? "" : " · ") + (meta.contains(app) ? "" : app));
        words.addView(m, Ui.ll(-1, -1, 0, 4));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wl.leftMargin = Ui.px(14);
        top.addView(words, wl);
        box.addView(top);
        String why = p.optString("why", "");
        if (!why.isEmpty()) {
            TextView w = Ui.text(this, 15, t.ink, 400);
            w.setText(why);
            w.setLineSpacing(Ui.pxf(22) - w.getPaint().getFontMetricsInt(null), 1f);
            box.addView(w, Ui.ll(-2, -1, 0, 12));
        }
        LinearLayout acts = Ui.row(this);
        Actions.App a = Actions.findApp(this, app);
        acts.addView(button(a != null ? "Play on " + a.label : "Find it", true, () -> play(p)), Ui.ll(-1, 38));
        acts.addView(button("Another", false, () -> send("Another one", null)), Ui.ll(-1, 38, 8, 0));
        box.addView(acts, Ui.ll(-1, -1, 0, 12));
        return box;
    }

    private TextView button(String label, boolean primary, Runnable go) {
        TextView b = Ui.line(this, 15, Ui.t.ink, primary ? 600 : 500, label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.px(16), 0, Ui.px(16), 0);
        pill(b, primary ? 600 : 500, 12, Ui.t.ink);
        b.setOnClickListener(v -> {
            Sound.pop();
            go.run();
        });
        return b;
    }

    /** The app's own banner (16:9) as the picture, else a soft gradient. */
    private Drawable art(String appName) {
        Actions.App a = appName == null ? null : Actions.findApp(this, appName);
        if (a != null) {
            try {
                Drawable banner = getPackageManager().getApplicationBanner(a.pkg);
                if (banner != null) return banner;
            } catch (Exception ignored) {
                // no banner
            }
        }
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF9FD3E8, 0xFF4C8FB5, 0xFFF3CDB0, 0xFFE58E6A});
    }

    private void showThinking() {
        Ui.Tone t = Ui.t;
        LinearLayout d = Ui.row(this);
        List<View> ds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            dot.setBackground(Ui.oval(t.ink2));
            d.addView(dot, Ui.ll(8, 8, i == 0 ? 2 : 6, 0));
            ds.add(dot);
        }
        thinking = d;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(24));
        lp.topMargin = feed.getChildCount() == 0 ? 0 : Ui.px(14);
        d.setGravity(Gravity.CENTER_VERTICAL);
        feed.addView(d, lp);
        dots = ValueAnimator.ofFloat(0f, 3f);
        dots.setDuration(1200);
        dots.setRepeatCount(ValueAnimator.INFINITE);
        dots.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            for (int i = 0; i < 3; i++) {
                float k = Math.max(0f, 1f - Math.abs(f - i - .5f));
                ds.get(i).setAlpha(.25f + .75f * k);
            }
        });
        dots.start();
        scrollDown();
    }

    private void hideThinking() {
        if (dots != null) dots.cancel();
        dots = null;
        if (thinking != null) feed.removeView(thinking);
        thinking = null;
    }

    private void dropNext() {
        spare(nextView);
        if (nextView != null) feed.removeView(nextView);
        nextView = null;
    }

    // ---- talking ----------------------------------------------------------------------------------------------

    /** Something said, typed or picked: into the conversation, then Glass's reply (and what it did). */
    private void send(String text, Talk.Turn answering) {
        if (busy || closing) return;
        String said = text.trim();
        if (said.isEmpty()) return;
        busy = true;
        stopListening();
        dropNext();
        rowText.setText("Ask Glass…");
        rowText.setTextColor(Ui.t.ink3);
        lamp.setLevel(0f);
        hint();
        if (answering == null) {
            Talk.Turn y;
            synchronized (talk) {
                y = talk.add("you", said);
            }
            render(y, true, true);
        }
        showThinking();
        worker.execute(() -> {
            try {
                tvReady.await(1800, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Brain.Reply r;
            try {
                r = brain.reply(talk, said);
            } catch (RuntimeException e) {
                r = new Brain.Reply();
                r.say = "Something went wrong. Try again.";
            }
            // things to do: done now, except turning the TV off (and an input nobody named), which wait for a yes
            List<Doer.Done> done = new ArrayList<>();
            Brain.Act wait = null;
            for (Brain.Act a : r.acts) {
                if ("off".equals(a.act()) || ("input".equals(a.act()) && !Doer.saidInput(said))) {
                    wait = a;
                    continue;
                }
                done.add(Doer.run(this, a));
            }
            List<Talk.Turn> fresh = new ArrayList<>();
            boolean leaves = false;
            synchronized (talk) {
                talk.remember("user", said);
                talk.remember("assistant", r.memo != null ? r.memo : memo(r, done));
                if (!done.isEmpty()) {
                    Talk.Turn d = talk.add("done", null);
                    for (Doer.Done x : done) {
                        d.items.add((x.ok ? "" : "✕ ") + x.label);
                        leaves |= x.ok && x.leaves;
                    }
                    fresh.add(d);
                }
                if (!r.say.isEmpty()) fresh.add(talk.add("glass", r.say));
                if (wait != null) {
                    boolean off = "off".equals(wait.act());
                    Talk.Turn q = talk.add("ask", off ? "Turn the TV off now?" : "Switch to " + wait.j.optString("value", "that input") + "?");
                    q.items.add(off ? "Turn it off" : "Switch");
                    q.items.add("Not now");
                    q.pending = wait.j;
                    fresh.add(q);
                } else if (r.ask != null && !r.choices.isEmpty()) {
                    Talk.Turn q = talk.add("ask", r.ask);
                    q.items.addAll(r.choices);
                    fresh.add(q);
                } else if (r.ask != null) {
                    fresh.add(talk.add("glass", r.ask));
                }
                if (r.pick != null) {
                    Talk.Turn p = talk.add("pick", null);
                    p.pick = r.pick;
                    fresh.add(p);
                }
                if (!r.next.isEmpty() && r.ask == null && wait == null) {
                    Talk.Turn n = talk.add("next", null);
                    n.items.addAll(r.next);
                    fresh.add(n);
                }
                if (fresh.isEmpty()) fresh.add(talk.add("glass", "Done."));
                talk.save(this);
            }
            final boolean away = leaves;
            ui.post(() -> arrive(fresh, away));
        });
    }

    /** What Glass remembers of a reply it made on the TV itself (no model involved). */
    private static String memo(Brain.Reply r, List<Doer.Done> done) {
        StringBuilder s = new StringBuilder("{\"say\":\"\",\"do\":[");
        for (int i = 0; i < r.acts.size(); i++) s.append(i > 0 ? "," : "").append(r.acts.get(i).j.toString());
        s.append("],\"done\":\"");
        for (Doer.Done d : done) s.append(d.label.replace("\"", "'")).append("; ");
        return s.append("\"}").toString();
    }

    private void arrive(List<Talk.Turn> fresh, boolean away) {
        busy = false;
        hideThinking();
        if (isFinishing() || closing) return;
        for (int i = 0; i < fresh.size(); i++) render(fresh.get(i), true, i == fresh.size() - 1);
        hint();
        Sound.pop();
        // the remote lands on the first thing to pick (a reply, the pick's button), else back on the row
        ui.postDelayed(() -> {
            View f = firstPickable(fresh.size());
            (f != null ? f : row).requestFocus();
        }, Ui.ms(360));
        idleClose(away ? 4_000 : 90_000);
    }

    /** The first focusable thing in the newest `n` items of the conversation. */
    private View firstPickable(int n) {
        for (int i = Math.max(0, feed.getChildCount() - n); i < feed.getChildCount(); i++) {
            View v = first(feed.getChildAt(i));
            if (v != null) return v;
        }
        return null;
    }

    private static View first(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = first(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** A reply to Glass's question back. A yes to turning off (or switching input) does it; anything else talks on. */
    private void pick(Talk.Turn q, String label) {
        if (busy || closing || q.picked != null) return;
        q.picked = label;
        View old = null;
        for (int i = 0; i < feed.getChildCount(); i++) if (feed.getChildAt(i).getTag() == q) old = feed.getChildAt(i);
        if (old != null) {
            spare(old);
            int at = feed.indexOfChild(old);
            ViewGroup.LayoutParams lp = old.getLayoutParams();
            feed.removeView(old);
            feed.addView(question(q), at, lp);
        }
        if (q.pending != null) {
            JSONObject act = q.pending;
            q.pending = null;
            if (!label.equals(q.items.get(0))) {
                Talk.Turn n;
                synchronized (talk) {
                    n = talk.add("glass", "Okay, leaving it as it is.");
                    talk.save(this);
                }
                render(n, true, true);
                row.requestFocus();
                return;
            }
            busy = true;
            showThinking();
            worker.execute(() -> {
                Doer.Done d = Doer.run(this, new Brain.Act(act));
                Talk.Turn t;
                synchronized (talk) {
                    t = talk.add("done", null);
                    t.items.add((d.ok ? "" : "✕ ") + d.label);
                    talk.remember("user", label);
                    talk.remember("assistant", "{\"say\":\"\",\"done\":\"" + d.label.replace("\"", "'") + "\"}");
                    talk.save(this);
                }
                List<Talk.Turn> fresh = new ArrayList<>();
                fresh.add(t);
                ui.post(() -> arrive(fresh, d.ok && ("off".equals(act.optString("act")) || d.leaves)));
            });
            return;
        }
        synchronized (talk) {
            talk.save(this);
        }
        send(label, q);
    }

    private void play(JSONObject p) {
        String q = p.optString("title");
        String app = p.optString("app", "").toLowerCase(java.util.Locale.ROOT);
        Brain.Act a;
        try {
            a = new Brain.Act(app.contains("youtube") || app.isEmpty()
                    ? new JSONObject().put("act", "search").put("app", "YouTube").put("query", q)
                    : new JSONObject().put("act", "search").put("app", p.optString("app")).put("query", q));
        } catch (Exception e) {
            return;
        }
        worker.execute(() -> {
            Doer.Done d = Doer.run(this, a);
            synchronized (talk) {
                Talk.Turn t = talk.add("done", null);
                t.items.add((d.ok ? "" : "✕ ") + d.label);
                talk.save(this);
            }
        });
    }

    private void newChat() {
        if (busy) return;
        Sound.close();
        stopListening();
        synchronized (talk) {
            talk.clear(this);
        }
        feed.removeAllViews();
        nextView = null;
        rowText.setText("Ask Glass…");
        rowText.setTextColor(Ui.t.ink3);
        hint();
        row.requestFocus();
    }

    // ---- listening ------------------------------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        listen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readContext(intent, null);
        bare = intent.getBooleanExtra("bare", false);
        if (intent.getBooleanExtra("fresh", false) && !busy) {
            synchronized (talk) {
                talk.clear(this);
            }
            spare(feed);
            feed.removeAllViews();
            nextView = null;
        }
        String q = intent.getStringExtra("q");
        if (intent.getBooleanExtra("button", false) && listening) {   // pressed again: send what was heard
            sendNow();
            return;
        }
        loadTv();   // the TV may have moved on since this opened
        if (q != null && !q.trim().isEmpty()) {
            send(q.trim(), null);
            return;
        }
        if (!busy) listen();
    }

    /** Send what has been heard so far (OK, or the assistant button again while listening). */
    private void sendNow() {
        if (listening && speech != null) {
            try {
                speech.stopListening();   // the recogniser finishes with what it has: onResults
                return;
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        if (partial != null && !partial.trim().isEmpty()) send(partial.trim(), null);
    }

    private void listen() {
        if (busy || closing) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showTyping();
            return;
        }
        stopListening();
        hideTyping();
        partial = null;
        listening = true;
        // "Listening" once the recognizer is: on a TV where it starts cold (Google's, just stopped) that takes a moment
        rowText.setText("One moment…");
        rowText.setTextColor(Ui.t.ink3);
        hint();
        breathe(true);
        idleClose(60_000);
        final long asked = android.os.SystemClock.uptimeMillis();
        final float[] loudest = {-100f};
        try {
            speech = SpeechRecognizer.createSpeechRecognizer(this);
            speech.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) {
                    if (partial == null) rowText.setText("Listening…");
                    note("ready after " + (android.os.SystemClock.uptimeMillis() - asked) + " ms");
                }
                @Override public void onBeginningOfSpeech() {
                    breathe(false);
                    note("speech began after " + (android.os.SystemClock.uptimeMillis() - asked) + " ms");
                }
                @Override public void onRmsChanged(float db) {
                    loudest[0] = Math.max(loudest[0], db);
                    if (breathe != null) return;
                    float target = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
                    level += (target - level) * 0.45f;
                    lamp.setLevel(level);
                }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { lamp.setLevel(.15f); }
                @Override public void onEvent(int t, Bundle b) { }
                @Override public void onError(int error) {
                    note("error " + error + " after " + (android.os.SystemClock.uptimeMillis() - asked) + " ms, loudest " + Math.round(loudest[0]) + " dB");
                    listening = false;
                    breathe(false);
                    lamp.setLevel(0f);
                    if (busy || closing) return;
                    if (partial != null && !partial.trim().isEmpty() && error != SpeechRecognizer.ERROR_CLIENT) {
                        send(partial.trim(), null);   // a pause ended it early: what was heard still counts
                        return;
                    }
                    rowText.setTextColor(Ui.t.ink3);
                    rowText.setText(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "Didn’t catch that" : "Couldn’t listen just now");
                    hint();
                }
                @Override public void onPartialResults(Bundle b) {
                    String p = first(b);
                    if (p != null && !p.isEmpty()) {
                        partial = p;
                        words(p);
                    }
                }
                @Override public void onResults(Bundle b) {
                    listening = false;
                    breathe(false);
                    String w = first(b);
                    note("heard " + (w == null ? 0 : w.length()) + " characters after " + (android.os.SystemClock.uptimeMillis() - asked) + " ms, loudest " + Math.round(loudest[0]) + " dB");
                    if ((w == null || w.trim().isEmpty()) && partial != null) w = partial;
                    if (w == null || w.trim().isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
                    send(w, null);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // a breath mid-sentence passes: only a real stop ends it (or OK, or the button again)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            speech.startListening(i);
        } catch (RuntimeException e) {
            listening = false;
            showTyping();
        }
    }

    /** For tuning how the TV's speech recognizer does (times, lengths and loudness; never the words). */
    private static void note(String what) {
        android.util.Log.i("GlassKeys", "listening: " + what);
    }

    /** Your words along the row; the word still being said is dimmer. */
    private void words(String s) {
        Ui.Tone t = Ui.t;
        rowText.setTextColor(t.ink);
        int cut = s.lastIndexOf(' ');
        if (cut < 0) {
            rowText.setText(s);
            return;
        }
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new ForegroundColorSpan(t.ink3), cut + 1, s.length(), 0);
        rowText.setText(sp);
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private void stopListening() {
        listening = false;
        breathe(false);
        if (speech != null) {
            try {
                speech.destroy();
            } catch (RuntimeException ignored) {
            }
            speech = null;
        }
    }

    private void breathe(boolean on) {
        if (breathe != null) {
            breathe.cancel();
            breathe = null;
        }
        if (!on) return;
        breathe = ValueAnimator.ofFloat(0f, 1f);
        breathe.setDuration(1600);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.addUpdateListener(a -> lamp.setLevel(.25f * (float) a.getAnimatedValue()));
        breathe.start();
    }

    private void showTyping() {
        stopListening();
        rowText.setVisibility(View.GONE);
        rowHint.setVisibility(View.GONE);
        typed.setVisibility(View.VISIBLE);
        typed.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(typed, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
        idleClose(120_000);
    }

    private void hideTyping() {
        if (typed.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(typed.getWindowToken(), 0);
        typed.setText("");
        typed.setVisibility(View.GONE);
        rowText.setVisibility(View.VISIBLE);
        rowHint.setVisibility(View.VISIBLE);
        row.requestFocus();
    }

    // ---- motion ---------------------------------------------------------------------------------------------

    /** Pours from the bar's assistant item; the conversation fades in behind the glass. */
    private void appear() {
        Sound.glass();
        inner.setAlpha(0f);
        pane.pour(Ui.pxf(GX - CX), null);
        inner.animate().alpha(1f).setStartDelay(Ui.ms(140)).setDuration(Ui.ms(260)).setInterpolator(Ui.OUT).start();
    }

    private final Runnable close = this::close;

    private void idleClose(long ms) {
        ui.removeCallbacks(close);
        ui.postDelayed(close, ms);
    }

    /** Folds back into the bar's assistant item, then goes (the conversation is kept). */
    private void close() {
        if (closing) return;
        closing = true;
        Sound.close();
        stopListening();
        hideThinking();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && typed != null) imm.hideSoftInputFromWindow(typed.getWindowToken(), 0);
        inner.animate().alpha(0f).setDuration(Ui.ms(120)).start();
        pane.unpour(Ui.pxf(GX - CX), this::finish);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        idleClose(busy ? 120_000 : 90_000);
        if (typed.getVisibility() == View.VISIBLE && typed.hasFocus() && keyCode != KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                if (listening) sendNow();
                else if (!busy) listen();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (typed.getVisibility() == View.VISIBLE) {
                    hideTyping();
                    return true;
                }
                if (listening) {
                    stopListening();
                    rowText.setText("Ask Glass…");
                    rowText.setTextColor(Ui.t.ink3);
                    lamp.setLevel(0f);
                    hint();
                    return true;
                }
                close();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isFinishing()) {
            closing = true;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        hideThinking();
        breathe(false);
        stopListening();
        worker.shutdown();   // a reply on its way still lands in the kept conversation
        super.onDestroy();
    }

    @Override
    public void finish() {
        if (desk) ask("assist", "close");
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ---- small parts ----------------------------------------------------------------------------------------

    /** Lays its children in rows, wrapping, with gaps (replies, receipts). */
    static final class Flow extends ViewGroup {
        private final int hg, vg;

        Flow(Context c, float hgap, float vgap) {
            super(c);
            hg = Ui.px(hgap);
            vg = Ui.px(vgap);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                LayoutParams lp = c.getLayoutParams();
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST),
                        lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += rowH + vg;
                    rowH = 0;
                }
                x += c.getMeasuredWidth() + hg;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + rowH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += rowH + vg;
                    rowH = 0;
                }
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + hg;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
        }
    }

    /** Glass's mark, the glass orb holding a sunrise: it glows warmer and grows a little as it hears you. */
    static final class Lamp extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF box = new RectF();
        private final Bitmap orb;
        private final float size;
        private float level;

        Lamp(Context c, float size) {
            super(c);
            this.size = size;
            orb = BitmapFactory.decodeResource(c.getResources(), R.drawable.glass_mark);
        }

        void setLevel(float l) {
            level = Math.max(0f, Math.min(1f, l));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = Ui.pxf(size), cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (level > 0.01f) {
                float r = w * (0.62f + 0.5f * level);
                glow.setShader(new RadialGradient(cx, cy, r, new int[]{Ui.a(0xFF9664, .12f + .42f * level), 0x00FF9664}, null, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, glow);
            }
            float s = w * (1f + 0.07f * level) / 2f;
            box.set(cx - s, cy - s, cx + s, cy + s);
            if (orb != null) c.drawBitmap(orb, null, box, pic);
        }
    }
}
