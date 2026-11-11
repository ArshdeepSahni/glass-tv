package com.gathin.glass.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Glass on a phone: the same conversation as on the TV (Talk), asked from the phone's page through the desktop
 * (PhoneServer), which only does so for phones the TV allowed. Only an app signed like this one may call it.
 *
 *   turns            the conversation so far
 *   send (text)      something said; Glass replies (and does what it says, as on the TV); extras "turn": answers
 *                    that question back
 *   play (pick JSON) plays a pick on the TV
 *   assist_entry     the desktop started: AssistEntry checks who answers the TV's assistant request
 */
public final class ChatProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    private static final String DESK = "content://com.gathin.tvhome.desk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        if (c == null) return null;
        if (c.getPackageManager().checkSignatures(Process.myUid(), Binder.getCallingUid()) != PackageManager.SIGNATURE_MATCH) return null;
        long id = Binder.clearCallingIdentity();
        try {
            synchronized (LOCK) {
                switch (method) {
                    case "turns":
                        return out(Talk.load(c));
                    case "send":
                        return out(send(c, arg == null ? "" : arg.trim(), extras == null ? -1 : extras.getInt("turn", -1)));
                    case "play":
                        return out(play(c, arg));
                    case "assist_entry":   // the desktop started: answer the TV's assistant request only where nothing else does
                        AssistEntry.check(c);
                        return new Bundle();
                    default:
                        return null;
                }
            }
        } catch (RuntimeException e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    private static Bundle out(Talk talk) {
        Bundle b = new Bundle();
        try {
            JSONArray turns = new JSONArray();
            for (int i = 0; i < talk.turns.size(); i++) {
                Talk.Turn t = talk.turns.get(i);
                JSONObject o = t.json();
                o.remove("pending");
                o.put("i", i);
                turns.put(o);
            }
            b.putString("json", new JSONObject().put("ok", true).put("turns", turns).toString());
        } catch (Exception e) {
            b.putString("json", "{\"ok\":false}");
        }
        return b;
    }

    /** As on the TV (AskActivity.send): the reply, what it did, and any question back. */
    private static Talk send(Context c, String said, int turn) {
        Talk talk = Talk.load(c);
        if (said.isEmpty()) return talk;
        Talk.Turn answering = turn >= 0 && turn < talk.turns.size() ? talk.turns.get(turn) : null;
        if (answering != null && "ask".equals(answering.who) && answering.picked == null) {
            answering.picked = said;
            if (answering.pending != null) {   // a yes or no to turning the TV off (or switching input)
                JSONObject act = answering.pending;
                answering.pending = null;
                if (!answering.items.isEmpty() && said.equals(answering.items.get(0))) {
                    Doer.Done d = Doer.run(c, new Brain.Act(act));
                    talk.add("done", null).items.add((d.ok ? "" : "✕ ") + d.label);
                    talk.remember("user", said);
                    talk.remember("assistant", "{\"say\":\"\",\"done\":\"" + d.label.replace("\"", "'") + "\"}");
                } else {
                    talk.add("glass", "Okay, leaving it as it is.");
                }
                talk.save(c);
                return talk;
            }
        } else {
            answering = null;
            talk.add("you", said);
        }
        Brain brain = new Brain(c);
        try {
            Bundle cx = c.getContentResolver().call(Uri.parse(DESK), "context", null, null);
            if (cx != null) brain.context(cx);
        } catch (RuntimeException ignored) {
            // the desktop can't say what's on the TV: answer without it
        }
        Brain.Reply r;
        try {
            r = brain.reply(talk, said);
        } catch (RuntimeException e) {
            r = new Brain.Reply();
            r.say = "Something went wrong. Try again.";
        }
        List<Doer.Done> done = new ArrayList<>();
        Brain.Act wait = null;
        for (Brain.Act a : r.acts) {
            if ("off".equals(a.act()) || ("input".equals(a.act()) && !Doer.saidInput(said))) {
                wait = a;
                continue;
            }
            done.add(Doer.run(c, a));
        }
        talk.remember("user", said);
        talk.remember("assistant", r.memo != null ? r.memo : "{\"say\":\"\",\"done\":" + done.size() + "}");
        boolean any = false;
        if (!done.isEmpty()) {
            Talk.Turn d = talk.add("done", null);
            for (Doer.Done x : done) d.items.add((x.ok ? "" : "✕ ") + x.label);
            any = true;
        }
        if (!r.say.isEmpty()) {
            talk.add("glass", r.say);
            any = true;
        }
        if (wait != null) {
            boolean off = "off".equals(wait.act());
            Talk.Turn q = talk.add("ask", off ? "Turn the TV off now?" : "Switch to " + wait.j.optString("value", "that input") + "?");
            q.items.add(off ? "Turn it off" : "Switch");
            q.items.add("Not now");
            q.pending = wait.j;
            any = true;
        } else if (r.ask != null && !r.choices.isEmpty()) {
            Talk.Turn q = talk.add("ask", r.ask);
            q.items.addAll(r.choices);
            any = true;
        } else if (r.ask != null) {
            talk.add("glass", r.ask);
            any = true;
        }
        if (r.pick != null) {
            talk.add("pick", null).pick = r.pick;
            any = true;
        }
        if (!r.next.isEmpty() && r.ask == null && wait == null) {
            talk.add("next", null).items.addAll(r.next);
        }
        if (!any) talk.add("glass", "Done.");
        talk.save(c);
        return talk;
    }

    private static Talk play(Context c, String pickJson) {
        Talk talk = Talk.load(c);
        try {
            JSONObject p = new JSONObject(pickJson);
            String app = p.optString("app", "");
            JSONObject act = new JSONObject().put("act", "search").put("app", app.isEmpty() ? "YouTube" : app).put("query", p.optString("title"));
            Doer.Done d = Doer.run(c, new Brain.Act(act));
            talk.add("done", null).items.add((d.ok ? "" : "✕ ") + d.label);
            talk.save(c);
        } catch (Exception ignored) {
            // not a pick
        }
        return talk;
    }

    // a provider for calls only
    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
