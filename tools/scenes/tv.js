'use strict';
/* Glass's TV interface, drawn for the website's pictures and films (tools/scenes.py renders this page).

   It follows the launcher's code: places in 1280 x 720 design px (Ui.at), the two tones (Ui.DAY, Ui.NIGHT), the
   glass (Pane.drawGlass, on a canvas from the same textures), the bar (Bar), the dock (Dock), the widgets
   (Widgets), the sheets and the assistant, with the TV's fonts, glyphs (Glyph.java), wallpapers and app icons.
   The content is Sample.java's, the same in both looks: by day in the light look, in the evening in the dark one.

   tv.html?scene=NAME&look=light|dark draws one scene; window.READY turns true when it's ready to shoot.
   With &t=SECONDS a film's scene is drawn at that moment (window.frame(t) draws another). */

const Q = new URLSearchParams(location.search);
const LOOK = Q.get('look') === 'dark' ? 'dark' : 'light';
const NIGHT = LOOK === 'dark';
const SCENE = Q.get('scene') || 'desktop';
const DPR = window.devicePixelRatio || 1;
const CACHE = '.cache/';
const ASSETS = '../../launcher/app/src/main/assets/';
const TV = document.getElementById('tv');
TV.classList.add(NIGHT ? 'night' : 'day');

// ---- the tone, for what is drawn on canvases ----------------------------------------------------------------------
const a = (rgb, alpha) => `rgba(${(rgb >> 16) & 255},${(rgb >> 8) & 255},${rgb & 255},${alpha})`;
const TONE = NIGHT ? {
  ink: '#F2F2F5', ink2: a(0xF2F2F5, .62), ink3: a(0xF2F2F5, .38), hover: a(0xFFFFFF, .07), sel: '#F2F2F5',
  selink: '#0E0E10', field: a(0xFFFFFF, .08), red: '#FF6961',
} : {
  ink: '#16181D', ink2: a(0x16181D, .62), ink3: a(0x16181D, .40), hover: a(0x16181D, .06), sel: '#0E0E10',
  selink: '#FFFFFF', field: a(0xFFFFFF, .55), red: '#E5372B',
};

// ---- sample content (Sample.java), by day and in the evening --------------------------------------------------
const NOW = NIGHT
  ? {clock: '8:40', ampm: 'PM', bar: 'Tue 10 Nov  8:40 PM', date: 'Tuesday, November 10'}
  : {clock: '10:30', ampm: 'AM', bar: 'Tue 10 Nov  10:30 AM', date: 'Tuesday, November 10'};
const WEATHER = NIGHT ? {
  place: 'Lisbon', temp: 18, text: 'Clear', glyph: 'moon', hi: 24, lo: 16, sunrise: '7:04 AM', sunset: '5:31 PM',
  hours: [['9pm', 'moon', 17], ['10pm', 'moon', 17], ['11pm', 'moon', 16], ['12am', 'moon', 16]],
} : {
  place: 'Lisbon', temp: 21, text: 'Sunny', glyph: 'sun', hi: 24, lo: 16, sunrise: '7:04 AM', sunset: '5:31 PM',
  hours: [['11am', 'sun', 22], ['12pm', 'sun', 23], ['1pm', 'sun_cloud', 23], ['2pm', 'sun_cloud', 22]],
};
WEATHER.days = [['Today', 'sun', 24, 16], ['Wed', 'sun_cloud', 23, 15], ['Thu', 'rain', 21, 14], ['Fri', 'sun_cloud', 22, 15], ['Sat', 'sun', 25, 17]];
let VOLUME = 20;   // the films change it
const SONG = {app: 'MUSIC', title: 'Evening Light', artist: 'Harbor & Pines', at: '1:14', left: '−2:20', done: 74 / 214};
const APPS = [   // the dock's order (Sample.APPS, with the TV's Live TV), then Glass's own places
  {id: 'youtube', label: 'YouTube'}, {id: 'netflix', label: 'Netflix'}, {id: 'prime', label: 'Prime Video'},
  {id: 'livetv', label: 'Live TV'}, {id: 'spotify', label: 'Spotify'}, {id: 'ytmusic', label: 'YT Music'},
  {id: 'play', label: 'Google Play Store'}, {id: 'vlc', label: 'VLC'},
];
const PLACES = [{id: ':files', label: 'Files'}, {id: ':settings', label: 'Settings'}, {id: ':apps', label: 'All apps'}];

// ---- little helpers -----------------------------------------------------------------------------------------------
function h(tag, props, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'style') Object.assign(e.style, v);
    else if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k === 'html') e.innerHTML = v;
    else e.setAttribute(k, v);
  }
  for (const k of kids.flat(9)) if (k != null && k !== false) e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return e;
}
const px = v => (typeof v === 'number' ? v + 'px' : v);
/** Absolute placement in design px (Ui.at); a negative size wraps the content. */
function at(e, x, y, w, hh) {
  Object.assign(e.style, {position: 'absolute', left: px(x), top: px(y)});
  if (w != null && w >= 0) e.style.width = px(w);
  if (hh != null && hh >= 0) e.style.height = px(hh);
  return e;
}
/** Words (Ui.text / Ui.line): size, colour (a token or a colour), weight. */
function txt(size, color, weight, s, extra) {
  const outfit = extra && extra.cls && extra.cls.split(' ').includes('o');
  const e = h('span', {class: 't' + (extra && extra.cls ? ' ' + extra.cls : ''), text: s});
  // a TextView without font padding is the font's ascent + descent, each rounded up to a whole pixel
  const lh = outfit ? Math.ceil(size) + Math.ceil(size * .26) : Math.ceil(size * .96875) + Math.ceil(size * .24121);
  Object.assign(e.style, {fontSize: size + 'px', lineHeight: lh + 'px', color: color.startsWith('--') ? `var(${color})` : color, fontWeight: weight});
  if (extra && extra.style) Object.assign(e.style, extra.style);
  if (extra && extra.html) e.innerHTML = s;
  return e;
}
/** Outfit Light, for the big numbers (Widgets.big). */
function big(size, color, s) {
  return txt(size, color, 300, s, {cls: 'o'});
}
/** Words over several lines, `lead` px apart (setLineSpacing). */
function para(size, color, weight, s, lead, extra) {
  const e = txt(size, color, weight, s, Object.assign({}, extra, {cls: 'wrap' + (extra && extra.cls ? ' ' + extra.cls : '')}));
  e.style.lineHeight = px(lead);
  return e;
}
const sp = (w, hh) => h('div', {style: {flex: 'none', width: px(w || 0), height: px(hh || 0)}});
const fill = () => h('div', {style: {flex: '1 1 0'}});
const col = (style, ...kids) => h('div', {class: 'col', style}, ...kids);
const row = (style, ...kids) => h('div', {class: 'row', style}, ...kids);
const css = c => (c.startsWith('--') ? `var(${c})` : c);

// ---- glyphs (Glyph.java: a 24-unit grid, round caps and joins, a 1.9 unit line) ---------------------------------
let GLYPHS = {};
function glyph(name, size, color, width) {
  const p = GLYPHS[name] || GLYPHS.info;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.classList.add('g');
  const c = css(color);
  svg.innerHTML = `<path d="${p[0]}" fill="none" stroke="${c}" stroke-width="${width || 1.9}" stroke-linecap="round" stroke-linejoin="round"/>`
    + (p[1] ? `<path d="${p[1]}" fill="${c}"/>` : '');
  return svg;
}

// ---- pictures -----------------------------------------------------------------------------------------------------
const IMAGES = {};
function load(key, src) {
  return IMAGES[key] = IMAGES[key] || new Promise((ok, no) => {
    const im = new Image();
    im.onload = () => ok(im);
    im.onerror = () => no(new Error('no picture: ' + src));
    im.src = src;
  });
}
const pending = [];
/** Something drawn once its pictures are in. */
function later(fn) {
  pending.push(fn);
}

// ---- the glass (Pane.drawGlass) -----------------------------------------------------------------------------------
const FROST_BANDS = [[0, 2, 1.16], [2, 5, 1.07]], CLEAR_BANDS = [[0, 3, 1.30], [3, 11, 1.14]];
let TEX = null;   // {sharp, frost}: the wallpaper as the glass shows it (scenes.py makes them, as Pane.glass does)

function rrect(g, x, y, w, hh, r) {
  r = Math.max(0, Math.min(r, w / 2, hh / 2));
  g.moveTo(x + r, y);
  g.arcTo(x + w, y, x + w, y + hh, r);
  g.arcTo(x + w, y + hh, x, y + hh, r);
  g.arcTo(x, y + hh, x, y, r);
  g.arcTo(x, y, x + w, y, r);
  g.closePath();
}

/** Draws the scene behind the glass, magnified `mag` about the glass's centre (Pane.lens). */
function lens(g, tex, p, box, mag) {
  const cx = p.x + box.l + box.w / 2, cy = p.y + box.t + box.h / 2;
  g.save();
  g.translate(-p.x, -p.y);
  g.translate(cx, cy);
  g.scale(mag, mag);
  g.translate(-cx, -cy);
  g.drawImage(tex, 0, 0, 1280, 720);
  g.restore();
}

function angled(g, box, deg, stops) {
  const rad = deg * Math.PI / 180, ux = Math.sin(rad), uy = -Math.cos(rad);
  const len = Math.max(1, Math.abs(box.w * ux) + Math.abs(box.h * uy));
  const mx = box.l + box.w / 2, my = box.t + box.h / 2;
  const gr = g.createLinearGradient(mx - ux * len / 2, my - uy * len / 2, mx + ux * len / 2, my + uy * len / 2);
  for (const [o, c] of stops) gr.addColorStop(o, c);
  return gr;
}

function drawGlass(canvas, p) {
  const g = canvas.getContext('2d');
  g.setTransform(DPR, 0, 0, DPR, 0, 0);
  g.clearRect(0, 0, p.w, p.h);
  const day = !NIGHT;
  const sp_ = p.shape || {};
  const box = {l: sp_.l || 0, t: sp_.t || 0, w: p.w - (sp_.l || 0) - (sp_.r || 0), h: sp_.h != null ? sp_.h : p.h};
  const r = sp_.radius != null ? sp_.radius : p.r;
  if (box.w <= 1 || box.h <= 1) return;
  g.save();
  g.beginPath();
  rrect(g, box.l, box.t, box.w, box.h, r);
  g.clip();
  // 1 the scene through the glass
  lens(g, p.frost ? TEX.frost : TEX.sharp, p, box, p.frost ? 1.05 : 1.04);
  // 2 the lip: thin and sharp on frosted panes, deep on clear glass
  for (const [o, i, mag] of (p.frost ? FROST_BANDS : CLEAR_BANDS)) {
    if (box.w - 2 * i <= 0 || box.h - 2 * i <= 0) continue;
    g.save();
    g.beginPath();
    rrect(g, box.l + o, box.t + o, box.w - 2 * o, box.h - 2 * o, Math.max(0, r - o));
    rrect(g, box.l + i, box.t + i, box.w - 2 * i, box.h - 2 * i, Math.max(0, r - i));
    g.clip('evenodd');
    lens(g, TEX.sharp, p, box, mag);
    g.restore();
  }
  if (p.frost) {   // the frosted body's tint lies over its lip too
    const wT = FROST_BANDS[FROST_BANDS.length - 1][1], mid = wT / 2;
    g.beginPath();
    rrect(g, box.l + mid, box.t + mid, box.w - wT, box.h - wT, Math.max(0, r - mid));
    g.lineWidth = wT;
    g.strokeStyle = day ? a(0xFAFAFC, .6) : a(0x18181E, .62);
    g.stroke();
  }
  // 3-5 bloom under the top edge (by day), depth, and the rim
  const bw = box.w, bh = box.h, hh = Math.min(bh, 170);
  const ex = box.l + bw * .03, ew = Math.max(2, bw * .64), ey = box.t - hh * .6, eh = hh * 1.15;
  if (day) {
    const bl = p.frost ? .24 : .32;
    g.save();
    g.translate(ex + ew / 2, ey + eh / 2);
    g.scale(1, eh / ew);
    const rg = g.createRadialGradient(0, 0, 0, 0, 0, ew / 2);
    rg.addColorStop(0, a(0xFFFFFF, bl));
    rg.addColorStop(1, a(0xFFFFFF, 0));
    g.fillStyle = rg;
    g.beginPath();
    g.arc(0, 0, ew / 2, 0, Math.PI * 2);
    g.fill();
    g.restore();
  }
  const line = g.createLinearGradient(box.l + bw * .12, 0, box.l + bw * .70 + 1, 0);
  line.addColorStop(0, a(0xFFFFFF, 0));
  line.addColorStop(.5, a(0xFFFFFF, day ? .9 : .5));
  line.addColorStop(1, a(0xFFFFFF, 0));
  g.fillStyle = line;
  g.fillRect(box.l + bw * .12, box.t + 1, bw * .58, 1);
  const d1 = Math.max(1, Math.min(bh / 2, 28)), d2 = Math.max(1, Math.min(bh / 2, 40));
  if (day) {
    const top = g.createLinearGradient(0, box.t, 0, box.t + d1);
    top.addColorStop(0, a(0xFFFFFF, .10));
    top.addColorStop(1, a(0xFFFFFF, 0));
    g.fillStyle = top;
    g.fillRect(box.l, box.t, bw, d1);
  }
  const bottom = g.createLinearGradient(0, box.t + bh - d2, 0, box.t + bh);
  bottom.addColorStop(0, a(0, 0));
  bottom.addColorStop(1, a(0, day ? .05 : .20));
  g.fillStyle = bottom;
  g.fillRect(box.l, box.t + bh - d2, bw, d2);
  const light = day ? .95 : .6;
  const edge = (width, dx, dy, style) => {
    g.beginPath();
    rrect(g, box.l + width / 2 + dx, box.t + width / 2 + dy, bw - width, bh - width, Math.max(0, r - width / 2));
    g.lineWidth = width;
    g.strokeStyle = style;
    g.stroke();
  };
  edge(1.4, 0, 0, angled(g, box, 150, [[0, a(0xFFFFFF, light)], [.28, a(0xFFFFFF, .22)], [.55, a(0xFFFFFF, .03)], [1, a(0xFFFFFF, light * .55)]]));
  edge(1, -.6, -.4, angled(g, box, 150, [[0, a(0xFF7878, .30)], [.4, a(0xFF7878, 0)]]));
  edge(1, .6, .4, angled(g, box, 330, [[0, a(0x78A0FF, .30)], [.4, a(0x78A0FF, 0)]]));
  g.restore();
}

