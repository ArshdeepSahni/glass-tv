package com.gathin.glass.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The conversation: everything said and done, in order, kept on the TV for the evening (until 30 quiet minutes have
 * passed) so a question later picks up where it left off. New chat clears it. Nothing leaves the TV from here; the
 * model sees the recent turns only when it is asked something.
 *
 *   you    what someone said (or picked)
 *   glass  what Glass said
 *   done   what Glass did on the TV, as receipts
 *   ask    a question back, with replies to pick (and, once answered, which one)
 *   pick   one thing to watch, with where it is
 *   next   follow-up questions to pick
 */
final class Talk {
    static final long FRESH_MS = 30 * 60_000L;
    private static final int KEEP = 60, MEMORY = 16;

    static final class Turn {
        final String who;
        String text;
        final List<String> items = new ArrayList<>();
        JSONObject pick;
        String picked;
        JSONObject pending;   // an act that waits for a yes (turning the TV off)

        Turn(String who, String text) {
            this.who = who;
            this.text = text;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject().put("who", who);
            if (text != null) o.put("text", text);
            if (!items.isEmpty()) o.put("items", new JSONArray(items));
            if (pick != null) o.put("pick", pick);
            if (picked != null) o.put("picked", picked);
            if (pending != null) o.put("pending", pending);
            return o;
        }

        static Turn from(JSONObject o) {
            Turn t = new Turn(o.optString("who"), o.has("text") ? o.optString("text") : null);
            JSONArray a = o.optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) t.items.add(a.optString(i));
            t.pick = o.optJSONObject("pick");
            t.picked = o.has("picked") ? o.optString("picked") : null;
            t.pending = o.optJSONObject("pending");
            return t;
        }
    }

    final List<Turn> turns = new ArrayList<>();
    /** What the model remembers: {role, content}, the newest last (assistant content is the JSON it replied). */
    final List<String[]> memory = new ArrayList<>();
    long updatedAt;

    Turn add(String who, String text) {
        Turn t = new Turn(who, text);
        turns.add(t);
        while (turns.size() > KEEP) turns.remove(0);
        updatedAt = System.currentTimeMillis();
        return t;
    }

    void remember(String role, String content) {
        memory.add(new String[]{role, content});
        while (memory.size() > MEMORY) memory.remove(0);
    }

    /** The last question back that is still open (nobody picked yet), or null. */
    Turn openQuestion() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn t = turns.get(i);
            if ("you".equals(t.who)) return null;
            if ("ask".equals(t.who) && t.picked == null) return t;
        }
        return null;
    }

    boolean isEmpty() {
        return turns.isEmpty();
    }

    // ---- kept on the TV ---------------------------------------------------------------------------------------

    private static File file(Context c) {
        return new File(c.getFilesDir(), "talk.json");
    }

    static Talk load(Context c) {
        Talk t = new Talk();
        try {
            File f = file(c);
            if (!f.exists()) return t;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            long at = o.optLong("at");
            if (System.currentTimeMillis() - at > FRESH_MS) {   // last evening's chat: start fresh
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return t;
            }
            t.updatedAt = at;
            JSONArray turns = o.optJSONArray("turns");
            if (turns != null) for (int i = 0; i < turns.length(); i++) t.turns.add(Turn.from(turns.getJSONObject(i)));
            JSONArray mem = o.optJSONArray("memory");
            if (mem != null) for (int i = 0; i < mem.length(); i++) {
                JSONArray m = mem.getJSONArray(i);
                t.memory.add(new String[]{m.optString(0), m.optString(1)});
            }
        } catch (Exception e) {
            return new Talk();
        }
        return t;
    }

    void save(Context c) {
        try {
            JSONArray ts = new JSONArray();
            for (Turn t : turns) ts.put(t.json());
            JSONArray mem = new JSONArray();
            for (String[] m : memory) mem.put(new JSONArray().put(m[0]).put(m[1]));
            JSONObject o = new JSONObject().put("at", updatedAt == 0 ? System.currentTimeMillis() : updatedAt).put("turns", ts).put("memory", mem);
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception ignored) {
            // the conversation is a convenience: losing it is fine
        }
    }

    void clear(Context c) {
        turns.clear();
        memory.clear();
        updatedAt = 0;
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }
}
