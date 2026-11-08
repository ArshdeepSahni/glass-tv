package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Glass's sounds: small, soft, and Glass's own (synthesised here, not recordings of anyone's). They follow the
 * TV's volume, so a quiet TV makes quiet clicks, and Settings ▸ Sound turns them off.
 *
 *   tick    focus moved                    pop     OK, something opens
 *   close   something closes               on/off  a switch flipped
 *   bump    nowhere further to go          step    one notch of volume
 *   glass   the assistant arrives
 */
final class Sound {
    private Sound() {
    }

    static final String PREF = "sounds";
    private static final String[] NAMES = {"tick", "pop", "close", "on", "off", "bump", "step", "glass"};
    private static SoundPool pool;
    // SoundPool.play can stop for a binder call to the audio server: never on the thread that draws
    private static android.os.Handler audio;
    private static final int[] ids = new int[NAMES.length];
    private static final boolean[] loaded = new boolean[NAMES.length];
    private static int pending = -1;
    private static float pendingVol;
    private static long pendingAt;
    private static boolean ready, enabled = true;
    private static long lastTick, quietUntil;

    static void init(Context c) {
        enabled = c.getSharedPreferences(Glass.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, true);
        if (pool != null) return;
        android.os.HandlerThread t = new android.os.HandlerThread("glass-sound", android.os.Process.THREAD_PRIORITY_AUDIO);
        t.start();
        audio = new android.os.Handler(t.getLooper());
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        // a sound asked for while the pool was still loading (the assistant's chime at a cold start) plays on arrival
        pool.setOnLoadCompleteListener((sp, sample, status) -> {
            for (int i = 0; i < NAMES.length; i++) {
                if (ids[i] != sample || status != 0) continue;
                loaded[i] = true;
                if (pending == i && android.os.SystemClock.uptimeMillis() - pendingAt < 600 && enabled) {
                    final float v = pendingVol;
                    audio.post(() -> sp.play(sample, v, v, 1, 0, 1f));
                }
                if (pending == i) pending = -1;
            }
        });
        final Context app = c.getApplicationContext();
        Ui.BG.execute(() -> {
            try {
                File dir = new File(app.getCacheDir(), "sounds-v1");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                for (int i = 0; i < NAMES.length; i++) {
                    File f = new File(dir, NAMES[i] + ".wav");
                    if (!f.exists() || f.length() < 100) write(f, synth(NAMES[i]));
                    ids[i] = pool.load(f.getPath(), 1);
                }
                ready = true;
            } catch (IOException | RuntimeException ignored) {
                ready = false;
            }
        });
    }

    static void setEnabled(Context c, boolean on) {
        enabled = on;
        c.getSharedPreferences(Glass.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
    }

    static boolean enabled() {
        return enabled;
    }

    private static void play(int i, float vol) {
        if (!enabled || pool == null) return;
        if (!ready || !loaded[i]) {
            if (i == 0) return;   // a late tick is worse than none
            pending = i;
            pendingVol = vol;
            pendingAt = android.os.SystemClock.uptimeMillis();
            return;
        }
        final int id = ids[i];
        final SoundPool sp = pool;
        audio.post(() -> sp.play(id, vol, vol, 1, 0, 1f));
    }

    static void tick() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTick < 35 || now < quietUntil) return;   // a held key shouldn't buzz; a press already spoke
        lastTick = now;
        play(0, 0.32f);
    }

    /** A press, an open or a close: the focus that lands right after it stays quiet. */
    private static void spoke(int i, float vol) {
        quietUntil = android.os.SystemClock.uptimeMillis() + 180;
        play(i, vol);
    }

    static void pop() { spoke(1, 0.42f); }
    static void close() { spoke(2, 0.38f); }
    static void toggle(boolean on) { spoke(on ? 3 : 4, 0.40f); }
    static void bump() { play(5, 0.45f); }
    static void step() { play(6, 0.38f); }
    static void glass() { spoke(7, 0.40f); }

    // ---- synthesis ------------------------------------------------------------------------------------------

    private static final int RATE = 44100;

    private static float[] synth(String name) {
        switch (name) {
            case "tick": return tone(new float[]{2350}, new float[]{2100}, 0.016f, 0.0015f, 0.9f, 0.05f);
            case "pop": return tone(new float[]{520, 1040}, new float[]{760, 1520}, 0.060f, 0.004f, 0.75f, 0.25f);
            case "close": return tone(new float[]{720, 1440}, new float[]{430, 860}, 0.070f, 0.004f, 0.7f, 0.22f);
            case "on": return concat(tone(new float[]{660}, new float[]{660}, 0.045f, 0.003f, 0.7f, 0.1f), tone(new float[]{990}, new float[]{990}, 0.060f, 0.003f, 0.7f, 0.1f));
            case "off": return concat(tone(new float[]{990}, new float[]{990}, 0.045f, 0.003f, 0.7f, 0.1f), tone(new float[]{660}, new float[]{660}, 0.060f, 0.003f, 0.7f, 0.1f));
            case "bump": return tone(new float[]{170, 340}, new float[]{120, 240}, 0.060f, 0.002f, 0.9f, 0.3f);
            case "step": return tone(new float[]{1180}, new float[]{1120}, 0.030f, 0.002f, 0.8f, 0.08f);
            default: return chime();
        }
    }

    /** Sines gliding from `from` to `to` Hz (the second partial quieter), quick attack, exponential decay. */
    private static float[] tone(float[] from, float[] to, float dur, float attack, float gain, float second) {
        int n = Math.round(dur * RATE);
        float[] out = new float[n];
        double[] phase = new double[from.length];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE, k = i / (float) n;
            float env = (t < attack ? t / attack : 1f) * (float) Math.exp(-5.5 * k);
            float v = 0;
            for (int p = 0; p < from.length; p++) {
                double f = from[p] + (to[p] - from[p]) * k;
                phase[p] += 2 * Math.PI * f / RATE;
                v += (p == 0 ? 1f : second) * (float) Math.sin(phase[p]);
            }
            out[i] = gain * env * v;
        }
        return out;
    }

    /** The assistant: three soft partials rising together, a slow swell and a long fade. */
    private static float[] chime() {
        float dur = 0.42f;
        int n = Math.round(dur * RATE);
        float[] out = new float[n];
        float[] f = {523.25f, 783.99f, 1046.5f};
        float[] g = {0.5f, 0.32f, 0.18f};
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE, k = i / (float) n;
            float env = Math.min(1f, t / 0.06f) * (float) Math.exp(-3.2 * k);
            float v = 0;
            for (int p = 0; p < f.length; p++) v += g[p] * (float) Math.sin(2 * Math.PI * f[p] * (1 + 0.015 * k) * t);
            out[i] = 0.8f * env * v;
        }
        return out;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] o = new float[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static void write(File f, float[] s) throws IOException {
        int bytes = s.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + bytes).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(bytes);
        for (float v : s) b.putShort((short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32000));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
    }
}
