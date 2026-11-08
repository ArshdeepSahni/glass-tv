package com.gathin.tvhome;

import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Glass checking its own layout (for the person building it, through the TV's debugging; nothing on screen changes).
 * Walks a screen's views and reports, per screen and text size: words cut short or clipped, words smaller than the
 * smallest size allowed, a focused thing (grown by its focus lift) cut by something around it or off the screen, two
 * focusable things that touch once one of them grows, and text overlapping text. Lines go to the log as GlassAudit.
 * Words are shown only as their first few letters, never a network or a device's name.
 */
final class Audit {
    private Audit() {
    }

    static final String TAG = "GlassAudit";
    /** The smallest words allowed, in design px at text size M (13: captions; S takes them to 11). */
    static final float MIN_PX = 12.5f;

    private static final int SCALE_TAG = 0x7f0f0f02, SECRET_TAG = 0x7f0f0f04;

    /** Something personal (a town, a song, a network's name, someone's photo): its box is listed so a picture of the
     *  screen can be blurred there before anyone looks at it. */
    static <T extends View> T secret(T v) {
        v.setTag(SECRET_TAG, Boolean.TRUE);
        return v;
    }

    /** Ui.lift records the scale a view grows to on focus, so the audit can check the grown size. */
    static void focusScale(View v, float scale) {
        v.setTag(SCALE_TAG, scale);
    }

    static float scaleOf(View v) {
        Object o = v.getTag(SCALE_TAG);
        return o instanceof Float ? (Float) o : 1f;
    }

    static void run(String screen, View root) {
        List<String> out = new ArrayList<>();
        int[] counts = new int[1];
        String[] hide = private_(root.getContext());
        walk(root, root, out, counts, hide);
        String pref = Glass.prefs(root.getContext()).getString(GlassHomeActivity.APPEARANCE, "auto");
        String head = String.format(Locale.ROOT, "%s fs=%.2f %s pref=%s views=%d issues=%d", screen, Ui.fs, Ui.t.night ? "dark" : "light", pref, counts[0], out.size());
        Log.i(TAG, head);
        for (String s : out) Log.i(TAG, "  " + s);
        Log.i(TAG, "end " + screen);
    }

    private static String[] private_(android.content.Context c) {
        List<String> l = new ArrayList<>();
        try {
            String ssid = Net.current(c);
            if (ssid != null && !ssid.isEmpty()) l.add(ssid);
        } catch (RuntimeException ignored) {
        }
        return l.toArray(new String[0]);
    }

    private static void walk(View root, View v, List<String> out, int[] counts, String[] hide) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.02f) return;
        if (v.getWidth() == 0 || v.getHeight() == 0) return;
        counts[0]++;
        if (out.size() > 60) return;
        if (v.getTag(SECRET_TAG) != null) {
            int[] a = new int[2], b = new int[2];
            v.getLocationInWindow(a);
            root.getLocationInWindow(b);
            Log.i(TAG, String.format(Locale.ROOT, "private %d,%d,%d,%d", Math.round((a[0] - b[0]) / Ui.s) - 6, Math.round((a[1] - b[1]) / Ui.s) - 4,
                    Math.round(v.getWidth() * v.getScaleX() / Ui.s) + 12, Math.round(v.getHeight() * v.getScaleY() / Ui.s) + 8));
        }
        if (v instanceof TextView) text((TextView) v, root, out, hide);
        if ((v.isFocusable() || v.getTag(SCALE_TAG) != null) && v.getWidth() > 0) focusRoom(v, root, out);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(root, g.getChildAt(i), out, counts, hide);
            overlaps(g, out, hide);
        }
    }

    private static void text(TextView tv, View root, List<String> out, String[] hide) {
        if (tv.getText() == null || tv.getText().length() == 0) return;
        Layout l = tv.getLayout();
        String id = name(tv, hide);
        float design = tv.getTextSize() / Ui.s;
        if (design < MIN_PX * Math.min(1f, Ui.fs) - 0.05f && !(tv instanceof EditText)) {
            out.add(String.format(Locale.ROOT, "small %.1fpx %s", design, id));
        }
        if (l == null) return;
        int lines = l.getLineCount();
        if (lines > 0 && l.getEllipsisCount(lines - 1) > 0 && !"clip-ok".equals(tv.getTag())) {
            out.add("cut " + id + where(tv, root));
        }
        int inner = tv.getHeight() - tv.getCompoundPaddingTop() - tv.getCompoundPaddingBottom();
        if (l.getHeight() > inner + Math.max(2, Ui.px(2)) && !(tv instanceof EditText)) {
            out.add(String.format(Locale.ROOT, "clipped-v %d>%d %s%s", l.getHeight(), inner, id, where(tv, root)));
        }
        if (lines == 1 && tv.getEllipsize() == null && !(tv instanceof EditText)) {
            float avail = tv.getWidth() - tv.getCompoundPaddingLeft() - tv.getCompoundPaddingRight();
            if (l.getLineWidth(0) > avail + 1.5f) out.add(String.format(Locale.ROOT, "clipped-h %.0f>%.0f %s%s", l.getLineWidth(0), avail, id, where(tv, root)));
        }
    }

    /** A focusable view at its focus size: is any of it cut by a parent that clips, or off the screen? */
    private static void focusRoom(View v, View root, List<String> out) {
        float k = scaleOf(v);
        RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
        float px = v.getPivotX(), py = v.getPivotY();
        r.set(px - (px - r.left) * k, py - (py - r.top) * k, px + (r.right - px) * k, py + (r.bottom - py) * k);
        // walk up, moving the rect into each parent's space
        View cur = v;
        RectF inP = new RectF(r);
        boolean vScroll = false, hScroll = false;   // past a scroller, that direction scrolls into view: not a cut
        while (cur.getParent() instanceof ViewGroup && cur != root) {
            ViewGroup p = (ViewGroup) cur.getParent();
            inP.offset(cur.getLeft() - p.getScrollX() + cur.getTranslationX(), cur.getTop() - p.getScrollY() + cur.getTranslationY());
            boolean scrolls = p instanceof android.widget.ScrollView || p instanceof android.widget.HorizontalScrollView
                    || p.getClass().getName().contains("RecyclerView");
            if (p.getClipChildren() && !scrolls) {
                float slack = Ui.pxf(0.5f);
                // a group that clips to its padding cuts its children there, not at its edge
                float l = p.getClipToPadding() ? p.getPaddingLeft() : 0, t = p.getClipToPadding() ? p.getPaddingTop() : 0;
                float rr = p.getWidth() - (p.getClipToPadding() ? p.getPaddingRight() : 0);
                float bb = p.getHeight() - (p.getClipToPadding() ? p.getPaddingBottom() : 0);
                if (vScroll) { inP.top = Math.max(inP.top, t); inP.bottom = Math.min(inP.bottom, bb); }
                if (hScroll) { inP.left = Math.max(inP.left, l); inP.right = Math.min(inP.right, rr); }
                if (inP.left < l - slack || inP.top < t - slack || inP.right > rr + slack || inP.bottom > bb + slack) {
                    out.add(String.format(Locale.ROOT, "focus-cut x%.2f by %s (%.0f,%.0f,%.0f,%.0f in %dx%d) %s", k, p.getClass().getSimpleName(),
                            inP.left, inP.top, inP.right, inP.bottom, p.getWidth(), p.getHeight(), name(v, new String[0])));
                    return;
                }
            }
            if (scrolls) {
                boolean vert = !(p instanceof android.widget.HorizontalScrollView)
                        && !(p.getClass().getName().contains("RecyclerView") && (p.canScrollHorizontally(1) || p.canScrollHorizontally(-1)));
                if (vert) vScroll = true; else hScroll = true;
            }
            if (scrolls && p.getClipChildren()) {
                // inside a scroller only the cross-axis matters (the list scrolls the rest into view)
                boolean vertical = !(p instanceof android.widget.HorizontalScrollView);
                boolean cut = vertical ? (inP.left < -Ui.pxf(0.5f) || inP.right > p.getWidth() + Ui.pxf(0.5f))
                        : (inP.top < -Ui.pxf(0.5f) || inP.bottom > p.getHeight() + Ui.pxf(0.5f));
                if (cut && p.getClipToPadding()) {
                    out.add(String.format(Locale.ROOT, "focus-cut x%.2f by %s (cross axis) %s", k, p.getClass().getSimpleName(), name(v, new String[0])));
                    return;
                }
            }
            cur = p;
        }
        Rect screen = new Rect(0, 0, root.getWidth(), root.getHeight());
        if (inP.left < screen.left - 1 || inP.top < screen.top - 1 || inP.right > screen.right + 1 || inP.bottom > screen.bottom + 1) {
            out.add("focus-offscreen " + name(v, new String[0]));
        }
    }

    /** Text over text among one group's children (spacing that collapsed at a bigger text size). */
    private static void overlaps(ViewGroup g, List<String> out, String[] hide) {
        if (g.getClass().getName().contains("RecyclerView") || g instanceof android.widget.FrameLayout && !(g instanceof android.widget.ScrollView)) {
            // frames stack on purpose (pictures under words): only rows and columns are checked
            if (!(g instanceof android.widget.LinearLayout)) return;
        }
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View a = g.getChildAt(i);
            if (!(a instanceof TextView) || a.getVisibility() != View.VISIBLE || ((TextView) a).length() == 0) continue;
            for (int j = i + 1; j < n; j++) {
                View b = g.getChildAt(j);
                if (!(b instanceof TextView) || b.getVisibility() != View.VISIBLE || ((TextView) b).length() == 0) continue;
                Rect ra = new Rect(a.getLeft(), a.getTop(), a.getRight(), a.getBottom());
                Rect rb = new Rect(b.getLeft(), b.getTop(), b.getRight(), b.getBottom());
                if (Rect.intersects(ra, rb)) {
                    Rect x = new Rect(ra);
                    x.intersect(rb);
                    if (x.width() > Ui.px(2) && x.height() > Ui.px(2)) out.add("overlap " + name(a, hide) + " / " + name(b, hide));
                }
            }
        }
    }

    private static String where(View v, View root) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        root.getLocationInWindow(b);
        return String.format(Locale.ROOT, " @%d,%d %dx%d", Math.round((a[0] - b[0]) / Ui.s), Math.round((a[1] - b[1]) / Ui.s),
                Math.round(v.getWidth() / Ui.s), Math.round(v.getHeight() / Ui.s));
    }

    private static String name(View v, String[] hide) {
        String cls = v.getClass().getSimpleName();
        CharSequence cd = v.getContentDescription();
        String words = null;
        if (v instanceof TextView && ((TextView) v).getText() != null) words = ((TextView) v).getText().toString();
        else if (cd != null) words = cd.toString();
        if (words != null) {
            for (String h : hide) if (!h.isEmpty() && words.contains(h)) words = "(private)";
            words = words.replace('\n', ' ');
            if (words.length() > 22) words = words.substring(0, 22) + "…";
        }
        String parent = v.getParent() instanceof View ? ((View) v.getParent()).getClass().getSimpleName() : "";
        CharSequence pcd = v.getParent() instanceof View ? ((View) v.getParent()).getContentDescription() : null;
        return cls + (words != null ? " \"" + words + "\"" : "") + " in " + parent + (pcd != null ? "[" + shorten(pcd.toString(), hide) + "]" : "");
    }

    private static String shorten(String s, String[] hide) {
        for (String h : hide) if (!h.isEmpty() && s.contains(h)) return "(private)";
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }
}
