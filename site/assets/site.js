/* Glass, the website's behaviour. Nothing here is needed to read the page; everything answers something you do, or
   something coming into view. The mode (light or dark) is :root[data-mode], set before the first paint (see the head);
   here it changes behind a ring of light that grows from the switch: the new mode inside the ring, the old one outside,
   the ring and the edge of the new mode on one clock. */
(function () {
  'use strict';
  var root = document.documentElement;
  var $ = function (s, el) { return (el || document).querySelector(s); };
  var $$ = function (s, el) { return Array.prototype.slice.call((el || document).querySelectorAll(s)); };
  var reduced = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  var KEY = 'glass-look';
  var page = $('main.docs') ? 'docs' : $('main.lost') ? 'lost' : 'index';
  var mode = function () { return root.getAttribute('data-mode') === 'dark' ? 'dark' : 'light'; };
  var shows = function (el) { return !!(el && (el.offsetParent !== null || el.getClientRects().length)); };

  function visible(el, fn, ratio) {   // fn(true|false) as el comes into and leaves view
    if (!('IntersectionObserver' in window)) { fn(true); return; }
    new IntersectionObserver(function (es) { es.forEach(function (e) { fn(e.isIntersecting); }); },
      { threshold: ratio == null ? 0.25 : ratio }).observe(el);
  }
  function scrollInto(list, item) {   // keep a chosen chip in its row's view, without moving the page
    if (!list || !item) return;
    if (list.scrollWidth > list.clientWidth + 2) {
      var left = item.offsetLeft - list.offsetLeft - list.clientWidth / 2 + item.offsetWidth / 2;
      list.scrollTo({ left: Math.max(0, left), behavior: reduced ? 'auto' : 'smooth' });
    }
    if (list.scrollHeight > list.clientHeight + 2) {
      var top = item.offsetTop - list.offsetTop - list.clientHeight / 2 + item.offsetHeight / 2;
      list.scrollTo({ top: Math.max(0, top), behavior: reduced ? 'auto' : 'smooth' });
    }
  }

  // ---- the mode, and the halo of light it changes behind ---------------------------------------------------------
  // After Kubai's shockwave halo, in its Frost palette: four broad rings of light grow from the switch, very fast at
  // first and slow near the far edge, and fade as they go (one second). Over the page's light look they tint it; over
  // its dark look they add light (screen). The new mode comes in under them through a soft edge, the page's rules and
  // borders catch the light as they pass, and the screen's edges light up where the halo meets them. The bar stays
  // clear. One clock draws every layer, frame by frame, so nothing can drift apart.
  var halo = $('.halo');
  var tint = halo && $('.halo-tint', halo), lit = halo && $('.halo-lit', halo), glint = halo && $('.halo-glint', halo);
  var FROST = {
    dark: [[80, 220, 175], [70, 195, 225], [180, 240, 150], [235, 255, 245]],
    light: [[40, 190, 150], [50, 170, 210], [140, 210, 90], [180, 235, 215]]
  };
  var RINGS = [-1.5, -0.5, 0.5, 1.5], DUR = 1000 * (window.glassHaloSlow || 1), FEATHER = 44, LO = -3.4, HI = 3.4, N = 34;
  function ring(x, pal) {   // the colour x ring-widths from the halo's middle: the four rings, blended where they meet
    var sw = 0, r = 0, g = 0, b = 0;
    for (var i = 0; i < 4; i++) { var k = (x - RINGS[i]) / 0.8, w = Math.exp(-k * k); sw += w; r += w * pal[i][0]; g += w * pal[i][1]; b += w * pal[i][2]; }
    return [r / sw, g / sw, b / sw];
  }
  var growth = function (t) { return (1 - Math.pow(2, -9 * t)) / (1 - Math.pow(2, -9)); };
  var strength = function (t) { return Math.min(1, t / 0.03) * (1 - Math.pow(t, 2.2)); };
  var c255 = function (v) { return Math.max(0, Math.min(255, Math.round(v))); };
  var SVGNS = 'http://www.w3.org/2000/svg';
  var GLINT = '.screen, .frame, .cmd, .code, .chip, .asks .tab, .seg > .tablist, .btn, .callout, .credits a, .rows > li, ' +
    '.keys > li, .flags li, .endpoints li, .keylist > li, .table th, .table td, .more-d, .item, .bars .g, .term-ctl, ' +
    '.people a, .foot, .release + .release, .grp-pick select, p code, li code, td code';
  function svg(tag, attrs, parent) {
    var el = document.createElementNS(SVGNS, tag);
    for (var k in attrs) el.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(el);
    return el;
  }
  // the page's rules and borders on screen, as hairlines where they are: they're what catches the light
  function lines(W, H, top) {
    while (glint.firstChild) glint.removeChild(glint.firstChild);
    var defs = svg('defs', {}, glint), grads = {};
    ['hg', 'he'].forEach(function (id) {
      var g = svg('radialGradient', { id: id, gradientUnits: 'userSpaceOnUse', cx: 0, cy: 0, r: 1 }, defs);
      grads[id] = { el: g, stops: [] };
      for (var j = 0; j <= N; j++) grads[id].stops.push(svg('stop', { offset: 0, 'stop-color': '#fff', 'stop-opacity': 0 }, g));
    });
    var gl = svg('g', { fill: 'none', stroke: 'url(#hg)', 'stroke-width': 1 }, glint);
    $$(GLINT).forEach(function (el) {
      var b = el.getBoundingClientRect();
      if (!b.width || !b.height || b.bottom < top || b.top > H || b.right < 0 || b.left > W) return;
      var cs = getComputedStyle(el), side = function (s) { return cs['border' + s + 'Style'] !== 'none' ? parseFloat(cs['border' + s + 'Width']) || 0 : 0; };
      var t = side('Top'), r = side('Right'), bo = side('Bottom'), l = side('Left');
      var dash = cs.borderTopStyle === 'dashed' || cs.borderBottomStyle === 'dashed' ? { 'stroke-dasharray': '3 3' } : {};
      if (t && r && bo && l) {
        var rad = Math.min(parseFloat(cs.borderTopLeftRadius) || 0, b.width / 2, b.height / 2);
        svg('rect', Object.assign({ x: b.left + l / 2, y: b.top + t / 2, width: Math.max(0, b.width - (l + r) / 2), height: Math.max(0, b.height - (t + bo) / 2), rx: rad }, dash), gl);
        return;
      }
      if (t) svg('line', Object.assign({ x1: b.left, y1: b.top + t / 2, x2: b.right, y2: b.top + t / 2 }, dash), gl);
      if (bo) svg('line', Object.assign({ x1: b.left, y1: b.bottom - bo / 2, x2: b.right, y2: b.bottom - bo / 2 }, dash), gl);
      if (l) svg('line', { x1: b.left + l / 2, y1: b.top, x2: b.left + l / 2, y2: b.bottom }, gl);
      if (r) svg('line', { x1: b.right - r / 2, y1: b.top, x2: b.right - r / 2, y2: b.bottom }, gl);
    });
    // the screen's sides and foot, where the halo meets them: a thin line of light with a soft fall-off inside it
    var edge = 'M1 ' + top + ' V' + (H - 1) + ' H' + (W - 1) + ' V' + top;
    svg('path', { d: edge, fill: 'none', stroke: 'url(#he)', 'stroke-width': 12, opacity: 0.22 }, glint);
    svg('path', { d: edge, fill: 'none', stroke: 'url(#he)', 'stroke-width': 3 }, glint);
    return grads;
  }
  // one frame of every layer: where the halo is (r), how bright (s), and which look each side of its middle is in
  function draw(f, r, s) {
    var tS = ['transparent 0px'], lS = ['rgb(0 0 0) 0px'], R = r + HI * f.w;
    for (var j = 0; j <= N; j++) {
      var x = LO + (HI - LO) * j / N, d = Math.max(0, r + x * f.w);
      var m = f.vt ? Math.max(0, Math.min(1, (r + FEATHER - d) / (2 * FEATHER))) : 1;   // how much of the new mode shows here
      var light = f.next === 'light' ? m : 1 - m;                                         // how much of the page here is light
      var a = s * Math.exp(-1.1 * (x / 2) * (x / 2));
      var cl = ring(x, FROST.light), cd = ring(x, FROST.dark), A = a * 0.24 * light, k = a * 0.45 * (1 - light);
      var px = d.toFixed(1) + 'px';
      tS.push('rgb(' + c255(cl[0] * 1.2) + ' ' + c255(cl[1] * 1.2) + ' ' + c255(cl[2] * 1.2) + ' / ' + A.toFixed(4) + ') ' + px);
      lS.push('rgb(' + c255(cd[0] * k) + ' ' + c255(cd[1] * k) + ' ' + c255(cd[2] * k) + ') ' + px);
      if (f.grads) {
        var c = light >= 0.5 ? cl : cd, off = (d / R).toFixed(4);
        var g = s * Math.exp(-1.4 * (x / 2) * (x / 2)) * (0.3 * light + 0.14 * (1 - light));
        var hs = f.grads.hg.stops[j], es = f.grads.he.stops[j];
        hs.setAttribute('offset', off); es.setAttribute('offset', off);
        hs.setAttribute('stop-color', 'rgb(' + c255(c[0] * 0.8 + 51) + ',' + c255(c[1] * 0.8 + 51) + ',' + c255(c[2] * 0.8 + 51) + ')');
        hs.setAttribute('stop-opacity', g.toFixed(4));
        es.setAttribute('stop-color', 'rgb(' + c255(c[0] * 0.55 + 115) + ',' + c255(c[1] * 0.55 + 115) + ',' + c255(c[2] * 0.55 + 115) + ')');
        es.setAttribute('stop-opacity', (a * (0.6 * light + 0.85 * (1 - light))).toFixed(4));
      }
    }
    tS.push('transparent ' + (R + 1).toFixed(1) + 'px');
    lS.push('rgb(0 0 0) ' + (R + 1).toFixed(1) + 'px');
    var at = 'radial-gradient(circle at ' + f.x + 'px ' + f.y + 'px, ';
    tint.style.background = at + tS.join(', ') + ')';
    lit.style.background = at + lS.join(', ') + ')';
    if (f.grads) ['hg', 'he'].forEach(function (id) {
      var el = f.grads[id].el;
      el.setAttribute('cx', f.x); el.setAttribute('cy', f.y); el.setAttribute('r', Math.max(1, R).toFixed(1));
    });
  }
  var toggles = $$('[data-mode-toggle]');
  function label() {
    toggles.forEach(function (b) { b.setAttribute('aria-label', mode() === 'dark' ? 'Light mode' : 'Dark mode'); b.setAttribute('aria-pressed', mode() === 'dark' ? 'true' : 'false'); });
  }
  function save(m) {
    try { localStorage.setItem(KEY, m); } catch (e) { /* private window: the session keeps it */ }
    try { sessionStorage.setItem(KEY, m); } catch (e) { /* nothing keeps it; the page still works */ }
  }
  // ---- a film's still comes a screen before the film does, so the first screen loads only what it shows --------------
  function still(v) { var p = v.getAttribute('data-poster'); if (p && !v.getAttribute('poster')) v.setAttribute('poster', p); }
  var stills = $$('video[data-poster]');
  if ('IntersectionObserver' in window) {
    var near = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { still(e.target); near.unobserve(e.target); } });
    }, { rootMargin: '100% 0px' });
    stills.forEach(function (v) { near.observe(v); });
  } else stills.forEach(still);

  // the pictures, films and posters of the coming look that are in view, decoded first (a fifth of a second at most),
  // so the new look arrives whole instead of filling in after the halo has passed
  function prepare(next) {
    var jobs = [];
    $$('.l-' + next).forEach(function (el) {
      var box = (el.parentElement || el).getBoundingClientRect();
      if (box.bottom < -300 || box.top > innerHeight + 300 || !box.width) return;
      var img = el.tagName === 'PICTURE' ? el.querySelector('img') : el.tagName === 'IMG' ? el : null;
      if (img) { img.loading = 'eager'; if (img.decode) jobs.push(img.decode().catch(function () {})); }
      if (el.tagName === 'VIDEO') still(el);
      if (el.tagName === 'VIDEO' && el.poster) { var p = new Image(); p.src = el.poster; if (p.decode) jobs.push(p.decode().catch(function () {})); }
    });
    return Promise.race([Promise.all(jobs), new Promise(function (r) { setTimeout(r, 200); })]);
  }
  var busy = false;
  function setMode(next, from) {
    if (busy || next === mode()) return;
    save(next);
    var apply = function () { root.setAttribute('data-mode', next); label(); document.dispatchEvent(new CustomEvent('glass:mode')); };
    if (reduced || !halo || !tint) { apply(); return; }
    busy = true;
    var W = innerWidth, H = innerHeight, nav = $('.nav'), top = nav ? Math.round(nav.getBoundingClientRect().bottom) : 0;
    var b = from ? from.getBoundingClientRect() : { left: W / 2, top: 0, width: 0, height: 0 };
    var x = Math.round(b.left + b.width / 2), y = Math.round(b.top + b.height / 2);
    // it ends just past the farthest corner of the page under the bar, so the whole page has changed by then
    var far = Math.max(Math.hypot(x, top - y), Math.hypot(W - x, top - y), Math.hypot(x, H - y), Math.hypot(W - x, H - y));
    var rEnd = Math.ceil(far + FEATHER + 30);
    var f = { x: x, y: y, w: Math.max(70, Math.min(110, Math.min(W, H) * 0.125)), next: next, vt: !!document.startViewTransition };
    prepare(next).then(function () {
      root.classList.add('switching');
      f.grads = lines(W, H, top);
      draw(f, 0, 0);
      var reveal = null, frostAt = null, frostOn = null, t0 = 0, over = false, guard = 0;
      root.style.setProperty('--hw', f.w.toFixed(1) + 'px');
      var done = function () {
        if (over) return;
        over = true; clearTimeout(guard);
        halo.classList.remove('run'); tint.style.background = ''; lit.style.background = '';
        while (glint.firstChild) glint.removeChild(glint.firstChild);
        root.classList.remove('switching'); root.style.removeProperty('--hw'); busy = false;
      };
      var frame = function (now) {
        if (over) return;
        if (!t0) t0 = now;
        var t = Math.min(1, (now - t0) / DUR), g = growth(t);
        if (reveal) { try { reveal.currentTime = DUR * g; } catch (e) { /* the transition has gone */ } }
        if (frostAt) { try { frostAt.currentTime = DUR * g; frostOn.currentTime = DUR * strength(t); } catch (e) { /* gone */ } }
        draw(f, rEnd * g, strength(t));
        if (t < 1) requestAnimationFrame(frame);
        else if (reveal) { try { if (frostAt) { frostAt.finish(); frostOn.finish(); } reveal.finish(); } catch (e) { done(); } }
        else done();
      };
      if (f.vt) {
        var vt = document.startViewTransition(function () { apply(); halo.classList.add('run'); });
        vt.ready.then(function () {
          var R0 = FEATHER, R1 = rEnd + FEATHER;
          var size = function (R) { return 2 * R + 'px ' + 2 * R + 'px'; }, pos = function (R) { return (x - R) + 'px ' + (y - R) + 'px'; };
          var kf = [{ maskSize: size(R0), maskPosition: pos(R0), webkitMaskSize: size(R0), webkitMaskPosition: pos(R0) },
                    { maskSize: size(R1), maskPosition: pos(R1), webkitMaskSize: size(R1), webkitMaskPosition: pos(R1) }];
          reveal = root.animate(kf, { duration: DUR, easing: 'linear', fill: 'both', pseudoElement: '::view-transition-new(root)' });
          reveal.pause();
          reveal.currentTime = 0;
          // the frost: under the halo's middle the page softens a touch, as through thick glass, where one look gives
          // way to the other; it follows the halo and fades with it
          var B0 = 2.1 * f.w, B1 = rEnd + 2.1 * f.w;
          try {
            frostAt = root.animate([{ maskSize: size(B0), maskPosition: pos(B0), webkitMaskSize: size(B0), webkitMaskPosition: pos(B0) },
                                    { maskSize: size(B1), maskPosition: pos(B1), webkitMaskSize: size(B1), webkitMaskPosition: pos(B1) }],
              { duration: DUR, easing: 'linear', fill: 'both', pseudoElement: '::view-transition-group(halo-frost)' });
            frostOn = root.animate([{ opacity: 0 }, { opacity: 1 }], { duration: DUR, easing: 'linear', fill: 'both', pseudoElement: '::view-transition-group(halo-frost)' });
            frostAt.pause(); frostOn.pause(); frostAt.currentTime = 0; frostOn.currentTime = 0;
          } catch (e) { frostAt = frostOn = null; }
          requestAnimationFrame(frame);
        }).catch(done);
        vt.finished.then(done, done);
        guard = setTimeout(function () { try { vt.skipTransition(); } catch (e) { /* gone */ } done(); }, DUR + 1500);
      } else {   // no view transitions: the page changes at once and the halo of light passes over it
        apply();
        halo.classList.add('run');
        requestAnimationFrame(frame);
      }
    });
  }
  toggles.forEach(function (b) { b.addEventListener('click', function () { setMode(mode() === 'dark' ? 'light' : 'dark', b); }); });
  label();
  if (window.MutationObserver) new MutationObserver(function () { label(); document.dispatchEvent(new CustomEvent('glass:mode')); })
    .observe(root, { attributes: true, attributeFilter: ['data-mode'] });

  // ---- the bar; the name and Docs always take you to the top of their page ----------------------------------------
  var nav = $('.nav');
  if (nav) {
    var stuck = function () { nav.classList.toggle('stuck', window.scrollY > 4); };
    window.addEventListener('scroll', stuck, { passive: true });
    stuck();
  }
  function toTop() {
    window.scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' });
    if (history.replaceState) history.replaceState(null, '', location.pathname + location.search);
  }
  $$('[data-top-of]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      if (a.getAttribute('data-top-of') !== page || e.metaKey || e.ctrlKey || e.shiftKey) return;   // the other page: it opens at its top
      e.preventDefault();
      closeRail();
      toTop();
    });
  });
  $$('[data-top]').forEach(function (a) { a.addEventListener('click', function (e) { e.preventDefault(); closeRail(); toTop(); }); });
  if (!location.hash) window.scrollTo(0, 0);

  // ---- a row wider than its column scrolls sideways, its ends fading; one that fits doesn't fade ---------------------
  var rowsOver = function () {
    $$('.tablist, .chips').forEach(function (r) { r.classList.toggle('over', r.scrollWidth > r.clientWidth + 2); });
  };
  rowsOver();
  addEventListener('resize', function () { requestAnimationFrame(rowsOver); });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(rowsOver);

  // ---- copy ----------------------------------------------------------------------------------------------------
  $$('.copy').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var host = btn.closest('[data-copy]');
      var text = host ? host.getAttribute('data-copy') : '';
      var ok = function () { btn.classList.add('done'); setTimeout(function () { btn.classList.remove('done'); }, 1600); };
      var pick = function () {   // no clipboard here: select the text, ready to copy by hand
        var t = host && host.querySelector('code, pre');
        if (!t) return;
        var r = document.createRange(); r.selectNodeContents(t);
        var s = getSelection(); s.removeAllRanges(); s.addRange(r);
      };
      try { navigator.clipboard.writeText(text).then(ok, pick); } catch (e) { pick(); }
    });
  });
  // a command too long for its box fades at the end, so it's clear there's more of it to scroll to
  $$('.cmd code').forEach(function (c) {
    var f = function () { c.classList.toggle('more', c.scrollWidth - c.scrollLeft - c.clientWidth > 2); };
    f(); c.addEventListener('scroll', f, { passive: true }); addEventListener('resize', f);
  });

  // ---- films: the one for the mode plays while it's on screen; a change of mode carries on from the same moment -----
  function filmOf(fig) { var vs = $$('video', fig); return { all: vs, now: function () { return vs.filter(shows)[0] || vs[0]; } }; }
  function handOver(f, playing) {
    var to = f.now();
    f.all.forEach(function (v) {
      if (v === to || v.readyState === 0) return;
      try { to.currentTime = v.currentTime; } catch (e) { /* not loaded yet */ }
      v.pause();
    });
    if (playing) { to.preload = 'auto'; to.play().catch(function () {}); }
  }
  function media(panel, on) {   // films in a panel: playing while it's chosen and on screen
    $$('video', panel).forEach(function (v) { if (!on) v.pause(); });
    if (on) $$('video[data-inview]', panel).forEach(function (v) { if (shows(v)) { v.preload = 'auto'; v.play().catch(function () {}); } });
    if (on) $$('[data-film], [data-pov]', panel).forEach(function (f) { if (f.glassPlay) f.glassPlay(); });
    if (!on) $$('[data-film], [data-pov]', panel).forEach(function (f) { if (f.glassPause) f.glassPause(); });
  }
  $$('video[data-inview]').forEach(function (v) {
    if (v.closest('[data-film], [data-pov]')) return;
    visible(v.parentElement, function (on) {
      if (on && shows(v) && !v.closest('[hidden]')) { v.preload = 'auto'; v.play().catch(function () {}); } else v.pause();
    }, 0.4);
  });
  document.addEventListener('glass:mode', function () {
    $$('.screen').forEach(function (s) {
      var vs = $$('video[data-inview]', s);
      if (vs.length < 2) return;
      var was = vs.some(function (v) { return !v.paused; });
      handOver({ all: vs, now: function () { return vs.filter(shows)[0] || vs[0]; } }, was);
    });
  });

  // the remote's film: its buttons follow along, and start it from their part
  $$('[data-film]').forEach(function (fig) {
    var f = filmOf(fig);
    var cues = $$('.cue', fig);
    var row = cues.length ? cues[0].closest('ul') : null;
    var scroller = fig.querySelector('.keylist') || row;
    var ts = cues.map(function (c) { return parseFloat(c.getAttribute('data-t')); });
    var nowEl = fig.querySelector('.film-now');
    var at = -1, raf = 0, inView = false, last = 0;
    function tick() {
      var v = f.now(), t = v.currentTime, d = v.duration || 0;
      if (t + 1 < last) fig.dispatchEvent(new CustomEvent('glass:loop', { bubbles: true }));
      last = t;
      fig.glassProgress = d ? t / d : 0;
      var i = 0;
      ts.forEach(function (x, j) { if (t >= x - 0.05) i = j; });
      if (i !== at && cues.length) {
        at = i;
        cues.forEach(function (c, j) { c.setAttribute('aria-current', j === i ? 'true' : 'false'); if (j !== i) c.style.setProperty('--p', '0'); });
        if (nowEl) nowEl.textContent = cues[i].getAttribute('data-says');
        scrollInto(scroller, cues[i].closest('li') || cues[i]);
      }
      if (cues.length) {
        var end = i + 1 < ts.length ? ts[i + 1] : d;
        if (end > ts[i]) cues[i].style.setProperty('--p', Math.min(1, Math.max(0, (t - ts[i]) / (end - ts[i]))).toFixed(3));
      }
      if (!v.paused) raf = requestAnimationFrame(tick);
    }
    function play() {
      if (fig.closest('[hidden]') || !inView) return;
      var v = f.now(); v.preload = 'auto';
      v.play().then(function () { fig.classList.remove('paused'); cancelAnimationFrame(raf); raf = requestAnimationFrame(tick); }).catch(function () {});
    }
    function pause() { f.all.forEach(function (v) { v.pause(); }); fig.classList.add('paused'); }
    fig.glassPlay = function () { if (!reduced) play(); };
    fig.glassPause = pause;
    cues.forEach(function (c, j) {
      c.addEventListener('click', function () {
        var v = f.now(); v.preload = 'auto'; try { v.currentTime = ts[j] + 0.01; } catch (e) { /* loading */ }
        last = 0; inView = true; play(); tick();
        fig.dispatchEvent(new CustomEvent('glass:touched', { bubbles: true }));
      });
    });
    var btn = fig.querySelector('.film-play');
    var toggle = function (e) { if (e) e.stopPropagation(); if (f.now().paused) { inView = true; play(); } else pause(); fig.dispatchEvent(new CustomEvent('glass:touched', { bubbles: true })); };
    if (btn) btn.addEventListener('click', toggle);
    var scr = fig.querySelector('.screen');
    if (scr) scr.addEventListener('click', function (e) { if (e.target.closest('.film-play')) return; toggle(); });
    f.all.forEach(function (v) { v.addEventListener('play', function () { cancelAnimationFrame(raf); raf = requestAnimationFrame(tick); }); });
    visible(fig, function (v) { inView = v; if (v && !reduced) play(); else if (!v) pause(); }, 0.4);
    document.addEventListener('glass:mode', function () { var was = inView && !fig.classList.contains('paused') && !fig.closest('[hidden]'); handOver(f, was); });
  });

  // ---- the phone, from where you sit: each touch on the phone, then the TV answering --------------------------------
  $$('[data-pov]').forEach(function (fig) {
    var beats = []; try { beats = JSON.parse(fig.getAttribute('data-pov')); } catch (e) { return; }
    var f = filmOf(fig.querySelector('.pov-tv'));
    var scr = fig.querySelector('.pscreen'), touch = fig.querySelector('.touch'), ripple = fig.querySelector('.ripple');
    var cues = $$('.cue', fig), row = cues.length ? cues[0].closest('ul') : null, nowEl = fig.querySelector('.film-now');
    var cur = -1, raf = 0, inView = false, lastTap = -1;
    var first = beats[0].t, lastEnd = beats[beats.length - 1].end;
    function beatAt(t) { for (var i = 0; i < beats.length; i++) if (t >= beats[i].t - 0.02 && t < beats[i].end) return i; return -1; }
    function show(i) {
      if (i === cur || i < 0) return;
      if (i < cur) fig.dispatchEvent(new CustomEvent('glass:loop', { bubbles: true }));
      cur = i;
      cues.forEach(function (c, j) { c.setAttribute('aria-current', j === i ? 'true' : 'false'); if (j !== i) c.style.setProperty('--p', '0'); });
      if (nowEl) nowEl.textContent = beats[i].says;
      scrollInto(row, cues[i] && cues[i].closest('li'));
    }
    function put(p) { touch.style.setProperty('--tx', p[0] + '%'); touch.style.setProperty('--ty', p[1] + '%'); }
    function tick() {
      var v = f.now(), t = v.currentTime;
      var i = beatAt(t);
      if (i < 0) {   // between beats (the film's other parts): on to the next beat, or back to the first
        var next = beats.filter(function (b) { return b.t > t; })[0] || beats[0];
        try { v.currentTime = next.t + 0.01; } catch (e) { /* loading */ }
        raf = requestAnimationFrame(tick); return;
      }
      show(i);
      var b = beats[i];
      fig.glassProgress = Math.min(1, Math.max(0, (t - first) / (lastEnd - first)));
      if (cues[i]) cues[i].style.setProperty('--p', Math.min(1, (t - b.t) / (b.end - b.t)).toFixed(3));
      var on = false;
      b.g.forEach(function (g) {
        if (g.swipe && t >= g.at - 0.34 && t <= g.at + 0.06) {   // a swipe: the touch travels across the touchpad
          var k = Math.min(1, Math.max(0, (t - (g.at - 0.34)) / 0.34)); k = k * k * (3 - 2 * k);
          put([g.swipe[0][0] + (g.swipe[1][0] - g.swipe[0][0]) * k, g.swipe[0][1] + (g.swipe[1][1] - g.swipe[0][1]) * k]);
          on = true;
        }
        if (g.tap && t >= g.at - 0.14 && t <= g.at + 0.16) {    // a tap: the touch, and a ring from it
          put(g.tap); on = true;
          if (t >= g.at && lastTap !== g.at) {
            lastTap = g.at;
            scr.style.setProperty('--fx', g.tap[0] + '%'); scr.style.setProperty('--fy', g.tap[1] + '%');
            ripple.classList.remove('go'); void ripple.offsetWidth; ripple.classList.add('go');
          }
        }
      });
      touch.classList.toggle('on', on);
      if (!v.paused) raf = requestAnimationFrame(tick);
    }
    function play() {
      if (fig.closest('[hidden]') || !inView) return;
      var v = f.now(); v.preload = 'auto';
      if (beatAt(v.currentTime) < 0) { try { v.currentTime = beats[0].t + 0.01; } catch (e) { /* loading */ } }
      v.play().then(function () { cancelAnimationFrame(raf); raf = requestAnimationFrame(tick); }).catch(function () {});
    }
    function pause() { f.all.forEach(function (v) { v.pause(); }); touch.classList.remove('on'); }
    fig.glassPlay = function () { if (!reduced) play(); };
    fig.glassPause = pause;
    cues.forEach(function (c, j) {
      c.addEventListener('click', function () {
        var v = f.now(); try { v.currentTime = beats[j].t + 0.01; } catch (e) { /* loading */ }
        cur = j - 1 < 0 ? -1 : cur; inView = true; play();
        fig.dispatchEvent(new CustomEvent('glass:touched', { bubbles: true }));
      });
    });
    f.all.forEach(function (v) { v.style.transition = 'none'; });
    visible(fig, function (on) { inView = on; if (on && !reduced) play(); else if (!on) pause(); }, 0.4);
    document.addEventListener('glass:mode', function () { handOver(f, inView && !reduced && !fig.closest('[hidden]')); });
  });

  // ---- tabs: chosen by you, or one after another while they're on screen; the chosen one always in view -----------
  $$('[data-tabs]').forEach(function (box) {
    var list = box.querySelector('[role="tablist"]');
    var tabs = $$('[role="tab"]', list).filter(function (t) { return t.closest('[data-tabs]') === box; });
    var panels = tabs.map(function (t) { return document.getElementById(t.getAttribute('aria-controls')); });
    var cur = Math.max(0, tabs.findIndex(function (t) { return t.getAttribute('aria-selected') === 'true'; }));
    var auto = box.getAttribute('data-auto') || '', film = auto === 'film', ms = film ? 0 : parseInt(auto || '0', 10);
    var stopped = !auto || reduced, inView = false, hover = false, elapsed = 0, lastNow = 0;
    function choose(i, focus, byYou) {
      if (byYou) stop();
      if (i === cur && !focus) return;
      tabs.forEach(function (t, j) { t.setAttribute('aria-selected', j === i ? 'true' : 'false'); t.tabIndex = j === i ? 0 : -1; t.style.removeProperty('--p'); });
      panels.forEach(function (p, j) { if (p) { p.hidden = j !== i; if (j !== i) media(p, false); } });
      cur = i; elapsed = 0;
      if (focus) tabs[i].focus({ preventScroll: true });
      scrollInto(list, tabs[i]);
      if (inView) media(panels[i], true);
      rowsOver();
      box.dispatchEvent(new CustomEvent('tab', { detail: { panel: panels[i] } }));
    }
    function stop() { stopped = true; tabs.forEach(function (t) { t.classList.remove('running'); t.style.removeProperty('--p'); }); }
    tabs.forEach(function (t, i) {
      t.addEventListener('click', function () { choose(i, false, true); });
      t.addEventListener('keydown', function (e) {
        var n = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
        if (n) { e.preventDefault(); choose((cur + n + tabs.length) % tabs.length, true, true); }
        else if (e.key === 'Home') { e.preventDefault(); choose(0, true, true); }
        else if (e.key === 'End') { e.preventDefault(); choose(tabs.length - 1, true, true); }
      });
    });
    box.addEventListener('glass:touched', stop);
    box.addEventListener('pointerenter', function (e) { if (e.pointerType === 'mouse') hover = true; });
    box.addEventListener('pointerleave', function () { hover = false; });
    if (film) box.addEventListener('glass:loop', function (e) {
      if (stopped || !inView || !panels[cur] || !panels[cur].contains(e.target)) return;
      choose((cur + 1) % tabs.length);
    });
    visible(box, function (v) { inView = v; media(panels[cur], v); }, 0.3);
    if (!stopped) {
      (function frame(now) {
        if (stopped) return;
        var dt = lastNow ? Math.min(100, now - lastNow) : 0; lastNow = now;
        var paused = !inView || hover || document.hidden || box.contains(document.activeElement) && document.activeElement !== document.body
          || list.offsetParent === null;
        var t = tabs[cur];
        if (!paused) {
          if (film) {
            var fig = panels[cur] && panels[cur].querySelector('[data-film], [data-pov]');
            if (fig && fig.glassProgress != null) t.style.setProperty('--p', fig.glassProgress.toFixed(3));
          } else {
            elapsed += dt;
            var v = panels[cur] && $$('video', panels[cur]).filter(shows)[0];
            if (!(v && !v.paused && !v.ended && !v.loop && elapsed >= ms)) t.style.setProperty('--p', Math.min(1, elapsed / ms).toFixed(3));
            if (elapsed >= ms && !(v && !v.paused && !v.ended && !v.loop)) choose((cur + 1) % tabs.length);
          }
        }
        tabs.forEach(function (x, j) { x.classList.toggle('running', j === cur); });
        requestAnimationFrame(frame);
      })(performance.now());
    }
  });

  // ---- the bars: drawn as they come into view ------------------------------------------------------------------------
  $$('.bars').forEach(function (b) { visible(b, function (v) { if (v) b.classList.add('on'); }, 0.2); });

  // ---- a list beside a large view: the component browser, the changelog -------------------------------------------
  $$('[data-browser]').forEach(function (br) {
    var btns = $$('[data-view]', br);
    var grps = $$('.grp', br), sel = br.querySelector('.grp-pick select');
    function show(btn, focus) {
      if (!btn) return;
      var id = btn.getAttribute('data-view');
      btns.forEach(function (b) { b.setAttribute('aria-pressed', b === btn ? 'true' : 'false'); });
      $$('.view, .release', br).forEach(function (v) {
        var on = v.id === id;
        if (!on) $$('video', v).forEach(function (x) { x.pause(); });
        v.hidden = !on;
      });
      rowsOver();
      var g = btn.closest('.grp');
      if (g && !g.classList.contains('open')) open(g);
      if (focus) btn.focus({ preventScroll: true });
      scrollInto(btn.closest('ul'), btn.closest('li'));
    }
    function open(g) {
      grps.forEach(function (x) { var on = x === g; x.classList.toggle('open', on); var b = x.querySelector('.grp-b'); if (b) b.setAttribute('aria-expanded', on ? 'true' : 'false'); });
      if (sel) sel.value = String(grps.indexOf(g));
    }
    btns.forEach(function (b) {
      b.addEventListener('click', function () { show(b); });
      b.addEventListener('keydown', function (e) {
        var n = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
        if (!n) return;
        e.preventDefault();
        var live = btns.filter(shows), i = live.indexOf(b);
        show(live[(i + n + live.length) % live.length], true);
      });
    });
    grps.forEach(function (g) {
      var b = g.querySelector('.grp-b');
      if (b) b.addEventListener('click', function () {
        if (g.classList.contains('open')) { g.classList.remove('open'); b.setAttribute('aria-expanded', 'false'); return; }
        open(g); show(g.querySelector('[data-view]'));
      });
    });
    if (sel) sel.addEventListener('change', function () { var g = grps[+sel.value]; if (g) { open(g); show(g.querySelector('[data-view]')); } });
  });

  // ---- the installer's recordings, in the page's colours, at the size their fold allows ---------------------------------
  // The recording keeps the terminal's own colours; here each becomes what it means on this page, so it follows the
  // mode: words, quiet words, the frame, the hint track, done, and the accents (teal into aqua). On a narrow screen the
  // 44-column recording plays instead of the 100-column one, from the same step.
  function paint(hex) {
    if (!hex || hex === 'default') return null;
    var r = parseInt(hex.slice(0, 2), 16), g = parseInt(hex.slice(2, 4), 16), b = parseInt(hex.slice(4, 6), 16);
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 510, s = mx === mn ? 0 : (mx - mn) / (l > 0.5 ? 510 - mx - mn : mx + mn);
    if (g > r + 40 && g > b) return 'var(--t-ok)';
    if (s < 0.16) return l < 0.32 ? 'var(--t-track)' : l < 0.47 ? 'var(--t-frame)' : 'var(--t-dim)';
    if (r >= g && r > 190) { var k = Math.min(1, Math.max(0, (l - 0.6) / 0.33)); return 'color-mix(in oklab, var(--t-a1), var(--t-a2) ' + Math.round(k * 100) + '%)'; }
    return '#' + hex;
  }
  var playerN = 0;
  $$('[data-term]').forEach(function (term) {
    var id = 't' + (++playerN);
    term.setAttribute('data-p', id);
    var screen = term.querySelector('.term-screen'), playB = term.querySelector('.play'), scrub = term.querySelector('.scrub');
    var fill = term.querySelector('.scrub-fill'), timeEl = term.querySelector('.term-time'), speeds = $$('.speed button', term);
    var wrap = term.closest('.term-wrap'), stage = term.closest('.term-stage') || term.parentNode;
    var stepEl = wrap && wrap.querySelector('.term-step'), chapList = wrap && wrap.querySelector('.chapters');
    var chapBs = chapList ? $$('button', chapList) : [];
    var srcs = { w: term.getAttribute('data-term'), n: term.getAttribute('data-term-narrow') };
    var which = null, data = null, keys = [], chapters = [], pos = 0, end = 1, playing = false, last = 0, speed = 1, raf = 0, idx = -2;
    var rows = [], shownF = -2, autoplayed = false, loading = null, cache = {}, styleEl = null;
    var esc = function (s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); };
    var clock = function (ms) { var s = Math.round(ms / 1000); return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); };
    // the 44-column recording where the 100-column one would come out smaller than the room's height allows
    function want() {
      if (!srcs.n) return 'w';
      var w = stage.clientWidth - 40, h = stage.clientHeight - 90, byH = h / (30 * 1.28);
      return w / (100 * 0.6) < Math.min(15, byH) * 0.8 && w / (44 * 0.6) > w / (100 * 0.6) ? 'n' : 'w';
    }
    function load(w) {
      w = w || which || want();
      if (which === w && loading) return loading;
      var frac = data ? pos / end : null, ch = data ? Math.max(0, idx) : null;
      which = w;
      loading = (cache[w] ? Promise.resolve(cache[w]) : fetch(srcs[w]).then(function (r) { return r.json(); })).then(function (d) {
        cache[w] = d; data = d; keys = [];
        end = d.frames.length ? d.frames[d.frames.length - 1][0] + 1200 : 1;
        term.style.setProperty('--cols', d.w); term.style.setProperty('--rows', d.h);
        var css = '';
        d.styles.forEach(function (st, i) {
          var fg = paint(st[0]), bg = paint(st[1]), rule = '';
          if (st[3]) { fg = 'var(--t-plat-ink)'; bg = 'var(--t-plat)'; }
          if (fg) rule += 'color:' + fg + ';';
          if (bg) rule += 'background:' + bg + ';';
          if (st[2]) rule += 'font-weight:600;';
          if (rule) css += '[data-p="' + id + '"] .s' + i + '{' + rule + '}';
        });
        if (!styleEl) { styleEl = document.createElement('style'); document.head.appendChild(styleEl); }
        styleEl.textContent = css;
        var cur = {};   // the whole screen every 40 frames, so seeking never replays from the start
        d.frames.forEach(function (f, i) {
          for (var y in f[1]) cur[y] = f[1][y];
          if (i % 40 === 0) keys.push({ i: i, rows: JSON.parse(JSON.stringify(cur)) });
        });
        chapters = chapBs.map(function (b) {
          var t = parseFloat(b.getAttribute(w === 'n' && b.hasAttribute('data-tn') ? 'data-tn' : 'data-t'));
          return { t: t, name: b.getAttribute('data-name') };
        });
        $$('.scrub-mark', scrub).forEach(function (m) { m.remove(); });
        chapters.forEach(function (c) {
          var m = document.createElement('span'); m.className = 'scrub-mark'; m.style.left = (100 * c.t / end) + '%';
          scrub.insertBefore(m, scrub.firstChild);
        });
        scrub.setAttribute('aria-valuemax', String(Math.round(end / 1000)));
        shownF = -2;
        if (frac != null) { pos = ch != null && chapters[ch] && ch < chapters.length ? chapters[ch].t + 400 : frac * end; idx = -2; render(true); }
      });
      return loading;
    }
    function frameAt(t) { var f = data.frames, lo = 0, hi = f.length - 1, at = -1; while (lo <= hi) { var mid = (lo + hi) >> 1; if (f[mid][0] <= t) { at = mid; lo = mid + 1; } else hi = mid - 1; } return at; }
    function build(i) {
      var k = null;
      for (var j = keys.length - 1; j >= 0; j--) if (keys[j].i <= i) { k = keys[j]; break; }
      var cur = k ? JSON.parse(JSON.stringify(k.rows)) : {};
      for (var n = k ? k.i + 1 : 0; n <= i; n++) for (var y in data.frames[n][1]) cur[y] = data.frames[n][1][y];
      return cur;
    }
    function draw(state) {
      var out = '';
      for (var y = 0; y < data.h; y++) out += (state[y] || []).map(function (r) { return '<span class="s' + r[1] + '">' + esc(r[0]) + '</span>'; }).join('') + '\n';
      screen.innerHTML = out;
    }
    function render(force) {
      if (!data) return;
      var i = frameAt(pos);
      if (i !== shownF || force) {
        if (i === shownF + 1 && rows && !force) { for (var y in data.frames[i][1]) rows[y] = data.frames[i][1][y]; } else rows = build(i);
        shownF = i; draw(rows);
      }
      term.classList.toggle('ended', pos >= end);
      var p = Math.min(100, 100 * pos / end);
      fill.style.setProperty('--p', p + '%'); scrub.style.setProperty('--p', p + '%');
      scrub.setAttribute('aria-valuenow', String(Math.round(pos / 1000)));
      scrub.setAttribute('aria-valuetext', clock(pos) + ' of ' + clock(end));
      timeEl.textContent = clock(pos) + ' / ' + clock(end);
      var c = -1;
      chapters.forEach(function (ch, j) { if (pos >= ch.t) c = j; });
      if (pos >= end && chapters.length) c = chapters.length;
      if (c !== idx) {
        idx = c;
        if (stepEl) stepEl.innerHTML = c >= chapters.length ? '<b>Done</b>' : c >= 0 ? 'Step ' + (c + 1) + ' of ' + chapters.length + ': <b>' + esc(chapters[c].name) + '</b>' : '&nbsp;';
        chapBs.forEach(function (b, j) { b.setAttribute('aria-current', j === c ? 'true' : 'false'); b.classList.toggle('done', j < c); });
        if (chapBs[c]) scrollInto(chapList, chapBs[c].closest('li'));
      }
    }
    function tick(now) {
      if (!playing) return;
      pos += (now - last) * speed; last = now;
      if (pos >= end) { pos = end; playing = false; term.classList.remove('playing'); term.classList.add('ended'); playB.setAttribute('aria-label', 'Play again'); }
      render();
      if (playing) raf = requestAnimationFrame(tick);
    }
    function play() {
      load().then(function () {
        if (pos >= end) { pos = 0; shownF = -2; }
        playing = true; last = performance.now();
        term.classList.add('playing'); term.classList.remove('ended'); playB.setAttribute('aria-label', 'Pause');
        cancelAnimationFrame(raf); raf = requestAnimationFrame(tick);
      });
    }
    function pause() { playing = false; term.classList.remove('playing'); playB.setAttribute('aria-label', 'Play'); cancelAnimationFrame(raf); }
    function seek(t) { load().then(function () { pos = Math.max(0, Math.min(end, t)); if (pos < end) term.classList.remove('ended'); last = performance.now(); render(true); }); }
    playB.addEventListener('click', function () { autoplayed = true; if (playing) pause(); else play(); });
    speeds.forEach(function (b, i) {
      b.addEventListener('click', function () {   // the chosen speed again moves to the next (on phones it's the only one showing)
        var to = b.getAttribute('aria-pressed') === 'true' ? speeds[(i + 1) % speeds.length] : b;
        speed = parseFloat(to.getAttribute('data-speed'));
        speeds.forEach(function (x) { x.setAttribute('aria-pressed', x === to ? 'true' : 'false'); });
      });
    });
    var dragging = false;
    function fromX(x) { var r = scrub.getBoundingClientRect(); return (x - r.left) / r.width * end; }
    scrub.addEventListener('pointerdown', function (e) { dragging = true; autoplayed = true; try { scrub.setPointerCapture(e.pointerId); } catch (err) { /* older browsers */ } seek(fromX(e.clientX)); });
    scrub.addEventListener('pointermove', function (e) { if (dragging) seek(fromX(e.clientX)); });
    var up = function () { dragging = false; };
    scrub.addEventListener('pointerup', up); scrub.addEventListener('pointercancel', up);
    scrub.addEventListener('keydown', function (e) {
      var d = { ArrowRight: 5000, ArrowLeft: -5000, PageUp: 15000, PageDown: -15000 }[e.key];
      if (d) { e.preventDefault(); seek(pos + d); }
      else if (e.key === 'Home') { e.preventDefault(); seek(0); }
      else if (e.key === 'End') { e.preventDefault(); seek(end); }
      else if (e.key === ' ' || e.key === 'k') { e.preventDefault(); if (playing) pause(); else play(); }
    });
    chapBs.forEach(function (b, j) { b.addEventListener('click', function () { autoplayed = true; load().then(function () { seek(chapters[j].t + 1); if (!playing) play(); }); }); });
    if ('ResizeObserver' in window) new ResizeObserver(function () {
      var w = want();
      if (data && w !== which) { var was = playing; pause(); loading = null; load(w).then(function () { if (was) play(); }); }
    }).observe(stage);
    visible(term, function (v) {   // first sight: it plays (or, with reduced motion, shows the finished screen)
      if (v && !term.closest('[hidden]')) {
        load().then(function () {
          if (shownF === -2) { pos = reduced ? end : (chapters.length ? chapters[0].t + 400 : 0); render(true); }
          if (!autoplayed && !reduced) { autoplayed = true; play(); }
        });
      } else if (!v && playing) pause();
    }, 0.4);
    var host = term.closest('[role="tabpanel"]');
    if (host) {
      host.closest('[data-tabs]').addEventListener('tab', function (e) {
        if (e.detail.panel === host) load().then(function () { if (!playing) { if (shownF === -2 || pos >= end) pos = 0; render(true); if (!reduced) play(); } });
        else if (playing) pause();
      });
    }
  });

  // ---- the docs: where you are, in the rail; the rail as a sheet on narrow screens -------------------------------------
  var rail = $('.rail'), tocB = $('.toc-b');
  function closeRail() { if (rail && rail.classList.contains('open')) { rail.classList.remove('open'); if (tocB) tocB.setAttribute('aria-expanded', 'false'); } }
  if (rail) {
    var links = $$('a[href^="#"]', rail);
    var byId = {};
    links.forEach(function (a) { byId[a.getAttribute('href').slice(1)] = a; });
    var mark = function (id) {
      links.forEach(function (a) { if (a === byId[id]) a.setAttribute('aria-current', 'true'); else a.removeAttribute('aria-current'); });
      scrollInto(rail, byId[id]);
    };
    if ('IntersectionObserver' in window) {
      var io = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting && byId[e.target.id]) mark(e.target.id); }); }, { threshold: 0.55 });
      $$('.fold').forEach(function (f) { if (byId[f.id]) io.observe(f); });
    }
    if (tocB) tocB.addEventListener('click', function (e) {
      e.stopPropagation();
      var open = !rail.classList.contains('open');
      rail.classList.toggle('open', open); tocB.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (open && byId.top) (rail.querySelector('a[aria-current="true"]') || byId.top).focus({ preventScroll: true });
    });
    links.forEach(function (a) { a.addEventListener('click', closeRail); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeRail(); });
    document.addEventListener('click', function (e) { if (!rail.contains(e.target)) closeRail(); });
  }

  // ---- nothing scrolls inside a fold. One only a little too tall for this screen first lets its picture give (never
  // below 82% of its size), then closes up its spacing; one that still can't fit grows, and the page scrolls through it,
  // with a second stop at its foot so the snapping lands on its end as well as its start. Measured in four passes.
  function talls(only) {
    var h = innerHeight, all = only ? [only] : $$('.fold');
    all.forEach(function (f) { f.classList.remove('give', 'snug', 'tall'); });
    var info = all.filter(function (f) { return f.offsetHeight > h + 1; }).map(function (f) {
      var pics = $$('.stage, .term-stage', f);
      return { f: f, fin: $('.fold-in', f), pics: pics, was: pics.map(function (p) { return p.offsetHeight; }) };
    });
    function short(o) {  // its last words or picture come closer than 8px to the fold's foot, or a picture gave too much
      var last = o.fin && o.fin.lastElementChild;
      if (!last) return true;
      var foot = last.getBoundingClientRect().bottom - o.fin.getBoundingClientRect().top;
      return foot > o.fin.clientHeight - 8 || o.pics.some(function (p, i) { return p.offsetHeight < o.was[i] * .82; });
    }
    info.forEach(function (o) { o.f.classList.add('give'); });
    info = info.filter(short);
    info.forEach(function (o) { o.f.classList.add('snug'); });
    info = info.filter(short);
    info.forEach(function (o) { o.f.classList.remove('give', 'snug'); o.f.classList.add('tall'); });
  }
  // measured again only when something that changes a fold's height has: the window's size, or the fonts arriving
  var was = '';
  function fitAll() {
    var now = innerWidth + 'x' + innerHeight + (document.fonts ? document.fonts.status : '');
    if (now !== was) { was = now; talls(); }
  }
  fitAll();
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(fitAll);
  addEventListener('load', fitAll);
  var tt = 0;
  addEventListener('resize', function () { clearTimeout(tt); tt = setTimeout(fitAll, 150); });
  function fold(e) { return e.target && e.target.closest ? e.target.closest('.fold') : null; }
  document.addEventListener('click', function (e) { var f = fold(e); if (f) setTimeout(function () { talls(f); }, 60); });
  document.addEventListener('toggle', function (e) { var f = fold(e); if (f) setTimeout(function () { talls(f); }, 30); }, true);

  // ---- the people who made it: asked of GitHub again when the page opens (a public repository only) ----------------
  var people = $('[data-people][data-live]');
  var repo = people && /github\.com\/([^/]+\/[^/#?]+)$/.exec(people.getAttribute('data-people') || '');
  if (people && repo && !/^OWNER\//.test(repo[1]) && window.fetch) {
    var e2 = function (s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
    var drawPeople = function (list) {
      if (!Array.isArray(list)) return;
      var users = list.filter(function (p) { return p && p.type === 'User'; });
      if (!users.length) return;
      people.innerHTML = '<ul class="people">' + users.map(function (p) {
        return '<li><a href="' + e2(p.html_url) + '"><img src="' + e2(p.avatar_url) + '&amp;s=64" alt="" width="28" height="28" loading="lazy">' + e2(p.login) + '</a></li>';
      }).join('') + '</ul>';
    };
    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem('glass-people') || 'null'); } catch (e) { cached = null; }
    if (cached) drawPeople(cached);
    else {
      var asked = false;   // asked only once the credits come into view, not on every visit to the docs
      visible(people, function (on) {
        if (!on || asked) return;
        asked = true;
        fetch('https://api.github.com/repos/' + repo[1] + '/contributors?per_page=30').then(function (r) { return r.ok ? r.json() : null; })
          .then(function (list) { if (list) { try { sessionStorage.setItem('glass-people', JSON.stringify(list)); } catch (e) { /* fine */ } drawPeople(list); } })
          .catch(function () { /* offline: what the build wrote stays */ });
      }, 0);
    }
  }

  // ---- the real site keeps what it has served, so a second visit is instant -------------------------------------------
  if ('serviceWorker' in navigator && $('meta[name="glass-sw"]')) {
    addEventListener('load', function () { navigator.serviceWorker.register('/sw.js').catch(function () { /* fine */ }); });
  }
})();
