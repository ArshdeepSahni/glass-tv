package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Glass's own line glyphs, drawn on a 24-unit grid with round caps and joins (the same set the design boards use).
 * Each glyph is a stroked path plus an optional filled part (dots, play triangles). Scales to any bounds; the
 * stroke scales with it, so a 16 px glyph has a 1.27 px line, as on the boards.
 */
final class Glyph extends Drawable {
    private static final Map<String, String[]> P = new HashMap<>();
    private static final Map<String, Path[]> CACHE = new HashMap<>();

    static {
        P.put("search", new String[]{"M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0z M16 16l4.5 4.5", ""});
        P.put("wifi", new String[]{"M2.5 9a14 14 0 0 1 19 0 M5.8 12.4a9.4 9.4 0 0 1 12.4 0 M9.2 15.8a4.7 4.7 0 0 1 5.6 0 M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z", "M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z"});
        P.put("wifi_off", new String[]{"M2.5 9a14 14 0 0 1 19 0 M5.8 12.4a9.4 9.4 0 0 1 12.4 0 M9.2 15.8a4.7 4.7 0 0 1 5.6 0 M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z M4 4l16 16", "M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z"});
        P.put("volume", new String[]{"M4 9.5h3.5L12 6v12l-4.5-3.5H4z M15.5 9a4.2 4.2 0 0 1 0 6 M18 6.6a7.6 7.6 0 0 1 0 10.8", ""});
        P.put("mute", new String[]{"M4 9.5h3.5L12 6v12l-4.5-3.5H4z M16 9.5l5 5 M21 9.5l-5 5", ""});
        P.put("controls", new String[]{"M4 7h9 M17 7h3 M13 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M4 17h3 M11 17h9 M7 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", ""});
        P.put("assist", new String[]{"M4.5 12a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0z M9.4 12a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0z", "M9.4 12a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0z"});
        P.put("mark", new String[]{"M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z M6.8 9a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z", "M6.8 9a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z"});
        P.put("input", new String[]{"M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z M8 21h8 M12 17v4", ""});
        P.put("chev_l", new String[]{"M14.5 6l-6 6 6 6", ""});
        P.put("chev_r", new String[]{"M9.5 6l6 6-6 6", ""});
        P.put("grid", new String[]{"M5.6 4h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3a1.6 1.6 0 0 1 1.6 -1.6z M15.1 4h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3a1.6 1.6 0 0 1 1.6 -1.6z M5.6 13.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3a1.6 1.6 0 0 1 1.6 -1.6z M15.1 13.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3a1.6 1.6 0 0 1 1.6 -1.6z", ""});
        P.put("list", new String[]{"M8 6.5h12 M8 12h12 M8 17.5h12 M3.5 6.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M3.5 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M3.5 17.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", "M3.5 6.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M3.5 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M3.5 17.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"});
        P.put("clock", new String[]{"M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0z M12 7.5V12l3 2", ""});
        P.put("down", new String[]{"M12 4v11 M7 11l5 5 5-5 M5 20h14", ""});
        P.put("film", new String[]{"M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z M10 9.5v5l4.3-2.5z", "M10 9.5v5l4.3-2.5z"});
        P.put("photo", new String[]{"M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z M7.4 10a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z M5 17l5-4.5 3.5 3 2.5-2 3 3.5", ""});
        P.put("music", new String[]{"M9 18V6.5l10-2V16 M5 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M15 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", ""});
        P.put("drive", new String[]{"M6 7h12a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-5a2.5 2.5 0 0 1 2.5 -2.5z M15.5 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z M7 12h5", "M15.5 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"});
        P.put("tv", new String[]{"M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z M8 21h8", ""});
        P.put("moon", new String[]{"M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z", ""});
        P.put("bt", new String[]{"M7 7l10 10-5 4V3l5 4L7 17", ""});
        P.put("sun", new String[]{"M8.4 12a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0z M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4", ""});
        P.put("text", new String[]{"M5 18L9.5 6h1L15 18 M6.8 13.5h6.4 M16 18l2.4-6h.6l2.4 6", ""});
        P.put("sliders", new String[]{"M6 4v16 M12 4v16 M18 4v16 M5 13h2a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z M11 7h2a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z M17 15h2a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z", "M5 13h2a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z M11 7h2a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z M17 15h2a1 1 0 0 1 1 1v1a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-1a1 1 0 0 1 1 -1z"});
        P.put("apps", new String[]{"M4.5 6.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M10 6.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M15.5 6.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M4.5 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M15.5 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M4.5 17.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M10 17.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M15.5 17.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", ""});
        P.put("globe", new String[]{"M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0z M4 12h16 M12 4a12 12 0 0 1 0 16a12 12 0 0 1 0-16z", ""});
        P.put("play", new String[]{"M8 5.5v13l10.5-6.5z", "M8 5.5v13l10.5-6.5z"});
        P.put("live", new String[]{"M6 6h12a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-7a2.5 2.5 0 0 1 2.5 -2.5z M8 3.5l4 2.5 4-2.5", ""});
        P.put("cloud", new String[]{"M7 18h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.4 1.6A3.3 3.3 0 0 0 7 18z", ""});
        P.put("doc", new String[]{"M7 3.5h7l4 4V20a.5.5 0 0 1-.5.5h-10A.5.5 0 0 1 6.5 20V4a.5.5 0 0 1 .5-.5z M14 3.5V8h4", ""});
        P.put("folder", new String[]{"M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z", ""});
        P.put("check", new String[]{"M5 12.5l4.5 4.5L19 7.5", ""});
        P.put("power", new String[]{"M12 4v8 M7.4 7.2a6.8 6.8 0 1 0 9.2 0", ""});
        P.put("info", new String[]{"M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z M12 11v5.5 M11 7.8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", "M11 7.8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"});
        P.put("help", new String[]{"M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z M9.7 9.7a2.4 2.4 0 1 1 3.3 2.2c-.6.3-1 .8-1 1.5v.5 M11 16.8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", "M11 16.8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"});
        P.put("home", new String[]{"M4 11l8-6.5 8 6.5v8.5a.5.5 0 0 1-.5.5H15v-6H9v6H4.5a.5.5 0 0 1-.5-.5z", ""});
        P.put("phone", new String[]{"M9.6 3h4.8a2.6 2.6 0 0 1 2.6 2.6v12.8a2.6 2.6 0 0 1 -2.6 2.6h-4.8a2.6 2.6 0 0 1 -2.6 -2.6v-12.8a2.6 2.6 0 0 1 2.6 -2.6z M11 18h2", ""});
        P.put("remote", new String[]{"M12 2.5h0a4.5 4.5 0 0 1 4.5 4.5v10a4.5 4.5 0 0 1 -4.5 4.5h-0a4.5 4.5 0 0 1 -4.5 -4.5v-10a4.5 4.5 0 0 1 4.5 -4.5z M10 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M10.5 13.5h3 M10.5 16.5h3", ""});
        P.put("mic", new String[]{"M12 3.5h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z M5.5 11.5a6.5 6.5 0 0 0 13 0 M12 18v2.5", ""});
        P.put("back", new String[]{"M9 7L4.5 11.5 9 16 M5 11.5h9.5a5 5 0 0 1 0 10H12", ""});
        P.put("up", new String[]{"M12 19V5 M6.5 10.5L12 5l5.5 5.5", ""});
        P.put("menu", new String[]{"M5 7h14 M5 12h14 M5 17h14", ""});
        P.put("timer", new String[]{"M4.5 13a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0z M12 9v4l2.5 1.5 M9.5 2.5h5", ""});
        P.put("pin", new String[]{"M12 21v-6 M8 3.5h8l-1.5 5 3 3.5H6.5l3-3.5z", ""});
        P.put("unpin", new String[]{"M12 21v-6 M8 3.5h8l-1.5 5 3 3.5H6.5l3-3.5z M4 4l16 16", ""});
        P.put("left", new String[]{"M19 12H5 M10.5 6.5L5 12l5.5 5.5", ""});
        P.put("right", new String[]{"M5 12h14 M13.5 6.5L19 12l-5.5 5.5", ""});
        P.put("lock", new String[]{"M8 10.5h8a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1 -2.5 2.5h-8a2.5 2.5 0 0 1 -2.5 -2.5v-5a2.5 2.5 0 0 1 2.5 -2.5z M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5", ""});
        P.put("headphones", new String[]{"M4.5 15v-3a7.5 7.5 0 0 1 15 0v3 M5 14h1a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1 -1.5 1.5h-1a1.5 1.5 0 0 1 -1.5 -1.5v-3.5a1.5 1.5 0 0 1 1.5 -1.5z M18 14h1a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1 -1.5 1.5h-1a1.5 1.5 0 0 1 -1.5 -1.5v-3.5a1.5 1.5 0 0 1 1.5 -1.5z", ""});
        P.put("speaker", new String[]{"M9 3.5h6a3 3 0 0 1 3 3v11a3 3 0 0 1 -3 3h-6a3 3 0 0 1 -3 -3v-11a3 3 0 0 1 3 -3z M9 14.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z M11 8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", "M11 8a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"});
        P.put("wifi2", new String[]{"M5.8 12.4a9.4 9.4 0 0 1 12.4 0 M9.2 15.8a4.7 4.7 0 0 1 5.6 0 M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z", "M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z"});
        P.put("rain", new String[]{"M7 15h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.4 1.6A3.3 3.3 0 0 0 7 15z M9 18l-1 2.5 M13 18l-1 2.5 M17 18l-1 2.5", ""});
        P.put("snow", new String[]{"M7 15h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.4 1.6A3.3 3.3 0 0 0 7 15z M8.5 19h.01 M12 20.5h.01 M15.5 19h.01", ""});
        P.put("storm", new String[]{"M7 14.5h10a4 4 0 0 0 .5-8 6 6 0 0 0-11.4 1.6A3.3 3.3 0 0 0 7 14.5z M12.5 15.5l-2 3.5h3l-2 3.5", ""});
        P.put("fog", new String[]{"M5 9h14 M3.5 13h17 M6 17h12", ""});
        P.put("sun_cloud", new String[]{"M8.5 3.2v1.3 M3.7 5l.9.9 M2.6 9.5h1.3 M12.3 6.6a3.7 3.7 0 0 0-6.5 3.2 M9 19.5h8.5a3.5 3.5 0 0 0 .4-7 5.2 5.2 0 0 0-9.9 1.4A2.9 2.9 0 0 0 9 19.5z", ""});
        P.put("close", new String[]{"M6.5 6.5l11 11 M17.5 6.5l-11 11", ""});
        P.put("trash", new String[]{"M4.5 7h15 M9.5 7v-1.6a1.4 1.4 0 0 1 1.4 -1.4h2.2a1.4 1.4 0 0 1 1.4 1.4v1.6 M6.5 7l.9 11.6a2 2 0 0 0 2 1.9h5.2a2 2 0 0 0 2 -1.9l.9 -11.6 M10.3 10.8v5.6 M13.7 10.8v5.6", ""});
        P.put("pause", new String[]{"M8.5 5.5v13 M15.5 5.5v13", ""});
        P.put("next", new String[]{"M5.5 6.2v11.6l8.6-5.8z M17.5 6v12", "M5.5 6.2v11.6l8.6-5.8z"});
        P.put("prev", new String[]{"M18.5 6.2v11.6l-8.6-5.8z M6.5 6v12", "M18.5 6.2v11.6l-8.6-5.8z"});
        P.put("move", new String[]{"M12 3.5v17 M3.5 12h17 M9.5 6l2.5-2.5 2.5 2.5 M9.5 18l2.5 2.5 2.5-2.5 M6 9.5l-2.5 2.5 2.5 2.5 M18 9.5l2.5 2.5-2.5 2.5", ""});
        P.put("resize", new String[]{"M14 4.5h5.5v5.5 M19.5 4.5l-6.5 6.5 M10 19.5h-5.5v-5.5 M4.5 19.5l6.5-6.5", ""});
        P.put("location", new String[]{"M12 20.5c-3.6-3.9-6.2-7.1-6.2-10.3a6.2 6.2 0 0 1 12.4 0c0 3.2-2.6 6.4-6.2 10.3z M10 10.2a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z", ""});
        P.put("plus", new String[]{"M12 5v14 M5 12h14", ""});
        P.put("minus", new String[]{"M5 12h14", ""});
        P.put("edit", new String[]{"M4.5 19.5l1-4.2 10.6-10.6a1.8 1.8 0 0 1 2.6 0l.6 .6a1.8 1.8 0 0 1 0 2.6l-10.6 10.6z M14.3 6.5l3.2 3.2", ""});
        P.put("keyboard", new String[]{"M4.5 6.5h15a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-15a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z M6.6 10h.8 M10.1 10h.8 M13.6 10h.8 M17.1 10h.8 M8.5 14h7", ""});
        P.put("cast", new String[]{"M3.5 18.6a1.9 1.9 0 0 1 1.9 1.9 M3.5 15a5.5 5.5 0 0 1 5.5 5.5 M3.5 11.5a9 9 0 0 1 9 9 M3.5 8.5v-2a2 2 0 0 1 2-2h13a2 2 0 0 1 2 2v11a2 2 0 0 1 -2 2h-3.5", ""});
        P.put("send", new String[]{"M4 11.5l16-7-6.5 16-2.6-6.6z M10.9 13.9L20 4.5", ""});
        P.put("sparkle", new String[]{"M12 3.5l1.7 5.1 5.1 1.7-5.1 1.7-1.7 5.1-1.7-5.1-5.1-1.7 5.1-1.7z M18.5 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z", ""});
        P.put("pointer", new String[]{"M6.5 3.5l11.5 7.4-5.1 1.4 3 6-2.5 1.2-2.9-6-4 3.6z", ""});
        P.put("swap", new String[]{"M7 7.5h12 M15.5 4l3.5 3.5-3.5 3.5 M17 16.5H5 M8.5 13l-3.5 3.5 3.5 3.5", ""});
        P.put("rewind", new String[]{"M11.5 7v10l-7-5z M20 7v10l-7-5z", "M11.5 7v10l-7-5z M20 7v10l-7-5z"});
        P.put("forward", new String[]{"M4 7v10l7-5z M12.5 7v10l7-5z", "M4 7v10l7-5z M12.5 7v10l7-5z"});
        P.put("wifi1", new String[]{"M9.2 15.8a4.7 4.7 0 0 1 5.6 0 M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z", "M10.9 19a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z"});
    }

