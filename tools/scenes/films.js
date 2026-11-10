'use strict';
/* The films, drawn frame by frame from the same screens as the pictures (tools/scenes.py renders them). Each film is
   a list of moments, and draw(t) lays the TV out as it is t seconds in. Things move the way Glass moves them: by
   Spring.java's springs (FOCUS 700/0.78, PANEL 380/0.74, WINDOW 300/0.86, SOFT 500/1) and Ui's curves, with the
   launcher's own timings (Dock.arrange, Pane.pour, Sheet.animateIn and animateOut, Hud). */

// ---- motion -----------------------------------------------------------------------------------------------------------
const SPRINGS = {FOCUS: [700, .78], PANEL: [380, .74], WINDOW: [300, .86], SOFT: [500, 1]};
/** How far a spring has gone from 0 towards 1, dt seconds after it was let go (mass 1, at rest). */
function spring(kind, dt) {
  if (dt <= 0) return 0;
  const [k, z] = SPRINGS[kind], w = Math.sqrt(k);
  if (z >= 1) return 1 - Math.exp(-w * dt) * (1 + w * dt);
  const wd = w * Math.sqrt(1 - z * z);
  return 1 - Math.exp(-z * w * dt) * (Math.cos(wd * dt) + (z * w / wd) * Math.sin(wd * dt));
}
/** A value a spring takes to each new target at its moment. The springs are linear, so the moves add up exactly as
 *  a retargeted SpringAnimation does (it keeps its place and speed). */
function track(kind, init, moves, t) {
  let v = init, prev = init;
  for (const [when, to] of moves) {
    if (t <= when) break;
    v += (to - prev) * spring(kind, t - when);
    prev = to;
  }
  return v;
}
/** The latest value set by t (things that change at once: words, numbers). */
function latest(init, moves, t) {
  let v = init;
  for (const [when, to] of moves) if (t >= when) v = to;
  return v;
}
function bezier(x1, y1, x2, y2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx, cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const X = s => ((ax * s + bx) * s + cx) * s, Y = s => ((ay * s + by) * s + cy) * s, dX = s => (3 * ax * s + 2 * bx) * s + cx;
  return x => {
    let s = x;
    for (let i = 0; i < 8; i++) {
      const e = X(s) - x, d = dX(s);
      if (Math.abs(e) < 1e-6 || !d) break;
      s -= e / d;
    }
    return Y(Math.min(1, Math.max(0, s)));
  };
}
const EASE = {STD: bezier(.4, 0, .2, 1), EIN: bezier(.55, 0, .8, .2), OUT: bezier(.2, .8, .3, 1)};
/** 0..1 over a timed animation (ViewPropertyAnimator with an interpolator). */
function tween(t, from, secs, ease) {
  if (t <= from) return 0;
  if (t >= from + secs) return 1;
  return (ease || EASE.STD)((t - from) / secs);
}

// ---- the dock as focus moves along it (Dock.arrange) -------------------------------------------------------------------
/** focus: [[when, index]], the first one being where focus is at the start (-1: not in the dock). presses: [[when,
 *  index]] for OK (the icon dips in and springs back). */
function dockAt(t, focus, presses, running) {
  const G = dockGeometry();
  const [, f0] = focus[0], moves = focus.slice(1);
  const per = (fn, kind) => G.items.map((_, j) => track(kind || 'FOCUS', fn(f0, j), moves.map(([w, f]) => [w, fn(f, j)]), t));
  const tx = per((f, j) => (f < 0 ? 0 : j < f ? -SPREAD : j > f ? SPREAD : 0));
  const scale = per((f, j) => (j === f ? DF / DS : 1));
  const lift = per((f, j) => (j === f ? 1 : 0));
  for (const [w, j] of presses || []) {   // OK: in by a tenth on FOCUS, back on PANEL after 90 ms
    const base = scale[j];
    scale[j] = base - base * .1 * (spring('FOCUS', t - w) - spring('PANEL', t - w - .09));
  }
  const sepFor = f => (f < 0 ? 0 : f < G.nLeft ? SPREAD : -SPREAD);
  const sep = track('FOCUS', sepFor(f0), moves.map(([w, f]) => [w, sepFor(f)]), t);
  const inset = track('FOCUS', f0 < 0 ? SPREAD : 0, moves.map(([w, f]) => [w, f < 0 ? SPREAD : 0]), t);
  // the name above: glides to the focused cell, fades out when focus leaves the dock
  const cx = f => G.slots[f] + DS / 2 - 120;
  const named = focus.filter(([, f]) => f >= 0);
  const lx = named.length ? track('FOCUS', cx(named[0][1]), named.slice(1).map(([w, f]) => [w, cx(f)]), t) : 0;
  const alpha = track('SOFT', f0 >= 0 ? 1 : 0, moves.map(([w, f]) => [w, f >= 0 ? 1 : 0]), t);
  let text = f0 >= 0 ? G.items[f0].label : '', enter = -99, was = f0;
  for (const [w, f] of moves) {
    if (t < w) break;
    if (f >= 0) {
      text = G.items[f].label;
      if (was < 0) enter = w;
    }
    was = f;
  }
  const ly = 6 * (1 - spring('FOCUS', t - enter));
  return dockDraw({tx, scale, lift, sep, inset, running, label: text ? {text, x: lx, y: enter > -99 ? ly : 0, alpha} : null});
}