/** Elevation, as Android casts it from the shape: a close key shadow and a faint ambient one. */
function shadow(e) {
  if (!e) return 'none';
  return `0 ${(e * .55).toFixed(1)}px ${(e * 1.45).toFixed(1)}px var(--shadow), 0 0 ${(e * .6).toFixed(1)}px var(--ambient)`;
}

/** A pane of glass at (x, y), w x h, radius r: frosted or clear. Returns its element; content goes in .in. */
function pane(p) {
  p = Object.assign({frost: true, elev: p.frost === false ? 14 : 18, scale: 1, alpha: 1}, p);
  const W0 = p.w, H0 = p.h;
  // a pane that is growing (a window from its icon) is drawn where it is now, so the scene inside it stays put
  if (p.scale !== 1) {
    const [ox, oy] = p.pivot || [p.x + p.w / 2, p.y + p.h / 2];
    p = Object.assign({}, p, {x: ox + (p.x - ox) * p.scale, y: oy + (p.y - oy) * p.scale, w: p.w * p.scale, h: p.h * p.scale, r: p.r * p.scale});
  }
  const el = at(h('div', {class: 'pane'}), p.x, p.y, p.w, p.h);
  if (p.alpha !== 1) el.style.opacity = p.alpha;
  const sp_ = p.shape || {};
  const box = {l: sp_.l || 0, t: sp_.t || 0, w: p.w - (sp_.l || 0) - (sp_.r || 0), h: sp_.h != null ? sp_.h : p.h};
  const r = sp_.radius != null ? sp_.radius : p.r;
  const sh = at(h('div'), box.l, box.t, box.w, box.h);
  Object.assign(sh.style, {borderRadius: px(r), boxShadow: shadow(p.elev)});
  const canvas = h('canvas', {width: Math.round(p.w * DPR), height: Math.round(p.h * DPR)});
  Object.assign(canvas.style, {width: px(p.w), height: px(p.h)});
  const clip = h('div', {class: 'in', style: {borderRadius: px(r), left: px(box.l), top: px(box.t), width: px(box.w), height: px(box.h), right: 'auto', bottom: 'auto'}});
  // the content keeps the pane's own size and place; a shape (a pour, the dock's sides) only clips it
  const inner = h('div', {style: {position: 'absolute', left: px(-box.l), top: px(-box.t), width: px(W0), height: px(H0),
    transform: p.scale !== 1 ? `scale(${p.scale})` : 'none', transformOrigin: '0 0'}});
  clip.append(inner);
  el.append(sh, canvas, clip);
  el.inner = inner;
  el.spec = p;
  later(() => drawGlass(canvas, p));
  return el;
}

// ---- app icons (AppIcons) -----------------------------------------------------------------------------------------
/** Glass's own places, drawn as AppIcons.builtin draws them; the apps' own icons are pictures (icons/). */
function icon(id, size) {
  if (!id.startsWith(':')) {
    const im = h('img', {src: 'icons/' + id + '.png', width: size, height: size, draggable: 'false'});
    Object.assign(im.style, {display: 'block', width: px(size), height: px(size)});
    return im;
  }
  const c = h('canvas', {width: Math.round(size * DPR), height: Math.round(size * DPR)});
  Object.assign(c.style, {display: 'block', width: px(size), height: px(size)});
  const g = c.getContext('2d');
  g.scale(DPR, DPR);
  const s = size;
  g.save();
  g.beginPath();
  rrect(g, 0, 0, s, s, s * .235);
  g.clip();
  const tile = (from, to) => {
    g.fillStyle = angled(g, {l: 0, t: 0, w: s, h: s}, 160, [[0, from], [1, to]]);
    g.fillRect(0, 0, s, s);
  };
  const box = (l, t, r, b, rad, style) => {
    g.beginPath();
    rrect(g, s * l, s * t, s * (r - l), s * (b - t), s * rad);
    g.fillStyle = style;
    g.fill();
  };
  if (id === ':files') {
    tile('#3CC9B6', '#14897C');
    box(.18, .26, .82, .76, .08, a(0xFFFFFF, .92));
    box(.18, .20, .46, .36, .05, a(0xFFFFFF, .75));
    const lg = g.createLinearGradient(0, s * .40, 0, s * .76);
    lg.addColorStop(0, '#E9FBF8');
    lg.addColorStop(1, '#C9F1EB');
    box(.18, .40, .82, .76, .08, lg);
  } else {
    if (id === ':settings') tile('#8A93A3', '#4B5363'); else tile('#B9A6F5', '#6B54C9');
    const name = id === ':settings' ? 'sliders' : 'apps';
    const gs = Math.round(s * .5), o = (s - gs) / 2;
    const p = new Path2D(GLYPHS[name][0]);
    g.save();
    g.translate(o, o);
    g.scale(gs / 24, gs / 24);
    g.lineWidth = 2.1;
    g.lineCap = g.lineJoin = 'round';
    g.strokeStyle = a(0xFFFFFF, .95);
    g.stroke(p);
    if (GLYPHS[name][1]) {
      g.fillStyle = a(0xFFFFFF, .95);
      g.fill(new Path2D(GLYPHS[name][1]));
    }
    g.restore();
  }
  // AppIcons.finish: the sheen and the edge light
  const sheen = .22;
  const sg = g.createLinearGradient(0, 0, 0, s * .5);
  sg.addColorStop(0, a(0xFFFFFF, sheen * .7));
  sg.addColorStop(1, a(0xFFFFFF, 0));
  g.fillStyle = sg;
  g.fillRect(0, 0, s, s);
  const w = Math.max(1, s / 72);
  const eg = g.createLinearGradient(0, 0, 0, s);
  eg.addColorStop(0, a(0xFFFFFF, .16));
  eg.addColorStop(1, a(0xFFFFFF, .03));
  g.beginPath();
  rrect(g, w / 2, w / 2, s - w, s - w, s * .235 - w / 2);
  g.lineWidth = w;
  g.strokeStyle = eg;
  g.stroke();
  g.restore();
  return c;
}

// ---- the wallpaper ----------------------------------------------------------------------------------------------
function wallpaper() {
  const w = h('div', {class: 'wall'});
  w.style.backgroundImage = `url(${CACHE}wall-${LOOK}.jpg)`;
  TV.append(w);
}

// ---- the bar (Bar.java) ---------------------------------------------------------------------------------------------
function bar(o) {
  o = Object.assign({context: 'Home', open: null}, o || {});
  const b = h('div', {class: 'bar'});
  const wash = at(h('div'), 0, 0, 1280, 52);
  wash.style.background = 'var(--wash)';
  b.append(wash);
  const item = (key, gname, size, label) => {
    const it = h('div', {class: 'item' + (o.open === key ? ' open' : ''), 'data-key': key});
    if (gname === 'mark') {
      const m = h('img', {src: 'mark.png'});
      Object.assign(m.style, {width: px(size), height: px(size), display: 'block'});
      it.append(m);
    } else if (gname) it.append(glyph(gname, size, '--ink'));
    if (label) it.append(txt(15, '--ink', 400, label, {style: {marginLeft: gname ? '6px' : 0, letterSpacing: '-0.005em'}}));
    return it;
  };
  const left = at(row({height: '26px'}), 16 - 8, 4);
  left.append(item('mark', 'mark', 18), txt(15, '--ink', 600, o.context, {style: {marginLeft: '14px'}}),
    h('div', {style: {marginLeft: '14px'}}, item('inputs', null, 0, 'Inputs')));
  const right = row({position: 'absolute', top: '4px', right: '16px', height: '26px'});
  right.append(item('wifi', 'wifi', 16), h('div', {style: {marginLeft: '8px'}}, item('volume', 'volume', 16, String(o.volume != null ? o.volume : VOLUME))),
    h('div', {style: {marginLeft: '8px'}}, item('search', 'search', 16)),
    h('div', {style: {marginLeft: '8px'}}, item('controls', 'controls', 16)),
    h('div', {style: {marginLeft: '8px'}}, item('assist', 'assist', 16)),
    txt(15, '--ink', 500, o.time || NOW.bar, {cls: 'tnum', style: {marginLeft: '16px', letterSpacing: '-0.005em', whiteSpace: 'pre'}}));
  b.append(left, right);
  TV.append(b);
  return b;
}

/** The x (design px) a panel hangs from: the centre of its bar item (Desk.barCenter). */
function barCenter(key) {
  const e = TV.querySelector(`.bar [data-key="${key}"]`);
  if (!e) return 640;
  const r = e.getBoundingClientRect(), t = TV.getBoundingClientRect();
  return r.left - t.left + r.width / 2;
}

// ---- the dock (Dock.java) ---------------------------------------------------------------------------------------
const DS = 64, DF = 76, GAP = 12, PAD = 16, SEP = 24, SPREAD = 14, DTOP = 612, DH = 96, DR = 31;
function dockGeometry() {
  const items = APPS.concat(PLACES), nLeft = APPS.length, n = items.length;
  const width = n * DS + (n - 2) * GAP + SEP + 2 * PAD;
  const x0 = (1280 - width) / 2;
  const slots = [];
  let x = x0 + PAD, sepX = 0;
  for (let i = 0; i < n; i++) {
    slots.push(x);
    x += DS;
    if (i === nLeft - 1) {
      sepX = x + SEP / 2;
      x += SEP;
    } else x += GAP;
  }
  return {items, nLeft, n, width, x0, slots, sepX};
}

