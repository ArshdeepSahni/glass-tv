package com.gathin.glass.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glass's mind for a conversation. Two layers, cheapest first:
 *   0. Grammar: plain TV commands ("volume up", "pause", "HDMI 2") understood on the TV itself: instant, offline.
 *   1. The conversation model (Groq, then Cerebras, then OpenRouter, then Cloudflare; one that is out of free
 *      allowance or down is skipped for 10 minutes). It sees what's on the TV and the conversation so far, and
 *      replies with ONE JSON object: what to say, what to do on the TV, a question back when it needs one, a pick
 *      to watch, and follow-up questions. Glass shows all of it as the conversation and does the things.
 * Runs on a worker thread.
 */
final class Brain {
    /** One thing to do on the TV. */
    static final class Act {
        final JSONObject j;
        Act(JSONObject j) { this.j = j; }
        String act() { return j.optString("act"); }
    }

    /** Everything Glass replies with for one thing said. */
    static final class Reply {
        String say = "";
        final List<Act> acts = new ArrayList<>();
        String ask;
        final List<String> choices = new ArrayList<>();
        JSONObject pick;
        final List<String> next = new ArrayList<>();
        String via = "";
        String memo;   // what the model remembers of its own reply
    }

    /** What's on screen when the mic was pressed during playback (from the launcher). */
    String watchingTitle, watchingApp;
    private volatile String cxApp, cxScreen, cxFocused, cxPlaying, cxTv, cxRecent;

    private final Context ctx;
    private static final long[] coolUntil = new long[4];

