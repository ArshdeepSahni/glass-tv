package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Glass waking up (board GWake): each time the TV comes on, and once after it starts, the desktop opens with the
 * glass. Light finds a ball of clear glass, a sunrise comes up inside it, the view dives in, and the light opens
 * onto the desktop. The film (res/raw/wake_day and wake_night, 2.17 s of 720p, ray traced by brand/ball.py) has
 * nothing of the desktop in it. It plays on a black cover that goes up when the screen goes off, so nothing shows
 * before it; at 1.84 s the cover fades onto the real wallpaper (whatever it is), and at 2.02 s the bar and the dock
 * come in, as on the board. Any key ends it at once. Reduced motion, or wake_film off, leaves it out.
 */
final class Wake {
    private Wake() {
    }

    static final String TAG = "GlassWake";
    static final String PREF = "wake_film";

    /** A desktop that comes up this soon after the screen came on is the TV waking. */
    static final long AFTER_ON_MS = 8000;
    /** ...and this soon after Glass first ran in a boot (the TV's own home, then the redirect, take a while). */
    static final long AFTER_BOOT_MS = 60_000;
    /** The film's times (ms): the light opens (the cover fades) and the bar and the dock come in. */
    static final long OPEN_MS = 1840, OPEN_FOR = 280, IN_MS = 2020, IN_FOR = 400;
    /** A film that hasn't opened by now (no decoder, no frames) gets out of the way. */
    static final long WATCHDOG_MS = 5000;

    static final int NONE = 0, WAIT = 1, PLAY = 2;

    private static boolean primed;        // the screen went off (or the TV started): the next wake plays the film
    private static long until = -1;       // it plays if the desktop comes up before this; -1: the screen-on note is on its way
    private static boolean bootChecked;
    private static Film active;

    /** The film's own easing: what the board's frames use (smoothstep). */
    static final TimeInterpolator SMOOTH = t -> t * t * (3 - 2 * t);

    static boolean on(Context c) {
        SharedPreferences p = Glass.prefs(c);
        return p.getBoolean(PREF, true) && !p.getBoolean(GlassHomeActivity.REDUCED, false);
    }

    static boolean primed() {
        return primed;
    }

    /** HomeKeyService: the screen went off. The desktop is covered now, so it never shows before the film. */
    static void screenOff(Context c) {
        if (!on(c)) return;
        primed = true;
        until = -1;
        note(c, "screen off: the desktop is covered");
        GlassHomeActivity.wakeCover();
    }

    private static long onAt = -60_000;

    /** The TV is waking (the film, or the first seconds after the screen came on): the volume it sets by itself then
     *  brings no HUD (GlassKeys.volumeChanged). */
    static boolean quiet() {
        Film f = active;
        if (f != null && !f.gone) return true;
        return SystemClock.uptimeMillis() - onAt < 7000 || SystemClock.elapsedRealtime() < 90_000;
    }

    /** HomeKeyService: the screen came on. A desktop in front plays the film now; one that comes up later, on resume. */
    static void screenOn(Context c) {
        onAt = SystemClock.uptimeMillis();
        if (!primed) return;
        until = SystemClock.elapsedRealtime() + AFTER_ON_MS;
        note(c, "screen on");
        GlassHomeActivity.wakeNow();
    }

    /** Once per boot (the boot count), from whichever runs first: the desktop or the helper. */
    static void bootCheck(Context c) {
        if (bootChecked) return;
        bootChecked = true;
        if (SystemClock.elapsedRealtime() > HomeKeyService.BOOT_WINDOW_MS || !on(c)) return;
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences p = c.getSharedPreferences("boot", Context.MODE_PRIVATE);
        if (boot < 0 || p.getInt("wake_boot", -1) == boot) return;
        p.edit().putInt("wake_boot", boot).apply();
        primed = true;
        until = SystemClock.elapsedRealtime() + AFTER_BOOT_MS;
        note(c, "boot: the film plays when the desktop first comes up");
    }

    /** The desktop came up (onResume, or in front at screen-on): play, wait (the screen is still off) or nothing. */
    static int decide(Context c) {
        if (!primed) return NONE;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return WAIT;
        primed = false;
        boolean play = until < 0 || SystemClock.elapsedRealtime() < until;
        note(c, play ? "waking: the film plays" : "the desktop came up long after the TV woke: no film");
        return play ? PLAY : NONE;
    }

