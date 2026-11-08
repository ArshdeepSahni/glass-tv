package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Glass IV: hears what the apps say is playing. Android only shows other apps' media sessions to an enabled
 * notification listener (granted once by the installer with `cmd notification allow_listener`); nothing about
 * notifications themselves is read or kept. Each session's title, artwork and position become that app's thread;
 * a pause that lasts eight seconds while the app is in front opens the Pause Room over it.
 */
public final class ThreadListener extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession_Token, Watcher> watched = new HashMap<>();
    private MediaSessionManager sessions;
    private MediaSessionManager.OnActiveSessionsChangedListener changed;

    /** MediaSession.Token wrapper keyed by package + identity, so a controller's callbacks are registered once. */
    private static final class MediaSession_Token {
        final Object token;
        MediaSession_Token(Object token) { this.token = token; }
        @Override public boolean equals(Object o) { return o instanceof MediaSession_Token && ((MediaSession_Token) o).token.equals(token); }
        @Override public int hashCode() { return token.hashCode(); }
    }

    private final class Watcher extends MediaController.Callback {
        final MediaController controller;
        final String pkg;
        Watcher(MediaController c) { controller = c; pkg = c.getPackageName(); }

        @Override public void onMetadataChanged(MediaMetadata md) {
            Threads.fromSession(ThreadListener.this, pkg, md, controller.getPlaybackState());
        }

        @Override public void onPlaybackStateChanged(PlaybackState st) {
            Threads.fromSession(ThreadListener.this, pkg, controller.getMetadata(), st);
        }

        @Override public void onSessionDestroyed() {
            Threads.fromSession(ThreadListener.this, pkg, null, controller.getPlaybackState());
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        ComponentName me = new ComponentName(this, ThreadListener.class);
        changed = this::track;
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, handler);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException notGranted) {
            // the installer grants this; without it the shelf falls back to watch time
        }
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        List<MediaSession_Token> live = new ArrayList<>();
        for (MediaController c : controllers) {
            MediaSession_Token key = new MediaSession_Token(c.getSessionToken());
            live.add(key);
            if (watched.containsKey(key)) continue;
            Watcher w = new Watcher(c);
            c.registerCallback(w, handler);
            watched.put(key, w);
            Threads.fromSession(this, w.pkg, c.getMetadata(), c.getPlaybackState());
        }
        for (MediaSession_Token key : new ArrayList<>(watched.keySet())) {
            if (!live.contains(key)) {
                Watcher w = watched.remove(key);
                if (w != null) w.controller.unregisterCallback(w);
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null && changed != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Watcher w : watched.values()) w.controller.unregisterCallback(w);
        watched.clear();
        super.onListenerDisconnected();
    }
}