    Brain(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    void context(android.os.Bundle b) {
        cxApp = b.getString("app");
        cxScreen = b.getString("screen");
        cxFocused = b.getString("focused");
        cxPlaying = b.getString("playing");
        cxTv = b.getString("tv");
        cxRecent = b.getString("recent");
    }

    /** The TV right now, as the model reads it. Empty when nothing is known. */
    private String tvNow() {
        StringBuilder s = new StringBuilder();
        if (cxApp != null) s.append("In front: ").append(cxApp).append('\n');
        if (cxPlaying != null) s.append("Playing: ").append(cxPlaying).append('\n');
        else if (watchingTitle != null) s.append("Playing: \"").append(watchingTitle).append('"').append(watchingApp != null ? " in " + watchingApp : "").append('\n');
        if (cxFocused != null) s.append("The remote is pointing at: ").append(cxFocused).append('\n');
        if (cxScreen != null) s.append("On screen, top to bottom ([focused] is where the remote is):\n").append(cxScreen).append('\n');
        if (cxTv != null) s.append("TV: ").append(cxTv).append('\n');
        if (cxRecent != null) s.append("Lately: ").append(cxRecent).append('\n');
        return s.toString();
    }

    Reply reply(Talk talk, String said) {
        Reply r = grammar(said.trim());
        if (r != null) return r;
        return converse(talk, said.trim());
    }

    // ---- layer 0: plain commands, on the TV ------------------------------------------------------------------

    private static final Pattern OPEN = Pattern.compile("^(?:please )?(?:open|launch|start|go to|switch to|put on) (?:the )?(.+?)(?: app)?$");
    private static final Pattern VOL_N = Pattern.compile("^(?:set )?(?:the )?volume (?:to |at )?(\\d{1,3})(?: percent)?$");
    private static final Pattern HDMI = Pattern.compile("^(?:switch to |go to |open )?(?:hdmi|input) ?(\\d)$");

    Reply grammar(String said) {
        String s = said.toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").trim();
        Matcher m;
        if ((m = HDMI.matcher(s)).matches()) return acts(act("input", "HDMI " + m.group(1)));
        if ((m = VOL_N.matcher(s)).matches()) return acts(act("volume", m.group(1)));
        switch (s) {
            case "volume up": case "louder": case "turn it up": case "turn up the volume": return acts(act("volume", "up"));
            case "volume down": case "quieter": case "softer": case "turn it down": case "turn down the volume": return acts(act("volume", "down"));
            case "mute": case "silence": case "mute the tv": return acts(act("volume", "mute"));
            case "unmute": return acts(act("volume", "unmute"));
            case "pause": case "pause it": return acts(act("media", "pause"));
            case "play": case "resume": case "continue": return acts(act("media", "play"));
            case "next": case "skip": case "next episode": return acts(act("media", "next"));
            case "previous": return acts(act("media", "previous"));
            case "home": case "go home": return acts(act("home", ""));
            default: break;
        }
        if ((m = OPEN.matcher(s)).matches()) {
            Actions.App a = Actions.findApp(ctx, m.group(1));
            if (a != null) return acts(act("open", a.label));
        }
        return null;
    }

    private static Act act(String a, String v) {
        try {
            JSONObject j = new JSONObject().put("act", a);
            if ("open".equals(a)) j.put("app", v);
            else if (!v.isEmpty()) j.put("value", v);
            return new Act(j);
        } catch (Exception e) {
            return null;
        }
    }

    private static Reply acts(Act... list) {
        Reply r = new Reply();
        for (Act a : list) if (a != null) r.acts.add(a);
        r.via = "on the TV";
        return r;
    }

    // ---- layer 1: the conversation ---------------------------------------------------------------------------

    private String system() {
        String now = new SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(new Date());
        StringBuilder names = new StringBuilder();
        for (Actions.App a : Actions.apps(ctx)) names.append(a.label).append(", ");
        String tv = tvNow();
        return "You are Glass, the assistant built into this living-room TV in India. It is " + now + ".\n"
                + "You are in a running conversation with the people on the sofa, and you can do things on the TV.\n"
                + (tv.isEmpty() ? "" : "What the TV shows right now (read the moment they spoke):\n" + tv)
                + "Installed apps: " + names + "\n"
                + "Reply with ONE JSON object and nothing else:\n"
                + "{\"say\":\"...\",\"do\":[...],\"ask\":{\"q\":\"...\",\"choices\":[\"...\"]},\"pick\":{...},\"next\":[\"...\"]}\n"
                + "say: what you say, read from across the room: 1-3 short sentences (up to 6 to explain or summarise). Plain text, "
                + "no markdown, lists or emoji. Direct, warm, specific; name names. Never generic. If unsure about something recent "
                + "(scores, news, prices), say so briefly.\n"
                + "do: things to do on the TV now, in order ([] if none). Each one of:\n"
                + " {\"act\":\"open\",\"app\":\"<installed app>\"}\n"
                + " {\"act\":\"search\",\"app\":\"YouTube|SmartTube|Play Store|<installed app>\",\"query\":\"...\"}\n"
                + " {\"act\":\"media\",\"value\":\"play|pause|next|previous|forward|rewind|stop\"}\n"
                + " {\"act\":\"volume\",\"value\":\"up|down|mute|unmute|<0-100>\"}\n"
                + " {\"act\":\"input\",\"value\":\"HDMI 1|HDMI 2|HDMI 3|AV|Live TV\"}\n"
                + " {\"act\":\"sleep\",\"minutes\":<n, 0 cancels>}\n"
                + " {\"act\":\"night_volume\",\"level\":<0-30, 0 turns it off>}  (a ceiling after 11 pm)\n"
                + " {\"act\":\"wifi\",\"value\":\"on|off\"}  {\"act\":\"bluetooth\",\"value\":\"on|off\"}\n"
                + " {\"act\":\"settings\",\"value\":\"display|network|sound|apps|assistant|about\"}\n"
                + " {\"act\":\"home\"}  {\"act\":\"controls\"}  {\"act\":\"apps\"}  {\"act\":\"files\"}\n"
                + " {\"act\":\"remind\",\"minutes\":<n>,\"text\":\"...\"}\n"
                + " {\"act\":\"url\",\"value\":\"https://...\"}\n"
                + " {\"act\":\"off\"}  (puts the TV to sleep; Glass asks them to confirm)\n"
                + "ask: only when you truly need something to go on (which one, how old, which app): a short question and 2-4 "
                + "choices to pick with the remote, each under 28 characters. When you ask, don't act yet, and put the question "
                + "only in ask (say stays empty or a few words; never the same question twice).\n"
                + "pick: when suggesting something to watch: {\"title\":\"...\",\"app\":\"Netflix|Prime Video|YouTube|JioHotstar\","
                + "\"meta\":\"Film · 1 h 32\" or \"Series · 45-min episodes\" (a length only if you are sure),\"why\":\"one short line\"}. "
                + "One title, never a list; "
                + "prefer titles you are sure are on that app in India; if unsure use YouTube.\n"
                + "next: 2-3 follow-ups they might say next, in their words (\"Show me one\", not \"Show one?\"), each under 28 "
                + "characters, that build on your reply (after a film: "
                + "\"Who's in it?\", \"Something lighter\"); never generic menu items like \"Open an app\" ([] if none fit).\n"
                + "\"This\", \"it\", \"he\", \"she\", \"the second one\", \"the show\" mean what is on screen or playing. "
                + "Remember the conversation: follow-ups build on what was said.";
    }

    private Reply converse(Talk talk, String said) {
        Reply r = new Reply();
        JSONArray msgs = new JSONArray();
        try {
            msgs.put(new JSONObject().put("role", "system").put("content", system()));
            for (String[] m : talk.memory) msgs.put(new JSONObject().put("role", m[0]).put("content", m[1]));
            msgs.put(new JSONObject().put("role", "user").put("content", said));
        } catch (Exception e) {
            r.say = "Something went wrong. Try again.";
            return r;
        }
        String out = null;
        String[][] chain = {
                {"Groq", "https://api.groq.com/openai/v1/chat/completions", BuildConfig.GROQ_API_KEY, BuildConfig.GROQ_MODEL},
                {"Cerebras", "https://api.cerebras.ai/v1/chat/completions", BuildConfig.CEREBRAS_API_KEY, BuildConfig.CEREBRAS_MODEL},
                {"OpenRouter", "https://openrouter.ai/api/v1/chat/completions", BuildConfig.OPENROUTER_API_KEY, BuildConfig.OPENROUTER_MODEL},
        };
        long now = System.currentTimeMillis();
        for (int i = 0; i < chain.length && out == null; i++) {
            if (chain[i][2].isEmpty() || now < coolUntil[i]) continue;
            try {
                out = Net.chat(chain[i][1], chain[i][2], chain[i][3], msgs, 700);
                r.via = chain[i][0] + " · " + shortModel(chain[i][3]);
            } catch (Exception e) {
                coolUntil[i] = now + 10 * 60_000L;
            }
        }
        if (out == null && hasCloudflare() && now >= coolUntil[3]) {
            try {
                out = Net.cloudflare(BuildConfig.CF_ACCOUNT_ID, BuildConfig.CF_API_TOKEN, BuildConfig.CF_CHAT_MODEL, msgs, 700, 25000);
                r.via = "Cloudflare · " + shortModel(BuildConfig.CF_CHAT_MODEL);
            } catch (Exception e) {
                coolUntil[3] = now + 10 * 60_000L;
            }
        }
        if (out == null) {
            boolean anyKey = !BuildConfig.GROQ_API_KEY.isEmpty() || !BuildConfig.CEREBRAS_API_KEY.isEmpty()
                    || !BuildConfig.OPENROUTER_API_KEY.isEmpty() || hasCloudflare();
            r.say = anyKey ? "I can't reach any of my models right now. Check the internet and try again."
                    : "I can control the TV, but answering needs the keys file.";
            return r;
        }
        parse(out, r);
        return r;
    }

    /** The model's JSON (with any stray words around it); if it isn't JSON, its words are the answer. */
    static void parse(String out, Reply r) {
        String text = out.replaceAll("(?s)<think>.*?</think>", "").trim();
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        JSONObject j = null;
        if (a >= 0 && b > a) {
            try {
                j = new JSONObject(text.substring(a, b + 1));
            } catch (Exception ignored) {
                j = null;
            }
        }
        if (j == null) {
            r.say = clean(text);
            r.memo = r.say;
            return;
        }
        r.say = clean(j.optString("say", ""));
        JSONArray d = j.optJSONArray("do");
        if (d != null) for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.optJSONObject(i);
            if (o != null && !o.optString("act").isEmpty()) r.acts.add(new Act(o));
        }
        JSONObject ask = j.optJSONObject("ask");
        if (ask != null && !ask.optString("q").trim().isEmpty()) {
            r.ask = clean(ask.optString("q"));
            JSONArray c = ask.optJSONArray("choices");
            if (c != null) for (int i = 0; i < c.length() && r.choices.size() < 4; i++) {
                String s = c.optString(i).trim();
                if (!s.isEmpty()) r.choices.add(s.length() > 40 ? s.substring(0, 40) : s);
            }
        }
        JSONObject pick = j.optJSONObject("pick");
        if (pick != null && !pick.optString("title").trim().isEmpty()) r.pick = pick;
        JSONArray n = j.optJSONArray("next");
        if (n != null) for (int i = 0; i < n.length() && r.next.size() < 3; i++) {
            String s = n.optString(i).trim();
            if (!s.isEmpty()) r.next.add(s.length() > 40 ? s.substring(0, 40) : s);
        }
        r.memo = j.toString();
    }

    private boolean hasCloudflare() {
        return !BuildConfig.CF_ACCOUNT_ID.isEmpty() && !BuildConfig.CF_API_TOKEN.isEmpty();
    }

    private static String shortModel(String m) {
        String s = m.substring(m.lastIndexOf('/') + 1).replace(":free", "");
        return s.replace("-instruct", "").replace("-fp8-fast", "").replace("-versatile", "");
    }

    /** Models sometimes slip into markdown; a TV shows plain sentences. */
    static String clean(String t) {
        return t.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("\\*\\*|__|`|^#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*•]\\s+", "")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }
}
