/* AudioCool Desktop web UI: a small single-page app, no build step, no dependencies. */
'use strict';

(() => {
  // ---------------------------------------------------------------------------------------
  // Helpers

  const $ = (sel, el = document) => el.querySelector(sel);
  const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC[c]);

  class Raw { constructor(s) { this.s = s; } toString() { return this.s; } }
  const raw = (s) => new Raw(s);
  const out = (v) => (v == null || v === false ? '' : v instanceof Raw ? v.s : Array.isArray(v) ? v.map(out).join('') : esc(v));
  /** Tagged template that escapes interpolated values (wrap trusted HTML with raw()). */
  const html = (strings, ...vals) => raw(strings.reduce((acc, s, i) => acc + s + (i < vals.length ? out(vals[i]) : ''), ''));

  const ICONS = {
    play: '<svg viewBox="0 0 24 24"><path d="M8 5.6v12.8a1 1 0 0 0 1.53.85l10.2-6.4a1 1 0 0 0 0-1.7L9.53 4.75A1 1 0 0 0 8 5.6Z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="6" y="5" width="4.2" height="14" rx="1.3"/><rect x="13.8" y="5" width="4.2" height="14" rx="1.3"/></svg>',
    back: '<svg viewBox="0 0 24 24"><path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1"/><path d="M3 4.5v5h5"/><path d="M10 9.5v5M13.5 9.5h2.2v5h-2.2z"/></svg>',
    fwd: '<svg viewBox="0 0 24 24"><path d="M20.5 12a8.5 8.5 0 1 1-2.6-6.1"/><path d="M21 4.5v5h-5"/><path d="M8.5 9.5v5M12 9.5h2.2v5H12z"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
    note: '<svg viewBox="0 0 24 24"><path d="M5 4h14v10.5L14.5 19H5z"/><path d="M14 19v-5h5"/><path d="M8.5 8.5h7M8.5 12h4"/></svg>',
    speech: '<svg viewBox="0 0 24 24"><path d="M4 5.5h16v10.5H10l-4.5 3.5V16H4z"/><path d="M8 9.5h8M8 12.5h5"/></svg>',
    mic: '<svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/></svg>',
    clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.2 2"/></svg>',
    edit: '<svg viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/></svg>',
    download: '<svg viewBox="0 0 24 24"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 20h14"/></svg>',
    upload: '<svg viewBox="0 0 24 24"><path d="M12 16V5m0 0L7.5 9.5M12 5l4.5 4.5M5 20h14"/></svg>',
    folder: '<svg viewBox="0 0 24 24"><path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5z"/></svg>',
    trash: '<svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/></svg>',
    dots: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.4" fill="currentColor"/><circle cx="12" cy="12" r="1.4" fill="currentColor"/><circle cx="19" cy="12" r="1.4" fill="currentColor"/></svg>',
    left: '<svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg>',
    sun: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6 6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/></svg>',
    moon: '<svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg>',
    auto: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 3.5a8.5 8.5 0 0 1 0 17z" fill="currentColor"/></svg>',
    wave: '<svg viewBox="0 0 24 24"><path d="M3 12h1.5M7 8.5v7M11 5v14M15 8v8M19 10.5v3"/></svg>',
    phone: '<svg viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.2"/><path d="M11 18.5h2"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="m5 12.5 4.5 4.5L19 7"/></svg>',
    alert: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5h.01"/></svg>',
    copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h8"/></svg>',
    refresh: '<svg viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.34-5.66L20 8.5"/><path d="M20 3.5v5h-5"/></svg>',
    chip: '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9.5 2.5v3.5M14.5 2.5v3.5M9.5 18v3.5M14.5 18v3.5M2.5 9.5H6M2.5 14.5H6M18 9.5h3.5M18 14.5h3.5"/></svg>',
    heading: '<svg viewBox="0 0 24 24"><path d="M6 4.5v15M18 4.5v15M6 12h12"/></svg>',
    image: '<svg viewBox="0 0 24 24"><rect x="3.5" y="5" width="17" height="14" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="m4 17 5-4.5 3.5 3 3-2.5 4.5 4"/></svg>',
    zip: '<svg viewBox="0 0 24 24"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M10 7h2M10 10h2M10 13h2M10 16h2v2h-2z"/></svg>',
  };
  const icon = (name) => raw(ICONS[name] || '');

  async function api(path, opts = {}) {
    const init = { method: opts.method || 'GET', headers: {} };
    if (init.method !== 'GET') init.headers['X-AudioCool'] = '1';
    if (opts.json !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(opts.json);
    }
    const res = await fetch('/ui/api' + path, init);
    let data = null;
    try { data = await res.json(); } catch (e) { /* not JSON */ }
    if (!res.ok) throw new Error((data && data.error) || `${res.status} ${res.statusText}`);
    return data;
  }

  const pad2 = (n) => String(n).padStart(2, '0');
  /** "05:07" under an hour, "1:02:05" from an hour on (same as the phone). */
  function fmtTime(ms) {
    const t = Math.max(0, Math.floor((ms || 0) / 1000));
    const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
    return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`;
  }
  function fmtDuration(ms) {
    const t = Math.round((ms || 0) / 1000);
    if (t < 60) return `${t} s`;
    const h = Math.floor(t / 3600), m = Math.round((t % 3600) / 60);
    if (h === 0) return `${m} min`;
    return m ? `${h} h ${m} min` : `${h} h`;
  }
  const fmtDate = (ms) => new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  const fmtClock = (ms) => new Date(ms).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  const fmtDateTime = (ms) => `${fmtDate(ms)} · ${fmtClock(ms)}`;
  const plural = (n, word) => `${n} ${n === 1 ? word : /(s|x|ch|sh)$/.test(word) ? `${word}es` : `${word}s`}`;
  function ago(ms) {
    const s = Math.round((Date.now() - ms) / 1000);
    if (s < 45) return 'just now';
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return fmtDate(ms);
  }

  /** Text with <mark>s; ranges are code-point offsets from the server. */
  function highlight(text, ranges) {
    const chars = Array.from(text || '');
    let res = '', pos = 0;
    for (const [a, b] of ranges || []) {
      res += esc(chars.slice(pos, a).join('')) + '<mark>' + esc(chars.slice(a, b).join('')) + '</mark>';
      pos = b;
    }
    return raw(res + esc(chars.slice(pos).join('')));
  }

  function toast(message, kind = '') {
    const el = document.createElement('div');
    el.className = `toast ${kind}`;
    el.textContent = message;
    $('#toasts').append(el);
    setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, kind === 'err' ? 5200 : 2600);
    setTimeout(() => el.remove(), kind === 'err' ? 5600 : 3000);
  }

  /** Repeats fn every ms (a number or a function returning one; at most every 10 s while the tab
   * is hidden); returns a stop function. */
  function poll(fn, ms) {
    let timer = null, stopped = false;
    const interval = () => {
      const base = Number(typeof ms === 'function' ? ms() : ms) || 4000;
      return document.hidden ? Math.max(base, 10000) : base;
    };
    const tick = async () => {
      if (stopped) return;
      try { await fn(); } catch (e) { /* keep polling */ }
      if (!stopped) timer = setTimeout(tick, interval());
    };
    timer = setTimeout(tick, interval());
    return () => { stopped = true; clearTimeout(timer); };
  }

  function closeMenus(except) {
    $$('.menu-pop').forEach((m) => { if (m !== except) m.hidden = true; });
  }
  document.addEventListener('click', (e) => {
    const toggle = e.target.closest('[data-menu]');
    if (toggle) {
      const pop = toggle.parentElement.querySelector('.menu-pop');
      closeMenus(pop);
      pop.hidden = !pop.hidden;
      e.stopPropagation();
      return;
    }
    if (!e.target.closest('.menu-pop')) closeMenus();
  });

  // ---------------------------------------------------------------------------------------
  // Theme

  const THEMES = ['auto', 'light', 'dark'];
  function currentTheme() { try { return localStorage.getItem('ac-theme') || 'auto'; } catch (e) { return 'auto'; } }
  function applyTheme(t) {
    if (t === 'auto') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t;
    try { localStorage.setItem('ac-theme', t); } catch (e) { /* private mode */ }
    const btn = $('#theme-btn');
    btn.innerHTML = ICONS[t === 'auto' ? 'auto' : t === 'light' ? 'sun' : 'moon'];
    btn.title = `Theme: ${t} (click to change)`;
  }
  $('#theme-btn').addEventListener('click', () => applyTheme(THEMES[(THEMES.indexOf(currentTheme()) + 1) % THEMES.length]));
  applyTheme(currentTheme());

  // ---------------------------------------------------------------------------------------
  // Shared state: models and job count (refreshed in the background)

  let INFO = null;
  async function loadInfo() {
    INFO = await api('/info');
    const badge = $('#jobs-badge');
    badge.hidden = !INFO.activeJobs;
    badge.textContent = INFO.activeJobs || '';
    return INFO;
  }
  poll(loadInfo, 4000);

  // ---------------------------------------------------------------------------------------
  // Router

  const view = $('#view');
  let page = null; // { name, key, cleanup, update }
  const routes = {};

  function parseHash() {
    const h = location.hash.replace(/^#\/?/, '');
    const [path, query] = h.split('?');
    const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
    return { name: parts[0] || 'library', args: parts.slice(1), params: new URLSearchParams(query || '') };
  }

  async function route() {
    const r = parseHash();
    const key = `${r.name}/${r.args.join('/')}`;
    $$('.nav a').forEach((a) => a.classList.toggle('active', a.dataset.nav === r.name || (r.name === 'session' && a.dataset.nav === 'library')));
    if (page && page.key === key && page.update) { page.update(r); return; }
    if (page && page.cleanup) page.cleanup();
    closeMenus();
    if (r.name !== 'search' && document.activeElement !== searchInput) searchInput.value = '';
    const fn = routes[r.name] || routes.notfound;
    page = { name: r.name, key };
    const mine = page;
    view.innerHTML = '';
    window.scrollTo(0, 0);
    try {
      const res = await fn(view, r);
      if (page === mine && res) Object.assign(page, res); else if (res && res.cleanup) res.cleanup();
    } catch (e) {
      if (page === mine) view.innerHTML = out(html`<div class="card card-pad empty">${icon('alert')}<h2>Something went wrong</h2><p>${e.message}</p><a class="btn" href="#/">Back to the library</a></div>`);
    }
  }
  window.addEventListener('hashchange', route);

  // Search box in the top bar
  const searchInput = $('#search-input');
  let searchTimer = null;
  $('#search-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const q = searchInput.value.trim();
    if (q) location.hash = `#/search?q=${encodeURIComponent(q)}`;
  });
  searchInput.addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      const q = searchInput.value.trim();
      if (q.length >= 2) {
        const target = `#/search?q=${encodeURIComponent(q)}`;
        if (location.hash !== target) {
          if (parseHash().name === 'search') history.replaceState(null, '', target); else history.pushState(null, '', target);
          route();
        }
      }
    }, 280);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && !isTyping(e.target)) { e.preventDefault(); searchInput.focus(); searchInput.select(); }
  });
  const isTyping = (el) => el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));

  // ---------------------------------------------------------------------------------------
  // Library

  function statusBadge(s) {
    const running = s.activeJobs.find((j) => j.status === 'running');
    if (running) {
      const pct = Math.round(running.progress * 100);
      return html`<span class="badge accent run" title="${running.phase || ''}"><span class="dot"></span>Transcribing ${pct}%</span><div class="progress ${pct === 0 ? 'indet' : ''}"><div style="width:${pct}%"></div></div>`;
    }
    if (s.activeJobs.length) return html`<span class="badge accent run"><span class="dot"></span>Queued</span>`;
    const recs = s.recordings;
    if (!recs.length) return html`<span class="badge">Notes only</span>`;
    const desktop = recs.filter((r) => r.transcriptSource === 'desktop' || r.transcriptSource === 'phone-edited');
    const models = [...new Set(recs.filter((r) => r.transcriptSource === 'desktop').map((r) => r.modelName))];
    if (desktop.length === recs.length) {
      return html`<span class="badge ok">${icon('check')}Transcribed</span><span class="muted small">${models.join(', ') || 'edited'}</span>`;
    }
    if (s.lastError && !desktop.length) return html`<span class="badge err" title="${s.lastError}">${icon('alert')}Failed</span>`;
    if (!recs.some((r) => r.hasAudio)) return html`<span class="badge warn">Waiting for audio</span>`;
    if (desktop.length) return html`<span class="badge ok">${desktop.length} of ${recs.length} transcribed</span>`;
    if (recs.some((r) => r.transcriptSource === 'phone')) return html`<span class="badge">Phone transcript</span>`;
    return html`<span class="badge">Not transcribed</span>`;
  }

  function groupLabel(ms) {
    const d = new Date(ms), now = new Date();
    const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.round((day(now) - day(d)) / 86400000);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Yesterday';
    if (diff < 7) return 'This week';
    return d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  }

  function sessionCard(s) {
    const d = new Date(s.createdAt);
    const recs = s.recordings.length;
    return html`<a class="card session-card" href="#/session/${encodeURIComponent(s.id)}">
      ${s.thumbnail
        ? html`<img class="session-thumb" src="${s.thumbnail}" alt="" loading="lazy">`
        : html`<div class="session-date"><span class="m">${d.toLocaleDateString(undefined, { month: 'short' })}</span><span class="d">${d.getDate()}</span></div>`}
      <div style="min-width:0">
        <div class="session-title">${s.title || 'Untitled'}</div>
        <div class="session-meta">
          <span>${icon('clock')}${fmtClock(s.createdAt)}</span><span class="sep"></span>
          <span>${icon('mic')}${recs ? fmtDuration(s.durationMs) : 'no audio'}</span><span class="sep"></span>
          <span>${icon('note')}${plural(s.noteCount, 'note')}</span>
          ${recs > 1 ? html`<span class="sep"></span><span>${recs} recordings</span>` : ''}
        </div>
      </div>
      <div class="session-status">${statusBadge(s)}</div>
    </a>`;
  }

  routes.library = async (el) => {
    el.innerHTML = '<div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>';
    let last = '';
    async function load() {
      const data = await api('/sessions');
      const sig = JSON.stringify(data);
      if (sig === last) return;
      last = sig;
      const list = data.sessions;
      const total = list.reduce((a, s) => a + s.durationMs, 0);
      let body;
      if (!list.length) {
        body = html`<div class="card empty">
          <svg class="big" viewBox="0 0 24 24">${raw(ICONS.mic.replace(/<\/?svg[^>]*>/g, ''))}</svg>
          <h2>No sessions yet</h2>
          <p>Pair the AudioCool app on your phone and send a session, or import a phone backup folder.
             Sessions are kept in <span class="mono">${data.library}</span>.</p>
          <div class="row" style="justify-content:center"><a class="btn primary" href="#/pair">${icon('phone')}Pair your phone</a><a class="btn" href="#/import">${icon('upload')}Import a backup</a></div>
        </div>`;
      } else {
        let group = null;
        const parts = [];
        for (const s of list) {
          const g = groupLabel(s.createdAt);
          if (g !== group) { parts.push(html`<div class="group-label">${g}</div>`); group = g; }
          parts.push(sessionCard(s));
        }
        body = html`<div class="sessions">${parts}</div>`;
      }
      el.innerHTML = out(html`
        <div class="page-head">
          <div><h1>Library</h1><div class="sub">${plural(list.length, 'session')}${list.length ? html` · ${fmtDuration(total)} of audio` : ''}</div></div>
          <div class="spacer"></div>
          <a class="btn" href="#/import">${icon('upload')}Import</a>
          <a class="btn" href="#/pair">${icon('phone')}Pair phone</a>
        </div>
        ${body}`);
    }
    await load();
    return { cleanup: poll(load, 3000) };
  };

  // ---------------------------------------------------------------------------------------
  // Search

  routes.search = async (el, r) => {
    const render = async (q) => {
      searchInput.value = q;
      if (!q.trim()) {
        el.innerHTML = out(html`<div class="page-head"><h1>Search</h1></div><p class="muted">Type in the search box above. Every word must match; case and accents don't matter.</p>`);
        return;
      }
      const data = await api(`/search?q=${encodeURIComponent(q)}&limit=400`);
      if (searchInput.value.trim() !== q.trim() && document.activeElement === searchInput) return; // stale
      const groups = [];
      const byId = new Map();
      for (const h of data.hits) {
        if (!byId.has(h.sessionId)) { byId.set(h.sessionId, []); groups.push(h.sessionId); }
        byId.get(h.sessionId).push(h);
      }
      const hitLink = (h) => {
        const p = new URLSearchParams();
        if (h.recId) p.set('rec', h.recId);
        if (h.atMs != null) p.set('t', h.atMs);
        if (h.noteId) p.set('note', h.noteId);
        if (h.line != null) p.set('line', h.line);
        return `#/session/${encodeURIComponent(h.sessionId)}?${p}`;
      };
      el.innerHTML = out(html`
        <div class="page-head"><div><h1>Search</h1>
          <div class="sub">${data.total ? html`${plural(data.total, 'match')} for “${q}”${data.truncated ? ` (showing ${data.hits.length})` : ''}` : html`Nothing matches “${q}”`}</div></div></div>
        ${groups.map((sid) => {
          const hits = byId.get(sid);
          return html`<div class="card hit-group">
            <div class="hit-group-head"><a href="#/session/${encodeURIComponent(sid)}">${hits[0].sessionTitle || 'Untitled'}</a>
              <span class="muted small">${fmtDateTime(hits[0].sessionCreatedAt)}</span><span class="muted small">· ${plural(hits.length, 'match')}</span></div>
            ${hits.map((h) => html`<a class="hit" href="${hitLink(h)}">
              <span class="kind ${h.kind}" title="${{ note: 'Note', speech: 'Transcript', title: 'Title', photo: 'On a photo' }[h.kind]}">${icon({ note: 'note', speech: 'speech', title: 'heading', photo: 'image' }[h.kind])}</span>
              <span class="chip">${h.kind === 'title' ? 'title' : h.label || '—'}</span>
              <span>${highlight(h.text, h.matches)}</span></a>`)}
          </div>`;
        })}`);
    };
    await render(r.params.get('q') || '');
    return { update: (r2) => render(r2.params.get('q') || '') };
  };

  // ---------------------------------------------------------------------------------------
  // Session

  const LEAD_IN_MS = 3000; // a note plays from a little before it, like on the phone
  const LINE_LEAD_MS = 150; // a transcript line starts a hair early so its first word is heard whole
  const audio = $('#audio');

  routes.session = async (el, r) => {
    const sid = r.args[0];
    el.innerHTML = '<div class="skeleton" style="height:60px"></div><div class="skeleton" style="height:110px"></div><div class="skeleton" style="height:320px"></div>';
    let data;
    try {
      data = await api(`/sessions/${encodeURIComponent(sid)}`);
    } catch (e) {
      el.innerHTML = out(html`<div class="card empty">${icon('alert')}<h2>Session not found</h2>
        <p>It may have been deleted, or the library folder changed (${e.message}).</p><a class="btn" href="#/">Back to the library</a></div>`);
      return null;
    }
    if (!INFO) await loadInfo();
    let recIdx = 0;
    let follow = true;
    let editing = false;
    let lastSig = '';
    let activeLine = -1, activeNote = null;
    let raf = 0;
    let wasActive = new Set(data.jobs.filter((j) => j.status === 'queued' || j.status === 'running').map((j) => j.id));
    let pendingSeek = null;

    const s = () => data.session;
    const rec = () => s().recordings[recIdx];
    const recInfo = () => data.summary.recordings[recIdx];
    const segs = () => (rec() && rec().transcript) || [];

    function pickRecording(params) {
      const want = params.get('rec');
      const i = s().recordings.findIndex((x) => x.id === want);
      if (i >= 0) recIdx = i;
    }
    pickRecording(r.params);

    function render() {
      const sess = s();
      const recs = sess.recordings;
      const models = INFO ? INFO.models : [];
      const defaultModel = INFO ? INFO.defaultModel : '';
      const anyDesktop = data.summary.recordings.some((x) => x.transcriptSource === 'desktop');
      el.innerHTML = out(html`
        <a class="back" href="#/">${icon('left')}Library</a>
        <div class="session-head">
          <h1 class="title-edit" id="title" contenteditable="true" spellcheck="false" title="Click to rename">${sess.title || 'Untitled'}</h1>
          <div class="session-sub">
            <div class="session-meta">
              <span>${fmtDateTime(sess.createdAt)}</span><span class="sep"></span>
              <span>${recs.length ? fmtDuration(data.summary.durationMs) : 'no audio'}</span><span class="sep"></span>
              <span>${plural(sess.notes.length, 'note')}</span>
              ${recs.length > 1 ? html`<span class="sep"></span><span>${recs.length} recordings</span>` : ''}
            </div>
          <div class="actions">
            ${recs.length ? html`<div class="split-btn" title="Transcribe every recording in this session">
              <select class="select" id="model" aria-label="Model">
                ${models.map((m) => html`<option value="${m.id}" ${m.id === defaultModel ? 'selected' : ''}>${m.name}${m.device === 'cpu' && !m.id.endsWith('-cpu') ? ' (CPU)' : ''}</option>`)}
              </select>
              <button class="btn primary" id="transcribe">${icon('wave')}${anyDesktop ? 'Re-transcribe' : 'Transcribe'}</button>
            </div>` : ''}
            <div class="menu">
              <button class="btn" data-menu>${icon('download')}Export</button>
              <div class="menu-pop" hidden>
                <a href="/ui/api/sessions/${encodeURIComponent(sid)}/export.md" download>${icon('note')}<span>Markdown<span class="desc">Notes and transcript (.md)</span></span></a>
                <a href="/ui/api/sessions/${encodeURIComponent(sid)}/export.txt" download>${icon('note')}<span>Plain text<span class="desc">Notes and transcript (.txt)</span></span></a>
                ${recs.map((x, i) => html`<a href="/ui/api/sessions/${encodeURIComponent(sid)}/export.srt?recording=${encodeURIComponent(x.id)}" download class="${x.transcript && x.transcript.length ? '' : 'disabled'}">${icon('speech')}<span>Subtitles${recs.length > 1 ? ` · recording ${i + 1}` : ''}<span class="desc">${x.transcript && x.transcript.length ? 'Transcript with timings (.srt)' : 'No transcript yet'}</span></span></a>`)}
              </div>
            </div>
            <div class="menu">
              <button class="icon-btn" data-menu aria-label="More">${icon('dots')}</button>
              <div class="menu-pop" hidden>
                <button id="copy-folder">${icon('folder')}<span>Copy folder path<span class="desc mono">${data.folder}</span></span></button>
                <div class="hr"></div>
                <button id="delete" class="danger">${icon('trash')}<span>Delete session…<span class="desc">Removes the folder from the library</span></span></button>
              </div>
            </div>
          </div>
          </div>
        </div>
        <div id="banner"></div>
        ${recs.length > 1 ? html`<div class="rec-tabs" role="tablist">${recs.map((x, i) => html`<button class="rec-tab ${i === recIdx ? 'active' : ''}" data-rec="${i}" role="tab">Recording ${i + 1} · ${fmtTime(data.summary.recordings[i].durationMs)}</button>`)}</div>` : ''}
        ${recs.length ? html`<div class="card player" id="player"></div>` : ''}
        <div class="pane-tabs"><button class="rec-tab active" data-pane="transcript">Transcript</button><button class="rec-tab" data-pane="notes">Notes (${sess.notes.length})</button></div>
        <div class="panes" data-tab="transcript">
          <section class="card pane pane-notes">
            <div class="pane-head"><h3>Notes</h3><span class="muted small">${sess.notes.length}</span></div>
            <div class="pane-body scroll" id="notes"></div>
          </section>
          <section class="card pane pane-transcript">
            <div class="pane-head"><h3>Transcript</h3><span class="model-label" id="model-label"></span><span class="grow"></span>
              <label class="toggle"><input type="checkbox" id="follow" ${follow ? 'checked' : ''}>Follow playback</label></div>
            <div class="pane-body scroll" id="transcript"></div>
          </section>
        </div>`);
      bindHead();
      renderPlayer();
      renderNotes();
      renderTranscript();
      renderBanner();
    }

    // -- header ------------------------------------------------------------------------
    function bindHead() {
      const title = $('#title', el);
      let before = title.textContent;
      title.addEventListener('focus', () => { before = title.textContent; });
      title.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); title.blur(); }
        if (e.key === 'Escape') { title.textContent = before; title.blur(); }
      });
      title.addEventListener('blur', async () => {
        const t = title.textContent.replace(/\s+/g, ' ').trim();
        if (!t) { title.textContent = before; return; }
        if (t === before) return;
        try {
          data = await api(`/sessions/${encodeURIComponent(sid)}`, { method: 'PATCH', json: { title: t } });
          toast('Renamed');
        } catch (e) { title.textContent = before; toast(e.message, 'err'); }
      });
      const tb = $('#transcribe', el);
      if (tb) tb.addEventListener('click', async () => {
        if (data.summary.recordings.some((x) => x.edited) && !confirm('Transcribing again replaces the transcript, including the lines you corrected. Continue?')) return;
        tb.disabled = true;
        try {
          const model = $('#model', el).value;
          const res = await api(`/sessions/${encodeURIComponent(sid)}/transcribe`, { method: 'POST', json: { model, recordingIds: null } });
          res.jobs.forEach((j) => wasActive.add(j.id));
          toast(`Queued ${plural(res.jobs.length, 'recording')} for ${INFO.models.find((m) => m.id === model)?.name || model}`);
          await refresh(true);
        } catch (e) { toast(e.message, 'err'); }
        tb.disabled = false;
      });
      $$('[data-rec]', el).forEach((b) => b.addEventListener('click', () => {
        recIdx = Number(b.dataset.rec);
        $$('[data-rec]', el).forEach((x) => x.classList.toggle('active', x === b));
        activeLine = -1; activeNote = null;
        renderPlayer(); renderNotes(); renderTranscript();
      }));
      $$('[data-pane]', el).forEach((b) => b.addEventListener('click', () => {
        $('.panes', el).dataset.tab = b.dataset.pane;
        $$('[data-pane]', el).forEach((x) => x.classList.toggle('active', x === b));
      }));
      $('#copy-folder', el).addEventListener('click', async () => {
        try { await navigator.clipboard.writeText(data.folder); toast('Folder path copied'); } catch (e) { toast(data.folder); }
        closeMenus();
      });
      $('#delete', el).addEventListener('click', async () => {
        closeMenus();
        if (!confirm(`Delete “${s().title}” and its audio from the library?\n\n${data.folder}\n\nThis can't be undone (the phone keeps its copy).`)) return;
        try { await api(`/sessions/${encodeURIComponent(sid)}`, { method: 'DELETE' }); toast('Session deleted'); location.hash = '#/'; } catch (e) { toast(e.message, 'err'); }
      });
      $('#follow', el).addEventListener('change', (e) => { follow = e.target.checked; if (follow) scrollToActive(true); });
      const tr = $('#transcript', el);
      const stopFollow = () => { if (follow && !audio.paused) { follow = false; $('#follow', el).checked = false; } };
      tr.addEventListener('wheel', stopFollow, { passive: true });
      tr.addEventListener('touchmove', stopFollow, { passive: true });
    }

    // -- player ------------------------------------------------------------------------
    function durationMs() {
      const d = audio.duration;
      return Number.isFinite(d) && d > 0 ? d * 1000 : (recInfo() ? recInfo().durationMs : 0);
    }

    function linkedNotes() {
      const r0 = rec();
      if (!r0) return [];
      return s().notes.filter((n) => n.recId === r0.id && n.offsetMs != null).sort((a, b) => a.offsetMs - b.offsetMs);
    }

    function renderPlayer() {
      const box = $('#player', el);
      if (!box) return;
      const info = recInfo();
      if (!info.hasAudio) {
        audio.removeAttribute('src');
        box.innerHTML = out(html`<div class="row muted">${icon('alert')}<span>The audio for this recording (${rec().file}) hasn't arrived from the phone yet.</span></div>`);
        return;
      }
      const src = `/ui/api/sessions/${encodeURIComponent(sid)}/audio/${encodeURIComponent(rec().id)}`;
      if (audio.dataset.src !== src) {
        audio.pause();
        audio.dataset.src = src;
        audio.src = src;
        audio.load();
      }
      const total = durationMs() || 1;
      const markers = linkedNotes().map((n) => html`<div class="seek-marker" data-note="${n.id}" style="left:${Math.min(100, (n.offsetMs / total) * 100)}%"></div>`);
      box.innerHTML = out(html`
        <div class="player-row">
          <button class="play-btn" id="play" aria-label="Play">${icon(audio.paused ? 'play' : 'pause')}</button>
          <button class="icon-btn" id="back" title="Back 10 s (←)" aria-label="Back 10 seconds">${icon('back')}</button>
          <button class="icon-btn" id="fwd" title="Forward 10 s (→)" aria-label="Forward 10 seconds">${icon('fwd')}</button>
          <div class="seek" id="seek" role="slider" aria-label="Position" tabindex="0">
            <div class="seek-track"></div><div class="seek-fill"></div>${markers}<div class="seek-thumb"></div>
            <div class="seek-hover" hidden></div>
          </div>
          <span class="time" id="time">00:00 / ${fmtTime(total)}</span>
          <select class="select speed" id="speed" aria-label="Playback speed">
            ${[0.75, 1, 1.25, 1.5, 1.75, 2].map((v) => html`<option value="${v}" ${audio.playbackRate === v ? 'selected' : ''}>${v}×</option>`)}
          </select>
        </div>
        <div class="player-foot"><span class="mono">${rec().file}</span><span class="sep"></span><span>${fmtDuration(total)}</span>
          ${markers.length ? html`<span class="sep"></span><span><span style="display:inline-block;width:4px;height:10px;border-radius:2px;background:var(--note);vertical-align:-1px;margin-right:6px"></span>${plural(markers.length, 'note')} on the timeline</span>` : ''}
          <span class="sep hint"></span><span class="hint">Space play/pause · ← → 5 s</span></div>`);
      $('#play', box).addEventListener('click', togglePlay);
      $('#back', box).addEventListener('click', () => seekBy(-10000));
      $('#fwd', box).addEventListener('click', () => seekBy(10000));
      $('#speed', box).addEventListener('change', (e) => { audio.playbackRate = Number(e.target.value); });
      bindSeek($('#seek', box));
      updateTime();
    }

    function bindSeek(bar) {
      const tip = $('.seek-hover', bar);
      const frac = (e) => { const r0 = bar.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r0.left) / r0.width)); };
      let dragging = false;
      bar.addEventListener('pointerdown', (e) => {
        const m = e.target.closest('.seek-marker');
        if (m) { const n = s().notes.find((x) => x.id === m.dataset.note); if (n) playNote(n); return; }
        dragging = true; bar.setPointerCapture(e.pointerId);
        seekTo(frac(e) * durationMs());
      });
      bar.addEventListener('pointermove', (e) => {
        const f = frac(e);
        const ms = f * durationMs();
        if (dragging) seekTo(ms);
        const m = e.target.closest('.seek-marker');
        const note = m ? s().notes.find((x) => x.id === m.dataset.note) : null;
        tip.hidden = false;
        tip.style.left = `${f * 100}%`;
        tip.innerHTML = out(html`${fmtTime(note ? note.offsetMs : ms)}${note ? html`<span class="note-tip">${note.text.slice(0, 120)}</span>` : ''}`);
      });
      bar.addEventListener('pointerup', () => { dragging = false; });
      bar.addEventListener('pointerleave', () => { tip.hidden = true; });
      bar.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowLeft') { seekBy(-5000); e.preventDefault(); e.stopPropagation(); }
        if (e.key === 'ArrowRight') { seekBy(5000); e.preventDefault(); e.stopPropagation(); }
      });
    }

    function togglePlay() {
      if (!audio.src) return;
      if (audio.paused) audio.play().catch(() => toast('Press play to start the audio')); else audio.pause();
    }
    function seekTo(ms, play = false) {
      if (!audio.src) return;
      const apply = () => {
        audio.currentTime = Math.max(0, ms / 1000);
        updateTime();
        if (play) audio.play().catch(() => toast('Press play to start the audio'));
      };
      if (audio.readyState >= 1) apply(); else { pendingSeek = apply; }
    }
    function seekBy(delta) { seekTo(audio.currentTime * 1000 + delta); }
    function playNote(n) {
      const prev = linkedNotes().map((x) => x.offsetMs).filter((o) => o < n.offsetMs);
      seekTo(Math.max(n.offsetMs - LEAD_IN_MS, prev.length ? Math.max(...prev) : 0, 0), true);
    }

    function updateTime() {
      const box = $('#player', el);
      if (!box || !audio.src) return;
      const pos = audio.currentTime * 1000;
      const total = durationMs() || 1;
      const f = Math.min(1, pos / total);
      const fill = $('.seek-fill', box), thumb = $('.seek-thumb', box), time = $('#time', box);
      if (fill) fill.style.width = `${f * 100}%`;
      if (thumb) thumb.style.left = `${f * 100}%`;
      if (time) time.textContent = `${fmtTime(pos)} / ${fmtTime(total)}`;
      markActive(pos);
    }

    // -- notes -------------------------------------------------------------------------
    /** A note's photo (a slide, say), if its file came with the session (via an imported backup). */
    function photoBlock(n) {
      const url = data.photos && data.photos[n.photo];
      if (!url) return html`<div class="photo-missing">${icon('image')}<span>Photo not on this computer (${n.photo})</span></div>`;
      return html`<a class="note-photo" href="${url}" target="_blank" rel="noopener" title="Open the photo"><img src="${url}" alt="${n.text || 'Photo'}" loading="lazy"></a>`;
    }

    function renderNotes() {
      const box = $('#notes', el);
      const sess = s();
      const recNum = (id) => sess.recordings.findIndex((x) => x.id === id) + 1;
      const ordered = [...sess.notes].sort((a, b) => key(a) - key(b) || a.createdAt - b.createdAt);
      function key(n) {
        const r0 = sess.recordings.find((x) => x.id === n.recId);
        return r0 && n.offsetMs != null ? r0.createdAt + n.offsetMs : n.createdAt;
      }
      if (!ordered.length) { box.innerHTML = '<div class="transcript-empty">No notes in this session.</div>'; return; }
      box.innerHTML = out(ordered.map((n) => {
        const linked = n.offsetMs != null && recNum(n.recId) > 0;
        const label = linked ? (sess.recordings.length > 1 ? `#${recNum(n.recId)} ${fmtTime(n.offsetMs)}` : fmtTime(n.offsetMs)) : '—';
        return html`<div class="note-item ${linked ? 'linked' : ''} ${n.photo ? 'has-photo' : ''}" data-note="${n.id}" ${linked ? '' : 'title="Not linked to the audio"'}>
          <span class="chip">${label}</span>
          <div>${n.photo ? photoBlock(n) : ''}${n.text ? html`<div class="note-text">${n.text}</div>` : ''}
            ${n.photoText ? html`<details class="photo-text"><summary>Text in the photo</summary><div>${n.photoText}</div></details>` : ''}
            ${linked ? '' : html`<div class="when">written ${fmtDateTime(n.createdAt)}</div>`}</div></div>`;
      }));
      $$('.note-item.linked', box).forEach((item) => item.addEventListener('click', (e) => {
        if (e.target.closest('a, details')) return; // opening a photo or its text isn't "play"
        const n = sess.notes.find((x) => x.id === item.dataset.note);
        const i = sess.recordings.findIndex((x) => x.id === n.recId);
        if (i !== recIdx) { recIdx = i; render(); }
        playNote(n);
      }));
      activeNote = null;
    }

    // -- transcript --------------------------------------------------------------------
    function renderTranscript() {
      const box = $('#transcript', el);
      const label = $('#model-label', el);
      const info = recInfo();
      const lines = segs();
      activeLine = -1;
      if (!info) { box.innerHTML = '<div class="transcript-empty">This session has no recordings.</div>'; label.innerHTML = ''; return; }
      if (info.transcriptSource === 'desktop' || info.transcriptSource === 'phone-edited') {
        label.innerHTML = out(html`<span class="badge ${info.transcriptSource === 'desktop' ? 'ok' : ''}">${icon('chip')}${info.modelName}</span>${info.transcribedAt ? html`<span>${ago(info.transcribedAt)}${info.device ? ` · ${info.device === 'cuda' ? 'GPU' : 'CPU'}` : ''}</span>` : ''}${info.edited ? html`<span>· edited</span>` : ''}`);
      } else if (info.transcriptSource === 'phone') {
        label.innerHTML = out(html`<span class="badge">${icon('phone')}Phone transcript</span>`);
      } else {
        label.innerHTML = '';
      }
      if (!lines.length) {
        box.innerHTML = out(html`<div class="transcript-empty"><svg viewBox="0 0 24 24">${raw(ICONS.speech.replace(/<\/?svg[^>]*>/g, ''))}</svg>
          <div>${info.transcriptSource ? 'The transcript is empty (no speech found).' : info.hasAudio ? 'Not transcribed yet. Pick a model above and press Transcribe.' : 'Waiting for the audio from the phone.'}</div></div>`);
        return;
      }
      box.innerHTML = out(lines.map((seg, i) => html`<div class="line" data-i="${i}">
        <span class="chip">${fmtTime(seg.s)}</span>
        <span class="line-text">${seg.t}</span><button class="icon-btn edit-btn" title="Edit this line" aria-label="Edit line">${icon('edit')}</button></div>`));
      box.onclick = (e) => {
        const line = e.target.closest('.line');
        if (!line || editing) return;
        const i = Number(line.dataset.i);
        if (e.target.closest('.edit-btn')) { startEdit(line, i); return; }
        seekTo(Math.max(0, segs()[i].s - LINE_LEAD_MS), true);
      };
      box.ondblclick = (e) => {
        const line = e.target.closest('.line');
        if (line && e.target.closest('.line-text') && !editing) startEdit(line, Number(line.dataset.i));
      };
      markActive(audio.currentTime * 1000, true);
    }

    function startEdit(line, i) {
      const textEl = $('.line-text', line);
      const before = textEl.textContent;
      editing = true;
      textEl.contentEditable = 'true';
      textEl.focus();
      const range = document.createRange();
      range.selectNodeContents(textEl);
      range.collapse(false);
      const sel = getSelection(); sel.removeAllRanges(); sel.addRange(range);
      const finish = async (save) => {
        textEl.contentEditable = 'false';
        textEl.removeEventListener('keydown', onKey);
        textEl.removeEventListener('blur', onBlur);
        const t = textEl.textContent.replace(/\s+/g, ' ').trim();
        if (!save || t === before || !t) { textEl.textContent = before; editing = false; return; }
        try {
          const seg = segs()[i];
          const res = await api(`/sessions/${encodeURIComponent(sid)}/recordings/${encodeURIComponent(rec().id)}/transcript/${i}`, { method: 'PATCH', json: { text: t, s: seg.s } });
          seg.t = res.segment.t;
          line.classList.add('edited');
          toast('Line saved');
        } catch (e) { textEl.textContent = before; toast(e.message, 'err'); }
        editing = false;
        refresh(true);
      };
      const onKey = (e) => {
        if (e.key === 'Enter') { e.preventDefault(); finish(true); }
        if (e.key === 'Escape') { e.preventDefault(); finish(false); }
        e.stopPropagation();
      };
      const onBlur = () => finish(true);
      textEl.addEventListener('keydown', onKey);
      textEl.addEventListener('blur', onBlur);
    }

    /** Highlights the transcript line and the note at the playback position. */
    function markActive(pos, force = false) {
      const lines = segs();
      let i = -1, lo = 0, hi = lines.length - 1;
      while (lo <= hi) { const mid = (lo + hi) >> 1; if (lines[mid].s <= pos + LINE_LEAD_MS + 50) { i = mid; lo = mid + 1; } else hi = mid - 1; }
      if (i >= 0 && pos > lines[i].e + 1500 && (i + 1 >= lines.length || pos < lines[i + 1].s)) i = -1; // in a long pause
      if (i !== activeLine || force) {
        const box = $('#transcript', el);
        if (box) {
          $$('.line.active', box).forEach((x) => x.classList.remove('active'));
          const line = i >= 0 ? box.querySelector(`.line[data-i="${i}"]`) : null;
          if (line) line.classList.add('active');
          activeLine = i;
          if (follow && line && !editing && (!audio.paused || force)) scrollToActive(false);
        }
      }
      const notes = linkedNotes();
      let n = null;
      for (const x of notes) { if (x.offsetMs <= pos) n = x; else break; }
      const nid = n ? n.id : null;
      if (nid !== activeNote) {
        activeNote = nid;
        const box = $('#notes', el);
        if (box) {
          $$('.note-item.current', box).forEach((x) => x.classList.remove('current'));
          if (nid) { const item = box.querySelector(`.note-item[data-note="${CSS.escape(nid)}"]`); if (item) item.classList.add('current'); }
        }
      }
    }

    function scrollToActive(force) {
      const box = $('#transcript', el);
      const line = box && box.querySelector('.line.active');
      if (!line) return;
      const br = box.getBoundingClientRect(), lr = line.getBoundingClientRect();
      const scrollable = box.scrollHeight > box.clientHeight + 4;
      if (scrollable) {
        if (force || lr.top < br.top + 40 || lr.bottom > br.bottom - 40) box.scrollTo({ top: box.scrollTop + (lr.top - br.top) - box.clientHeight / 3, behavior: 'smooth' });
      } else if (force || lr.top < 150 || lr.bottom > window.innerHeight - 40) {
        line.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    }

    // -- jobs banner -------------------------------------------------------------------
    function renderBanner() {
      const box = $('#banner', el);
      if (!box) return;
      const jobs = data.jobs;
      const active = jobs.filter((j) => j.status === 'queued' || j.status === 'running');
      const recNum = (id) => s().recordings.findIndex((x) => x.id === id) + 1;
      const nRecs = s().recordings.length;
      const modelName = (id) => (INFO && INFO.models.find((m) => m.id === id)?.name) || id;
      if (active.length) {
        const run = active.find((j) => j.status === 'running');
        const j = run || active[0];
        const pct = Math.round(j.progress * 100);
        const what = nRecs > 1 ? `recording ${recNum(j.recordingId)} of ${nRecs}` : 'the recording';
        box.innerHTML = out(html`<div class="card job-banner">
          <div class="grow"><strong>${run ? `Transcribing ${what}` : 'Waiting in the queue'}</strong>
            <span class="muted"> · ${modelName(j.model)}${run && j.phase ? ` · ${j.phase}` : ''}${run ? ` · ${pct}%` : ''}${active.length > 1 ? ` · ${active.length - 1} more queued` : ''}</span>
            <div class="progress ${run && pct === 0 ? 'indet' : ''}"><div style="width:${run ? Math.max(pct, 2) : 0}%"></div></div></div>
          <button class="btn sm" id="cancel-job">Cancel</button></div>`);
        $('#cancel-job', box).addEventListener('click', async () => {
          try { for (const a of active) await api(`/jobs/${a.id}`, { method: 'DELETE' }); toast('Cancelled'); refresh(true); } catch (e) { toast(e.message, 'err'); }
        });
        return;
      }
      const lastErr = [...jobs].reverse().find((j) => j.status === 'error' && j.error !== 'Cancelled' && wasActive.has(j.id));
      if (lastErr) {
        box.innerHTML = out(html`<div class="card job-banner err"><span style="color:var(--err)">${icon('alert')}</span>
          <div class="grow"><strong>Transcription failed</strong><div class="small">${lastErr.error}</div></div>
          <button class="btn sm ghost" id="dismiss">${icon('x')}</button></div>`);
        $('#dismiss', box).addEventListener('click', () => { wasActive.delete(lastErr.id); box.innerHTML = ''; });
        return;
      }
      box.innerHTML = '';
    }

    // -- refresh -----------------------------------------------------------------------
    const signature = (d) => JSON.stringify([d.session, d.summary.recordings, d.photos]);

    async function refresh(force = false) {
      const fresh = await api(`/sessions/${encodeURIComponent(sid)}`);
      const sessionSig = signature(fresh);
      const changed = sessionSig !== lastSig;
      // Job news first, so nothing is missed while an edit holds back the redraw.
      for (const j of fresh.jobs.filter((x) => wasActive.has(x.id) && x.status === 'done')) {
        wasActive.delete(j.id);
        toast(`Transcript ready (${(INFO && INFO.models.find((m) => m.id === j.model)?.name) || j.model})`);
      }
      fresh.jobs.filter((j) => j.status === 'queued' || j.status === 'running').forEach((j) => wasActive.add(j.id));
      const busy = editing || (document.activeElement && document.activeElement.id === 'title');
      if (changed && busy) {
        // Keep what's on screen (and what an edit refers to) until the edit is done; the next
        // refresh after it brings the new content.
        data.jobs = fresh.jobs;
        renderBanner();
        return;
      }
      data = fresh;
      if (changed) {
        const y = $('#transcript', el)?.scrollTop;
        render();
        if (y) $('#transcript', el).scrollTop = y;
      } else {
        renderBanner();
      }
      lastSig = sessionSig;
      if (force) renderBanner();
    }

    // -- audio events ------------------------------------------------------------------
    const onPlay = () => { const b = $('#play', el); if (b) b.innerHTML = ICONS.pause; loop(); };
    const onPause = () => { const b = $('#play', el); if (b) b.innerHTML = ICONS.play; cancelAnimationFrame(raf); updateTime(); };
    const onMeta = () => {
      if (pendingSeek) { const f = pendingSeek; pendingSeek = null; f(); }
      renderPlayer();
    };
    const onSeeked = () => updateTime();
    function loop() { updateTime(); if (!audio.paused) raf = requestAnimationFrame(loop); }
    audio.addEventListener('play', onPlay);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('ended', onPause);
    audio.addEventListener('loadedmetadata', onMeta);
    audio.addEventListener('seeked', onSeeked);
    const onKey = (e) => {
      if (isTyping(e.target) || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === ' ') { e.preventDefault(); togglePlay(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); seekBy(-5000); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); seekBy(5000); }
    };
    document.addEventListener('keydown', onKey);

    function applyParams(params) {
      const before = recIdx;
      pickRecording(params);
      if (before !== recIdx) render();
      const t = params.get('t');
      const noteId = params.get('note');
      if (noteId) {
        const item = $(`.note-item[data-note="${CSS.escape(noteId)}"]`, el);
        if (item) {
          item.scrollIntoView({ block: 'center', behavior: 'smooth' });
          item.classList.add('current');
          $('.panes', el).dataset.tab = t == null ? 'notes' : $('.panes', el).dataset.tab;
        }
      }
      if (t != null && rec() && recInfo().hasAudio) {
        follow = true;
        const f = $('#follow', el); if (f) f.checked = true;
        const note = noteId && s().notes.find((n) => n.id === noteId && n.recId === rec().id && n.offsetMs != null);
        if (note) playNote(note); // from just before the note, as when clicking it
        else seekTo(Math.max(0, Number(t) - LINE_LEAD_MS), true);
        setTimeout(() => scrollToActive(true), 150);
      }
    }

    lastSig = signature(data);
    render();
    applyParams(r.params);
    const stopPoll = poll(() => refresh(), () => (data.jobs.some((j) => j.status === 'queued' || j.status === 'running') ? 1000 : 4000));

    return {
      update: (r2) => applyParams(r2.params),
      cleanup: () => {
        stopPoll();
        cancelAnimationFrame(raf);
        audio.pause();
        audio.removeEventListener('play', onPlay);
        audio.removeEventListener('pause', onPause);
        audio.removeEventListener('ended', onPause);
        audio.removeEventListener('loadedmetadata', onMeta);
        audio.removeEventListener('seeked', onSeeked);
        document.removeEventListener('keydown', onKey);
      },
    };
  };

  // ---------------------------------------------------------------------------------------
  // Jobs

  routes.jobs = async (el) => {
    let last = '';
    async function load() {
      const { jobs } = await api('/jobs');
      const sig = JSON.stringify(jobs);
      if (sig === last) return;
      last = sig;
      const active = jobs.filter((j) => j.status === 'queued' || j.status === 'running').length;
      const badge = (j) => {
        if (j.status === 'running') return html`<span class="badge accent run"><span class="dot"></span>Running ${Math.round(j.progress * 100)}%</span>`;
        if (j.status === 'queued') return html`<span class="badge"><span class="dot"></span>Queued</span>`;
        if (j.status === 'done') return html`<span class="badge ok">${icon('check')}Done</span>`;
        if (j.error === 'Cancelled') return html`<span class="badge">Cancelled</span>`;
        return html`<span class="badge err">${icon('alert')}Error</span>`;
      };
      const took = (j) => (j.startedAt && j.finishedAt ? fmtDuration(j.finishedAt - j.startedAt) : '');
      el.innerHTML = out(html`
        <div class="page-head"><div><h1>Jobs</h1><div class="sub">${active ? `${plural(active, 'job')} waiting or running` : 'Nothing running'} · one transcription at a time, oldest first</div></div></div>
        ${jobs.length ? html`<div class="card jobs">
          <div class="job job-head"><span>Status</span><span>Session</span><span class="c-model">Model</span><span class="c-time">When</span><span></span></div>
          ${jobs.map((j) => html`<div class="job">
            <div>${badge(j)}</div>
            <div style="min-width:0">
              <div class="title">${j.sessionTitle ? html`<a href="#/session/${encodeURIComponent(j.sessionId)}">${j.sessionTitle}</a>` : html`<span class="muted">Deleted session</span>`}
                ${j.recordingCount > 1 ? html`<span class="muted small"> · recording ${j.recordingNumber}</span>` : ''}</div>
              ${j.status === 'running' ? html`<div class="progress" style="margin-top:6px"><div style="width:${Math.max(2, Math.round(j.progress * 100))}%"></div></div>${j.phase ? html`<div class="muted small" style="margin-top:3px">${j.phase}…</div>` : ''}` : ''}
              ${j.status === 'error' && j.error !== 'Cancelled' ? html`<div class="err-text">${j.error}</div>` : ''}
              ${j.info ? html`<div class="muted small">${j.info}</div>` : ''}
            </div>
            <div class="c-model small">${j.modelName}${j.device ? html`<span class="muted"> · ${j.device === 'cuda' ? 'GPU' : 'CPU'}</span>` : ''}</div>
            <div class="c-time small muted">${ago(j.createdAt)}${took(j) ? html`<br>took ${took(j)}` : ''}</div>
            <div>${j.status === 'queued' || j.status === 'running' ? html`<button class="btn sm" data-cancel="${j.id}">Cancel</button>` : ''}</div>
          </div>`)}
        </div>` : html`<div class="card empty"><svg class="big" viewBox="0 0 24 24">${raw(ICONS.wave.replace(/<\/?svg[^>]*>/g, ''))}</svg><h2>No jobs yet</h2><p>Transcriptions you start here or from the phone show up in this list.</p></div>`}`);
      $$('[data-cancel]', el).forEach((b) => b.addEventListener('click', async () => {
        try { await api(`/jobs/${b.dataset.cancel}`, { method: 'DELETE' }); toast('Cancelled'); load(); } catch (e) { toast(e.message, 'err'); }
      }));
    }
    await load();
    return { cleanup: poll(load, 1200) };
  };

  // ---------------------------------------------------------------------------------------
  // Import

  function resultHtml(res) {
    const parts = [];
    if (res.added) parts.push(`${res.added} added`);
    if (res.updated) parts.push(`${res.updated} updated`);
    if (res.unchanged) parts.push(`${res.unchanged} already up to date`);
    return html`<div class="result ${res.errors && res.errors.length && !parts.length ? 'err' : 'ok'}">
      <strong>${parts.join(', ') || 'Nothing imported'}</strong>${parts.length ? html` · <a href="#/">open the library</a>` : ''}
      ${(res.errors || []).map((e) => html`<div class="small" style="color:var(--err)">${e}</div>`)}</div>`;
  }

  routes.import = async (el) => {
    const s = await api('/settings');
    el.innerHTML = out(html`
      <div class="page-head"><div><h1>Import</h1><div class="sub">Bring in sessions from an AudioCool phone backup (Backup &amp; restore on the phone). Existing sessions are updated; desktop transcripts are kept.</div></div></div>
      <div class="grid-2">
        <section class="card card-pad stack">
          <div><h2>${icon('folder')} From a folder on this computer</h2>
          <p class="muted small">The backup folder (it holds one folder per session, like <span class="mono">2026-09-22_15-05_a1b2c3d4e5f6</span>), or a single session folder. Windows drives are under <span class="mono">/mnt/c/…</span></p></div>
          <form id="path-form" class="row wrap">
            <input class="input" style="flex:1 1 260px" id="path" placeholder="/mnt/c/Users/you/Documents/AudioCool Backup" spellcheck="false">
            <button class="btn primary" type="submit">Import</button>
          </form>
          <div id="path-result"></div>
        </section>
        <section class="card card-pad stack">
          <div><h2>${icon('zip')} Upload a zip</h2><p class="muted small">Zip the backup folder (or some session folders) and drop it here.</p></div>
          <label class="drop" id="drop">
            ${icon('upload')}<div style="margin-top:6px"><strong>Drop a .zip here</strong> or click to choose</div>
            <input type="file" id="file" accept=".zip,application/zip" hidden>
          </label>
          <div id="zip-progress" hidden><div class="row small muted"><span id="zip-label">Uploading…</span></div><div class="progress"><div id="zip-bar" style="width:0"></div></div></div>
          <div id="zip-result"></div>
        </section>
      </div>
      <section class="card card-pad" style="margin-top:18px">
        <h2>Or use the backup folder as the library</h2>
        <p class="muted">The library uses the same layout as the phone's backup folder, so you can point it at that folder directly (for example one synced to this PC) in <a href="#/settings">Settings</a>. Current library: <span class="mono">${s.library}</span></p>
      </section>`);
    $('#path-form', el).addEventListener('submit', async (e) => {
      e.preventDefault();
      const box = $('#path-result', el);
      const path = $('#path', el).value.trim();
      if (!path) return;
      box.innerHTML = '<div class="result">Importing…</div>';
      try { box.innerHTML = out(resultHtml(await api('/import/path', { method: 'POST', json: { path } }))); } catch (err) { box.innerHTML = out(html`<div class="result err">${err.message}</div>`); }
    });
    const drop = $('#drop', el), file = $('#file', el);
    const upload = (f) => {
      if (!f) return;
      const prog = $('#zip-progress', el), bar = $('#zip-bar', el), label = $('#zip-label', el), box = $('#zip-result', el);
      prog.hidden = false; box.innerHTML = ''; bar.style.width = '0';
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/ui/api/import/zip');
      xhr.setRequestHeader('X-AudioCool', '1');
      xhr.setRequestHeader('Content-Type', 'application/zip');
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) { bar.style.width = `${(e.loaded / e.total) * 100}%`; label.textContent = `Uploading ${f.name}… ${Math.round((e.loaded / e.total) * 100)}%`; } };
      xhr.upload.onload = () => { label.textContent = 'Importing…'; };
      xhr.onload = () => {
        prog.hidden = true;
        let res = null; try { res = JSON.parse(xhr.responseText); } catch (e) { /* ignore */ }
        if (xhr.status >= 200 && xhr.status < 300) box.innerHTML = out(resultHtml(res));
        else box.innerHTML = out(html`<div class="result err">${(res && res.error) || `Upload failed (${xhr.status})`}</div>`);
      };
      xhr.onerror = () => { prog.hidden = true; box.innerHTML = '<div class="result err">Upload failed</div>'; };
      xhr.send(f);
    };
    file.addEventListener('change', () => upload(file.files[0]));
    ['dragenter', 'dragover'].forEach((t) => drop.addEventListener(t, (e) => { e.preventDefault(); drop.classList.add('over'); }));
    ['dragleave', 'drop'].forEach((t) => drop.addEventListener(t, (e) => { e.preventDefault(); drop.classList.remove('over'); }));
    drop.addEventListener('drop', (e) => upload(e.dataTransfer.files[0]));
  };

  // ---------------------------------------------------------------------------------------
  // Pair

  routes.pair = async (el) => {
    let info = await api('/pair');
    let shown = 0;
    function render() {
      const code = info.codes[shown];
      el.innerHTML = out(html`
        <div class="page-head"><div><h1>Pair your phone</h1><div class="sub">The phone and this computer must be on the same Wi-Fi network.</div></div></div>
        <section class="card card-pad pair">
          <div class="qr-box">${code ? raw(code.svg) : html`<div style="width:264px;height:264px;display:grid;place-items:center;color:#555;line-height:1.4;padding:20px;text-align:center">No LAN address found. Connect this computer to Wi-Fi or Ethernet.</div>`}</div>
          <div class="stack">
            <div><h3>Pairing code</h3><div class="code" id="code">${Array.from(info.token).map((c, i) => html`<span${raw(i === 3 ? ' style="margin-right:.6em"' : '')}>${c}</span>`)}</div></div>
            <div><h3>Address</h3><div class="url-list">${info.urls.length ? info.urls.map((u, i) => html`<div class="row"><code>${u}</code>${info.urls.length > 1 ? html`<button class="btn sm ${i === shown ? 'primary' : ''}" data-show="${i}">${i === shown ? 'Shown' : 'Show QR'}</button>` : ''}</div>`) : html`<span class="muted">none found</span>`}</div></div>
            <ol class="steps">
              <li>Open AudioCool on the phone and start pairing with a desktop.</li>
              <li>Scan this QR code, or type the address and the code.</li>
              <li>If the phone can't connect, allow port ${info.port} through the Windows firewall (below).</li>
            </ol>
            <div class="row wrap">
              <button class="btn" id="copy">${icon('copy')}Copy code</button>
              <button class="btn danger" id="reset">${icon('refresh')}New code…</button>
            </div>
          </div>
        </section>
        <section class="card card-pad" style="margin-top:18px">
          <h2>Windows firewall (one time)</h2>
          <p class="muted">With WSL's mirrored networking the phone connects to this PC's own address, so two firewalls must let port ${info.port} in: Windows Defender Firewall and the Hyper-V firewall that guards WSL. Run in <strong>PowerShell as Administrator</strong>:</p>
          <pre class="cmd">New-NetFirewallRule -DisplayName "AudioCool Desktop" -Direction Inbound -Protocol TCP -LocalPort ${info.port} -Action Allow

New-NetFirewallHyperVRule -Name "AudioCool-Desktop" -DisplayName "AudioCool Desktop (WSL)" -Direction Inbound \`
  -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts ${info.port}</pre>
          <p class="muted small" style="margin-top:10px">Still no luck? Check that the phone is on the same Wi-Fi and that Windows treats it as a <em>Private</em> network. README.md has more (including how to undo these rules).</p>
        </section>`);
      $('#copy', el).addEventListener('click', async () => { try { await navigator.clipboard.writeText(info.token); toast('Code copied'); } catch (e) { toast(info.token); } });
      $('#reset', el).addEventListener('click', async () => {
        if (!confirm('Make a new pairing code? Phones paired with the old code will have to pair again.')) return;
        try { info = await api('/pair/reset', { method: 'POST' }); render(); toast('New pairing code made'); } catch (e) { toast(e.message, 'err'); }
      });
      $$('[data-show]', el).forEach((b) => b.addEventListener('click', () => { shown = Number(b.dataset.show); render(); }));
    }
    render();
  };

  // ---------------------------------------------------------------------------------------
  // Settings

  routes.settings = async (el) => {
    const [s, info] = await Promise.all([api('/settings'), loadInfo()]);
    el.innerHTML = out(html`
      <div class="page-head"><div><h1>Settings</h1><div class="sub">AudioCool Desktop ${info.version} · API ${info.apiVersion}</div></div></div>
      <form class="settings" id="form">
        <section class="card card-pad stack">
          <label class="field">Computer name<span class="hint">Shown on the phone when it finds this computer.</span>
            <input class="input" id="name" value="${s.name}" maxlength="60"></label>
          <label class="field">Default model<span class="hint">Used when the phone or the Transcribe button doesn't pick one.</span>
            <select class="select" id="model">${info.models.map((m) => html`<option value="${m.id}" ${m.id === s.defaultModel ? 'selected' : ''}>${m.name}</option>`)}</select></label>
          <label class="field">Library folder<span class="hint">Sessions are stored here in the phone's backup layout. You can point this at the phone's backup folder.${s.libraryOverridden ? ' (Currently set by --library / AUDIOCOOL_LIBRARY for this run.)' : ''}</span>
            <input class="input wide mono" id="library" value="${s.library}" spellcheck="false"></label>
          <div class="row"><button class="btn primary" type="submit">Save</button><span class="muted small" id="saved"></span></div>
        </section>
        <section class="card card-pad">
          <h2>Speech models</h2>
          <p class="muted small">${info.cuda ? html`GPU: <strong>${info.gpu}</strong> (CUDA)` : 'No CUDA GPU found: models run on the CPU.'}</p>
          ${info.models.map((m) => html`<div class="model-row">
            <div><strong>${m.name}</strong>${m.default ? html` <span class="badge accent">default</span>` : ''}<div class="muted small">${m.description}</div></div>
            <div class="row small">${m.loaded ? html`<span class="badge ok">loaded</span>` : ''}<span class="badge">${icon('chip')}${m.device === 'cuda' ? 'GPU' : 'CPU'}</span>${m.status ? html`<span class="badge ${m.status === 'ready' ? '' : 'warn'}">${m.status === 'ready' ? 'downloaded' : m.status}</span>` : ''}</div>
          </div>`)}
        </section>
      </form>`);
    let saved = { name: s.name, defaultModel: s.defaultModel, library: s.library };
    $('#form', el).addEventListener('submit', async (e) => {
      e.preventDefault();
      // Send only what changed: saving the library folder also rescans it (and makes a
      // --library folder given for this run the saved one).
      const now = { name: $('#name', el).value.trim(), defaultModel: $('#model', el).value, library: $('#library', el).value.trim() };
      const changes = Object.fromEntries(Object.entries(now).filter(([k, v]) => v !== saved[k]));
      if (!Object.keys(changes).length) { toast('Nothing changed'); return; }
      try {
        const res = await api('/settings', { method: 'PUT', json: changes });
        saved = { name: res.name, defaultModel: res.defaultModel, library: res.library };
        await loadInfo();
        $('#saved', el).textContent = 'Saved';
        toast('Settings saved');
      } catch (err) { toast(err.message, 'err'); }
    });
  };

  routes.notfound = async (el) => {
    el.innerHTML = out(html`<div class="card empty"><h2>Page not found</h2><p><a href="#/">Back to the library</a></p></div>`);
  };

  route();
})();
