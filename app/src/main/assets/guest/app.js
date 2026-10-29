(() => {
  'use strict';
  const params = new URLSearchParams(location.search);
  const room = params.get('room') || '';

  let guestId = null;
  try { guestId = localStorage.getItem('guestId'); } catch (e) {}
  if (!guestId) {
    guestId = 'g-' + Math.random().toString(36).slice(2, 10);
    try { localStorage.setItem('guestId', guestId); } catch (e) {}
  }

  const $ = (id) => document.getElementById(id);
  const state = { queue: [], now: null, locked: false, adActive: false, adSkippable: false, tv: true, results: [], searching: false };

  // ---------- helpers ----------
  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text; // textContent lang: walang XSS galing sa YouTube titles
    return n;
  }
  function fmt(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0');
  }
  function who(id) { return id === guestId ? 'You' : 'Guest ' + String(id || '').slice(-4); }
  let toastTimer;
  function toast(msg, kind) {
    const t = $('toast');
    t.textContent = msg;
    t.className = 'show ' + (kind || '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.className = ''; }, 2800);
  }
  async function api(path, opts) {
    const url = path + (path.includes('?') ? '&' : '?') + 'room=' + encodeURIComponent(room);
    const res = await fetch(url, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts));
    let data = null;
    try { data = await res.json(); } catch (e) {}
    if (!res.ok) {
      const err = new Error((data && data.detail) || res.statusText);
      err.status = res.status;
      throw err;
    }
    return data;
  }
  const inQueue = (id) => state.queue.some((i) => i.videoId === id) || (state.now && state.now.videoId === id);

  // ---------- render ----------
  function row(item, right, leading) {
    const r = el('div', 'row');
    if (leading) r.appendChild(leading);
    const img = el('img');
    img.src = item.thumbnail; img.alt = ''; img.loading = 'lazy';
    r.appendChild(img);
    const m = el('div', 'meta');
    m.appendChild(el('div', 'title', item.title));
    m.appendChild(el('div', 'sub', right.sub));
    r.appendChild(m);
    if (right.btn) r.appendChild(right.btn);
    return r;
  }

  function renderNow() {
    const n = state.now;
    $('idle').hidden = !!n;
    const vi = $('vimg');
    vi.hidden = !n;
    if (n) {
      const src = 'https://i.ytimg.com/vi/' + n.videoId + '/hqdefault.jpg';
      if (vi.getAttribute('src') !== src) vi.src = src;
    }
    $('ntitle').textContent = n ? n.title : 'Nothing is playing yet';
    $('nsub').textContent = n ? fmt(n.duration) + ' \u2022 ' + who(n.addedBy) : 'Search for a song below';
    $('adstrip').hidden = !state.adActive;
    const b = $('skipad');
    b.disabled = !state.adSkippable;
    b.textContent = state.adSkippable ? 'Skip Ad' : 'Skip Ad (wait...)';
  }

  function renderQueue() {
    const box = $('queue');
    box.replaceChildren();
    $('qcount').textContent = String(state.queue.length);
    if (!state.queue.length) { box.appendChild(el('div', 'empty', 'The queue is empty. Add a song!')); return; }
    state.queue.forEach((it, n) => {
      box.appendChild(row(it, { sub: fmt(it.duration) + ' • Added by ' + who(it.addedBy) }, el('div', 'num', String(n + 1))));
    });
  }

  function renderResults(msg) {
    const box = $('results');
    box.replaceChildren();
    if (msg) { box.appendChild(el('div', 'empty', msg)); return; }
    state.results.forEach((r) => {
      const btn = el('button', 'btn', 'Add');
      btn.type = 'button';
      const dup = inQueue(r.videoId);
      if (dup) { btn.textContent = 'In queue'; btn.disabled = true; }
      else if (state.locked) { btn.textContent = '🔒 Lock'; btn.disabled = true; }
      btn.addEventListener('click', () => addSong(r, btn));
      box.appendChild(row(r, { sub: fmt(r.duration), btn }));
    });
  }

  function renderBanners() {
    $('locked').hidden = !state.locked;
    $('tvoff').hidden = state.tv;
  }
  function renderAll() { renderNow(); renderQueue(); renderBanners(); if (state.results.length) renderResults(); }

  // ---------- actions ----------
  async function addSong(r, btn) {
    btn.disabled = true; btn.textContent = 'Adding...';
    try {
      const out = await api('/api/queue/add', { method: 'POST', body: JSON.stringify({ videoId: r.videoId, guestId }) });
      state.queue = out.queue;
      toast('Added: ' + r.title, 'ok');
    } catch (e) {
      toast(e.message, 'err');
    }
    renderAll();
  }

  $('skipad').addEventListener('click', async () => {
    const b = $('skipad');
    b.disabled = true;
    try { await api('/api/ad/skip', { method: 'POST' }); toast('Ad skipped', 'ok'); }
    catch (e) { toast(e.message, 'err'); }
    renderNow();
  });

  // ---------- search (300ms debounce) ----------
  let timer, reqId = 0;
  $('q').addEventListener('input', (ev) => {
    clearTimeout(timer);
    const q = ev.target.value.trim();
    if (q.length < 2) { state.results = []; renderResults(); return; }
    timer = setTimeout(() => runSearch(q), 300);
  });
  async function runSearch(q) {
    const my = ++reqId;
    renderResults('Searching...');
    try {
      const data = await api('/api/search?q=' + encodeURIComponent(q));
      if (my !== reqId) return; // luma na ang request
      state.results = data;
      renderResults(data.length ? '' : 'No results found.');
    } catch (e) {
      if (my !== reqId) return;
      state.results = [];
      renderResults(e.message);
    }
  }

  // ---------- polling (bawat 2 segundo) ----------
  const conn = $('conn');
  function setConn(on) { conn.className = 'conn ' + (on ? 'on' : 'off'); conn.textContent = on ? '● Connected' : '● Connecting...'; }
  async function poll() {
    try {
      const s = await api('/api/state');
      state.now = s.nowPlaying; state.queue = s.queue; state.locked = s.locked;
      state.adActive = s.adPlaying; state.adSkippable = s.adSkippable; state.tv = s.tv;
      setConn(true); renderAll();
    } catch (e) { setConn(false); }
  }

  // ---------- init ----------
  $('idlelogo').src = $('hlogo').src;
  poll();
  setInterval(poll, 2000);
})();