    /** How long something else that wants the screen (the power-on chooser) should wait for the film. */
    static long busy() {
        Film f = active;
        if (f == null || f.gone) return 0;
        if (f.asked < 0) return 400;   // covered, about to play
        long left = f.asked + IN_MS + IN_FOR + 900 - SystemClock.uptimeMillis();   // + the decoder's start
        return left <= 0 ? 0 : Math.max(200, Math.min(left, WATCHDOG_MS));
    }

    /** Each wake in a few words (times and frame counts only), in the log and in a small file in the app's own
     *  external folder, which the installer reads over ADB after a real power cycle. */
    static void note(Context c, String line) {
        Log.i(TAG, line);
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "wake.log");
            try (FileWriter w = new FileWriter(f, f.length() < 16 * 1024)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + line + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * The black cover and the film on it, over everything on the desktop. Black until the film's first frame (which
     * is black too); then only the film's own pixels, so its fade is a straight crossfade onto the wallpaper.
     */
    static final class Film extends FrameLayout implements TextureView.SurfaceTextureListener {
        private TextureView screen;
        private MediaPlayer player;
        private Surface surface;
        private View[] late = new View[0];
        private ObjectAnimator[] lateIn = new ObjectAnimator[0];
        private Runnable done;
        private boolean night, opening, gone, skipped;
        private long asked = -1, began = -1;
        private int frames;
        private final Runnable watchdog = () -> {
            note(getContext(), "the film didn't open in time: out of the way");
            skip();
        };

        Film(Context c) {
            super(c);
            setBackgroundColor(0xFF000000);
            setClickable(true);   // nothing under it takes a touch or a click while it's up
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;   // one picture (the cover, then the film): alpha applies to it directly, no layer
        }

        boolean started() {
            return asked >= 0;
        }

        /** Plays the film; the late views (the bar, the dock) stay out until the light has opened. */
        void play(boolean night, Runnable done, View... late) {
            if (asked >= 0 || gone) return;
            this.night = night;
            this.done = done;
            this.late = late;
            asked = SystemClock.uptimeMillis();
            active = this;
            lateIn = new ObjectAnimator[late.length];
            for (View v : late) v.setAlpha(0f);
            screen = new TextureView(getContext());
            screen.setSurfaceTextureListener(this);
            addView(screen, new LayoutParams(-1, -1));
            postDelayed(watchdog, WATCHDOG_MS);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            if (gone) return;
            try {
                player = new MediaPlayer();
                surface = new Surface(st);
                player.setSurface(surface);
                source(getContext(), player, night ? R.raw.wake_night : R.raw.wake_day);
                player.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) began();
                    return false;
                });
                player.setOnPreparedListener(mp -> {
                    if (gone) return;
                    mp.start();
                    postDelayed(this::began, 700);   // a decoder that never says when its first frame showed
                });
                player.setOnCompletionListener(mp -> open(OPEN_FOR / 2));
                player.setOnErrorListener((mp, what, extra) -> {
                    note(getContext(), "the film can't play here (" + what + "/" + extra + ")");
                    skip();
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                note(getContext(), "the film can't play here: " + e);
                skip();
            }
        }

        /** The film straight from the APK (stored there uncompressed); a copy in the cache if it ever isn't. */
        private static void source(Context c, MediaPlayer p, int res) throws IOException {
            try (AssetFileDescriptor fd = c.getResources().openRawResourceFd(res)) {
                p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                return;
            } catch (Resources.NotFoundException compressed) {
                // falls through to the copy
            }
            File f = new File(c.getCacheDir(), "wake_" + res + ".mp4");
            if (f.length() == 0) {
                try (InputStream in = c.getResources().openRawResource(res); OutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[64 * 1024];
                    for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                }
            }
            p.setDataSource(f.getPath());
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            release();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            frames++;
        }

        /** The first frame is on screen: the film's clock starts (and its sound, on its own player). */
        private void began() {
            if (began >= 0 || gone || opening) return;
            began = SystemClock.uptimeMillis();
            Chime.play(getContext());
            setBackground(null);   // the film covers every pixel from here
            postDelayed(() -> open(OPEN_FOR), OPEN_MS);
            note(getContext(), "first frame " + (began - asked) + " ms after the film was asked for");
        }

        /** The light opens onto the desktop: the film fades onto the wallpaper, then the bar and the dock come in. */
        private void open(long fade) {
            if (opening || gone) return;
            opening = true;
            removeCallbacks(watchdog);
            animate().alpha(0f).setDuration(Ui.ms(fade)).setInterpolator(SMOOTH).withEndAction(this::end).start();
            bringIn(Math.max(0, IN_MS - OPEN_MS) * fade / OPEN_FOR, IN_FOR);
            postDelayed(this::end, fade + 600);   // in case the fade never reports its end
        }