    private final Path stroke, fill;
    private final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG), fp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    private Glyph(Path stroke, Path fill, int color, float width) {
        this.stroke = stroke;
        this.fill = fill;
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeCap(Paint.Cap.ROUND);
        sp.setStrokeJoin(Paint.Join.ROUND);
        sp.setStrokeWidth(width);
        sp.setColor(color);
        fp.setStyle(Paint.Style.FILL);
        fp.setColor(color);
    }

    static Glyph of(String name, int color) {
        return of(name, color, 1.9f);
    }

    static Glyph of(String name, int color, float width) {
        Path[] p = paths(name);
        return new Glyph(p[0], p[1], color, width);
    }

    static boolean has(String name) {
        return P.containsKey(name);
    }

    private static synchronized Path[] paths(String name) {
        Path[] p = CACHE.get(name);
        if (p != null) return p;
        String[] d = P.get(name);
        if (d == null) d = new String[]{"", ""};
        p = new Path[]{parse(d[0]), d[1].isEmpty() ? null : parse(d[1])};
        CACHE.put(name, p);
        return p;
    }

    void setColor(int color) {
        sp.setColor(color);
        fp.setColor(color);
        applyAlpha();
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        c.save();
        c.translate(b.left, b.top);
        c.scale(b.width() / 24f, b.height() / 24f);
        c.drawPath(stroke, sp);
        if (fill != null) c.drawPath(fill, fp);
        c.restore();
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        applyAlpha();
        invalidateSelf();
    }

    private void applyAlpha() {
        int base = sp.getColor() >>> 24;
        // the colour's own alpha is kept; the drawable alpha multiplies it
        sp.setAlpha(base * alpha / 255);
        fp.setAlpha(base * alpha / 255);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        sp.setColorFilter(cf);
        fp.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // ---- a small SVG path parser (M L H V C S Q T A Z, absolute and relative) -----------------------------

    static Path parse(String d) {
        Path p = new Path();
        int[] at = {0};
        int n = d.length();
        char cmd = 0;
        float cx = 0, cy = 0, sx = 0, sy = 0, lx = 0, ly = 0;   // current point, subpath start, last control
        char last = 0;
        while (true) {
            skip(d, at);
            if (at[0] >= n) break;
            char ch = d.charAt(at[0]);
            if (Character.isLetter(ch) && ch != 'e' && ch != 'E') {
                cmd = ch;
                at[0]++;
                if (cmd == 'z' || cmd == 'Z') {
                    p.close();
                    cx = sx;
                    cy = sy;
                    last = cmd;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    p.moveTo(x, y);
                    cx = sx = x;
                    cy = sy = y;
                    cmd = rel ? 'l' : 'L';     // further pairs are lines
                    break;
                }
                case 'L': {
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    p.lineTo(x, y);
                    cx = x;
                    cy = y;
                    break;
                }
                case 'H': {
                    float x = num(d, at) + ox;
                    p.lineTo(x, cy);
                    cx = x;
                    break;
                }
                case 'V': {
                    float y = num(d, at) + oy;
                    p.lineTo(cx, y);
                    cy = y;
                    break;
                }
                case 'C': {
                    float x1 = num(d, at) + ox, y1 = num(d, at) + oy, x2 = num(d, at) + ox, y2 = num(d, at) + oy;
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lx = x2;
                    ly = y2;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'S': {
                    boolean prev = last == 'C' || last == 'c' || last == 'S' || last == 's';
                    float x1 = prev ? 2 * cx - lx : cx, y1 = prev ? 2 * cy - ly : cy;
                    float x2 = num(d, at) + ox, y2 = num(d, at) + oy, x = num(d, at) + ox, y = num(d, at) + oy;
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lx = x2;
                    ly = y2;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'Q': {
                    float x1 = num(d, at) + ox, y1 = num(d, at) + oy, x = num(d, at) + ox, y = num(d, at) + oy;
                    p.quadTo(x1, y1, x, y);
                    lx = x1;
                    ly = y1;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'T': {
                    boolean prev = last == 'Q' || last == 'q' || last == 'T' || last == 't';
                    float x1 = prev ? 2 * cx - lx : cx, y1 = prev ? 2 * cy - ly : cy;
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    p.quadTo(x1, y1, x, y);
                    lx = x1;
                    ly = y1;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'A': {
                    float rx = num(d, at), ry = num(d, at), rot = num(d, at);
                    boolean large = flag(d, at), sweep = flag(d, at);
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    arc(p, cx, cy, rx, ry, rot, large, sweep, x, y);
                    cx = x;
                    cy = y;
                    break;
                }
                default:
                    at[0]++;   // unknown: skip a character rather than loop forever
            }
            last = cmd;
        }
        return p;
    }

    private static void skip(String d, int[] at) {
        while (at[0] < d.length()) {
            char c = d.charAt(at[0]);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') at[0]++;
            else break;
        }
    }

    private static boolean flag(String d, int[] at) {
        skip(d, at);
        char c = d.charAt(at[0]++);
        return c == '1';
    }

    private static float num(String d, int[] at) {
        skip(d, at);
        int i = at[0], n = d.length();
        int start = i;
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') { i++; continue; }
            if (c == '.' && !dot) { dot = true; i++; continue; }
            break;
        }
        if (i < n && (d.charAt(i) == 'e' || d.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (d.charAt(j) == '-' || d.charAt(j) == '+')) j++;
            if (j < n && Character.isDigit(d.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(d.charAt(i))) i++;
            }
        }
        at[0] = i;
        if (i == start) { at[0]++; return 0; }
        return Float.parseFloat(d.substring(start, i));
    }

    /** SVG elliptical arc (endpoint form) as cubic Béziers, one per quarter turn at most (SVG 1.1, F.6.5). */
    private static void arc(Path p, float x1, float y1, float rx, float ry, float rotDeg, boolean large, boolean sweep, float x2, float y2) {
        if (x1 == x2 && y1 == y2) return;
        if (rx == 0 || ry == 0) { p.lineTo(x2, y2); return; }
        double Rx = Math.abs(rx), Ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
        double x1p = cos * dx2 + sin * dy2, y1p = -sin * dx2 + cos * dy2;
        double lam = (x1p * x1p) / (Rx * Rx) + (y1p * y1p) / (Ry * Ry);
        if (lam > 1) { double sq = Math.sqrt(lam); Rx *= sq; Ry *= sq; }
        double num = Rx * Rx * Ry * Ry - Rx * Rx * y1p * y1p - Ry * Ry * x1p * x1p;
        double den = Rx * Rx * y1p * y1p + Ry * Ry * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (Rx * y1p / Ry), cyp = coef * (-Ry * x1p / Rx);
        double ccx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, ccy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double th1 = angle(1, 0, (x1p - cxp) / Rx, (y1p - cyp) / Ry);
        double dth = angle((x1p - cxp) / Rx, (y1p - cyp) / Ry, (-x1p - cxp) / Rx, (-y1p - cyp) / Ry);
        if (!sweep && dth > 0) dth -= 2 * Math.PI;
        else if (sweep && dth < 0) dth += 2 * Math.PI;
        int segs = Math.max(1, (int) Math.ceil(Math.abs(dth) / (Math.PI / 2) - 1e-7));
        double delta = dth / segs, t = 4.0 / 3.0 * Math.tan(delta / 4), th = th1;
        for (int k = 0; k < segs; k++) {
            double c1 = Math.cos(th), s1 = Math.sin(th), c2 = Math.cos(th + delta), s2 = Math.sin(th + delta);
            double ax = c1 - t * s1, ay = s1 + t * c1, bx = c2 + t * s2, by = s2 - t * c2;
            p.cubicTo((float) (cos * Rx * ax - sin * Ry * ay + ccx), (float) (sin * Rx * ax + cos * Ry * ay + ccy),
                    (float) (cos * Rx * bx - sin * Ry * by + ccx), (float) (sin * Rx * bx + cos * Ry * by + ccy),
                    (float) (cos * Rx * c2 - sin * Ry * s2 + ccx), (float) (sin * Rx * c2 + cos * Ry * s2 + ccy));
            th += delta;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy, len = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return ux * vy - uy * vx < 0 ? -a : a;
    }
}