// ---- what opens and closes ----------------------------------------------------------------------------------------------
/** Control Center poured from the bar at `open`, poured back at `close` (Pane.pour / unpour, Sheet.animateIn). */
function controlsAt(t, open, close) {
  if (t < open) return false;
  if (t < close) {
    controls({pour: spring('PANEL', t - open), content: spring('SOFT', t - open - .09), contentY: -6 * (1 - spring('PANEL', t - open - .09))});
    return true;
  }
  const g = 1 - spring('SOFT', t - close);
  const fade = 1 - tween(t, close + .32, .06);
  if (fade <= 0) return false;
  controls({pour: g, unpour: true, content: 1 - tween(t, close, .09), alpha: fade});
  return true;
}
/** An overlay (the open apps, All apps) fading in on SOFT, out in 160 ms; its glass settling from .94 on PANEL. */
function overlayAt(t, open, close) {
  if (t < open) return null;
  if (t >= close + .16) return null;
  const alpha = t < close ? spring('SOFT', t - open) : 1 - tween(t, close, .16);
  return {alpha: Math.max(0, Math.min(1, alpha)), scale: .94 + .06 * spring('PANEL', t - open)};
}
/** A window growing from the middle of the screen on WINDOW, going back into it in 320 ms (Sheet, window). */
function windowAt(t, open, close) {
  if (t < open || t >= close + .32) return null;
  if (t < close) return {scale: .06 + .94 * spring('WINDOW', t - open), alpha: Math.min(1, spring('SOFT', t - open)), content: Math.min(1, spring('SOFT', t - open - .12))};
  const k = tween(t, close, .32, EASE.EIN);
  return {scale: 1 - .94 * k, alpha: 1 - k, content: 1 - k};
}
/** The assistant arriving (a quick fade as it rises a little) and listening, the lamp breathing; then what it heard
 *  is sent, and the talk comes in turn by turn. talk: {heard: [[when, words]], sent: when, turns: [[when, who,
 *  what, focus]]}. */
function assistantAt(t, open, talk) {
  if (t < open) return false;
  const sent = talk && t >= talk.sent;
  const level = sent ? 0 : .25 * (.5 - .5 * Math.cos(2 * Math.PI * (t - open) / 1.6));
  const heard = talk && !sent ? latest(null, talk.heard, t) : null;
  const turns = talk ? talk.turns.filter(([w]) => t >= w).map(([w, who, what, focus]) => [who, what, focus, tween(t, w, .32, EASE.OUT)]) : [];
  assistant({listening: !sent, level, heard, turns, rowFocus: true, alpha: tween(t, open, .18, EASE.OUT), y: 8 * (1 - spring('PANEL', t - open))});
  return true;
}

