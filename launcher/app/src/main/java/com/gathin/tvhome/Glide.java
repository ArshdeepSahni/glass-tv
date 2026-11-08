package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import androidx.dynamicanimation.animation.SpringAnimation;

/**
 * The focus highlight that glides. One per group (a menu's rows, the Controls tiles, the bar, the switcher's
 * cards): when focus moves, the highlight travels to the new item on the FOCUS spring, stretching to its size,
 * instead of blinking out here and in there. A fill: the focus platter (white on a soft shadow by day, lit glass
 * with a line of light along its top by night), behind the items; the words on it keep their colour. (A ring is
 * still possible, over the items, but Glass no longer draws one for focus.)
 */
final class Glide extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG), light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    private float radius, inset;
    private boolean shown;
    private SpringAnimation move;
    private float alpha = 0f;
    private SpringAnimation fade;

    /** ring = stroke width in design px (0 for a fill); inset: grow (negative) or shrink the item's box. */
    Glide(Context c, int color, float ring, float radius, float inset) {
        super(c);
        p.setColor(color);
        if (ring > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.pxf(ring));
        }
        this.radius = Ui.pxf(radius);
        this.inset = Ui.pxf(inset);
        shade.setColor(0xFF000000);
        light.setStyle(Paint.Style.STROKE);
        light.setStrokeWidth(Math.max(1f, Ui.pxf(1)));
        setOutlineProvider(null);
        setWillNotDraw(false);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidate();
    }

    void setRadius(float r) {
        radius = Ui.pxf(r);
        invalidate();
    }

    private View waiting;

    /** Glides to `item` (a descendant of this view's parent). */
    void to(View item) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || item == null) return;
        // focus often lands while a panel is still being built: measure once it has its size, then glide
        if (!item.isLaidOut() || item.getWidth() == 0 || !isLaidOut()) {
            waiting = item;
            item.post(() -> {
                if (waiting != item) return;
                waiting = null;
                if (item.isLaidOut() && item.hasFocus()) to(item);
            });
            return;
        }
        waiting = null;
        Rect r = new Rect();
        item.getDrawingRect(r);
        try {
            parent.offsetDescendantRectToMyCoords(item, r);
        } catch (IllegalArgumentException notInside) {
            return;
        }
        float half = p.getStyle() == Paint.Style.STROKE ? p.getStrokeWidth() / 2 : 0;
        to.set(r.left + inset + half - getLeft(), r.top + inset + half - getTop(), r.right - inset - half - getLeft(), r.bottom - inset - half - getTop());
        if (!shown || Ui.reduced) {
            cur.set(to);
            shown = true;
            if (move != null) move.cancel();
            invalidate();
            fadeTo(1f);
            return;
        }
        from.set(cur);
        if (move != null) move.cancel();
        move = Spring.value(0f, 1f, Spring.FOCUS, f -> {
            cur.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                    from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
            invalidate();
        }, null);
        fadeTo(1f);
    }

    void hide() {
        shown = false;
        fadeTo(0f);
    }

    private void fadeTo(float a) {
        if (fade != null) fade.cancel();
        fade = Spring.value(alpha, a, Spring.SOFT, v -> {
            alpha = Math.max(0f, Math.min(1f, v));
            invalidate();
        }, null);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (alpha <= 0.01f || cur.isEmpty()) return;
        int base = p.getAlpha();
        float rr = Math.min(radius, Math.min(cur.width(), cur.height()) / 2);
        boolean fill = p.getStyle() == Paint.Style.FILL;
        if (fill && !Ui.t.night) {
            // by day the white platter stands on a soft shadow: four widening, fainter rounds (a blur, cheaply)
            float k = Ui.pxf(1.6f);
            for (int i = 4; i >= 1; i--) {
                tmp.set(cur);
                tmp.inset(-i * k * 0.8f, -i * k * 0.6f);
                tmp.offset(0, k * (0.8f + i * 0.45f));
                shade.setAlpha(Math.round(255 * 0.034f * alpha));
                c.drawRoundRect(tmp, rr + i * k * 0.7f, rr + i * k * 0.7f, shade);
            }
        }
        p.setAlpha(Math.round(base * alpha));
        c.drawRoundRect(cur, rr, rr, p);
        p.setAlpha(base);
        if (fill) {
            // its edge catches the light: a hairline, brightest along the top
            float h = light.getStrokeWidth() / 2;
            tmp.set(cur.left + h, cur.top + h, cur.right - h, cur.bottom - h);
            light.setShader(new android.graphics.LinearGradient(0, cur.top, 0, cur.bottom,
                    Ui.t.night ? Ui.a(0xFFFFFF, .42f * alpha) : Ui.a(0xFFFFFF, .9f * alpha),
                    Ui.t.night ? Ui.a(0xFFFFFF, .06f * alpha) : Ui.a(0x16181D, .07f * alpha), android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(tmp, rr - h, rr - h, light);
        }
    }
}
