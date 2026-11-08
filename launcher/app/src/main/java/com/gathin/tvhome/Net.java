package com.gathin.tvhome;

import android.content.Context;
import android.net.wifi.ScanResult;
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

    static String current(Context c) {
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