// ---- the films ------------------------------------------------------------------------------------------------------------
const FILMS = {
  /** The remote's buttons: along the dock, Menu, Home held, Home, and the assistant. */
  remote: {
    duration: 20.5,
    parts: [
      {key: 'move', start: 0, presses: [.6, 1.3, 2.0, 3.2]},
      {key: 'menu', start: 4.4, presses: [5.0, 7.6]},
      {key: 'hold-home', start: 8.4, presses: [8.9, 10.6]},
      {key: 'home', start: 11.3, presses: [13.2]},
      {key: 'assistant', start: 14.2, presses: [14.7]},
    ],
    draw(t) {
      const focus = [[-9, 0], [.6, 1], [1.3, 2], [2.0, 3], [3.2, 2], [5.0, -1], [7.75, 2], [8.9, -1], [10.75, 2], [11.6, -1], [13.35, 2], [14.7, -1]];
      wallpaper();
      desk();
      dockAt(t, focus);
      const weather = windowAt(t, 11.6, 13.2);
      const open = t >= 5 && t < 7.6 ? 'controls' : t >= 14.7 ? 'assist' : null;
      bar({open, context: weather ? 'Settings' : 'Home', volume: t >= 17.25 ? 18 : 20});
      controlsAt(t, 5.0, 7.6);
      const sw = overlayAt(t, 8.9, 10.6);
      if (sw) switcher(Object.assign({focus: 0}, sw));
      if (weather) weatherWin(weather);
      assistantAt(t, 14.7, {heard: [[15.3, 'Turn'], [15.55, 'Turn it'], [15.85, 'Turn it down'], [16.15, 'Turn it down a'], [16.35, 'Turn it down a bit']],
        sent: 16.8, turns: [[16.8, 'you', 'Turn it down a bit'], [17.25, 'done', ['Volume 18']], [17.45, 'says', 'Turned it down to 18.'],
          [18.3, 'next', ['A little more', 'Mute it']]]});
    },
  },
  /** What the phone does (tools/scenes' POV shot plays it behind the phone): swipe, tap, back, volume, home, Glass. */
  phone: {
    duration: 21.5,
    parts: [
      {key: 'move', start: 0, presses: [.6, 1.4, 2.2, 3.0]},
      {key: 'tap', start: 4.4, presses: [5.0]},
      {key: 'back', start: 7.2, presses: [7.8]},
      {key: 'volume', start: 9.2, presses: [9.8, 10.4, 11.4, 12.0]},
      {key: 'home', start: 13.0, presses: [13.9]},
      {key: 'assistant', start: 15.7, presses: [16.2]},
    ],
    draw(t) {
      const focus = [[-9, 6], [.6, 7], [1.4, 8], [2.2, 9], [3.0, 10], [5.09, -1], [7.9, 10], [13.05, -1], [14.25, 0], [16.2, -1]];
      const levels = [[9.8, 21], [10.4, 22], [11.4, 21], [12.0, 20]];
      const vol = latest(20, levels, t);
      wallpaper();
      desk();
      dockAt(t, focus, [[5.0, 10]]);
      const weather = windowAt(t, 13.05, 13.9);
      bar({open: t >= 16.2 ? 'assist' : null, context: weather ? 'Settings' : 'Home', volume: vol});
      const apps = overlayAt(t, 5.09, 7.8);
      if (apps) allApps({focus: 0, alpha: apps.alpha});
      if (t >= 9.8 && t < 13.2) {   // the volume, along the bottom while it changes, then away
        const out = tween(t, 13.0, .2);
        volumePill(vol, {fill: track('FOCUS', .20, levels.map(([w, v]) => [w, v / 100]), t), dy: 10 * (1 - spring('PANEL', t - 9.8)) + 10 * out, alpha: 1 - out});
      }
      if (weather) weatherWin(weather);
      assistantAt(t, 16.2, {heard: [[16.8, 'Turn'], [17.05, 'Turn the'], [17.3, 'Turn the TV'], [17.55, 'Turn the TV off'], [17.8, 'Turn the TV off in'],
        [18.0, 'Turn the TV off in an'], [18.2, 'Turn the TV off in an hour']], sent: 18.6,
        turns: [[18.6, 'you', 'Turn the TV off in an hour'], [19.05, 'done', ['Sleep in 60 min']], [19.25, 'says', 'Sure, I’ll turn the TV off in an hour.'],
          [20.0, 'next', ['Cancel the sleep timer', 'Make it 90 minutes']]]});
    },
  },
  /** A reminder: asked, the assistant closes, and two minutes later the note comes up. */
  remind: {
    duration: 9,
    parts: [{key: 'ask', start: 0, presses: []}, {key: 'note', start: 4.2, presses: [4.2]}],
    draw(t) {
      const later = t >= 4.0;
      NOW.clock = NIGHT ? (later ? '8:42' : '8:40') : (later ? '10:32' : '10:30');
      NOW.bar = NIGHT ? `Tue 10 Nov  ${NOW.clock} PM` : `Tue 10 Nov  ${NOW.clock} AM`;
      wallpaper();
      desk();
      dock();
      bar({open: t < 2.4 ? 'assist' : null});
      const fade = 1 - tween(t, 2.4, .16);
      if (fade > 0) assistant({talk: 'remind', rowFocus: true, alpha: fade});
      if (t >= 4.2) note('reminder', {alpha: Math.min(1, spring('SOFT', t - 4.2)), dy: -10 * (1 - spring('PANEL', t - 4.2))});
    },
  },
};
window.FILMS = FILMS;