/** The dock at rest or with a cell focused (o.focus; o.f: how far focus has arrived, 0..1). */
function dock(o) {
  o = Object.assign({focus: -1, running: [], f: 1, label: true}, o || {});
  const G = dockGeometry();
  const f = o.focus >= 0 ? o.f : 0;
  return dockDraw({
    tx: G.items.map((_, j) => (o.focus < 0 ? 0 : (j < o.focus ? -SPREAD : j > o.focus ? SPREAD : 0) * f)),
    scale: G.items.map((_, j) => (j === o.focus ? 1 + (DF / DS - 1) * f : 1)),
    lift: G.items.map((_, j) => (j === o.focus ? f : 0)),
    sep: o.focus < 0 ? 0 : (o.focus < G.nLeft ? SPREAD : -SPREAD) * f,
    inset: SPREAD * (1 - f),
    label: o.focus >= 0 && o.label ? {text: G.items[o.focus].label, x: G.slots[o.focus] + DS / 2 - 120, y: 6 * (1 - f), alpha: f} : null,
    running: o.running,
  });
}

/** Draws the dock from its state: each cell's shift, size and lift, the hairline's shift, the glass's sides, the
 *  name above (Dock.arrange). */
function dockDraw(st) {
  const G = dockGeometry();
  const d = h('div', {class: 'dock'});
  d.append(pane({x: G.x0 - SPREAD, y: DTOP, w: G.width + 2 * SPREAD, h: DH, r: DR, frost: false, elev: 4,
    shape: {l: st.inset, r: st.inset}}));
  const sep = at(h('div'), G.sepX + st.sep, DTOP + 24, 1, DH - 48);
  sep.style.background = 'var(--sep)';
  d.append(sep);
  G.items.forEach((it, j) => {
    const cell = at(h('div'), G.slots[j] + st.tx[j], DTOP + PAD, DS, DS);
    Object.assign(cell.style, {transform: `scale(${st.scale[j]})`, borderRadius: px(DS * .235)});
    const lf = st.lift[j];
    if (lf > 0.01) cell.style.boxShadow = `0 ${6 * lf}px ${16 * lf}px rgba(0,0,0,${(NIGHT ? .45 : .22) * Math.min(1, lf)})`;
    cell.append(icon(it.id, DS));
    d.append(cell);
    if (st.running && st.running.includes(it.id)) {
      const dash = at(h('div'), G.slots[j] + DS / 2 - 8 + st.tx[j], DTOP + DH - 7, 16, 3);
      Object.assign(dash.style, {background: 'var(--ink2)', borderRadius: '2px'});
      d.append(dash);
    }
  });
  if (st.label && st.label.alpha > 0.005) {
    const wrap = at(h('div', {style: {display: 'flex', justifyContent: 'center', opacity: Math.min(1, st.label.alpha)}}),
      st.label.x, DTOP - 10 - 30 + st.label.y, 240, 30);
    wrap.append(txt(15, '--ink', 500, st.label.text, {style: {padding: '0 12px', height: '30px', lineHeight: '30px',
      background: 'var(--glass2)', borderRadius: '10px', boxShadow: `inset 0 0 0 1px var(--edge), ${shadow(13)}`}}));
    d.append(wrap);
  }
  TV.append(d);
  return d;
}

// ---- the widgets (Widgets.java) -----------------------------------------------------------------------------------
const WS = {S: 0, M: 1, L: 2};
const ww = size => (size === WS.S ? 150 : 316), wh = size => (size === WS.L ? 316 : 150);

function clockW(size) {
  if (size === WS.S) return col({padding: '14px 16px', height: '100%'},
    big(44, '--ink', NOW.clock), fill(), txt(13, '--ink', 600, 'Tuesday'), txt(13, '--ink2', 500, 'November 10', {style: {marginTop: '2px'}}));
  if (size === WS.M) return col({padding: '16px 20px', height: '100%', justifyContent: 'center'},
    big(58, '--ink', NOW.clock), txt(15, '--ink2', 500, NOW.date, {style: {marginLeft: '2px', marginTop: '8px'}}));
  return col({padding: '20px', height: '100%', alignItems: 'center'}, fill(),
    big(92, '--ink', NOW.clock), txt(15, '--ink3', 600, NOW.ampm, {style: {marginTop: '4px'}}),
    txt(16, '--ink2', 500, NOW.date, {style: {marginTop: '10px'}}), fill(),
    txt(13, '--ink3', 500, `Sunrise ${WEATHER.sunrise}  ·  Sunset ${WEATHER.sunset}`, {style: {whiteSpace: 'pre'}}));
}

function weatherW(size) {
  const W = WEATHER;
  const now = col({width: size === WS.S ? 'auto' : '118px', flex: 'none'},
    txt(13, '--ink', 600, W.place), big(size === WS.S ? 36 : 40, '--ink', W.temp + '°'),
    row({marginTop: '4px'}, glyph(W.glyph, 16, '--ink'), txt(13, '--ink2', 500, W.text, {style: {marginLeft: '6px'}})),
    txt(13, '--ink2', 500, `H:${W.hi}°  L:${W.lo}°`, {style: {marginTop: '2px', whiteSpace: 'pre'}}));
  now.children[1].style.marginTop = '2px';
  const top = row({alignItems: 'flex-start'}, now);
  if (size !== WS.S) {
    const hours = row({flex: '1 1 0', marginLeft: '10px', alignItems: 'center'});
    for (const [label, gl, temp] of W.hours) hours.append(col({flex: '1 1 0', alignItems: 'center'},
      txt(13, '--ink2', 600, label), h('div', {style: {marginTop: '8px'}}, glyph(gl, 20, '--ink')),
      txt(13, '--ink', 600, temp + '°', {style: {marginTop: '8px'}})));
    top.append(hours);
  }
  const outer = col({padding: '14px 16px', height: '100%'}, top);
  if (size === WS.L) {
    outer.append(h('div', {style: {height: '1px', background: 'var(--hair)', marginTop: '12px', flex: 'none'}}));
    const days = col({marginTop: '6px'});
    const lo = Math.min(...W.days.map(d => d[3])), hi = Math.max(...W.days.map(d => d[2]));
    for (const [label, gl, dhi, dlo] of W.days.slice(0, 4)) {
      const track = h('div', {style: {flex: '1 1 0', height: '4px', margin: '0 10px', background: 'var(--hover)', borderRadius: '2px', position: 'relative'}});
      const span = Math.max(1, hi - lo);
      const fillBar = h('div', {style: {position: 'absolute', top: 0, bottom: 0, left: (100 * (dlo - lo) / span) + '%',
        width: (100 * (dhi - dlo) / span) + '%', borderRadius: '2px', background: 'linear-gradient(90deg,#5AC8FA,#9AD66B,#FFCC00,#FF9500)'}});
      track.append(fillBar);
      days.append(row({height: '38px'}, txt(13, '--ink', 600, label, {style: {width: '58px'}}),
        h('div', {style: {marginLeft: '2px'}}, glyph(gl, 18, '--ink')),
        txt(13, '--ink2', 500, dlo + '°', {style: {width: '40px', marginLeft: '6px', textAlign: 'right'}}), track,
        txt(13, '--ink', 600, dhi + '°', {style: {width: '36px'}})));
    }
    outer.append(days);
  }
  return outer;
}

function art(size, radius) {
  const im = h('img', {src: CACHE + 'art.jpg'});
  Object.assign(im.style, {width: px(size), height: px(size), borderRadius: px(radius), objectFit: 'cover', display: 'block', flex: 'none'});
  return im;
}

function progress(f) {
  const b = h('div', {style: {height: '4px', background: 'var(--hover)', borderRadius: '2px', position: 'relative', flex: 'none'}});
  b.append(h('div', {style: {position: 'absolute', left: 0, top: 0, bottom: 0, width: (f * 100) + '%', background: 'var(--ink2)', borderRadius: '2px'}}));
  return b;
}

function playingW(size) {
  if (size === WS.S) {
    const f = h('div', {style: {position: 'absolute', inset: 0}});
    const im = art(150, 0);
    Object.assign(im.style, {width: '100%', height: '100%'});
    f.append(im, at(h('div', {style: {background: 'linear-gradient(to top, rgba(0,0,0,.66), rgba(0,0,0,0))'}}), 0, 150 - 96, 150, 96));
    const words = at(col({padding: '0 14px 12px'}, txt(14, '#FFFFFF', 600, SONG.title), txt(13, 'rgba(255,255,255,.75)', 500, SONG.artist, {style: {marginTop: '2px'}})), 0, null, 150);
    words.style.top = 'auto';
    words.style.bottom = '0';
    f.append(words);
    const st = at(h('div', {style: {borderRadius: '50%', background: 'rgba(0,0,0,.35)', padding: '6px'}}), 150 - 10 - 28, 10, 28, 28);
    st.append(glyph('pause', 16, '#FFFFFF'));
    f.append(st);
    return f;
  }
  const artSize = size === WS.L ? 132 : 122;
  const words = col({flex: '1 1 0', minWidth: 0, marginLeft: '14px'},
    txt(13, '--ink3', 700, SONG.app, {style: {letterSpacing: '.06em'}}),
    txt(16, '--ink', 600, SONG.title, {style: {marginTop: '4px'}}),
    txt(13, '--ink2', 500, SONG.artist, {style: {marginTop: '3px'}}));
  const times = row({marginTop: size === WS.L ? '6px' : '5px'}, txt(13, '--ink3', 600, SONG.at, {cls: 'tnum', style: {flex: '1 1 0'}}),
    txt(13, '--ink3', 600, SONG.left, {cls: 'tnum', style: {flex: '1 1 0', textAlign: 'right'}}));
  const outer = col({padding: '14px 16px 14px 14px', height: '100%'}, row({}, art(artSize, 14), words));
  if (size === WS.L) {
    const btn = (n, s, c, ml) => h('div', {style: {marginLeft: px(ml || 0)}}, glyph(n, s, c));
    outer.append(fill(), progress(SONG.done), times,
      row({justifyContent: 'center', marginTop: '10px'}, btn('prev', 24, '--ink2'), btn('pause', 30, '--ink', 40), btn('next', 24, '--ink2', 40)));
  } else {
    const p = progress(SONG.done);
    p.style.marginTop = '12px';
    words.append(p, times);
  }
  return outer;
}

