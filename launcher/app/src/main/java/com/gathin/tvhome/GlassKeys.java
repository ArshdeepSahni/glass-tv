package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

/**
 * The remote, run first in HomeKeyService's key filter. Inside apps it only takes what apps don't need (Home,
 * the assistant key when it reaches us, the night volume ceiling); OK, Back, Menu and the D-pad belong to the
 * app in front. On the desktop, Menu opens Controls (GlassHomeActivity handles it).
 *
 *   Home        tap: the desktop (anything open on it closes) · hold: the open apps, over the app in front
 *               (it stays as it is underneath) or over the desktop
 *   Assistant   Glass's assistant (normally it arrives as the TV's assistant, see GlassVoiceService)
 *   Volume/Mute the TV applies Vol+/- itself; Glass follows the change (volumeChanged): its HUD over the
 *               TV's bar, and the night ceiling after 11 pm
 *   Menu        Control Center, over any app
 *   Input       the sources, as a glass menu
 */
final class GlassKeys {
    private GlassKeys() {
    }

    static final long HOLD_MS = 450;
    static final String ASSISTANT = "com.gathin.glass.assistant";

    private static boolean homeHeld;
    private static Runnable hold;

    static boolean handle(HomeKeyService svc, KeyEvent e, Handler h) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // the TV's original home is in charge: every key behaves as the TV shipped (Glass's tile brings Glass back)
        if (Glass.original(svc)) return false;
        // which remote button arrives as what (key names and the device only; never typed text)
        if (e.getRepeatCount() == 0 && (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST
                || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_TV_INPUT
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_UNKNOWN || code == KeyEvent.KEYCODE_ALL_APPS)) {
            android.view.InputDevice d = e.getDevice();
            android.util.Log.i("GlassKeys", (down ? "down " : "up ") + KeyEvent.keyCodeToString(code) + " scan=" + e.getScanCode()
                    + " from " + (d != null ? d.getName() : "?"));
        }

