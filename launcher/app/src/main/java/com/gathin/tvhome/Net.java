package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wi-Fi from Control Center, in place: the switch, the networks in reach (strongest first, the one you're on at
 * the top), joining a saved network, an open one, or a new one with its password.
 */
final class Net {
    private Net() {
    }

    static final class Ap {
        String ssid, caps;
        int level, netId = -1;
        boolean secured, saved, current;
    }

    private static WifiManager wm(Context c) {
        return c.getApplicationContext().getSystemService(WifiManager.class);
    }

    static boolean on(Context c) {
        WifiManager w = wm(c);
        return w != null && w.isWifiEnabled();
    }

    @SuppressWarnings("deprecation")
    static boolean set(Context c, boolean on) {
        WifiManager w = wm(c);
        try {
            return w != null && w.setWifiEnabled(on);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Android lists the networks around, and names the one the TV is on, to apps only while Location is on. */
    @SuppressWarnings("deprecation")
    static boolean locationOn(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            LocationManager lm = c.getSystemService(LocationManager.class);
            try {
                return lm == null || lm.isLocationEnabled();
            } catch (RuntimeException e) {
                return true;
            }
        }
        // Android 9 and before: its Wi-Fi asks for the location mode, which any allowed provider turns on
        try {
            return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    /**
     * Location on, in its device-only kind: nothing goes to Google's location service, and nothing is asked (its
     * network kind puts Google's consent screen on the TV and is switched back off until someone agrees). A TV has no
     * GPS, so this finds no one's position; it only lets Android list the Wi-Fi around. Needs WRITE_SECURE_SETTINGS
     * (the installer grants it). True when Location is on.
     */
    @SuppressWarnings("deprecation")
    static boolean turnOnLocation(Context c) {
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false;
        ContentResolver cr = c.getContentResolver();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                try {   // Android's own switch, where it lets Glass use it
                    LocationManager lm = c.getSystemService(LocationManager.class);
                    LocationManager.class.getMethod("setLocationEnabledForUser", boolean.class, UserHandle.class)
                            .invoke(lm, true, Process.myUserHandle());
                } catch (Exception ignored) {
                }
                if (!locationOn(c)) Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, 3);   // LOCATION_MODE_ON
            } else {
                // "device only": Android 9 writes it as the providers allowed, gps (it is only asked for while off)
                Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_SENSORS_ONLY);
            }
        } catch (RuntimeException e) {
            return false;
        }
        return locationOn(c);
    }

    /** Glass can join networks and switch Wi-Fi itself (Android 10 keeps both for its own settings). */
    static boolean inPlace() {
        return Build.VERSION.SDK_INT < 29;
    }

    static String current(Context c) {
        if (Sample.on) return Sample.NETWORK;
        WifiManager w = wm(c);
        try {
            WifiInfo i = w == null ? null : w.getConnectionInfo();
            if (i == null || i.getNetworkId() == -1) return null;
            String s = i.getSSID();
            if (s == null) return null;
            s = s.replace("\"", "");
            return s.isEmpty() || s.contains("unknown ssid") ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void scan(Context c) {
        WifiManager w = wm(c);
        try {
            if (w != null) //noinspection deprecation
                w.startScan();
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    static List<Ap> list(Context c) {
        if (Sample.on) return Sample.networks();
        WifiManager w = wm(c);
        List<Ap> out = new ArrayList<>();
        if (w == null) return out;
        String cur = current(c);
        Map<String, Integer> saved = new LinkedHashMap<>();
        try {
            List<WifiConfiguration> cfg = w.getConfiguredNetworks();
            if (cfg != null) for (WifiConfiguration k : cfg) if (k.SSID != null) saved.put(k.SSID.replace("\"", ""), k.networkId);
        } catch (RuntimeException ignored) {
        }
        Map<String, Ap> best = new LinkedHashMap<>();
        try {
            List<ScanResult> rs = w.getScanResults();
            if (rs != null) for (ScanResult r : rs) {
                if (r.SSID == null || r.SSID.isEmpty()) continue;
                Ap a = best.get(r.SSID);
                if (a != null && a.level >= r.level) continue;
                a = new Ap();
                a.ssid = r.SSID;
                a.level = r.level;
                a.caps = r.capabilities == null ? "" : r.capabilities;
                a.secured = a.caps.contains("WPA") || a.caps.contains("WEP") || a.caps.contains("SAE") || a.caps.contains("PSK");
                Integer id = saved.get(r.SSID);
                a.saved = id != null;
                a.netId = id == null ? -1 : id;
                a.current = r.SSID.equals(cur);
                best.put(r.SSID, a);
            }
        } catch (RuntimeException ignored) {
        }
        if (cur != null && !best.containsKey(cur)) {
            Ap a = new Ap();
            a.ssid = cur;
            a.current = true;
            a.saved = true;
            a.level = -40;
            Integer id = saved.get(cur);
            a.netId = id == null ? -1 : id;
            best.put(cur, a);
        }
        out.addAll(best.values());
        Collections.sort(out, (x, y) -> x.current != y.current ? (x.current ? -1 : 1) : Integer.compare(y.level, x.level));
        return out;
    }

    /** Bars 0–3 for a signal level. */
    static int bars(int level) {
        return level >= -55 ? 3 : level >= -67 ? 2 : level >= -78 ? 1 : 0;
    }

    /** Joins a network: a saved one directly, an open one as is, a new secured one with `password`. */
    @SuppressWarnings("deprecation")
    static boolean join(Context c, Ap a, String password) {
        WifiManager w = wm(c);
        if (w == null) return false;
        try {
            int id = a.netId;
            if (id < 0) {
                WifiConfiguration k = new WifiConfiguration();
                k.SSID = "\"" + a.ssid + "\"";
                if (a.secured) {
                    if (password == null || password.length() < 8) return false;
                    k.preSharedKey = "\"" + password + "\"";
                    k.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                } else {
                    k.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                }
                id = w.addNetwork(k);
                if (id < 0) return false;
            }
            boolean ok = w.enableNetwork(id, true);
            w.reconnect();
            return ok;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
