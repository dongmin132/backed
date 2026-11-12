/* Day close, one item at a time. The page never needs this file: without it every break card simply follows the
   one before, and every form works.

   1. The stage. All break cards are in the page; this shows one (.is-on) with the next one peeking out behind it
      (.is-next), and moves between them without a reload: Previous and Next buttons, the left and right arrow keys,
      a step in the strip, or the peeking card. Which card comes first: the #break-N in the address, else the first
      one waiting for the signed-in person (.is-turn), else the first with no proposal, else the first.
   2. The gap resolves (once). This browser remembers, per business date, the unexplained amount and the strip's
      steps (sessionStorage). When the amount is now smaller, the step of the break that is gone shrinks away, the
      bars settle, the figure counts down and the card on stage comes forward. About 1.2 s. Nothing plays under
      prefers-reduced-motion.

   Hooks: .fx-stage, .fx-deck, .fx-card[data-break-id][data-name][data-first][data-amount][data-amount-cents]
   [data-state][data-after][data-has-proposal], .fx-nav-prev, .fx-nav-next, .fx-band (.fx-pos, .fx-marks i,
   .wf-step[data-break-id], .fx-left-amount[data-unexplained-cents], .fx-after, .fx-bar-this, .fx-bar-rest),
   .focus-wrap[data-business-date], #day-closed. The strip layout lives in the page as window.backedLayoutWaterfall. */
