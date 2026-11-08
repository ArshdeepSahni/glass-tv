package com.gathin.glass.assistant;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Physical motion. Every move in Glass is a spring with a feel, not a duration: it keeps its velocity when you
 * change your mind half-way (focus racing along the dock), settles naturally, and runs at real speed whatever
 * the TV's animation scale says.
 *
 *   FOCUS   quick, a touch of overshoot      (dock lift, gliding highlights, the bar's lozenge)
 *   PANEL   a little slower, a soft bounce   (panels pouring from the bar, menus, the volume pill)
 *   WINDOW  heavier, settles without wobble  (windows growing from the dock)
 *   SOFT    no bounce                        (fades)
 */
final class Spring {
    private Spring() {
    }

    static final int FOCUS = 0, PANEL = 1, WINDOW = 2, SOFT = 3;

    private static final Map<View, Map<DynamicAnimation.ViewProperty, SpringAnimation>> RUNNING = new WeakHashMap<>();

    static float stiffness(int kind) {
        switch (kind) {
            case FOCUS: return 700f;
            case PANEL: return 380f;
            case WINDOW: return 300f;
            default: return 500f;
        }
    }

    static float damping(int kind) {
        switch (kind) {
            case FOCUS: return 0.78f;
            case PANEL: return 0.74f;
            case WINDOW: return 0.86f;
            default: return 1f;
        }
    }

    private static float visible(DynamicAnimation.ViewProperty p) {
        if (p == DynamicAnimation.ALPHA) return DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA;
        if (p == DynamicAnimation.SCALE_X || p == DynamicAnimation.SCALE_Y) return DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE;
        return 0.5f;
    }

    /** Springs one property of a view to a value; calling again retargets the same spring, velocity kept. */
    static SpringAnimation to(View v, DynamicAnimation.ViewProperty p, float value, int kind) {
        Map<DynamicAnimation.ViewProperty, SpringAnimation> m = RUNNING.get(v);
        if (m == null) {
            m = new HashMap<>();
            RUNNING.put(v, m);
        }
        SpringAnimation a = m.get(p);
        if (Ui.reduced) {
            if (a != null) a.cancel();
            p.setValue(v, value);
            return a;
        }
        if (a == null) {
            a = new SpringAnimation(v, p);
            a.setMinimumVisibleChange(visible(p));
            m.put(p, a);
        }
        SpringForce f = a.getSpring();
        if (f == null) {
            f = new SpringForce(value);
            a.setSpring(f);
        }
        f.setStiffness(stiffness(kind)).setDampingRatio(damping(kind));
        a.animateToFinalPosition(value);
        return a;
    }

    static void scale(View v, float value, int kind) {
        to(v, DynamicAnimation.SCALE_X, value, kind);
        to(v, DynamicAnimation.SCALE_Y, value, kind);
    }

    static void stop(View v) {
        Map<DynamicAnimation.ViewProperty, SpringAnimation> m = RUNNING.get(v);
        if (m == null) return;
        for (SpringAnimation a : m.values()) a.cancel();
    }

    /** A spring on a plain number (0 → 1 progress, insets, rects); `apply` sees every frame. */
    interface Frame {
        void apply(float value);
    }

    static SpringAnimation value(float from, float to, int kind, Frame apply, Runnable end) {
        if (Ui.reduced) {
            apply.apply(to);
            if (end != null) end.run();
            return null;
        }
        FloatValueHolder h = new FloatValueHolder(from);
        SpringAnimation a = new SpringAnimation(h);
        a.setSpring(new SpringForce(to).setStiffness(stiffness(kind)).setDampingRatio(damping(kind)));
        a.setMinimumVisibleChange(0.002f);
        a.addUpdateListener((anim, v, vel) -> apply.apply(v));
        a.addEndListener((anim, canceled, v, vel) -> {
            if (!canceled) apply.apply(to);
            if (end != null && !canceled) end.run();
        });
        a.start();
        return a;
    }
}
