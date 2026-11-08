package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Things the assistant asks the TV to do that only the desktop can (it holds the remote helper and the permissions):
 * the sleep timer, night volume, Wi-Fi and Bluetooth, Glass's own places (Settings, Control Center, All apps,
 * Files), reminders over anything that's playing, and standby. Called through DeskProvider ("do", one JSON act);
 * each returns what it did, in a few words for the conversation's receipt.
 */
final class Errands {
    private Errands() {
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static Bundle run(Context c, String json) {
        Bundle out = new Bundle();
        final String[] label = {null};
        try {
            JSONObject j = new JSONObject(json == null ? "{}" : json);
            CountDownLatch done = new CountDownLatch(1);
            MAIN.post(() -> {   // timers and overlays live on the main thread
                try {
                    label[0] = act(c, j);
                } catch (RuntimeException e) {
                    label[0] = null;
                } finally {
                    done.countDown();
                }
            });
            done.await(2500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // a bad act: nothing done
        }
        out.putBoolean("ok", label[0] != null);
        if (label[0] != null) out.putString("label", label[0]);
        return out;
    }

    private static String act(Context c, JSONObject j) {
        String a = j.optString("act");
        String v = j.optString("value");
        HomeKeyService svc = HomeKeyService.current();
        switch (a) {
            case "sleep": {
                int m = j.optInt("minutes", parse(v, -1));
                if (m < 0) return null;
                if (svc == null && m > 0) return null;   // the timer lives in the remote helper
                SleepTimer.set(m);
                return m == 0 ? "Sleep timer off" : "Sleep in " + m + " min";
            }
            case "night_volume": {
                int n = Math.max(0, Math.min(100, j.optInt("level", parse(v, 0))));
                Glass.prefs(c).edit().putInt(Glass.NIGHT_CEILING, n).apply();
                return n == 0 ? "Night volume off" : "Night volume " + n + " after 11 pm";
            }
            case "wifi": {
                boolean on = !"off".equalsIgnoreCase(v);
                return Net.set(c, on) ? (on ? "Wi‑Fi on" : "Wi‑Fi off") : null;
            }
            case "bluetooth": {
                boolean on = !"off".equalsIgnoreCase(v);
                BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
                if (b == null) return null;
                boolean ok = on ? b.enable() : b.disable();
                return ok || b.isEnabled() == on ? (on ? "Bluetooth on" : "Bluetooth off") : null;
            }
            case "settings": {
                String section = v.isEmpty() ? null : v.toLowerCase(Locale.ROOT);
                GlassKeys.open(c, "settings" + (section != null ? ":" + section : ""));
                return "Settings" + (section != null ? " · " + Character.toUpperCase(section.charAt(0)) + section.substring(1) : "");
            }
            case "controls":
                if (GlassHomeActivity.showing() || svc == null) GlassKeys.open(c, "controls");
                else Hud.of(svc).controls();
                return "Control Center";
            case "apps":
                GlassKeys.open(c, "apps");
                return "All apps";
            case "files":
                GlassKeys.open(c, "files");
                return "Files";
            case "remind": {
                int m = j.optInt("minutes", parse(v, -1));
                String text = j.optString("text", "").trim();
                if (m <= 0 || m > 24 * 60) return null;
                long at = System.currentTimeMillis() + m * 60_000L;
                Remind.schedule(c, at, text.isEmpty() ? "Your reminder" : text);
                return "Reminder at " + new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(at));
            }
            case "off":
                if (svc == null) return null;
                // a moment for the assistant to fold away first
                MAIN.postDelayed(() -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), 1500);
                return "Turning the TV off";
            default:
                return null;
        }
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Reminders: an alarm, then Glass's note over whatever is on (Remind.Receiver). */
    static final class Remind {
        private Remind() {
        }

        static void schedule(Context c, long at, String text) {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            Intent i = new Intent(c, RemindReceiver.class).putExtra("text", text);
            int id = (int) (at / 1000 % Integer.MAX_VALUE);
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        }
    }
}