(function () {
  'use strict';

  var still = !window.matchMedia || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var slice = function (list) { return Array.prototype.slice.call(list || []); };

  /* ---------------------------------------------------------------- 1. The stage */
  var stage = document.querySelector('.fx-stage');
  var deck = stage && stage.querySelector('.fx-deck');
  var cards = deck ? slice(deck.querySelectorAll('.fx-card[data-break-id]')) : [];
  var band = document.querySelector('.fx-band');
  var posEl = band && band.querySelector('.fx-pos');
  var marks = band ? slice(band.querySelectorAll('.fx-marks i')) : [];
  var steps = band ? slice(band.querySelectorAll('.wf-step[data-break-id]')) : [];
  var leftAmount = band && band.querySelector('.fx-left-amount[data-unexplained-cents]');
  var afterEl = band && band.querySelector('.fx-after');
  var barThis = band && band.querySelector('.fx-bar-this');
  var barRest = band && band.querySelector('.fx-bar-rest');
  var prevBox = stage && stage.querySelector('.fx-nav-prev');
  var nextBox = stage && stage.querySelector('.fx-nav-next');
  var prevBtn = prevBox && prevBox.querySelector('button');
  var nextBtn = nextBox && nextBox.querySelector('button');
  var liveEl = stage && stage.querySelector('.fx-live');
  var current = -1;
  var timers = [];

  function attr(el, name) { return (el && el.getAttribute(name)) || ''; }
  function indexOfId(id) {
    for (var i = 0; i < cards.length; i++) if (attr(cards[i], 'data-break-id') === String(id)) return i;
    return -1;
  }
  function fromHash() {
    var m = /^#break-(\d+)$/.exec(window.location.hash || '');
    return m ? indexOfId(m[1]) : -1;
  }
  function firstWhere(test) {
    for (var i = 0; i < cards.length; i++) if (test(cards[i])) return i;
    return -1;
  }
  function initial() {
    var i = fromHash();
    if (i < 0) i = firstWhere(function (c) { return c.classList.contains('is-turn'); });
    if (i < 0) i = firstWhere(function (c) { return attr(c, 'data-has-proposal') !== 'true'; });
    return i < 0 ? 0 : i;
  }
  /* The card that peeks out behind is the one Next goes to; the last card has none, as Next stops there. */
  function peekOf(i) {
    return i + 1 < cards.length ? i + 1 : -1;
  }
  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }

  function show(i, how) {
    if (i < 0 || i >= cards.length || i === current) return;
    timers.forEach(clearTimeout);
    timers = [];
    var was = current;
    var p = peekOf(i);
    var animate = how && !still && was >= 0;
    current = i;
    cards.forEach(function (c, k) {
      c.classList.remove('is-arriving', 'is-arriving-late', 'is-leaving', 'to-right');
      c.classList.toggle('is-on', k === i);
      /* The card that leaves becomes the peeking one only once it has gone. */
      c.classList.toggle('is-next', k === p && !(animate && k === was));
      if (k === p) c.setAttribute('aria-hidden', 'true'); else c.removeAttribute('aria-hidden');
    });
    deck.classList.toggle('has-peek', p >= 0);
    if (animate) {
      var old = cards[was], cur = cards[i];
      old.classList.add('is-leaving');
      if (how === 'prev') old.classList.add('to-right');
      cur.classList.add('is-arriving');
      later(function () {
        old.classList.remove('is-leaving', 'to-right');
        if (was === peekOf(current)) old.classList.add('is-next');
        cur.classList.remove('is-arriving');
      }, 280);
    }
    chrome();
  }

  function caption(box, btn, k, word) {
    if (!box || !btn) return;
    var c = cards[k];
    btn.disabled = !c;
    box.classList.toggle('is-end', !c);
    var who = box.querySelector('.fx-nav-who');
    if (who) who.textContent = c ? attr(c, 'data-first') + ' ' + attr(c, 'data-amount') : '';
    btn.setAttribute('aria-label', c ? word + ': ' + attr(c, 'data-name') + ', ' + attr(c, 'data-amount') : word + ' item');
  }

  function chrome() {
    var c = cards[current];
    var id = attr(c, 'data-break-id');
    if (posEl) posEl.textContent = String(current + 1);
    marks.forEach(function (m) { m.classList.toggle('is-on', attr(m, 'data-break-id') === id); });
    steps.forEach(function (s) { s.classList.toggle('is-lit', attr(s, 'data-break-id') === id); });
    if (afterEl) {
      var after = attr(c, 'data-after');
      afterEl.textContent = after ? after + (c.classList.contains('is-turn') ? ' after you approve' : ' after approval') : '';
      afterEl.hidden = !after;
    }
    if (barThis && barRest && leftAmount) {
      var total = Number(attr(leftAmount, 'data-unexplained-cents')) || 0;
      var mine = Math.min(Number(attr(c, 'data-amount-cents')) || 0, total);
      barThis.hidden = !(mine > 0);
      barThis.style.flexGrow = String(mine);
      barRest.style.flexGrow = String(Math.max(total - mine, 0));
    }
    caption(prevBox, prevBtn, current - 1, 'Previous');
    caption(nextBox, nextBtn, current + 1, 'Next');
  }

  function go(i, how) {
    if (i < 0 || i >= cards.length || i === current) return;
    show(i, how || (i > current ? 'next' : 'prev'));
    try { window.history.replaceState(null, '', '#break-' + attr(cards[i], 'data-break-id')); } catch (e) { /* file:// or sandbox */ }
    if (liveEl) {
      var c = cards[i];
      liveEl.textContent = 'Showing ' + (i + 1) + ' of ' + cards.length + ': ' + attr(c, 'data-name') + ', '
        + attr(c, 'data-amount') + '. ' + attr(c, 'data-state') + '.';
    }
  }

  if (deck && cards.length) {
    stage.classList.add('is-live');
    if (prevBox) prevBox.hidden = false;
    if (nextBox) nextBox.hidden = false;
    show(initial(), null);

    if (prevBtn) prevBtn.addEventListener('click', function () { go(current - 1, 'prev'); });
    if (nextBtn) nextBtn.addEventListener('click', function () { go(current + 1, 'next'); });
    deck.addEventListener('click', function (e) {
      var c = e.target.closest && e.target.closest('.fx-card.is-next');
      if (!c) return;
      e.preventDefault();
      go(cards.indexOf(c), 'next');
    });
    steps.forEach(function (s) {
      var link = s.querySelector('a[href^="#break-"]');
      if (!link) return;
      link.addEventListener('click', function (e) {
        var i = indexOfId(attr(s, 'data-break-id'));
        if (i < 0) return;
        e.preventDefault();
        go(i);
      });
    });
    document.addEventListener('keydown', function (e) {
      if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      var t = e.target;
      if (t && t.closest && t.closest('input, textarea, select, [contenteditable], .diff-toggle, details[open] table')) return;
      e.preventDefault();
      go(current + (e.key === 'ArrowRight' ? 1 : -1));
    });
    window.addEventListener('hashchange', function () {
      var i = fromHash();
      if (i >= 0) go(i);
    });
  }

  /* ---------------------------------------------------------------- 2. The gap resolves */
  var wrap = document.querySelector('.focus-wrap[data-business-date]');
  if (!wrap) return;

  var store = null;
  try { store = window.sessionStorage; } catch (e) { store = null; }
  function read(key) {
    try { var v = store && store.getItem(key); return v ? JSON.parse(v) : null; } catch (e) { return null; }
  }
  function write(key, value) {
    try { if (store) store.setItem(key, JSON.stringify(value)); } catch (e) { /* storage full or blocked */ }
  }
  function money(cents) {
    var a = Math.abs(Math.round(cents));
    var c2 = String(a % 100);
    return (cents < 0 ? '-' : '') + '$' + Math.floor(a / 100).toLocaleString('en-US') + '.' + (c2.length < 2 ? '0' : '') + c2;
  }
  var MONEY = /-?\$[\d,]+\.\d{2}/;

  /* Counts the first dollar amount inside each element from one value to another. */
  function count(items, delay, ms) {
    items = items.filter(function (it) { return it[0] && it[1] !== it[2] && isFinite(it[1]) && isFinite(it[2]); });
    if (!items.length) return;
    var texts = items.map(function (it) { return it[0].textContent; });
    var done = false;
    function end() {
      if (done) return;
      done = true;
      items.forEach(function (it, i) { it[0].textContent = texts[i]; });
    }
    items.forEach(function (it, i) { it[0].textContent = texts[i].replace(MONEY, money(it[1])); });
    /* The server's text always comes back, even if animation frames never run (a hidden tab). */
    setTimeout(end, delay + ms + 150);
    setTimeout(function () {
      var start = null;
      function frame(t) {
        if (done) return;
        if (start === null) start = t;
        var p = Math.min(1, (t - start) / ms), e = 1 - Math.pow(1 - p, 3);
        if (p >= 1) { end(); return; }
        items.forEach(function (it, i) { it[0].textContent = texts[i].replace(MONEY, money(it[1] + (it[2] - it[1]) * e)); });
        window.requestAnimationFrame(frame);
      }
      window.requestAnimationFrame(frame);
    }, delay);
  }

  var date = wrap.getAttribute('data-business-date');
  var lede = document.querySelector('.report-amount[data-unexplained-cents]');
  var amountEl = leftAmount || lede;
  var wf = document.querySelector('.wf[data-book][data-paypal]');
  var closed = document.getElementById('day-closed');
  var now = {
    cents: amountEl ? Number(amountEl.getAttribute('data-unexplained-cents')) : (closed ? 0 : null),
    book: wf ? Number(wf.getAttribute('data-book')) : null,
    paypal: wf ? Number(wf.getAttribute('data-paypal')) : null,
    steps: wf ? slice(wf.querySelectorAll('.wf-step[data-break-id]')).map(function (li) {
      var name = li.querySelector('.wf-name');
      return { id: li.getAttribute('data-break-id'), delta: Number(li.getAttribute('data-delta')) || 0,
               name: name ? name.textContent : '' };
    }) : []
  };
  var key = 'backed.close.' + date;
  var seen = read(key);
  if (now.cents !== null && isFinite(now.cents)) write(key, now);

  if (!still && seen && amountEl && !closed && isFinite(now.cents) && typeof seen.cents === 'number'
      && now.cents < seen.cents) {
    playGap(seen);
  }

  function playGap(was) {
    count([[leftAmount, was.cents, now.cents], [lede, was.cents, now.cents]], 260, 880);
    var left = leftAmount && leftAmount.closest('.fx-left');
    if (left) {
      left.classList.add('is-settling');
      setTimeout(function () { left.classList.remove('is-settling'); }, 1200);
    }

    /* The card on stage comes forward once the strip has settled. */
    var cur = cards[current];
    if (cur) {
      cur.classList.add('is-arriving-late');
      setTimeout(function () { cur.classList.remove('is-arriving-late'); }, 1150);
    }

    var layout = window.backedLayoutWaterfall;
    var gone = (was.steps || []).filter(function (s) {
      return !now.steps.some(function (n) { return n.id === s.id; });
    });
    if (!wf || typeof layout !== 'function' || typeof was.book !== 'number' || !gone.length) return;

    var list = wf.querySelector('.wf-cols');
    var ghosts = gone.map(function (s) {
      var li = document.createElement('li');
      li.className = 'wf-col wf-step wf-ghost';
      li.setAttribute('aria-hidden', 'true');
      li.setAttribute('data-delta', String(s.delta));
      li.innerHTML = '<div class="wf-plot"><span class="wf-link"></span><span class="wf-bar"></span></div>'
        + '<div class="wf-label"><span class="wf-name"></span></div>';
      li.querySelector('.wf-name').textContent = s.name;
      /* Put it back where it stood: before the first step that followed it last time and is still here. */
      var at = was.steps.indexOf(s), anchor = null;
      for (var j = at + 1; j < was.steps.length && !anchor; j++) {
        anchor = wf.querySelector('.wf-step[data-break-id="' + String(was.steps[j].id).replace(/"/g, '') + '"]');
      }
      list.insertBefore(li, anchor || wf.querySelector('.wf-rest') || wf.querySelector('.wf-end'));
      return li;
    });

    var bookAttr = wf.getAttribute('data-book');
    function finish() {
      ghosts.forEach(function (g) { if (g.parentNode) g.parentNode.removeChild(g); });
      wf.setAttribute('data-book', bookAttr);
      wf.classList.remove('wf-moving', 'wf-resolving');
      try { layout(wf); } catch (e) { /* the page layout already ran once */ }
    }
    try {
      /* The old picture, drawn without transitions. */
      wf.classList.add('wf-resolving');
      wf.setAttribute('data-book', String(was.book));
      layout(wf);
      void wf.offsetWidth;
      /* Then let it move: the step goes, and the bars settle on the new levels. */
      wf.classList.add('wf-moving');
      setTimeout(function () { ghosts.forEach(function (g) { g.classList.add('is-gone'); }); }, 30);
      setTimeout(function () {
        wf.setAttribute('data-book', bookAttr);
        ghosts.forEach(function (g) { g.setAttribute('data-delta', '0'); });
        try { layout(wf); } catch (e) { finish(); }
      }, 250);
    } catch (err) {
      finish();
      return;
    }
    setTimeout(finish, 1250);
  }
})();