        if (code == KeyEvent.KEYCODE_HOME) {
            if (down) {
                if (e.getRepeatCount() > 0) return true;
                homeHeld = false;
                if (hold != null) h.removeCallbacks(hold);
                // which apps are really open, asked now: the list is fresh by the time the hold counts
                Running.refresh(svc, pkgs -> { });
                hold = () -> {
                    homeHeld = true;
                    if (Glass.selfInFront() && GlassHomeActivity.showing() && !Hud.open()) {
                        open(svc, "switcher");    // on the desktop: the desktop's own
                    } else {
                        Hud.of(svc).switcher();   // inside an app: over it, without leaving it
                    }
                };
                h.postDelayed(hold, HOLD_MS);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (hold != null) h.removeCallbacks(hold);
                if (!homeHeld && !e.isCanceled()) home(svc);
                homeHeld = false;
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            Intent ask = svc.getPackageManager().getLaunchIntentForPackage(ASSISTANT);
            if (ask == null) return false;   // not installed: the TV's own assistant stays
            if (down && e.getRepeatCount() == 0) start(svc, assistant(svc, ask));
            return true;
        }

        // volume and mute: Glass's volume pill instead of the TV's bar, everywhere. A held key repeats here (a key
        // Glass takes gets no repeat of its own) and speeds up the longer it's held: 1 step, then 2, then 3 or 4 at a time.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            AudioManager audio = svc.getSystemService(AudioManager.class);
            if (audio == null) return false;
            if (!down) {
                stopVolume(h);
                return true;
            }
            userVolume();
            if (e.getRepeatCount() > 0) return true;   // our own repeat drives a held key
            stopVolume(h);
            if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                return true;
            }
            final boolean upKey = code == KeyEvent.KEYCODE_VOLUME_UP;
            final long downAt = SystemClock.uptimeMillis();
            volumeStep(svc, audio, upKey, 1);
            held = new Runnable() {
                @Override
                public void run() {
                    long t = SystemClock.uptimeMillis() - downAt;
                    if (t > 6000) return;   // a lost key-up never runs the volume away
                    int n = t < 900 ? 1 : t < 1600 ? 2 : upKey ? 3 : 4;
                    if (!volumeStep(svc, audio, upKey, n)) return;   // the end of the range, or the night ceiling
                    userVolume();
                    h.postDelayed(this, 60);
                }
            };
            h.postDelayed(held, 350);
            return true;   // the change itself brings the HUD and the step (volumeChanged)
        }

        // Menu and the settings key: Control Center (on the desktop the desktop handles it itself)
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
            if (Glass.selfInFront() && GlassHomeActivity.showing() && !Hud.open()) return false;
            if (!down && !e.isCanceled()) Hud.of(svc).controls();
            return true;
        }

        // Input: the sources, as a glass menu
        if (code == KeyEvent.KEYCODE_TV_INPUT) {
            if (!down && !e.isCanceled()) {
                if (Glass.selfInFront() && GlassHomeActivity.showing() && !Hud.open()) open(svc, "inputs");
                else Hud.of(svc).inputs();
            }
            return true;
        }
        return false;   // the app does the rest
    }

    private static Runnable held;
    private static long userVolumeAt;

    /** A person changed the volume just now (the remote, a phone): its HUD shows, even while the TV is waking. */
    static void userVolume() {
        userVolumeAt = SystemClock.uptimeMillis();
    }

    private static void stopVolume(Handler h) {
        if (held != null) h.removeCallbacks(held);
        held = null;
    }

    /** n steps up or down; false when nothing more can change (the end, or the night ceiling, which bumps). */
    private static boolean volumeStep(HomeKeyService svc, AudioManager audio, boolean up, int n) {
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int ceiling = ceiling(svc);
        int top = up && ceiling > 0 && Glass.lateNight() ? Math.min(max, ceiling) : max;
        int next = Math.max(0, Math.min(top, v + (up ? n : -n)));
        if (up && v >= top && top < max) {   // held at the night ceiling: nothing changes, so say so here
            Hud.of(svc).volume(true);
            Sound.bump();
            return false;
        }
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        if (next == v) return false;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
        return true;
    }

    private static long lastStep, ownChangeAt, ceilingAt;

    /** Glass itself is about to change the volume (Control Center's slider): no HUD over the slider's own work. */
    static void ownChange() {
        ownChangeAt = SystemClock.uptimeMillis();
    }

    /** Every change of the TV's volume, whoever made it. On Android TV the remote's Vol+/Vol- never reach an app
     *  (the system applies them and shows its own bar), so Glass follows the volume itself: the night ceiling
     *  pulls it back, and the HUD comes up over the TV's bar with a soft step. */
    static void volumeChanged(HomeKeyService svc, int stream) {
        if (stream != AudioManager.STREAM_MUSIC) return;
        if (Glass.original(svc)) return;   // the original home: the TV's own volume bar
        Hud hud = Hud.of(svc);
        if (SystemClock.uptimeMillis() - ownChangeAt < 400) return;   // Control Center's own slider: it shows itself
        // the TV waking up sets its volume again by itself: no HUD over the wake film unless someone pressed a key
        if (Wake.quiet() && SystemClock.uptimeMillis() - userVolumeAt > 1500) {
            hud.refreshControls();
            return;
        }
        hud.refreshControls();                                     // an open Control Center follows the remote
        GlassHomeActivity.refreshControls();
        AudioManager audio = svc.getSystemService(AudioManager.class);
        if (audio == null) return;
        int ceiling = ceiling(svc);
        boolean stopped = false;
        long now = SystemClock.uptimeMillis();
        if (ceiling > 0 && Glass.lateNight()) {
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v > ceiling) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, ceiling, 0);   // back down (this change comes round too)
                stopped = true;
                ceilingAt = now;
            } else if (v == ceiling && now - ceilingAt < 600) {
                stopped = true;   // the echo of our own pull-back: still "held at night volume"
            }
        }
        hud.volume(stopped);
        if (stopped) {
            if (now - lastStep > 400) Sound.bump();
        } else if (now - lastStep > 90) {
            Sound.step();   // a held key: a step now and then, not a buzz
        }
        lastStep = now;
    }

    /** The assistant, told what's in front so it can answer about it and sit over it. */
    static Intent assistant(Context ctx, Intent ask) {
        ask.putExtra("voice", true);
        String f = Glass.front();
        if (!Glass.selfInFront() && f != null) {
            ask.putExtra("watching", f);
            Threads.Thread t = Threads.of(ctx, f);
            if (t != null && t.title != null) ask.putExtra("watching_title", t.title);
            CharSequence app = Glass.label(ctx, f);
            if (app != null) ask.putExtra("watching_app", app.toString());
        }
        return ask;
    }

    static void home(Context ctx) {
        Hud.closeAll();
        Intent open = Glass.original(ctx)
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(Glass.STOCK_HOME)
                : new Intent(ctx, GlassHomeActivity.class);
        if (!start(ctx, open)) start(ctx, new Intent(ctx, GlassHomeActivity.class));
    }

    /** The desktop with something open on it: "switcher", "controls", "apps", "search", "files", "settings". */
    static void open(Context ctx, String what) {
        start(ctx, new Intent(ctx, GlassHomeActivity.class).putExtra(GlassHomeActivity.EXTRA_OPEN, what));
    }

    static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- night ceiling --------------------------------------------------------------------------------------

    static int ceiling(Context ctx) {
        return Glass.prefs(ctx).getInt(Glass.NIGHT_CEILING, 0);
    }

    private static Runnable night;

    /** Every 5 minutes: at night, a volume above the ceiling is brought down once (and shown). */
    static void watchNight(Context ctx, Handler h) {
        if (night == null) night = new Runnable() {
            @Override public void run() {
                int c = ceiling(ctx);
                if (c > 0 && Glass.lateNight()) {
                    AudioManager audio = ctx.getSystemService(AudioManager.class);
                    if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > c) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, c, AudioManager.FLAG_SHOW_UI);
                    }
                }
                h.postDelayed(this, 5 * 60_000);
            }
        };
        h.removeCallbacks(night);
        h.postDelayed(night, 60_000);
    }

    // ---- power-on --------------------------------------------------------------------------------------------

    private static long lastPowerOn;

    /** At boot and at every screen-on: only the Glass-or-original question, when the TV's own home is kept. */
    static void powerOn(Context ctx) {
        powerOn(ctx, 0);
    }

    /** The chooser waits for Glass waking up (Wake): the film first, then the question (a few tries at most). */
    private static void powerOn(Context ctx, int waited) {
        long film = Wake.busy();
        if (film > 0 && waited < 8) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> powerOn(ctx, waited + 1), film);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPowerOn < 20_000) return;
        lastPowerOn = now;
        if (PowerOnActivity.shouldAsk(ctx)) start(ctx, new Intent(ctx, PowerOnActivity.class));
    }

    /** Whether this TV lets the launcher write Android's own settings (granted once by the installer). */
    static boolean canWriteSettings(Context ctx) {
        return Settings.System.canWrite(ctx);
    }

    static SharedPreferences prefs(Context ctx) {
        return Glass.prefs(ctx);
    }
}
