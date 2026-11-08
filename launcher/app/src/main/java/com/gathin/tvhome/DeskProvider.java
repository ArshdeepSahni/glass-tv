package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * What the assistant asks the desktop, through call() only, and only from an app signed like this one:
 *   "front"   is the desktop in front (then the assistant bends the same wallpaper), or which app and title is,
 *             and which look (day or night) the desktop is wearing.
 *   "assist"  the assistant opened ("open") or closed: its item in the menu bar lights while it's up.
 *   "context" what's on the TV right now, for an answer about it (Screen): read only when asked.
 *   "do"      one task for the conversation (Errands): returns what was done, for its receipt.
 */
public final class DeskProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        if (c == null) return null;
        if (c.getPackageManager().checkSignatures(Process.myUid(), Binder.getCallingUid()) != PackageManager.SIGNATURE_MATCH) return null;
        Bundle b = new Bundle();
        if ("front".equals(method)) {
            boolean desk = GlassHomeActivity.showing() || Glass.selfInFront();
            b.putBoolean("desk", desk);
            String f = Glass.front();
            if (!desk && f != null) {
                b.putString("front", f);
                CharSequence l = Glass.label(c, f);
                if (l != null) b.putString("app", l.toString());
                Threads.Thread t = Threads.of(c, f);
                if (t != null && t.title != null) b.putString("title", t.title);
            }
            b.putBoolean("night", Ui.night(Glass.prefs(c).getString(GlassHomeActivity.APPEARANCE, "auto")));
            b.putBoolean("sounds", Glass.prefs(c).getBoolean(Sound.PREF, true));
        } else if ("assist".equals(method)) {
            GlassHomeActivity.assistShowing("open".equals(arg));
        } else if ("context".equals(method)) {
            return Screen.context(c);   // what's on the TV, read now (or when the button was pressed)
        } else if ("do".equals(method)) {
            return Errands.run(c, arg);   // a task only the desktop can do (sleep timer, Wi-Fi, reminders, standby...)
        }
        return b;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
