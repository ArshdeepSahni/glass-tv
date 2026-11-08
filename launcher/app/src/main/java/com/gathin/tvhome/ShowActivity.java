package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.media.ThumbnailUtils;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Photos and videos full screen, the way Photos on an iPhone moves through them: ← → glides to the next at once
 * (its picture is already there: the ones either side are decoded ahead, a small one first, the sharp one a moment
 * later), and holding ← or → races through. A video plays when you stop on it. ↓ shows where you are (a strip of
 * the others, the name, when it came); ↑ or a few quiet seconds hide it. OK plays or pauses a video, or shows and
 * hides the strip on a photo. Menu (or holding OK): a slideshow, or Delete for what a phone sent. Back leaves.
 *
 * Opened from Files with the photos and videos of the place it was in, or with what phones sent.
 */
public final class ShowActivity extends Activity {
    static final String FILE = "file";   // one of the sent files (Show.dir), by name
    static final String AT = "at";
    private static final long SLIDE_MS = 5000;
    private static ArrayList<String> handoff;   // a list from Files, set just before it opens this

    /** Shows `files` (photos and videos) from `at`. */
    static void open(Context c, List<File> files, int at) {
        ArrayList<String> p = new ArrayList<>();
        for (File f : files) p.add(f.getPath());
        handoff = p;
        Intent i = new Intent(c, ShowActivity.class).putExtra(AT, at)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    // newest request first: what you've stopped on decodes before what you raced past
    private final ExecutorService decode = new java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    private final LruCache<String, Bitmap> full = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final LruCache<String, Bitmap> small = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final java.util.Set<String> pending = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private FrameLayout root, track, chrome, menu;
    private final ImageView[] pages = new ImageView[3];
    private final ImageView[] plays = new ImageView[3];
    private VideoView video;
    private TextView title, sub, empty;
    private LinearLayout strip;
    private HorizontalScrollView stripScroll;
    private List<File> items = new ArrayList<>();
    private int at;
    private int W;
    private ValueAnimator moving;
    private int movingDir;
    private boolean sliding, chromeUp;
    private final List<View> menuRows = new ArrayList<>();

    private final Runnable hideChrome = () -> chrome(false);
    private final Runnable settled = this::settled;
    private final Runnable slide = new Runnable() {
        @Override
        public void run() {
            if (!sliding) return;
            if (items.size() < 2) {
                sliding = false;
                return;
            }
            fadeTo((at + 1) % items.size());
            h.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);   // its own sizes only: the desktop's look (Ui.t) stays as it is
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        W = getResources().getDisplayMetrics().widthPixels;
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        track = new FrameLayout(this);
        root.addView(track, Ui.fill());
        for (int k = 0; k < 3; k++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setTranslationX((k - 1) * W);
            track.addView(iv, Ui.fill());
            pages[k] = iv;
            ImageView play = new ImageView(this);
            play.setImageDrawable(Glyph.of("play", 0xFFFFFFFF));
            play.setBackground(Ui.oval(Ui.a(0x000000, .45f)));
            int p = Ui.px(22);
            play.setPadding(p + Ui.px(3), p, p - Ui.px(3), p);
            play.setTranslationX((k - 1) * W);
            play.setVisibility(View.GONE);
            track.addView(play, new FrameLayout.LayoutParams(Ui.px(84), Ui.px(84), Gravity.CENTER));
            plays[k] = play;
        }
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVisibility(View.GONE);
        buildChrome();
        empty = Ui.text(this, 20, 0xFFF2F2F5, 500);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, Ui.fill());
        setContentView(root);
        Ui.marquee(root);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        load(intent);
    }

    // ---- what's shown -----------------------------------------------------------------------------------------