        /** The late views come in (alpha only, as drawn on the board), each on a layer while it fades. */
        private void bringIn(long delay, long dur) {
            for (int i = 0; i < late.length; i++) {
                final View v = late[i];
                if (lateIn[i] != null) lateIn[i].cancel();
                if (v.getAlpha() >= 1f) continue;
                ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, v.getAlpha(), 1f);
                a.setStartDelay(Ui.ms(delay));
                a.setDuration(Ui.ms(dur));
                a.setInterpolator(SMOOTH);
                final boolean layer = v.getLayerType() == View.LAYER_TYPE_NONE;
                a.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator an) {
                        if (layer) v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    }

                    @Override
                    public void onAnimationEnd(Animator an) {
                        if (layer) v.setLayerType(View.LAYER_TYPE_NONE, null);
                        v.setAlpha(1f);
                    }
                });
                lateIn[i] = a;
                a.start();
            }
        }

        /** A key, an error or the watchdog: out of the way now (the bar and the dock come straight back). */
        void skip() {
            if (gone) return;
            Chime.fade();
            if (opening) {
                bringIn(0, 160);
                return;
            }
            opening = true;
            skipped = asked >= 0;
            removeCallbacks(watchdog);
            animate().alpha(0f).setDuration(Ui.ms(160)).setInterpolator(SMOOTH).withEndAction(this::end).start();
            bringIn(0, 200);
            postDelayed(this::end, 800);
        }

        /** The desktop went away mid-film (another app came up): no animation, everything back as it was. */
        void cut() {
            cut(true);
        }

        private void cut(boolean remove) {
            if (gone) return;
            for (int i = 0; i < late.length; i++) {
                if (lateIn[i] != null) lateIn[i].cancel();
                late[i].setAlpha(1f);
            }
            end(remove);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (!gone) cut(false);   // the desktop is going (rebuilt or closed): nothing may stay hidden
        }

        private void end() {
            end(true);
        }

        private void end(boolean remove) {
            if (gone) return;
            gone = true;
            removeCallbacks(watchdog);
            if (asked >= 0) {
                note(getContext(), "done: " + frames + " frames in " + (SystemClock.uptimeMillis() - (began >= 0 ? began : asked))
                        + " ms" + (skipped ? " (a key ended it)" : ""));
            }
            release();
            if (active == this) active = null;
            if (remove && getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
            // whatever happened, the desktop is whole a moment later (a fade still running ends where it would)
            if (!opening) for (View v : late) v.setAlpha(1f);
            for (View v : late) {
                final View view = v;
                view.postDelayed(() -> {
                    if (view.getAlpha() < 1f) view.setAlpha(1f);
                }, IN_FOR + 900);
            }
            if (done != null) done.run();
        }

        private void release() {
            MediaPlayer p = player;
            if (p != null) p.setOnCompletionListener(null);
            player = null;
            if (p != null) {
                try {
                    p.release();
                } catch (RuntimeException ignored) {
                }
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }
    }

    /**
     * The film's sound (res/raw/wake_chime, 2.8 s): a breath of air as the light finds the glass, a warm chord as the
     * sunrise comes up inside it, a rush as the view dives in, and one clear glass note as it opens onto the desktop,
     * ringing out after the film has gone. Quiet (it plays on the TV's own volume), and only with Glass's sounds on.
     */
    static final class Chime {
        private Chime() {
        }

        private static MediaPlayer player;

        static void play(Context c) {
            if (!Sound.enabled()) return;
            stop();
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                try (AssetFileDescriptor fd = c.getResources().openRawResourceFd(R.raw.wake_chime)) {
                    p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
                p.setVolume(0.75f, 0.75f);
                p.setOnPreparedListener(MediaPlayer::start);
                p.setOnCompletionListener(mp -> stop());
                p.setOnErrorListener((mp, what, extra) -> {
                    stop();
                    return true;
                });
                player = p;
                p.prepareAsync();
            } catch (Exception e) {
                stop();
            }
        }

        /** A key ended the film: the sound goes too, quickly but not cut. */
        static void fade() {
            final MediaPlayer p = player;
            if (p == null) return;
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            for (int i = 1; i <= 6; i++) {
                final float v = 0.75f * (1 - i / 6f);
                h.postDelayed(() -> {
                    if (player != p) return;
                    try {
                        p.setVolume(v, v);
                    } catch (RuntimeException ignored) {
                    }
                    if (v <= 0.001f) stop();
                }, i * 30L);
            }
        }

        static void stop() {
            MediaPlayer p = player;
            player = null;
            if (p != null) {
                try {
                    p.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
