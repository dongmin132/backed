/* Two one-time moments on the day report. The page never needs this file: without it, or with reduced motion,
   everything shows its final state exactly as the server rendered it.

   A. The gap resolves. This browser remembers, per business date, the unexplained amount and the waterfall steps
      it last saw (sessionStorage). When the amount is now smaller, the step of the break that is gone shrinks away
      into the books, the bars settle on their new levels and the figure counts down to the new amount. About 1.2 s.
   B. The day closes. The first time this browser sees a business date closed, the seal lands, the rays and the
      confetti bloom and the matched amount settles. About 1.5 s.

   Hooks: .report-wrap[data-business-date], .report-amount[data-unexplained-cents], .wf[data-book][data-paypal],
   .wf-step[data-break-id][data-delta], #day-closed[data-business-date]. The waterfall layout itself lives in the
   page and is exposed as window.backedLayoutWaterfall(wf). */
(function () {
  'use strict';

  var wrap = document.querySelector('.report-wrap[data-business-date]');
  if (!wrap) return;

  var store = null;
  try { store = window.sessionStorage; } catch (e) { store = null; }
  function read(key) {
    try { var v = store && store.getItem(key); return v ? JSON.parse(v) : null; } catch (e) { return null; }
  }
  function write(key, value) {
    try { if (store) store.setItem(key, JSON.stringify(value)); } catch (e) { /* storage full or blocked */ }
  }
  var still = !window.matchMedia || window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function money(cents) {
    var a = Math.abs(Math.round(cents));
    var cents2 = String(a % 100);
    return (cents < 0 ? '-' : '') + '$' + Math.floor(a / 100).toLocaleString('en-US') + '.' + (cents2.length < 2 ? '0' : '') + cents2;
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

  /* What the page shows now. */
  var date = wrap.getAttribute('data-business-date');
  var amountEl = document.querySelector('.report-amount[data-unexplained-cents]');
  var wf = document.querySelector('.wf[data-book][data-paypal]');
  var closed = document.getElementById('day-closed');
  var now = {
    cents: amountEl ? Number(amountEl.getAttribute('data-unexplained-cents')) : (closed ? 0 : null),
    book: wf ? Number(wf.getAttribute('data-book')) : null,
    paypal: wf ? Number(wf.getAttribute('data-paypal')) : null,
    steps: wf ? Array.prototype.map.call(wf.querySelectorAll('.wf-step[data-break-id]'), function (li) {
      var name = li.querySelector('.wf-name');
      return { id: li.getAttribute('data-break-id'), delta: Number(li.getAttribute('data-delta')) || 0,
               name: name ? name.textContent : '' };
    }) : []
  };
  var key = 'backed.report.' + date;
  var seen = read(key);
  if (now.cents !== null && isFinite(now.cents)) write(key, now);

  /* ---------------------------------------------------------------- A. The gap resolves */
  if (!still && seen && amountEl && !closed && isFinite(now.cents) && typeof seen.cents === 'number'
      && now.cents < seen.cents) {
    playGap(seen);
  }

  function playGap(was) {
    var counters = [[amountEl, was.cents, now.cents]];
    if (wf && typeof was.book === 'number' && typeof was.paypal === 'number') {
      var head = document.querySelector('#gap .gap-amount');
      var oldGap = Math.abs(was.book - was.paypal), newGap = Math.abs(now.book - now.paypal);
      counters.push([head, oldGap, newGap]);
      counters.push([document.querySelector('.report-toc a[href="#gap"] span:last-child'), oldGap, newGap]);
      counters.push([wf.querySelector('.wf-total:not(.wf-end) .wf-amount'), was.book, now.book]);
    }
    count(counters, 260, 880);

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
      li.innerHTML = '<div class="wf-plot"><span class="wf-link"></span><span class="wf-bar"></span>'
        + '<span class="wf-amount"></span></div><div class="wf-label"><span class="wf-name"></span>'
        + '<span class="wf-sub">now in your books</span></div>';
      li.querySelector('.wf-amount').textContent = (s.delta < 0 ? '−' : '+') + money(Math.abs(s.delta));
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
    try {
      /* The old picture, drawn without transitions. */
      wf.classList.add('wf-resolving');
      wf.setAttribute('data-book', String(was.book));
      layout(wf);
      void wf.offsetWidth;
      /* Then let it move: the step goes, and the bars settle on the new levels. */
      wf.classList.add('wf-moving');
      setTimeout(function () {
        ghosts.forEach(function (g) { g.classList.add('is-gone'); });
      }, 30);
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

    function finish() {
      ghosts.forEach(function (g) { if (g.parentNode) g.parentNode.removeChild(g); });
      wf.setAttribute('data-book', bookAttr);
      wf.classList.remove('wf-moving', 'wf-resolving');
      try { layout(wf); } catch (e) { /* the page layout already ran once */ }
    }
  }

  /* ---------------------------------------------------------------- B. The day closes */
  if (closed) {
    var closedKey = 'backed.closed.' + (closed.getAttribute('data-business-date') || date);
    var first = !read(closedKey);
    write(closedKey, 1);
    if (first && !still) playClose(closed);
  }

  function playClose(party) {
    /* Each piece of confetti flies out from the seal to where it rests. */
    var seal = party.querySelector('.party-seal');
    var box = party.querySelector('.party-confetti');
    if (seal && box) {
      var b = box.getBoundingClientRect(), s = seal.getBoundingClientRect();
      var cx = s.left + s.width / 2 - b.left, cy = s.top + s.height / 2 - b.top;
      Array.prototype.forEach.call(box.querySelectorAll('i'), function (i, n) {
        i.style.setProperty('--fx', (cx - i.offsetLeft).toFixed(0) + 'px');
        i.style.setProperty('--fy', (cy - i.offsetTop).toFixed(0) + 'px');
        i.style.setProperty('--n', String(n));
      });
    }
    Array.prototype.forEach.call(party.querySelectorAll('.party-chips li'), function (li, n) {
      li.style.setProperty('--n', String(n));
    });
    party.classList.add('is-landing');
    setTimeout(function () { party.classList.remove('is-landing'); }, 1700);
  }
})();
