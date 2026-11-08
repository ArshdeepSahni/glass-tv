package com.gathin.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Bluetooth from Control Center, in place: the devices this TV knows, which are connected, and sending the TV's
 * sound to a pair of headphones or a speaker (and back). The remote is shown but never touched, so it can't be
 * switched off by accident.
 */
final class Bt {
    private Bt() {
    }

    static final class Dev {
        BluetoothDevice device;
        String name;
        boolean audio, remote, connected;
    }

    static boolean on() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Turns Bluetooth on or off; false when the TV wouldn't. */
    static boolean set(boolean on) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return false;
            return on ? a.isEnabled() || a.enable() : !a.isEnabled() || a.disable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A Bluetooth remote is connected right now (turning Bluetooth off would leave it unheard). */
    static boolean remoteConnected() {
        for (Dev d : devices()) if (d.remote && d.connected) return true;
        return false;
    }

    static List<Dev> devices() {
        List<Dev> out = new ArrayList<>();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return out;
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded == null) return out;
            for (BluetoothDevice d : bonded) {
                Dev v = new Dev();
                v.device = d;
                v.name = d.getName() == null ? "Device" : d.getName();
                BluetoothClass k = d.getBluetoothClass();
                int major = k == null ? 0 : k.getMajorDeviceClass();
                v.audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO;
                v.remote = major == BluetoothClass.Device.Major.PERIPHERAL || v.name.toLowerCase(java.util.Locale.ROOT).contains("remote");
                v.connected = connected(d);
                out.add(v);
            }
        } catch (RuntimeException ignored) {
        }
        Collections.sort(out, (x, y) -> x.remote != y.remote ? (x.remote ? 1 : -1) : x.connected != y.connected ? (x.connected ? -1 : 1) : x.name.compareToIgnoreCase(y.name));
        return out;
    }

    private static boolean connected(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            Object r = m.invoke(d);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    interface Done {
        void done(boolean ok);
    }

    /** Sends the TV's sound to `d` (connect) or back to the TV's speakers (disconnect). */
    static void audio(Context c, BluetoothDevice d, boolean connect, Done done) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) { done.done(false); return; }
        try {
            a.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    boolean ok = false;
                    try {
                        Method m = proxy.getClass().getMethod(connect ? "connect" : "disconnect", BluetoothDevice.class);
                        Object r = m.invoke(proxy, d);
                        ok = r instanceof Boolean && (Boolean) r;
                    } catch (Exception ignored) {
                    }
                    try { a.closeProfileProxy(profile, proxy); } catch (RuntimeException ignored) { }
                    done.done(ok);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);
        } catch (RuntimeException e) {
            done.done(false);
        }
    }
}