function monthGrid(size, heads) {
  // November 2026: the 1st is a Sunday, weeks start on Sunday; today is the 10th
  const lead = 0, days = 30, today = 10, weeks = Math.ceil((lead + days) / 7);
  const g = col({flex: '1 1 0', height: '100%'});
  if (heads) g.append(row({}, ...'SMTWTFS'.split('').map(l => txt(13, '--ink3', 600, l, {style: {flex: '1 1 0', textAlign: 'center'}}))));
  const dot = size * (heads ? 1.9 : 1.55);
  for (let wk = 0; wk < weeks; wk++) {
    const r = row({flex: '1 1 0'});
    for (let i = 0; i < 7; i++) {
      const day = wk * 7 + i - lead + 1, real = day >= 1 && day <= days, me = day === today;
      const cell = h('div', {style: {flex: '1 1 0', display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%'}});
      const d = txt(size, me ? (NIGHT ? '#0E0E10' : '#FFFFFF') : '--ink2', me ? 700 : 500, real ? String(day) : '',
        {style: {width: px(dot), height: px(dot), lineHeight: px(dot), textAlign: 'center', borderRadius: '50%', background: me ? 'var(--red)' : 'none', overflow: 'visible'}});
      cell.append(d);
      r.append(cell);
    }
    g.append(r);
  }
  return g;
}

function todayCol(pad) {
  return col({padding: `14px 8px 14px ${pad}px`, height: '100%'},
    txt(13, '--red', 700, 'TUESDAY', {style: {letterSpacing: '.04em'}}), big(56, '--ink', '10'), fill(),
    txt(13, '--ink2', 500, 'November 2026'));
}

function calendarW(size) {
  if (size === WS.S) return todayCol(16);
  if (size === WS.M) {
    const grid = monthGrid(12.5, false);
    Object.assign(grid.style, {marginRight: '12px', marginTop: '14px', marginBottom: '10px', height: 'auto'});
    const t = todayCol(16);
    Object.assign(t.style, {width: '124px', flex: 'none'});
    return row({alignItems: 'stretch', height: '100%'}, t, grid);
  }
  const grid = monthGrid(13, true);
  Object.assign(grid.style, {marginTop: '8px', height: 'auto'});
  return col({padding: '14px 16px 12px', height: '100%'}, txt(15, '--red', 700, 'NOVEMBER', {style: {letterSpacing: '.04em'}}), grid);
}

const PHOTOS = [['Dunes.jpg', 'walls/dunes-d.jpg'], ['Ridges.jpg', 'walls/ridges-d.jpg'], ['Aurora.jpg', 'walls/aurora-n.jpg'], ['Silk.jpg', 'walls/silk-d.jpg']];
function photosW(size) {
  const f = h('div', {style: {position: 'absolute', inset: 0}});
  const im = h('img', {src: ASSETS + PHOTOS[0][1]});
  Object.assign(im.style, {width: '100%', height: '100%', objectFit: 'cover', display: 'block'});
  f.append(im);
  if (size !== WS.S) {
    const chip = txt(13, '#FFFFFF', 600, `${PHOTOS.length} photos from phones`, {style: {padding: '5px 10px', background: 'rgba(0,0,0,.38)', borderRadius: '11px'}});
    const w = at(h('div'), 12, null);
    w.style.top = 'auto';
    w.style.bottom = '12px';
    w.append(chip);
    f.append(w);
  }
  return f;
}

const WIDGETS = {clock: clockW, weather: weatherW, playing: playingW, calendar: calendarW, photos: photosW};
function widget(id, size, x, y) {
  const p = pane({x, y, w: ww(size), h: wh(size), r: 22});
  const c = WIDGETS[id](size);
  if (c.style.position !== 'absolute') c.style.height = '100%';
  p.inner.append(c);
  TV.append(p);
  return p;
}

/** The desktop's widgets as the pictures show them: clock and weather on the left, calendar and now playing on the
 *  right, 16 apart (Widgets.GAP). `hide` leaves some out (under a window). */
function desk(hide) {
  hide = hide || [];
  if (!hide.includes('clock')) widget('clock', WS.M, 40, 70);
  if (!hide.includes('weather')) widget('weather', WS.M, 40, 236);
  if (!hide.includes('calendar')) widget('calendar', WS.M, 924, 70);
  if (!hide.includes('playing')) widget('playing', WS.M, 924, 236);
}

// ---- focus (Ui.focusRing): the platter behind what has none of its own, a sheen over what has, and a rise ------------
function veil(e, r) {
  e.style.position = e.style.position || 'relative';
  const v = h('div', {style: {position: 'absolute', inset: 0, borderRadius: px(r), pointerEvents: 'none',
    background: NIGHT ? 'rgba(255,255,255,.10)' : 'rgba(255,255,255,.26)', boxShadow: `inset 0 0 0 1px ${NIGHT ? 'rgba(255,255,255,.32)' : 'rgba(255,255,255,.70)'}`}});
  e.append(v);
  return e;
}
function rise(e, scale, z) {
  e.style.transform = `scale(${scale})`;
  e.style.boxShadow = `0 ${z * .6}px ${z * 2.2}px rgba(0,0,0,${NIGHT ? .32 : .14})`;
  return e;
}
function platter(e, r) {
  e.style.background = 'var(--focus)';
  e.style.boxShadow = `inset 0 0 0 1px var(--focusEdge), 0 3px 8px rgba(0,0,0,${NIGHT ? .25 : .10})`;
  e.style.borderRadius = px(r);
  return e;
}

// ---- Control Center (ControlsSheet) -------------------------------------------------------------------------------
function controls(o) {
  o = Object.assign({focus: 'volume', pour: null, content: 1, contentY: 0}, o || {});
  const W = 404, TILE = 64, SLIDE = 76, SEG = 52, LINK = 56, GAP = 8;
  const H = 12 + 2 * TILE + 2 * SLIDE + 2 * SEG + LINK + 6 * GAP + 12;
  const x = 1280 - 16 - W, y = 42;
  let shape = null;
  if (o.pour != null) {   // from a small pill under the bar item to the whole panel
    const g = Math.max(0, o.pour), pill = 28, cx = barCenter('controls') - x, l0 = Math.max(0, cx - 24), r0 = Math.min(W, cx + 24);
    const l = Math.max(0, l0 * (1 - g)), rr = Math.min(W, r0 + (W - r0) * g);
    shape = {l, r: W - rr, t: 0, h: Math.min(H, pill + (H - pill) * Math.min(o.unpour ? 1 : 1.04, g)), radius: pill / 2 + (28 - pill / 2) * Math.min(1, g)};
  }
  const p = pane({x, y, w: W, h: H, r: 28, shape, alpha: o.alpha == null ? 1 : o.alpha});
  const s = h('div', {style: {position: 'absolute', inset: 0, opacity: o.content, transform: `translateY(${o.contentY}px)`}});
  p.inner.append(s);
  const tile = (gname, name, on, sub) => {
    const v = row({padding: '0 12px', borderRadius: '16px', background: on ? 'var(--sel)' : 'var(--hover)'});
    const chip = h('div', {style: {width: '36px', height: '36px', borderRadius: '50%', flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: on ? 'var(--selChip)' : 'var(--field)'}}, glyph(gname, 18, on ? '--selink' : '--ink'));
    v.append(chip, col({flex: '1 1 0', minWidth: 0, marginLeft: '12px'}, txt(15, on ? '--selink' : '--ink', 600, name),
      txt(13, on ? '--selSub' : '--ink3', 400, sub, {style: {marginTop: '2px'}})));
    return v;
  };
  const slider = (gname, name, f) => {
    const v = col({padding: '12px 14px', borderRadius: '16px', background: 'var(--hover)'}, txt(13, '--ink', 600, name));
    const track = h('div', {style: {height: '28px', marginTop: '8px', borderRadius: '14px', position: 'relative', overflow: 'hidden',
      background: NIGHT ? 'rgba(255,255,255,.10)' : 'rgba(22,24,29,.10)', boxShadow: 'inset 0 0 0 1px var(--hair)'}});
    track.append(h('div', {style: {position: 'absolute', left: 0, top: 0, bottom: 0, width: (f * 100) + '%', background: NIGHT ? '#E9E9EE' : '#FFFFFF'}}));
    track.append(at(h('div', {}, glyph(gname, 18, '#5B6070')), 10, 5, 18, 18));
    v.append(track);
    return v;
  };
  const seg = (label, opts, on) => {
    const chips = row({padding: '3px', borderRadius: '8px', background: 'var(--field)'});
    opts.forEach((op, k) => chips.append(txt(13, k === on ? '--selink' : '--ink2', k === on ? 600 : 400, op,
      {style: {padding: '0 11px', height: '26px', lineHeight: '26px', borderRadius: '6px', background: k === on ? 'var(--sel)' : 'none'}})));
    return row({padding: '0 10px 0 14px', borderRadius: '16px', background: 'var(--hover)'}, txt(13, '--ink', 600, label, {style: {flex: '1 1 0'}}), chips);
  };
  const link = (title, value) => row({padding: '0 12px 0 14px', borderRadius: '16px', background: 'var(--hover)'},
    col({flex: '1 1 0', minWidth: 0}, txt(13, '--ink', 600, title), txt(15, '--ink2', 400, value, {style: {marginTop: '4px'}})),
    glyph('chev_r', 18, '--ink3'));
  let yy = 12;
  const put = (e, xx, w, hh, key) => {
    at(e, xx, yy, w, hh);
    if (o.focus === key) rise(veil(e, 16), 1.03, 3);
    s.append(e);
  };
  put(tile('wifi', 'Wi-Fi', true, 'Home'), 12, 186, TILE, 'wifi');
  put(tile('bt', 'Bluetooth', true, 'On'), 206, 186, TILE, 'bt');
  yy += TILE + GAP;
  put(tile('moon', 'Sleep', false, 'Off'), 12, 186, TILE, 'sleep');
  put(tile('volume', 'Night volume', false, 'Off'), 206, 186, TILE, 'night');
  yy += TILE + GAP;
  put(slider('volume', 'Volume', VOLUME / 100), 12, 380, SLIDE, 'volume');
  yy += SLIDE + GAP;
  put(slider('sun', 'Brightness', 1), 12, 380, SLIDE, 'brightness');
  yy += SLIDE + GAP;
  put(seg('Text size', ['S', 'M', 'L', 'XL'], 1), 12, 380, SEG, 'text');
  yy += SEG + GAP;
  put(seg('Appearance', ['Light', 'Auto', 'Dark'], NIGHT ? 2 : 0), 12, 380, SEG, 'look');
  yy += SEG + GAP;
  put(link('Phone', '1 phone connected'), 12, 380, LINK, 'phone');
  TV.append(p);
  return p;
}

/** Control Center's Wi-Fi page (ControlsSheet.wifi): the switch, the networks in reach (Sample.networks: Home is the
 *  one the TV is on, the first two are saved, all are secured), and the page's last action at the panel's foot. */
function controlsWifi(o) {
  o = Object.assign({focus: 1}, o || {});
  const W = 404, TILE = 64, SLIDE = 76, SEG = 52, LINK = 56, GAP = 8, HEAD = 52, ROW = 44, FOOTH = 40;
  const H = 12 + 2 * TILE + 2 * SLIDE + 2 * SEG + LINK + 6 * GAP + 12, FOOT = H - 12 - FOOTH;
  const p = pane({x: 1280 - 16 - W, y: 42, w: W, h: H, r: 28});
  const s = h('div', {style: {position: 'absolute', inset: 0}});
  p.inner.append(s);
  const knob = on => {   // ControlsSheet.Switch: ink when on, a quiet track when off
    const v = h('div', {style: {position: 'relative', width: '44px', height: '26px', borderRadius: '13px', flex: 'none',
      background: on ? 'var(--sel)' : (NIGHT ? 'rgba(255,255,255,.20)' : 'rgba(22,24,29,.18)')}});
    v.append(at(h('div', {style: {borderRadius: '50%', background: on ? 'var(--selink)' : '#FFFFFF', boxShadow: '0 1px 1px rgba(0,0,0,.18)'}}),
      on ? 21 : 3, 3, 20, 20));
    return v;
  };
  s.append(at(row({padding: '0 14px 0 12px'}, glyph('chev_l', 20, '--ink2'),
    txt(17, '--ink', 600, 'Wi-Fi', {style: {flex: '1 1 0', marginLeft: '6px'}}), knob(true)), 12, 12, 380, HEAD));
  const list = col({});
  [['Home', -44], ['Home 5G', -52], ['Studio', -69], ['Guest', -81]].forEach(([name, level], i) => {
    const bars = level >= -55 ? 3 : level >= -67 ? 2 : level >= -78 ? 1 : 0;
    const r = row({height: px(ROW), padding: '0 12px', borderRadius: '12px', flex: 'none'},
      glyph(bars >= 3 ? 'wifi' : bars === 2 ? 'wifi2' : 'wifi1', 18, i === 0 ? '--ink' : '--ink2'),
      txt(15, '--ink', i === 0 ? 600 : 400, name, {style: {flex: '1 1 0', marginLeft: '12px'}}));
    if (i < 2) r.append(txt(13, '--ink3', 400, i === 0 ? 'Connected' : 'Saved', {style: {marginLeft: '8px'}}));
    r.append(h('div', {style: {marginLeft: '8px', display: 'flex'}}, glyph('lock', 14, '--ink3')));
    if (i === o.focus) platter(r, 12);
    list.append(r);
  });
  s.append(at(list, 12, 12 + HEAD + 4, 380, FOOT - 12 - HEAD - 12));
  s.append(at(row({padding: '0 12px 0 14px', borderRadius: '16px', background: 'var(--hover)'},
    txt(15, '--ink', 500, 'Network settings', {style: {flex: '1 1 0'}}), glyph('chev_r', 18, '--ink3')), 12, FOOT, 380, FOOTH));
  TV.append(p);
  return p;
}

// ---- Settings (SettingsSheet) -------------------------------------------------------------------------------------
const SECTIONS = [['sun', 'Display & text', 'display'], ['sparkle', 'Backgrounds & savers', 'looks'], ['volume', 'Sound', 'sound'],
  ['wifi', 'Network & Bluetooth', 'network'], ['input', 'Inputs', 'inputs'], ['apps', 'Apps', 'apps'], ['sliders', 'Speed & memory', 'speed'],
  ['remote', 'Remote & keyboard', 'remote'], ['moon', 'Sleep & power', 'power'], ['assist', 'Assistant', 'assistant'],
  ['swap', 'Glass & original home', 'glass'], ['tv', 'TV settings', 'tv'], ['info', 'About this TV', 'about']];

/** A section's rows (SettingsSheet.fill), as the sample TV has them: {label, sub, end} rows, {head} subheads. */
const chevron = () => h('div', {style: {marginLeft: '6px'}}, glyph('chev_r', 16, '--ink3'));
const SETTING_ROWS = {
  display: () => [
    {label: 'Appearance', sub: 'Auto follows the time of day: light by day, dark from 6 pm', end: () => choice(['Auto', 'Light', 'Dark'], NIGHT ? 2 : 1)},
    {label: 'Brightness', sub: "The TV's backlight", end: () => row({}, h('div', {style: {width: '150px', height: '6px', borderRadius: '3px', background: 'var(--ink)'}}),
      txt(15, '--ink2', 500, '100', {style: {width: '44px', marginLeft: '10px', textAlign: 'right'}}),
      h('div', {style: {marginLeft: '8px'}}, glyph('chev_l', 14, '--ink3')), h('div', {style: {marginLeft: '2px'}}, glyph('chev_r', 14, '--ink3')))},
    {label: 'Text size', sub: 'Every app, not just Glass', end: () => choice(['S', 'M', 'L', 'XL'], 1)},
    {label: 'Motion', sub: 'How much things move', end: () => choice(['Full', 'Reduced'], 0)},
    {label: 'Wake animation', sub: 'The glass and its sound when the TV comes on', end: () => choice(['On', 'Off'], 0)},
    {label: 'Show it now', sub: 'Plays the wake animation', end: chevron},
  ],
  // the apps on the TV's apps row, by name (Sample.APPS), then the TV's own extras (Extras.ALL) that this TV has
  speed: () => {
    const bg = (name, keep) => ({label: name, sub: keep ? 'Keeps running in the background after you leave it' : "Runs only while it's open",
      end: () => choice(['In background', 'Only open'], keep ? 0 : 1)});
    const extra = (name, what, on) => ({label: name, sub: what, end: () => choice(['On', 'Off'], on ? 0 : 1)});
    return [
      {label: 'Free up memory', sub: 'Closes the apps waiting in the background. 418 MB of 976 MB free now', end: chevron},
      {label: 'Animations', sub: "How quickly the TV's windows open and close. Glass's own motion stays as it is", end: () => choice(['Normal', 'Quick'], 1)},
      {head: 'Apps in the background'},
      bg('Netflix', false), bg('Prime Video', false), bg('Spotify', true), bg('VLC', false), bg('YouTube', true), bg('YT Music', true),
      {head: "The TV's extras"},
      extra('Chromecast built-in', 'The cast button in YouTube, Netflix and Chrome', true),
      extra('Recommendations', "Sponsored rows for Android TV's home", false),
    ];
  },
};

function field(size, hint, r, withGlyph, hh, value) {
  const f = row({height: px(hh), padding: '0 12px', borderRadius: px(r), background: 'var(--field)',
    boxShadow: `inset 0 0 0 1px ${NIGHT ? 'rgba(255,255,255,.12)' : 'rgba(22,24,29,.10)'}`});
  if (withGlyph) f.append(glyph('search', 15, '--ink3'), sp(8));
  f.append(txt(size, value ? '--ink' : '--ink3', 400, value || hint));
  return f;
}

function choice(opts, on) {
  const chips = row({padding: '3px', borderRadius: '10px', background: NIGHT ? 'rgba(0,0,0,.22)' : 'rgba(22,24,29,.06)', flex: 'none'});
  opts.forEach((op, k) => chips.append(txt(13, k === on ? '--ink' : '--ink2', k === on ? 600 : 400, op, {style: {padding: '0 12px', height: '26px',
    lineHeight: '26px', borderRadius: '7px', background: k === on ? (NIGHT ? '#55555F' : '#FFFFFF') : 'none',
    boxShadow: k === on ? '0 1px 2px rgba(0,0,0,.14)' : 'none'}})));
  return chips;
}

function settings(o) {
  o = Object.assign({section: 0, focus: 'side'}, o || {});
  const X = 140, Y = 52, W = 1000, H = 560, SB = 252;
  const p = pane({x: X, y: Y, w: W, h: H, r: 28});
  const side = at(col({padding: '18px 16px 14px', background: 'var(--hover)'}), 0, 0, SB, H);
  side.append(field(14, 'Search settings', 10, true, 36));
  // the sections scroll (a ScrollView 12 px down, as tall as the column leaves it): at M the last is a little cut off
  const list = col({paddingTop: '12px', height: px(H - 18 - 14 - 36), overflow: 'hidden', boxSizing: 'border-box', flex: 'none'});
  SECTIONS.forEach(([gname, name], i) => {
    const r = row({height: '36px', padding: '0 10px', marginTop: i ? '2px' : 0, borderRadius: '10px', flex: 'none'});
    r.append(h('div', {style: {width: '24px', height: '24px', borderRadius: '7px', background: 'var(--field)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none'}},
      glyph(gname, 14, '--ink2')), txt(14, '--ink', 400, name, {style: {marginLeft: '10px'}}));
    if (i === o.section && o.focus === 'side') platter(r, 10);
    list.append(r);
  });
  side.append(list);
  const rule = at(h('div', {style: {background: 'var(--hair)'}}), SB - 1, 0, 1, H);
  const right = at(col({paddingTop: '22px'}), SB, 0, W - SB, H);
  right.append(txt(24, '--ink', 600, SECTIONS[o.section][1], {style: {padding: '0 24px 16px', letterSpacing: '-0.02em', flex: 'none'}}));
  const group = col({margin: '4px 24px 0', borderRadius: '16px', background: 'var(--field)', boxShadow: 'inset 0 0 0 1px var(--hair)', flex: 'none'});
  let n = 0;
  for (const r of SETTING_ROWS[SECTIONS[o.section][2]]()) {
    if (r.head) {   // SettingsSheet.subhead: quiet words inside the group, no line above
      group.append(txt(13, '--ink3', 500, r.head, {style: {padding: `${n ? 18 : 12}px 16px 4px`, flex: 'none'}}));
    } else {
      if (n) group.append(h('div', {style: {height: '1px', background: 'var(--hair)', margin: '0 16px', flex: 'none'}}));
      group.append(row({padding: '12px 16px', minHeight: '54px', flex: 'none'}, col({flex: '1 1 0', minWidth: 0, marginRight: '16px'},
        txt(15, '--ink', 500, r.label), txt(13, '--ink3', 400, r.sub, {style: {marginTop: '3px'}})), r.end()));
    }
    n++;
  }
  right.append(group);
  p.inner.append(side, rule, right);
  TV.append(p);
  return p;
}

/** A row whose focus is the gliding platter (Sheet.row): its words keep their colour, its glyph turns ink. */
function hline(margin) {
  return h('div', {style: {height: '1px', background: 'var(--hair)', flex: 'none', margin: margin || 0}});
}

// ---- the weather window (WeatherSheet) ----------------------------------------------------------------------------
function weatherWin(o) {
  o = Object.assign({scale: 1, alpha: 1, content: 1}, o || {});
  const W = 640, H = 500, x = (1280 - W) / 2, y = (720 - H) / 2 + 8, w = WEATHER;
  const p = pane({x, y, w: W, h: H, r: 28, scale: o.scale, pivot: [640, 360], alpha: o.alpha});
  const c = col({padding: '24px 28px 20px', height: '100%', opacity: o.content});
  c.append(txt(22, '--ink', 600, `${w.place}  ${w.temp}°`, {style: {whiteSpace: 'pre'}}),
    txt(14, '--ink2', 500, `${w.text}  ·  H:${w.hi}°  L:${w.lo}°`, {style: {marginTop: '4px', whiteSpace: 'pre'}}));
  const fc = row({marginTop: '14px', alignItems: 'flex-start'});
  for (const [label, gl, hi, lo] of w.days) fc.append(col({flex: '1 1 0', alignItems: 'center'}, txt(13, '--ink2', 600, label),
    h('div', {style: {marginTop: '8px'}}, glyph(gl, 22, '--ink')), txt(14, '--ink', 600, `${hi}°  ${lo}°`, {style: {marginTop: '8px', whiteSpace: 'pre'}})));
  c.append(fc, hline('18px 0 0'), txt(13, '--ink3', 600, 'LOCATION', {style: {letterSpacing: '.06em', marginTop: '16px'}}));
  const f = field(16, 'Type a town or city', 12, true, 44);
  f.style.marginTop = '10px';
  c.append(f);
  const list = col({paddingTop: '8px'});
  const wrow = (gl, title, sub, checked, focused) => {
    const r = row({minHeight: '52px', padding: '8px 14px', borderRadius: '12px', flex: 'none'});
    r.append(glyph(gl, 18, focused ? '--ink' : '--ink2'),
      col({flex: '1 1 0', minWidth: 0, marginLeft: '12px'}, txt(15, '--ink', 500, title), txt(13, focused ? '--focusSub' : '--ink3', 400, sub, {style: {marginTop: '2px'}})));
    if (checked) r.append(h('div', {style: {marginLeft: '10px'}}, glyph('check', 16, '--ink2')));
    if (focused) platter(r, 12);
    return r;
  };
  list.append(wrow('location', 'Automatic', "The town of the TV's internet address", false, true), wrow('check', w.place, 'Chosen', true, false));
  c.append(list);
  p.inner.append(c);
  TV.append(p);
  return p;
}

// ---- search (SearchSheet) -------------------------------------------------------------------------------------------
function caret(size) {
  return h('div', {style: {width: '2px', height: px(Math.round(size * 1.15)), background: 'var(--ink)', borderRadius: '1px', flex: 'none', marginLeft: '1px'}});
}
function appIcon(id, size) {
  const e = icon(id, size);
  e.style.borderRadius = px(size * .235);
  return e;
}
function searchWin(o) {
  o = Object.assign({query: '', focus: 0, typing: false}, o || {});
  const X = 320, Y = 88, W = 640, H = 446;
  const p = pane({x: X, y: Y, w: W, h: H, r: 28});
  const c = col({height: '100%'});
  const head = row({height: '63px', padding: '0 20px', flex: 'none'}, glyph('search', 22, '--ink2'), sp(12));
  if (o.query) head.append(txt(24, '--ink', 400, o.query, {style: {overflow: 'visible'}}));
  if (o.typing) head.append(caret(24));
  if (!o.query) head.append(txt(24, '--ink3', 400, 'Search apps, settings, files — or ask', {style: {marginLeft: o.typing ? '1px' : 0}}));
  c.append(head, hline());
  const hits = o.query === 'net' ? [
    ['Top hit', 'Netflix', 'App', () => appIcon('netflix', 28)],
    ['Settings', 'Network & Bluetooth', 'Settings', () => chip('wifi')],
    ['Ask', 'Ask Glass “net”', '', () => askTile()],
  ] : [['Recent', 'YouTube', 'App', () => appIcon('youtube', 28)], ['Recent', 'Netflix', 'App', () => appIcon('netflix', 28)],
    ['Recent', 'Live TV', 'App', () => appIcon('livetv', 28)], ['Recent', 'Spotify', 'App', () => appIcon('spotify', 28)]];
  const list = col({padding: '4px 10px 10px'});
  let section = null;
  hits.forEach(([sec, title, kind, ic], i) => {
    if (sec !== section) {
      section = sec;
      list.append(txt(13, '--ink3', 500, sec, {style: {padding: '12px 14px 4px'}}));
    }
    const focused = i === o.focus && !o.typing;
    const r = row({height: '48px', padding: '0 14px', borderRadius: '18px', flex: 'none'}, ic(),
      txt(15, '--ink', focused ? 600 : 400, title, {style: {marginLeft: '12px', flex: '1 1 0'}}), txt(13, focused ? '--focusSub' : '--ink3', 400, kind));
    if (focused) platter(r, 18);
    list.append(r);
  });
  c.append(list);
  p.inner.append(c);
  TV.append(p);
  return p;
}
function chip(gname) {
  return h('div', {style: {width: '28px', height: '28px', borderRadius: '8px', background: 'var(--hover)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none'}},
    glyph(gname, 17, '--ink2'));
}
function askTile() {
  return h('div', {style: {width: '28px', height: '28px', borderRadius: '8px', background: 'linear-gradient(135deg,#FFB27A,#E86A4C)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 'none'}},
    glyph('assist', 17, '#FFFFFF'));
}

// ---- Glass's keyboard (GlassKeyboard): dark glass along the bottom, in both looks --------------------------------
function keyboard(o) {
  o = Object.assign({row: 1, col: 0, action: 'Search', sug: null}, o || {});
  const KH = 50, GAP = 8, TOP = 50, SIDE = 70, H = 300;
  const k = at(h('div', {style: {background: 'rgba(20,20,24,.94)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,.14)'}}), 0, 720 - H, 1280, H);
  if (!o.sug) {
    const hint = txt(15, 'rgba(242,242,245,.5)', 500, 'Your phone can type here too · Hold OK for a capital · Back closes');
    k.append(at(hint, SIDE + 4, 14));
  }
  const rows = [['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p', '⌫'], ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', "'", '↵'],
    ['⇧', 'z', 'x', 'c', 'v', 'b', 'n', 'm', ',', '.', '?'], ['123', '@', 'space', '-', '‹', '›']];
  const width = 1280 - 2 * SIDE;
  rows.forEach((rk, r) => {
    const weights = rk.map(key => key === 'space' ? 4.6 : key === '.com' ? 1.5 : key === '123' ? 1.5 : 1);
    const total = weights.reduce((s, v) => s + v, 0);
    const unit = (width - GAP * (rk.length - 1)) / total;
    let x = SIDE;
    const y = TOP + 14 + r * (KH + GAP);
    rk.forEach((key, i) => {
      const w = unit * weights[i];
      const on = r === o.row && i === o.col;
      const special = (key.length > 1 && key !== '.com') || '⌫⇧↵‹›'.includes(key);
      const text = key === '↵' ? o.action : key;
      const size = text.length > 2 ? 15 : 21;
      const box = at(h('div', {style: {display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: on ? '13px' : '12px',
        background: on ? '#F5F5F7' : key === '↵' ? 'rgba(255,255,255,.25)' : special ? 'rgba(255,255,255,.11)' : 'rgba(255,255,255,.17)',
        boxShadow: on ? '0 5px 12px rgba(0,0,0,.30)' : 'none'}}), x - (on ? 6 : 0), y - (on ? 6 : 0), w + (on ? 12 : 0), KH + (on ? 12 : 0));
      box.append(txt(size * (on ? 1.06 : 1), on ? '#0E0E10' : 'rgba(242,242,245,.95)', text.length > 2 ? 600 : 500, text, {style: {overflow: 'visible'}}));
      k.append(box);
      x += w + GAP;
    });
  });
  TV.append(k);
  return k;
}

// ---- All apps (AppsSheet): the frosted wallpaper over everything, the apps in a grid --------------------------
const ALL_APPS = ['play', 'livetv', 'netflix', 'prime', 'spotify', 'vlc', 'youtube', 'ytmusic'];
function allApps(o) {
  o = Object.assign({focus: 0, alpha: 1}, o || {});
  const root = h('div', {class: 'wall', style: {opacity: o.alpha}});
  const veil = h('div', {class: 'wall'});
  veil.style.backgroundImage = `url(${CACHE}veil-${LOOK}.jpg)`;
  root.append(veil, h('div', {class: 'wall', style: {background: NIGHT ? 'rgba(10,10,14,.35)' : 'rgba(255,255,255,.18)'}}));
  root.append(at(field(15, 'Search apps', 12, true, 40), 470, 72, 340, 40));
  const gy = 92 + 20 + 18;
  ALL_APPS.forEach((id, i) => {
    const app = APPS.find(a => a.id === id);
    const cx = 115 + (i % 7) * 150, cy = gy + 8 + Math.floor(i / 7) * (148 + 14);
    const cell = at(col({alignItems: 'center', paddingTop: '16px', borderRadius: '20px'}), cx, cy, 150, 148);
    const focused = i === o.focus;
    const ic = appIcon(id, 88);
    if (focused) {
      platter(cell, 20);
      Object.assign(ic.style, {transform: 'scale(1.1)', transformOrigin: '44px 50px', boxShadow: '0 6px 16px rgba(0,0,0,.22)'});
    }
    cell.append(ic, txt(15, '--ink', focused ? 600 : 500, app.label, {style: {marginTop: '10px', maxWidth: '140px', textAlign: 'center',
      textShadow: '0 1px 7px rgba(0,0,0,.25)'}}));
    root.append(cell);
  });
  TV.append(root);
  return root;
}

// ---- Files (FilesSheet) -------------------------------------------------------------------------------------------
function filesWin(o) {
  o = Object.assign({focus: 0}, o || {});
  const X = 140, Y = 58, W = 1000, H = 530, SB = 232;
  const p = pane({x: X, y: Y, w: W, h: H, r: 28});
  const side = at(col({padding: '12px 10px', background: 'var(--hover)'}), 0, 0, SB, H);
  const head = s => txt(13, '--ink3', 600, s, {style: {padding: '14px 10px 6px'}});
  const place = (gname, title, cur) => {
    const r = row({height: '36px', padding: '0 10px', borderRadius: '10px', flex: 'none'}, glyph(gname, 18, cur ? '--ink' : '--ink2'),
      txt(15, '--ink', cur ? 600 : 400, title, {style: {marginLeft: '10px'}}));
    if (cur) Object.assign(r.style, {background: NIGHT ? 'rgba(255,255,255,.09)' : 'rgba(255,255,255,.60)', boxShadow: 'inset 0 0 0 1px var(--hair)'});
    return r;
  };
  side.append(head('Favourites'), place('phone', 'From phones', true), place('clock', 'Recents'), place('down', 'Downloads'),
    place('film', 'Movies'), place('photo', 'Photos'), place('music', 'Music'), head('Locations'), place('tv', 'TV storage'));
  const rule = at(h('div', {style: {background: 'var(--hair)'}}), SB - 1, 0, 1, H);
  const top = at(row({padding: '0 16px 0 20px'}, txt(20, '--ink', 600, 'From phones', {style: {flex: '1 1 0', letterSpacing: '-0.01em'}}),
    h('div', {style: {width: '190px', marginLeft: '12px'}}, field(13, 'Search', 10, true, 34))), SB, 0, W - SB, 60);
  const rule2 = at(h('div', {style: {background: 'var(--hair)'}}), SB, 59, W - SB, 1);
  const files = [['Aurora.jpg', 'walls/aurora-n.jpg', '164 kB'], ['Dunes.jpg', 'walls/dunes-d.jpg', '162 kB'],
    ['Ridges.jpg', 'walls/ridges-d.jpg', '158 kB'], ['Silk.jpg', 'walls/silk-d.jpg', '155 kB']];
  const grid = at(h('div'), SB + 14, 60 + 16, 700, H - 100);
  files.forEach(([name, src, size], i) => {
    const focused = i === o.focus;
    const cell = at(col({alignItems: 'center'}), (i % 5) * 140, Math.floor(i / 5) * 150, 140, 150);
    const frame = h('div', {style: {width: '108px', height: '84px', padding: '6px', borderRadius: '16px', flex: 'none',
      transform: focused ? 'scale(1.06)' : 'none', transformOrigin: '54px 84px'}});
    if (focused) platter(frame, 16);
    const im = h('img', {src: ASSETS + src});
    Object.assign(im.style, {width: '96px', height: '72px', objectFit: 'cover', borderRadius: '10px', display: 'block'});
    frame.append(im);
    cell.append(frame, txt(15, '--ink', focused ? 600 : 400, name, {style: {marginTop: '8px', padding: '2px 8px', maxWidth: '136px'}}),
      txt(13, '--ink3', 400, size, {style: {marginTop: '2px'}}));
    grid.append(cell);
  });
  const rule3 = at(h('div', {style: {background: 'var(--hair)'}}), SB, H - 40, W - SB, 1);
  const status = at(row({padding: '0 20px'}, txt(13, '--ink3', 400, '4 items · 3.2 GB free')), SB, H - 40, W - SB, 40);
  p.inner.append(side, rule, top, rule2, grid, rule3, status);
  TV.append(p);
  return p;
}

// ---- menus under the bar (Sheet.Menu), the TV's sources --------------------------------------------------------------
function menu(x, w, rows, focus) {
  const rowH = 40, hh = 16 + rows.length * rowH;
  const p = pane({x, y: 42, w, h: hh, r: 20});
  const list = col({padding: '8px'});
  rows.forEach(([gname, title, detail], i) => {
    const f = i === focus;
    const r = row({height: px(rowH), padding: '0 12px', borderRadius: '12px', flex: 'none'}, glyph(gname, 18, f ? '--ink' : '--ink2'),
      txt(15, '--ink', 400, title, {style: {flex: '1 1 0', marginLeft: '10px'}}));
    if (detail) r.append(txt(13, f ? '--focusSub' : '--ink3', 400, detail, {style: {marginLeft: '10px'}}));
    if (f) platter(r, 12);
    list.append(r);
  });
  p.inner.append(list);
  TV.append(p);
  return p;
}
function inputs() {
  return menu(113, 300, [['input', 'HDMI 3'], ['input', 'HDMI 2'], ['input', 'HDMI 1'], ['tv', 'AV', 'Composite'], ['live', 'Live TV'],
    ['sliders', 'Input settings…']], 0);
}

// ---- the open apps (SwitcherSheet) ------------------------------------------------------------------------------------
const RECENT = [['youtube', 'Just now'], ['netflix', '7 minutes ago'], ['prime', '14 minutes ago'], ['spotify', '21 minutes ago'],
  ['ytmusic', '28 minutes ago'], ['play', '35 minutes ago'], ['vlc', '42 minutes ago']];
function switcher(o) {
  o = Object.assign({focus: 0, alpha: 1, scale: 1}, o || {});
  const root = h('div', {class: 'wall', style: {opacity: o.alpha}});
  root.append(h('div', {class: 'wall', style: {background: 'rgba(0,0,0,.10)'}}));
  const CW = 150, GAP = 14, PAD = 26, ICON = 96, n = RECENT.length;
  const ch = 20 + ICON + 12 + 20 + 3 + 17 + 16;
  const w = n * CW + (n - 1) * GAP + 2 * PAD, hh = ch + 2 * PAD, x = (1280 - w) / 2, y = (720 - hh) / 2;
  const p = pane({x, y, w, h: hh, r: 36, frost: false, scale: o.scale});
  RECENT.forEach(([id, ago], i) => {
    const focused = i === o.focus;
    const card = at(col({alignItems: 'center', padding: '20px 8px 16px', borderRadius: '22px'}), PAD + i * (CW + GAP), PAD, CW, ch);
    if (focused) rise(platter(card, 22), 1.05, 8);
    const ic = appIcon(id, ICON);
    ic.style.boxShadow = '0 1px 3px rgba(0,0,0,.14)';
    card.append(ic, txt(15, '--ink', focused ? 600 : 500, APPS.find(a => a.id === id).label, {style: {marginTop: '12px', height: '20px'}}),
      txt(13, '--ink3', 400, ago, {style: {marginTop: '3px'}}));
    p.inner.append(card);
  });
  root.append(p);
  const hint = txt(13, '--ink', 400, '← → choose · OK open · ↑ close the app · hold OK for more', {style: {opacity: .7, textAlign: 'center'}});
  root.append(at(hint, 0, y + hh + 16, 1280));
  TV.append(root);
  return root;
}

// ---- connect a phone (PhoneSheet) -----------------------------------------------------------------------------------
function phoneWin() {
  const X = 280, Y = 86, W = 720, H = 470;
  const p = pane({x: X, y: Y, w: W, h: H, r: 28});
  const card = at(h('div', {style: {background: '#FFFFFF', borderRadius: '20px', boxShadow: shadow(4), display: 'flex', alignItems: 'center', justifyContent: 'center'}}), 28, 28, 272, 272);
  const qr = h('img', {src: CACHE + 'qr.svg'});
  Object.assign(qr.style, {width: '252px', height: '252px', display: 'block', imageRendering: 'pixelated'});
  card.append(qr);
  const count = at(txt(13, '--ink2', 400, 'A new code in 4:45', {style: {textAlign: 'center'}}), 28, 312, 272, 18);
  const right = at(col(), 332, 30, W - 332 - 32, H - 30 - 90);
  right.append(txt(24, '--ink', 600, 'Connect your phone', {style: {letterSpacing: '-0.02em'}}),
    para(15, '--ink2', 400, 'Glass opens in your phone’s browser — nothing to install.', 19, {style: {marginTop: '6px'}}));
  const steps = col({marginTop: '18px'});
  ['Point your phone’s camera here', 'Tap <b>Connect</b> on your phone', 'Allow it here, on the TV'].forEach((s, i) => {
    steps.append(row({minHeight: '40px', marginTop: i ? '4px' : 0}, txt(13, '--ink', 600, String(i + 1), {style: {width: '28px', height: '28px', lineHeight: '28px',
      textAlign: 'center', borderRadius: '14px', background: 'var(--hover)', boxShadow: 'inset 0 0 0 1px var(--hair)', flex: 'none'}}),
      txt(17, '--ink', 400, s, {html: true, style: {marginLeft: '12px'}})));
  });
  right.append(steps, hline('20px 0 0'),
    para(13, '--ink2', 400, 'No camera? On the phone, open <b>192.168.1.20:8765</b> and enter', 16, {html: true, style: {marginTop: '16px'}}));
  const digits = row({marginTop: '10px'});
  '482913'.split('').forEach((d, i) => digits.append(txt(24, '--ink', 600, d, {style: {width: '34px', height: '44px', lineHeight: '44px', textAlign: 'center',
    borderRadius: '10px', background: 'var(--field)', boxShadow: 'inset 0 0 0 1px var(--hair)', marginLeft: i === 0 ? 0 : i === 3 ? '16px' : '6px', flex: 'none'}})));
  right.append(digits);
  const note = at(row({}, para(13, '--ink2', 400, 'Phones work only on this home Wi‑Fi. Remove one any time from Phones here.', 16)), 28, H - 22 - 44, 330, 44);
  const btn = (label, primary) => txt(15, primary ? '--selink' : '--ink', primary ? 600 : 500, label, {style: {padding: '0 20px', height: '44px', lineHeight: '44px',
    borderRadius: '14px', background: primary ? 'var(--sel)' : 'var(--hover)', boxShadow: primary ? 'none' : 'inset 0 0 0 1px var(--hair)', flex: 'none'}});
  const done = btn('Done', true);
  rise(veil(done, 14), 1.05, 6);
  const buttons = at(row({justifyContent: 'flex-end'}, btn('Phones (1)', false), sp(10), done), W - 28 - 360, H - 22 - 44, 360, 44);
  p.inner.append(card, count, right, note, buttons);
  TV.append(p);
  return p;
}

// ---- Glass Assistant (AskActivity): a frosted card on the right, the talk in it --------------------------------------
const CW = 504, CX = 1280 - 16 - CW, CR = 28, HEAD = 69, ROWH = 56, LIFT = 8;
/** AskActivity.Lamp: the mark, a little bigger and in a warm glow as it hears you (level 0..1). */
function lamp(size, level) {
  level = level || 0;
  const m = h('img', {src: 'mark.png'});
  const s = size * (1 + 0.07 * level);
  Object.assign(m.style, {width: px(s), height: px(s), display: 'block', position: 'relative'});
  const box = h('div', {style: {width: '44px', height: '44px', display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative'}});
  if (level > 0.01) {
    const r = size * (0.62 + 0.5 * level);
    box.append(h('div', {style: {position: 'absolute', left: px(22 - r), top: px(22 - r), width: px(2 * r), height: px(2 * r), borderRadius: '50%',
      background: `radial-gradient(closest-side, rgba(255,150,100,${(.12 + .42 * level).toFixed(3)}), rgba(255,150,100,0))`}}));
  }
  box.append(m);
  return box;
}
function quietPill(label, size, weight, radius, hh, focused, extra) {
  const e = txt(size, focused ? '--selink' : (extra && extra.ink) || '--ink2', weight, label, {style: Object.assign({padding: '0 12px', height: px(hh), lineHeight: px(hh),
    borderRadius: px(radius), background: focused ? 'var(--sel)' : 'var(--hover)', boxShadow: focused ? 'none' : 'inset 0 0 0 1px var(--hair)', flex: 'none'},
    focused ? {transform: 'scale(1.05)'} : {}, extra && extra.style)});
  return e;
}
const TURNS = {
  you: t => h('div', {style: {display: 'flex', justifyContent: 'flex-end'}}, para(17, '--ink', 500, t, 24, {style: {maxWidth: '360px', padding: '10px 14px',
    background: 'var(--lozenge)', borderRadius: '18px 18px 6px 18px'}})),
  says: t => para(17, '--ink', 400, t, 25),
  done: items => h('div', {style: {display: 'flex', flexWrap: 'wrap', gap: '6px'}}, items.map(s => row({height: '28px', padding: '0 10px', borderRadius: '9px',
    background: 'var(--hover)', flex: 'none'}, glyph('check', 14, '--ink2', 2.4), txt(13, '--ink2', 600, s, {style: {marginLeft: '6px'}})))),
  next: (items, focus) => h('div', {style: {display: 'flex', flexWrap: 'wrap', gap: '8px'}}, items.map((s, i) => quietPill(s, 15, 500, 12, 38, i === focus, {ink: '--ink', style: {padding: '0 14px'}}))),
  pick: (pk, focus) => {
    const box = col({padding: '14px', borderRadius: '16px', background: 'var(--hover)', boxShadow: 'inset 0 0 0 1px var(--hair)'});
    const banner = h('div', {style: {width: '132px', height: '74px', borderRadius: '12px', overflow: 'hidden', background: '#FFFFFF', flex: 'none', position: 'relative'}});
    const ic = icon(pk.icon, 132);
    Object.assign(ic.style, {position: 'absolute', left: '-14px', top: '-43px', width: '160px', height: '160px'});
    banner.append(ic);
    box.append(row({}, banner, col({flex: '1 1 0', minWidth: 0, marginLeft: '14px'}, txt(20, '--ink', 600, pk.title, {style: {letterSpacing: '-0.015em'}}),
      txt(13, '--ink2', 400, pk.meta, {style: {marginTop: '4px'}}))));
    box.append(para(15, '--ink', 400, pk.why, 22, {style: {marginTop: '12px'}}));
    box.append(row({marginTop: '12px', overflow: 'visible'}, quietPill('Play on ' + pk.app, 15, 600, 12, 38, focus === 0, {ink: '--ink', style: {padding: '0 16px'}}), sp(8),
      quietPill('Another', 15, 500, 12, 38, focus === 1, {ink: '--ink', style: {padding: '0 16px'}})));
    return box;
  },
};
const PICK = {icon: 'netflix', app: 'Netflix', title: 'Glass Onion', meta: 'Film · 2 h 19 · Netflix', why: 'A sharp, funny whodunit on a Greek island'};
const REMIND_AT = NIGHT ? '8:42 PM' : '10:32 AM';
const TALKS = {
  volume: [['you', 'Turn it down a bit'], ['done', ['Volume 16']], ['says', 'Turned it down to 16.'], ['next', ['A little more', 'Mute it']]],
  watch: [['you', 'Something light to watch tonight'], ['says', 'How about Glass Onion? A twisty comedy-mystery that’s easy to enjoy.'],
    ['pick', PICK, 0], ['next', ["Who's in it?", 'Something else']]],
  cast: [['you', 'Something light to watch tonight'], ['says', 'How about Glass Onion? A twisty comedy-mystery that’s easy to enjoy.'],
    ['pick', PICK, -1], ['you', "Who's in it?"],
    ['says', 'Daniel Craig is back as detective Benoit Blanc, with Edward Norton, Janelle Monáe, Kathryn Hahn and Leslie Odom Jr.'],
    ['next', ['Play it', 'Something else'], 0]],
  remind: [['you', 'Remind me in 2 minutes to check the oven'], ['done', ['Reminder at ' + REMIND_AT]],
    ['says', 'Sure, I’ll remind you in 2 minutes to check the oven.'], ['next', ['Make it 5 minutes', 'Cancel it']]],
  sleep: [['you', 'Turn the TV off in an hour'], ['done', ['Sleep in 60 min']], ['says', 'Sure, I’ll turn the TV off in an hour.'],
    ['next', ['Cancel the sleep timer', 'Make it 90 minutes']]],
};
function assistant(o) {
  o = Object.assign({talk: null, turns: null, rowFocus: true, typed: null, heard: null, listening: false, level: 0, alpha: 1, y: 0}, o || {});
  const py = 42, ph = 612 - 8 - 42;
  const p = pane({x: CX, y: py + o.y, w: CW, h: ph, r: CR, alpha: o.alpha});
  const inner = p.inner;
  inner.append(at(lamp(28), 10, 14, 44, 44), at(txt(17, '--ink', 600, 'Glass'), 56, 16, 240, 20));
  const sees = row({}, glyph('clock', 13, '--ink2', 2.2), txt(13, '--ink2', 400, 'Sees the desktop', {style: {marginLeft: '6px'}}));
  inner.append(at(sees, 56, 39, CW - 56 - 128, 17));
  const nc = quietPill('New chat', 13, 600, 10, 32, false);
  const ncw = h('div', {style: {position: 'absolute', top: '20px', right: '18px'}}, nc);
  inner.append(ncw, at(h('div', {style: {background: 'var(--hair)'}}), 0, 68, CW, 1));
  // the talk, its newest end at the bottom, fading out under the header
  const feedBox = at(h('div', {style: {overflow: 'hidden', padding: '10px 0',
    maskImage: 'linear-gradient(to bottom, transparent 0, #000 30px, #000 100%)', WebkitMaskImage: 'linear-gradient(to bottom, transparent 10px, #000 40px, #000 100%)'}}),
    20 - LIFT, HEAD, CW - 40 + 2 * LIFT, ph - HEAD - ROWH - 24);
  const feed = col({position: 'absolute', left: px(LIFT), right: px(LIFT), bottom: '10px'});
  // each turn arrives fading in as it rises 10 px (320 ms, OUT): the fifth item is how far it has come, 0..1
  (o.turns || TALKS[o.talk] || []).forEach(([who, what, focus, k], i) => {
    const v = who === 'pick' ? TURNS.pick(what, focus) : who === 'next' ? TURNS.next(what, focus) : TURNS[who](what);
    if (i) v.style.marginTop = '14px';
    if (k != null && k < 1) Object.assign(v.style, {opacity: k, transform: `translateY(${10 * (1 - k)}px)`});
    feed.append(v);
  });
  feedBox.append(feed);
  inner.append(feedBox);
  // the row: talk or type
  const r = at(h('div', {style: {borderRadius: '16px', background: 'var(--field)',
    boxShadow: o.rowFocus ? 'inset 0 0 0 2.5px var(--ink)' : 'inset 0 0 0 1px var(--hair)'}}), 12, ph - ROWH - 12, CW - 24, ROWH);
  r.append(at(lamp(30, o.listening ? o.level : 0), 6, 6, 44, 44));
  // what it hears comes up as it's said, the word still being heard fainter (AskActivity.words)
  const said = o.heard ? (o.heard.includes(' ') ? `${o.heard.slice(0, o.heard.lastIndexOf(' ') + 1)}<span style="color:var(--ink3)">${o.heard.slice(o.heard.lastIndexOf(' ') + 1)}</span>` : o.heard) : null;
  r.append(at(row({height: px(ROWH)}, said ? txt(17, '--ink', 500, said, {html: true}) :
    txt(17, o.typed ? '--ink' : '--ink3', 500, o.typed || (o.listening ? 'Listening…' : 'Ask Glass…'))), 56, 0, CW - 24 - 56 - 160));
  r.append(at(row({height: px(ROWH), justifyContent: 'flex-end'}, txt(13, '--ink3', 400, o.listening ? 'OK to send' : 'OK to talk')), CW - 24 - 52 - 130, 0, 120));
  const keys = h('div', {style: {width: '36px', height: '36px', borderRadius: '10px', background: 'var(--hover)', boxShadow: 'inset 0 0 0 1px var(--hair)',
    display: 'flex', alignItems: 'center', justifyContent: 'center'}}, glyph('text', 18, '--ink2', 2));
  r.append(at(keys, CW - 24 - 10 - 36, 10, 36, 36));
  inner.append(r);
  TV.append(p);
  return p;
}

// ---- notes and the volume (Hud) -------------------------------------------------------------------------------------
const NOTES = {
  reminder: ['clock', 'Reminder', 'Check the oven'],
  phone: ['phone', 'Phone connected', 'Phone'],
  updated: ['check', 'Glass', 'Updated to Glass 1.0.0'],
};
function note(kind, o) {
  o = Object.assign({alpha: 1, dy: 0}, o || {});
  const [gname, title, text] = NOTES[kind];
  const w = 420, hh = 14 + 18 + 2 + 24 + 14;
  const p = pane({x: 1280 - 16 - w, y: 42 + o.dy, w, h: hh, r: 22, alpha: o.alpha});
  p.inner.append(at(h('div', {}, glyph(gname, 22, '--ink')), 20, hh / 2 - 11, 22, 22), at(txt(13, '--ink2', 600, title), 56, 14, w - 76, 18),
    at(txt(17, '--ink', 500, text), 56, 14 + 18 + 2, w - 76, 24));
  TV.append(p);
  return p;
}
function volumePill(v, o) {
  o = Object.assign({alpha: 1, dy: 0, fill: v / 100}, o || {});
  const HW = 444, HV = 66, HX = (1280 - HW) / 2, HY = 720 - HV;
  const p = pane({x: HX, y: HY + o.dy, w: HW, h: HV + 34, r: 26, alpha: o.alpha});
  const track = at(h('div', {style: {borderRadius: '3px', overflow: 'hidden', background: NIGHT ? 'rgba(255,255,255,.16)' : 'rgba(22,24,29,.12)'}}), 62, 30, HW - 62 - 84, 6);
  track.append(h('div', {style: {height: '100%', width: (Math.max(0, o.fill) * 100) + '%', background: 'var(--ink)', borderRadius: '3px'}}));
  p.inner.append(at(h('div', {}, glyph(v ? 'volume' : 'mute', 22, '--ink')), 24, 22, 22, 22), track,
    at(row({height: px(HV), justifyContent: 'flex-end'}, txt(17, '--ink', 600, String(v), {cls: 'tnum'})), HW - 84, 0, 62, HV));
  TV.append(p);
  return p;
}

// ---- the widgets' sizes, as Sample.size shows them -----------------------------------------------------------------------
function sizes(size) {
  if (size === WS.S) {
    widget('clock', WS.S, 40, 70); widget('weather', WS.S, 206, 70); widget('playing', WS.S, 372, 70);
    widget('calendar', WS.S, 40, 236); widget('photos', WS.S, 206, 236);
  } else if (size === WS.M) {
    widget('clock', WS.M, 40, 70); widget('weather', WS.M, 40, 236); widget('photos', WS.M, 40, 402);
    widget('playing', WS.M, 372, 70); widget('calendar', WS.M, 372, 236);
  } else {
    widget('clock', WS.L, 40, 70); widget('weather', WS.L, 372, 70); widget('playing', WS.L, 704, 70);
  }
}

// ---- the screen savers (Saver) ---------------------------------------------------------------------------------------
/** The time, thin and large, and the date under it (Saver.clock). */
function saverClock(size, centred, tintc) {
  const c = col({alignItems: centred ? 'center' : 'flex-start'});
  const colr = tintc || 'rgba(255,255,255,.95)';
  c.append(txt(size, colr, 200, NOW.clock, {cls: 'o', style: {fontWeight: 200, letterSpacing: '-0.03em', overflow: 'visible', textShadow: '0 2px 20px rgba(0,0,0,.35)'}}),
    txt(Math.max(17, size / 5.2), tintc || 'rgba(255,255,255,.78)', 500, NOW.date, {style: {marginTop: px(size / 14), marginLeft: centred ? 0 : px(size / 22), textShadow: '0 1px 12px rgba(0,0,0,.35)'}}));
  return c;
}
/** The widgets along the bottom right, on dark glass whatever the look (Widgets.Strip). */
function saverStrip(alpha) {
  const r = row({position: 'absolute', right: '48px', bottom: '40px', alignItems: 'flex-end', opacity: alpha == null ? 1 : alpha});
  const was = TV.className;
  [['weather', WS.S], ['playing', WS.M], ['calendar', WS.S], ['photos', WS.S]].forEach(([id, size], i) => {
    const chip = h('div', {class: 'night', style: {position: 'relative', width: px(ww(size)), height: px(wh(size)), borderRadius: '22px', overflow: 'hidden', flex: 'none',
      marginLeft: i ? '14px' : 0, background: 'rgba(16,16,20,.46)', boxShadow: 'inset 0 0 0 1px rgba(255,255,255,.12)'}});
    const c = WIDGETS[id](size);
    if (c.style.position !== 'absolute') c.style.height = '100%';
    chip.append(c);
    r.append(chip);
  });
  TV.className = was;
  return r;
}
function saver(style) {
  const bg = (src, fit) => {
    const im = h('img', {src});
    Object.assign(im.style, {position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: fit || 'cover'});
    TV.append(im);
  };
  TV.style.background = '#000';
  const shadeLow = () => TV.append(at(h('div', {style: {background: 'linear-gradient(to top, rgba(0,0,0,.45), rgba(0,0,0,0))'}}), 0, 720 - 260, 1280, 260));
  if (style === 'clock') {
    bg(`${CACHE}wall-${LOOK}.jpg`);
    TV.append(h('div', {class: 'wall', style: {background: `rgba(0,0,0,${NIGHT ? .45 : .35})`}}));
    TV.append(at(saverClock(140, false), 72, 380));
  } else if (style === 'drift' || style === 'photos') {
    bg(ASSETS + (style === 'drift' ? 'walls/aurora-n.jpg' : 'walls/dunes-d.jpg'));
    shadeLow();
    TV.append(at(saverClock(64, false), 56, 520));
  } else if (style === 'glass') {
    bg(ASSETS + 'saver/glass.jpg');
    TV.append(at(saverClock(56, false), 56, 520));
  } else if (style === 'glow') {
    [['#2F5BFF', 120, -110, 560], ['#00B3A4', 640, -40, 640], ['#9B4DFF', -60, 260, 720], ['#FF7A3D', 760, 250, 560], ['#E0407A', 330, 120, 640]].forEach(([c, x, y, size]) => {
      TV.append(at(h('div', {style: {borderRadius: '50%', opacity: .55, background: `radial-gradient(closest-side, ${c}, ${c}80 50%, ${c}00)`}}), x, y, size, size));
    });
    TV.append(at(h('div', {style: {display: 'flex', justifyContent: 'center'}}, saverClock(120, true)), 0, 250, 1280));
  } else if (style === 'night') {
    TV.append(at(h('div', {style: {display: 'flex', justifyContent: 'center', opacity: .32}}, saverClock(72, true, '#FFB37A')), 0, 300, 1280));
  }
  TV.append(saverStrip(style === 'night' ? .3 : 1));
}

// ---- scenes ---------------------------------------------------------------------------------------------------------
const SCENES = {
  'saver-clock'() { saver('clock'); },
  'saver-drift'() { saver('drift'); },
  'saver-glass'() { saver('glass'); },
  'saver-glow'() { saver('glow'); },
  'saver-photos'() { saver('photos'); },
  'saver-night'() { saver('night'); },
  desktop() {
    wallpaper();
    desk();
    dock();
    bar();
  },
  dock() {
    wallpaper();
    desk();
    dock({focus: 1, running: ['youtube', 'netflix', 'spotify']});
    bar();
  },
  controls() {
    wallpaper();
    dock();
    bar({open: 'controls'});
    controls();
  },
  settings() {
    wallpaper();
    dock();
    bar({context: 'Settings'});
    settings();
  },
  'settings-speed'() {
    wallpaper();
    dock();
    bar({context: 'Settings'});
    settings({section: 6});
  },
  'controls-wifi'() {
    wallpaper();
    dock();
    bar({open: 'controls'});
    controlsWifi();
  },
  weather() {
    wallpaper();
    dock();
    bar({context: 'Settings'});
    weatherWin();
  },
  search() {
    wallpaper();
    dock();
    bar({open: 'search'});
    searchWin({query: 'net', focus: 0});
  },
  keyboard() {   // the keyboard covers the dock: it's left out, so nothing shows through
    wallpaper();
    bar({open: 'search'});
    searchWin({query: '', typing: true});
    keyboard();
  },
  apps() {
    wallpaper();
    allApps({focus: 0});
  },
  files() {
    wallpaper();
    dock();
    bar({context: 'Files'});
    filesWin({focus: 0});
  },
  inputs() {
    wallpaper();
    dock();
    bar({open: 'inputs'});
    inputs();
  },
  switcher() {
    wallpaper();
    dock();
    bar();
    switcher({focus: 0});
  },
  phone() {
    wallpaper();
    dock();
    bar({context: 'Settings'});
    phoneWin();
  },
  'ask-volume'() { askScene('volume', true); },
  'ask-watch'() { askScene('watch', false); },
  'ask-cast'() { askScene('cast', false); },
  'ask-remind'() { askScene('remind', true); },
  'ask-sleep'() { askScene('sleep', true); },
  'note-reminder'() { noteScene('reminder'); },
  'note-phone'() { noteScene('phone'); },
  'note-updated'() { noteScene('updated'); },
  volume() {   // the pill along the bottom edge, as over an app: nothing under it
    wallpaper();
    bar();
    volumePill(VOLUME);
  },
  'widgets-small'() { sizeScene(WS.S); },
  'widgets-medium'() { sizeScene(WS.M); },
  'widgets-large'() { sizeScene(WS.L); },
  'solo-clock'() { soloScene('clock'); },
  'solo-weather'() { soloScene('weather'); },
  'solo-playing'() { soloScene('playing'); },
  'solo-calendar'() { soloScene('calendar'); },
  'solo-photos'() { soloScene('photos'); },
};
function askScene(talk, rowFocus) {
  wallpaper();
  desk(['calendar', 'playing']);
  dock();
  bar({open: 'assist'});
  assistant({talk, rowFocus});
}
function noteScene(kind) {   // a note over the bare desktop, so the note is what you look at
  wallpaper();
  dock();
  bar();
  note(kind);
}
function sizeScene(size) {
  wallpaper();
  sizes(size);
  dock();
  bar();
}
function soloScene(id) {
  wallpaper();
  widget(id, WS.M, 40, 70);
  dock();
  bar();
}

// ---- boot -----------------------------------------------------------------------------------------------------------
async function boot() {
  GLYPHS = await (await fetch(CACHE + 'glyphs.json')).json();
  const [sharp, frost] = await Promise.all([load('sharp', CACHE + `sharp-${LOOK}.jpg`), load('frost', CACHE + `frost-${LOOK}.jpg`)]);
  TEX = {sharp, frost};
  if (Q.get('film')) {
    const film = window.FILMS && window.FILMS[Q.get('film')];
    if (!film) throw new Error('no film: ' + Q.get('film'));
    window.FILM = {duration: film.duration, parts: film.parts};
    window.frame = async t => {
      TV.textContent = '';
      pending.length = 0;
      film.draw(t);
      for (const fn of pending) fn();
      await Promise.all([...TV.querySelectorAll('img')].map(im => im.decode().catch(() => 0)));
      return true;
    };
    await document.fonts.ready;
    await window.frame(0);
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    window.READY = true;
    return;
  }
  const make = SCENES[SCENE];
  if (!make) throw new Error('no scene: ' + SCENE);
  make();
  for (const fn of pending) fn();
  await document.fonts.ready;
  await Promise.all([...TV.querySelectorAll('img')].map(im => im.complete ? 0 : new Promise(ok => { im.onload = im.onerror = ok; })));
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  window.READY = true;
}
boot().catch(e => {
  document.title = 'error: ' + e.message;
  window.READY = 'error: ' + e.message;
  console.error(e);
});