    private void load(Intent i) {
        stopVideo();
        sliding = false;
        items = new ArrayList<>();
        at = 0;
        ArrayList<String> paths = handoff;
        handoff = null;
        if (paths != null && i != null && i.hasExtra(AT)) {
            for (String p : paths) items.add(new File(p));
            at = Math.max(0, Math.min(items.size() - 1, i.getIntExtra(AT, 0)));
        } else {
            items.addAll(Show.all(this));
            String name = i == null ? null : i.getStringExtra(FILE);
            if (name != null) for (int k = 0; k < items.size(); k++) if (items.get(k).getName().equals(name)) at = k;
        }
        if (items.isEmpty()) {
            empty.setText("Nothing sent yet\nOn your phone: the Glass remote ▸ Send");
            empty.setVisibility(View.VISIBLE);
            chrome.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        chrome.setVisibility(View.VISIBLE);
        fillStrip();
        bind();
        chrome(true);
        h.postDelayed(settled, 60);
    }

    private File item(int k) {
        return k >= 0 && k < items.size() ? items.get(k) : null;
    }

    /** The three pages show the one before, this one and the next (whatever of them is decoded yet). */
    private void bind() {
        for (int k = 0; k < 3; k++) {
            File f = item(at + k - 1);
            pages[k].setImageBitmap(f == null ? null : best(f));
            plays[k].setVisibility(f != null && Show.video(f.getName()) ? View.VISIBLE : View.GONE);
            if (f != null && full.get(f.getPath()) == null) want(f, false);
        }
        caption();
        markStrip();
    }

    private Bitmap best(File f) {
        Bitmap b = full.get(f.getPath());
        return b != null ? b : small.get(f.getPath());
    }

    /** Decodes a file's small picture (and, once you've stopped near it, its sharp one) in the background; the page
     *  showing it updates when they land. While racing through, only small ones are made. */
    private void want(File f, boolean sharp) {
        final String key = f.getPath();
        if (small.get(key) != null && (!sharp || full.get(key) != null)) return;
        if (!pending.add(key + (sharp ? "#s" : "#t"))) return;
        decode.execute(() -> {
            try {
                if (small.get(key) == null) {
                    Bitmap s = decodeAt(f, Ui.px(320), Ui.px(180));
                    if (s != null) {
                        small.put(key, s);
                        h.post(() -> landed(key));
                    }
                }
                if (sharp && full.get(key) == null) {
                    Bitmap b = decodeAt(f, W, getResources().getDisplayMetrics().heightPixels);
                    if (b != null) {
                        full.put(key, b);
                        h.post(() -> landed(key));
                    }
                }
            } finally {
                pending.remove(key + (sharp ? "#s" : "#t"));
            }
        });
    }

    private void landed(String key) {
        for (int k = 0; k < 3; k++) {
            File f = item(at + k - 1);
            if (f != null && f.getPath().equals(key) && (moving == null || !moving.isRunning())) pages[k].setImageBitmap(best(f));
        }
    }

    /** A photo (the right way up) or a video's first picture, fitted inside w x h. */
    private static Bitmap decodeAt(File f, int w, int h) {
        try {
            Bitmap b;
            if (Show.video(f.getName())) {
                b = ThumbnailUtils.createVideoThumbnail(f.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
                if (b == null) return null;
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                if (o.outWidth <= 0) return null;
                int s = 1;
                while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b == null) return null;
                int turn = 0;
                try {
                    int or = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    turn = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                } catch (Exception ignored) {
                }
                if (turn != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(turn);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                }
            }
            // no bigger than the screen needs (a 12 MP photo would take 48 MB)
            float k = Math.min(1f, Math.min(w / (float) b.getWidth(), h / (float) b.getHeight()));
            if (k < 0.99f) {
                Bitmap sc = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                if (sc != b) b.recycle();
                b = sc;
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- moving ---------------------------------------------------------------------------------------------------

    private void step(int d) {
        if (items.size() < 2) {
            Sound.bump();
            return;
        }
        int next = at + d;
        if (next < 0 || next >= items.size()) {   // the ends hold, as in Photos (a soft push back)
            Sound.bump();
            nudge(d);
            return;
        }
        Sound.tick();
        stopVideo();
        h.removeCallbacks(settled);
        if (moving != null && moving.isRunning()) moving.end();   // a fast run: finish the last glide at once
        movingDir = d;
        at = next;
        caption();
        markStrip();
        if (Ui.reduced) {
            track.setTranslationX(0);
            bind();
            h.postDelayed(settled, 120);
            return;
        }
        moving = ValueAnimator.ofFloat(0f, 1f);
        moving.setDuration(Ui.ms(230));
        moving.setInterpolator(Ui.OUT);
        moving.addUpdateListener(va -> track.setTranslationX(-movingDir * W * (float) va.getAnimatedValue()));
        moving.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean done;

            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (done) return;
                done = true;
                track.setTranslationX(0);
                bind();
                h.removeCallbacks(settled);
                h.postDelayed(settled, 140);
            }
        });
        moving.start();
    }

    private void nudge(int d) {
        if (Ui.reduced) return;
        track.animate().translationX(-d * Ui.pxf(28)).setDuration(Ui.ms(90)).setInterpolator(Ui.OUT)
                .withEndAction(() -> track.animate().translationX(0).setDuration(Ui.ms(220)).setInterpolator(Ui.OUT).start()).start();
    }

    /** The slideshow's way: the next one fades in where this one is. */
    private void fadeTo(int k) {
        stopVideo();
        pages[1].animate().alpha(0f).setDuration(Ui.ms(400)).withEndAction(() -> {
            at = k;
            bind();
            pages[1].animate().alpha(1f).setDuration(Ui.ms(600)).start();
            h.removeCallbacks(settled);
            h.postDelayed(settled, 700);
        }).start();
    }

    /** Stopped on one: its neighbours are decoded ahead, and a video plays. */
    private void settled() {
        File f = item(at);
        if (f == null) return;
        want(f, true);
        for (int d = 1; d <= 2; d++) {
            File n = item(at + d), p = item(at - d);
            if (n != null) want(n, d == 1);
            if (p != null) want(p, d == 1);
        }
        if (Show.video(f.getName()) && !sliding) playVideo(f);
    }

    private void playVideo(File f) {
        video.setVisibility(View.VISIBLE);
        video.setAlpha(0f);
        video.setVideoPath(f.getPath());
        video.setOnPreparedListener(mp -> {
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    video.animate().alpha(1f).setDuration(Ui.ms(160)).start();
                    plays[1].setVisibility(View.GONE);
                }
                return false;
            });
            video.start();
        });
        video.setOnCompletionListener(mp -> {
            plays[1].setVisibility(View.VISIBLE);
            video.setVisibility(View.GONE);
            chrome(true);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            video.setVisibility(View.GONE);
            sub.setText("This TV can't play this video");
            chrome(true);
            return true;
        });
    }

    private void stopVideo() {
        if (video.getVisibility() == View.VISIBLE || video.isPlaying()) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
        }
        File f = item(at);
        plays[1].setVisibility(f != null && Show.video(f.getName()) ? View.VISIBLE : View.GONE);
    }

    // ---- where you are: the name, when, and a strip of the others ---------------------------------------------

    private void buildChrome() {
        chrome = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{Ui.a(0x000000, .72f), Ui.a(0x000000, .38f), 0}));
        chrome.addView(scrim, new FrameLayout.LayoutParams(-1, Ui.px(250), Gravity.BOTTOM));
        LinearLayout col = Ui.column(this);
        title = Ui.line(this, 20, 0xFFF4F4F6, 600, "");
        sub = Ui.line(this, 14, Ui.a(0xF4F4F6, .72f), 400, "");
        col.addView(title);
        col.addView(sub, Ui.ll(-1, -1, 0, 4));
        stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setClipToPadding(false);
        stripScroll.setFocusable(false);
        stripScroll.setPadding(Ui.px(40), Ui.px(6), Ui.px(40), Ui.px(6));
        strip = Ui.row(this);
        strip.setClipChildren(false);
        stripScroll.addView(strip);
        col.addView(stripScroll, Ui.ll(-2, 72, 0, 16));
        title.setPadding(Ui.px(40), 0, Ui.px(40), 0);
        sub.setPadding(Ui.px(40), 0, Ui.px(40), 0);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cl.bottomMargin = Ui.px(26);
        chrome.addView(col, cl);
        root.addView(chrome, Ui.fill());
    }

    private void fillStrip() {
        strip.removeAllViews();
        int n = items.size();
        for (int k = 0; k < n; k++) {
            ImageView t = new ImageView(this);
            t.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.setBackgroundColor(Ui.a(0xFFFFFF, .08f));
            t.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.pxf(6));
                }
            });
            t.setClipToOutline(true);
            t.setAlpha(0.55f);
            strip.addView(t, Ui.ll(80, 54, k == 0 ? 0 : 6, 3));
        }
    }

    /** The strip's thumbnails near where you are (only those: the rest come when you get near them). */
    private void markStrip() {
        int n = strip.getChildCount();
        if (n == 0) return;
        for (int k = Math.max(0, at - 8); k < Math.min(n, at + 9); k++) {
            ImageView t = (ImageView) strip.getChildAt(k);
            File f = item(k);
            if (t.getDrawable() == null && f != null) {
                Bitmap s = small.get(f.getPath());
                if (s != null) t.setImageBitmap(s);
                else thumbLater(t, f);
            }
        }
        for (int k = 0; k < n; k++) {
            View t = strip.getChildAt(k);
            boolean me = k == at;
            t.setAlpha(me ? 1f : 0.55f);
            t.setScaleX(me ? 1.12f : 1f);
            t.setScaleY(me ? 1.12f : 1f);
        }
        View cur = strip.getChildAt(Math.min(at, n - 1));
        cur.post(() -> stripScroll.smoothScrollTo(Math.max(0, cur.getLeft() + cur.getWidth() / 2 - stripScroll.getWidth() / 2 + Ui.px(40)), 0));
    }

    private void thumbLater(ImageView t, File f) {
        decode.execute(() -> {
            Bitmap s = small.get(f.getPath());
            if (s == null) {
                s = decodeAt(f, Ui.px(320), Ui.px(180));
                if (s != null) small.put(f.getPath(), s);
            }
            final Bitmap b = s;
            if (b != null) h.post(() -> t.setImageBitmap(b));
        });
    }

    private void caption() {
        File f = item(at);
        if (f == null) return;
        boolean phone = f.getParentFile() != null && f.getParentFile().equals(Show.dir(this));
        title.setText(Show.nice(f.getName()));
        long age = System.currentTimeMillis() - f.lastModified();
        String when = age < DateUtils.MINUTE_IN_MILLIS ? "just now"
                : DateUtils.getRelativeTimeSpanString(f.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
        String where = phone ? "From a phone" : Files.where(this, f.getPath());
        sub.setText(where + " · " + when + " · " + Formatter.formatShortFileSize(this, f.length())
                + (items.size() > 1 ? "  ·  " + (at + 1) + " of " + items.size() : "") + (sliding ? "  ·  Slideshow" : ""));
    }

    private void chrome(boolean up) {
        h.removeCallbacks(hideChrome);
        chromeUp = up;
        chrome.animate().alpha(up ? 1f : 0f).translationY(up ? 0 : Ui.pxf(16)).setDuration(Ui.ms(up ? 180 : 320)).setInterpolator(Ui.OUT).start();
        if (up && !sliding) h.postDelayed(hideChrome, 3500);
    }

    // ---- the menu: slideshow, delete -----------------------------------------------------------------------

    private void openMenu() {
        if (menu != null || items.isEmpty()) return;
        File f = item(at);
        boolean mine = f != null && f.getParentFile() != null && f.getParentFile().equals(Show.dir(this));
        menu = new FrameLayout(this);
        menu.setBackgroundColor(Ui.a(0x000000, .35f));
        LinearLayout box = Ui.column(this);
        GradientDrawable g = Ui.fillRound(Ui.a(0x1C1C22, .94f), 20);
        g.setStroke(Math.max(1, Ui.px(1)), Ui.a(0xFFFFFF, .14f));
        box.setBackground(g);
        box.setPadding(Ui.px(8), Ui.px(8), Ui.px(8), Ui.px(8));
        menuRows.clear();
        menuRow(box, sliding ? "pause" : "play", sliding ? "Stop the slideshow" : "Slideshow", false, () -> {
            closeMenu();
            sliding = !sliding;
            h.removeCallbacks(slide);
            if (sliding) {
                stopVideo();
                h.postDelayed(slide, 1500);
            }
            caption();
            chrome(true);
        });
        if (mine) menuRow(box, "trash", "Delete", true, () -> {
            closeMenu();
            confirmDelete();
        });
        menu.addView(box, new FrameLayout.LayoutParams(Ui.px(300), -2, Gravity.CENTER));
        root.addView(menu, Ui.fill());
        menuRows.get(0).requestFocus();
    }

    private void menuRow(LinearLayout box, String glyph, String label, boolean danger, Runnable act) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.px(14), 0, Ui.px(14), 0);
        r.setFocusable(true);
        r.setClickable(true);
        int ink = danger ? 0xFFFF6B5E : 0xFFF2F2F5;
        ImageView gl = new ImageView(this);
        gl.setImageDrawable(Glyph.of(glyph, ink));
        r.addView(gl, Ui.ll(18, 18));
        TextView t = Ui.line(this, 16, ink, 500, label);
        r.addView(t, Ui.ll(-1, -1, 12, 0));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(Ui.FOCUSED, Ui.fillRound(Ui.a(0xFFFFFF, .16f), 13));
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> { if (has) Sound.tick(); });
        r.setOnClickListener(v -> act.run());
        box.addView(r, Ui.ll(-2, Ui.boxf(44, 16)));
        menuRows.add(r);
    }

    private void closeMenu() {
        if (menu == null) return;
        root.removeView(menu);
        menu = null;
        menuRows.clear();
    }

    private void confirmDelete() {
        File f = item(at);
        if (f == null) return;
        menu = new FrameLayout(this);
        menu.setBackgroundColor(Ui.a(0x000000, .5f));
        LinearLayout box = Ui.column(this);
        GradientDrawable g = Ui.fillRound(Ui.a(0x1C1C22, .96f), 22);
        g.setStroke(Math.max(1, Ui.px(1)), Ui.a(0xFFFFFF, .14f));
        box.setBackground(g);
        box.setPadding(Ui.px(20), Ui.px(20), Ui.px(20), Ui.px(14));
        TextView q = Ui.text(this, 18, 0xFFF4F4F6, 600);
        q.setText(Show.video(f.getName()) ? "Delete this video?" : "Delete this photo?");
        box.addView(q);
        TextView d = Ui.text(this, 14, Ui.a(0xF4F4F6, .66f), 400);
        d.setText("It's removed from the TV. It stays on the phone that sent it.");
        box.addView(d, Ui.ll(-1, -1, 0, 6));
        menuRows.clear();
        LinearLayout rows = Ui.column(this);
        box.addView(rows, Ui.ll(-2, -1, 0, 14));
        menuRow(rows, "trash", "Delete", true, () -> {
            closeMenu();
            delete(f);
        });
        menuRow(rows, "close", "Cancel", false, this::closeMenu);
        menu.addView(box, new FrameLayout.LayoutParams(Ui.px(380), -2, Gravity.CENTER));
        root.addView(menu, Ui.fill());
        menuRows.get(1).requestFocus();   // the safe answer first
    }

    private void delete(File f) {
        stopVideo();
        boolean ok = f.delete();
        Sound.toggle(ok);
        if (!ok) {
            Toast.makeText(this, "Couldn't delete it", Toast.LENGTH_SHORT).show();
            return;
        }
        full.remove(f.getPath());
        small.remove(f.getPath());
        items.remove(at);
        if (items.isEmpty()) {
            finish();
            return;
        }
        if (at >= items.size()) at = items.size() - 1;
        fillStrip();
        bind();
        chrome(true);
        h.removeCallbacks(settled);
        h.postDelayed(settled, 120);
    }

    // ---- the remote ---------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (menu != null) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeMenu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        return super.dispatchKeyEvent(e);
    }

    private long okDownAt;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (menu != null) return super.onKeyDown(keyCode, e);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                stopSlides();
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                stopSlides();
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                chrome(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                chrome(false);
                return true;
            case KeyEvent.KEYCODE_MENU:
                openMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) {
                    okDownAt = e.getDownTime();
                    e.startTracking();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                ok();
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            okDownAt = -1;
            openMenu();
            return true;
        }
        return super.onKeyLongPress(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (menu == null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (okDownAt != -1 && !e.isCanceled()) ok();
            okDownAt = 0;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && menu == null && sliding) {
            stopSlides();
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    private void ok() {
        if (items.isEmpty()) return;
        if (sliding) {
            stopSlides();
            return;
        }
        File f = item(at);
        if (f != null && Show.video(f.getName())) {
            if (video.getVisibility() != View.VISIBLE) playVideo(f);
            else if (video.isPlaying()) {
                video.pause();
                chrome(true);
            } else video.start();
            return;
        }
        chrome(!chromeUp);
    }

    private void stopSlides() {
        if (!sliding) return;
        sliding = false;
        h.removeCallbacks(slide);
        caption();
        chrome(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sliding = false;
        h.removeCallbacks(slide);
        if (video.isPlaying()) video.pause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        video.stopPlayback();
        decode.shutdownNow();
        super.onDestroy();
    }
}
