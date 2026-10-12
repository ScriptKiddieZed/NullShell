'use strict';
/* Network panel: every request of the page, headers, bodies, timing, WebSocket frames, HAR export and a request repeater. */
(function () {
  const rows = [];
  const byAgent = new Map();
  const P = { rec: true, q: '', type: 'all', sel: null, navStart: 0, seq: 0, tab: 'headers', dirty: 0 };
  const TYPES = [['all', 'All'], ['doc', 'Doc'], ['xhr', 'XHR'], ['js', 'JS'], ['css', 'CSS'], ['img', 'Img'], ['media', 'Media'], ['font', 'Font'], ['ws', 'WS'], ['other', 'Other']];
  let list, foot, detail, dBody, dTitle, dTabs;

  const norm = (u) => String(u).split('#')[0];
  function extOf(u) { try { return new URL(u).pathname.split('.').pop().toLowerCase(); } catch (e) { return ''; } }
  function guessType(r) {
    if (r.kind === 'ws') return 'ws';
    const e = extOf(r.url), acc = ((r.reqH.find((x) => /^accept$/i.test(x[0])) || [])[1] || '');
    const ini = r.ini || '';
    if (r.main) return 'doc';
    if (ini === 'iframe' || ini === 'frame') return 'doc';
    if (ini === 'script' || /^(js|mjs)$/.test(e)) return 'js';
    if (ini === 'css' && /^(woff2?|ttf|otf|eot)$/.test(e)) return 'font';
    if (ini === 'img' || ini === 'image' || /^(png|jpe?g|gif|webp|bmp|svg|ico|avif)$/.test(e) || /^image\//.test(acc)) return 'img';
    if (/^(css)$/.test(e) || ini === 'link' && /text\/css/.test(acc)) return 'css';
    if (/^(woff2?|ttf|otf|eot)$/.test(e)) return 'font';
    if (ini === 'video' || ini === 'audio' || /^(mp4|webm|mp3|ogg|m4a|wav|m3u8|ts|mpd)$/.test(e)) return 'media';
    if (r.kind === 'fetch' || r.kind === 'xhr' || ini === 'fetch' || ini === 'xmlhttprequest') return 'xhr';
    if (/^(html?)$/.test(e) && /text\/html/.test(acc)) return 'doc';
    return 'other';
  }
  const bad = (r) => r.status >= 400 || !!r.error;
  const statusText = (r) => r.error ? '(failed)' : r.status ? String(r.status) : '\u2026';

  function newRow(ts) { const r = { n: ++P.seq, method: 'GET', url: '', reqH: [], resH: null, status: 0, ts, time: null, size: null, kind: '', frames: [] }; rows.push(r); if (rows.length > 2000) rows.shift(); return r; }
  function findPartner(url, method, ts, wantNative) {
    for (let i = rows.length - 1; i >= 0 && i > rows.length - 80; i--) {
      const r = rows[i];
      if (norm(r.url) === norm(url) && r.method === method && Math.abs(r.ts - ts) < 5000 && (wantNative ? r.native && !r.agent : r.agent && !r.native)) return r;
    }
    return null;
  }
  function touch(r) { r.type = guessType(r); if (r.el) paint(r); P.dirty = 1; schedule(); }
  let sched = 0;
  function schedule() { if (sched) return; sched = setTimeout(() => { sched = 0; waterfall(); summary(); }, 400); }

  function onNative(e) {
    if (!P.rec) return;
    let r = findPartner(e.url, e.method, e.ts, false);
    if (r) { r.native = e.id; r.main = r.main || e.main; if (e.h && e.h.length) r.reqH = e.h; }
    else { r = newRow(e.ts); r.native = e.id; r.url = e.url; r.method = e.method; r.reqH = e.h || []; r.main = e.main; }
    r.type = guessType(r);
    attach(r);
  }
  function onAgent(e) {
    if (e.ph === 'start') {
      if (!P.rec) return;
      let r = findPartner(e.url, e.method, e.ts, true);
      if (r) { r.agent = e.id; if (e.rh && e.rh.length) r.reqH = mergeH(r.reqH, e.rh); }
      else { r = newRow(e.ts); r.agent = e.id; r.url = e.url; r.method = e.method; r.reqH = e.rh || []; }
      r.kind = e.kind; r.body = e.body;
      byAgent.set(e.id, r);
      r.type = guessType(r);
      attach(r);
      return;
    }
    const r = byAgent.get(e.id);
    if (!r) return;
    if (e.ph === 'end') { r.status = e.status; r.statusText = e.st; r.resH = e.h; r.time = e.dur != null ? e.dur : r.time; r.finalUrl = e.fu; r.redirected = e.redir; r.ws = e.ws; }
    else if (e.ph === 'fail') { r.error = e.e; r.time = e.dur; }
    else if (e.ph === 'body') { r.bodySize = e.size; r.prev = e.prev; r.hasBody = true; if (r.size == null) r.size = e.size; }
    touch(r);
  }
  const mergeH = (a, b) => { const have = new Set(a.map((x) => x[0].toLowerCase())); return a.concat(b.filter((x) => !have.has(x[0].toLowerCase()))); };
  function attach(r) {
    if (!r.el) { r.el = h('div', { class: 'nr', onclick: () => openDetail(r) }, h('div', { class: 'nm' }, h('div', { class: 'u' }), h('div', { class: 'd' }), h('div', { class: 'wf' }, h('i'))), h('div', { class: 'st' }), h('div', { class: 'ty' }), h('div', { class: 'sz' }), h('div', { class: 'tm' })); list.append(r.el); while (list.childElementCount > 2000) list.firstChild.remove(); }
    paint(r);
    P.dirty = 1;
    schedule();
  }
  function visible(r) {
    if (P.type !== 'all' && r.type !== P.type) return false;
    return !P.q || (r.url.toLowerCase().includes(P.q) || String(r.status).includes(P.q) || r.method.toLowerCase() === P.q);
  }
  function paint(r) {
    const c = r.el.children;
    let host = '', path = '';
    try { const u = new URL(r.url); host = u.host; path = u.pathname; } catch (e) { path = r.url; }
    c[0].children[0].textContent = (r.method !== 'GET' ? r.method + ' ' : '') + baseName(r.url);
    c[0].children[1].textContent = host + (r.ws ? '' : '') + (r.error ? '  \u00B7 ' + r.error : '');
    c[1].textContent = statusText(r);
    c[2].textContent = r.type;
    c[3].textContent = r.size != null ? fmtBytes(r.size) : '\u2014';
    c[4].textContent = r.time != null ? fmtMs(r.time) : '\u2014';
    r.el.classList.toggle('bad', bad(r));
    r.el.classList.toggle('sel', P.sel === r);
    r.el.hidden = !visible(r);
  }
  function waterfall() {
    const vis = rows.filter((r) => r.el && !r.el.hidden);
    let end = 1000;
    vis.forEach((r) => { end = Math.max(end, r.ts - P.navStart + (r.time || 0)); });
    vis.forEach((r) => { const i = r.el.querySelector('.wf i'); i.style.left = Math.max(0, (r.ts - P.navStart) / end * 100) + '%'; i.style.width = Math.max(0.5, (r.time || 0) / end * 100) + '%'; });
  }
  function summary() {
    const vis = rows.filter((r) => r.el && !r.el.hidden);
    let bytes = 0;
    vis.forEach((r) => { if (r.size > 0) bytes += r.size; });
    foot.textContent = vis.length + ' requests  \u00B7  ' + fmtBytes(bytes) + ' transferred' + (P.load ? '  \u00B7  load ' + fmtMs(P.load) : '') + (P.dcl ? '  \u00B7  DOM ' + fmtMs(P.dcl) : '');
    const errs = rows.filter(bad).length;
    NS.badge('network', rows.length, errs > 0 && false);
  }
  function refilter() { rows.forEach((r) => { if (r.el) paint(r); }); waterfall(); summary(); }
  function clearAll() { rows.length = 0; byAgent.clear(); clear(list); P.sel = null; P.load = P.dcl = 0; detail.classList.remove('on'); foot.textContent = ''; NS.badge('network', 0); }

  function onPerf(e) {
    e.e.forEach((p) => {
      const r = rows.find((x) => x.url === p.n && !x.perf);
      if (!r) return;
      r.perf = p;
      r.ini = p.i;
      if (p.d) r.time = r.time != null && r.agent ? r.time : p.d;
      if (p.ts > 0 || p.eb > 0) r.size = p.ts > 0 ? p.ts : p.eb;
      if (!r.status && p.st) r.status = p.st;
      r.proto = p.np;
      touch(r);
    });
  }
  function onNativeResp(e) {
    const r = rows.slice().reverse().find((x) => x.url === e.url && !x.status);
    if (!r) return;
    r.status = e.status; r.resH = r.resH || e.h; r.statusText = e.st;
    touch(r);
  }

  // ---------------------------------------------------------------- exports
  function curl(r) {
    const p = ['curl', shellQ(r.url)];
    if (r.method !== 'GET') p.push('-X', r.method);
    r.reqH.filter((x) => !/^(host|content-length|connection|accept-encoding)$/i.test(x[0])).forEach((x) => p.push('-H', shellQ(x[0] + ': ' + x[1])));
    if (r.body && r.body.text && !/^\[/.test(r.body.text)) p.push('--data-raw', shellQ(r.body.text));
    p.push('--compressed');
    return p.join(' ');
  }
  function fetchCode(r) {
    const hs = {};
    r.reqH.filter((x) => !/^(host|content-length|connection|accept-encoding|user-agent|referer)$/i.test(x[0])).forEach((x) => { hs[x[0]] = x[1]; });
    const o = { method: r.method, headers: hs }; if (r.body && r.body.text) o.body = r.body.text;
    return 'fetch(' + JSON.stringify(r.url) + ', ' + JSON.stringify(o, null, 2) + ');';
  }
  function har() {
    const list2 = rows.filter((r) => !r.el || !r.el.hidden);
    return { log: { version: '1.2', creator: { name: 'NullShell DevTools', version: '1' }, entries: list2.map((r) => ({
      startedDateTime: new Date(r.ts).toISOString(), time: r.time || 0,
      request: { method: r.method, url: r.url, httpVersion: r.proto || 'HTTP/1.1', headers: r.reqH.map((x) => ({ name: x[0], value: x[1] })), queryString: [], cookies: [], headersSize: -1, bodySize: -1, postData: r.body && r.body.text ? { mimeType: '', text: r.body.text } : undefined },
      response: { status: r.status || 0, statusText: r.statusText || '', httpVersion: r.proto || 'HTTP/1.1', headers: (r.resH || (r.replay && r.replay.headers) || []).map((x) => ({ name: x[0], value: x[1] })), cookies: [], content: { size: r.size || 0, mimeType: '', text: r.prev || undefined }, redirectURL: '', headersSize: -1, bodySize: r.size || 0 },
      cache: {}, timings: { send: 0, wait: r.time || 0, receive: 0 } })) } };
  }
  async function exportHar() {
    let host = 'page';
    try { host = new URL(NS.tab.url).host || host; } catch (e) {}
    try { const p = await native('save', { name: host + '.har', text: JSON.stringify(har(), null, 1) }); toast('Saved ' + p); } catch (e) { toast(e.message); }
  }

  // ---------------------------------------------------------------- details
  const kv = (k, v) => h('div', { class: 'kv mono' }, h('span', { class: 'k' }, k), h('span', { class: 'v' }, v));
  const section = (t, extra) => h('div', { class: 'sec' }, t, extra || null);
  async function replay(r) {
    toast('Requesting\u2026');
    try {
      r.replay = await native('replay', { url: r.url, headers: r.reqH, method: r.method === 'HEAD' ? 'HEAD' : 'GET' }, 70000);
      if (!r.status) r.status = r.replay.status;
      r.resH = r.resH || r.replay.headers;
      if (r.size == null) r.size = r.replay.size;
      touch(r);
      if (P.sel === r && detail.classList.contains('on')) renderDetail();
    } catch (e) { toast(e.message); }
  }
  async function bodyOf(r) {
    if (r.replay && (r.replay.text != null || r.replay.b64)) return r.replay;
    if (r.agent) { try { const b = await rpc('net.body', { id: r.agent }); if (b) return { text: b.text, size: b.size, status: r.status }; } catch (e) {} }
    return null;
  }
  function mimeOf(r) { const hs = r.resH || (r.replay && r.replay.headers) || []; const c = hs.find((x) => /^content-type$/i.test(x[0])); return c ? c[1].split(';')[0].trim() : ''; }
  function jsonTree(v, key) {
    const wrap = h('div', { class: 'tr' });
    const isObj = v && typeof v === 'object';
    const head = h('div', { class: 'r' }, h('span', { class: 'tw' }, isObj ? '\u25B6' : ''), key != null ? h('span', { class: 'pn' }, key + ': ') : null,
      isObj ? h('span', { class: 'dim' }, Array.isArray(v) ? 'Array(' + v.length + ')' : '{' + Object.keys(v).length + '}') : h('span', { class: typeof v === 'string' ? 'v-s' : 'v-n' }, typeof v === 'string' ? JSON.stringify(v) : String(v)));
    wrap.append(head);
    if (isObj) head.addEventListener('click', () => {
      let k = wrap.querySelector(':scope > .kids');
      if (k) { k.hidden = !k.hidden; head.firstChild.textContent = k.hidden ? '\u25B6' : '\u25BC'; return; }
      k = h('div', { class: 'kids' });
      Object.keys(v).slice(0, 500).forEach((x) => k.append(jsonTree(v[x], x)));
      wrap.append(k);
      head.firstChild.textContent = '\u25BC';
    });
    return wrap;
  }
  function codeBox(text, lang) {
    const box = h('div', { class: 'cod mono' });
    let cur = text, pp = false;
    const draw = () => { clear(box); const lines = hlLines(cur.length > 300000 ? cur.slice(0, 300000) : cur, lang); lines.slice(0, 4000).forEach((f) => { const d = h('div', { style: { whiteSpace: 'pre-wrap', wordBreak: 'break-all' } }); d.append(f); d.append(''); box.append(d); }); if (lines.length > 4000) box.append(h('div', { class: 'dim' }, '\u2026 truncated')); };
    draw();
    const bar = h('div', { class: 'tb' }, h('button', { class: 'b', onclick: () => { pp = !pp; cur = pp ? pretty(text, lang) : text; draw(); } }, '{} Format'), h('button', { class: 'b', onclick: () => copyText(text) }, 'Copy'), h('span', { class: 'dim' }, fmtBytes(text.length)));
    return h('div', null, bar, box);
  }
  async function renderDetail() {
    const r = P.sel;
    if (!r) return;
    clear(dBody);
    for (const b of dTabs.querySelectorAll('button')) b.classList.toggle('on', b.dataset.t === P.tab);
    const tab = P.tab;
    if (tab === 'headers') {
      dBody.append(section('General'), kv('Request URL', r.url), kv('Method', r.method), kv('Status', r.error ? 'Failed: ' + r.error : (r.status || '(unknown)') + (r.statusText ? ' ' + r.statusText : '')));
      if (r.proto) dBody.append(kv('Protocol', r.proto));
      if (r.finalUrl && r.finalUrl !== r.url) dBody.append(kv('Final URL', r.finalUrl));
      const rh = r.resH || (r.replay && r.replay.headers);
      dBody.append(section('Response headers', !rh && (r.method === 'GET' || r.method === 'HEAD') ? h('button', { class: 'b p', style: { marginLeft: '8px' }, onclick: () => replay(r) }, 'Load (repeats the request)') : null));
      if (rh) rh.forEach((x) => dBody.append(kv(x[0], x[1]))); else dBody.append(h('div', { class: 'dim', style: { padding: '4px 8px' } }, 'Not captured for this request. Use Load to repeat it, or the Repeater tab.'));
      dBody.append(section('Request headers'));
      if (r.reqH.length) r.reqH.forEach((x) => dBody.append(kv(x[0], x[1]))); else dBody.append(h('div', { class: 'dim', style: { padding: '4px 8px' } }, 'None captured'));
    } else if (tab === 'payload') {
      let u = null; try { u = new URL(r.url); } catch (e) {}
      if (u && [...u.searchParams].length) { dBody.append(section('Query parameters')); u.searchParams.forEach((v, k) => dBody.append(kv(k, v))); }
      if (r.body && r.body.text) {
        dBody.append(section('Request body (' + (r.body.size >= 0 ? fmtBytes(r.body.size) : 'form') + ')'));
        const t = r.body.text;
        let parsed = null; try { parsed = JSON.parse(t); } catch (e) {}
        if (parsed && typeof parsed === 'object') dBody.append(h('div', { class: 'cod' }, jsonTree(parsed)), codeBox(t, 'json'));
        else if (/^[^=\s]+=/.test(t) && !/^\[/.test(t)) { t.split('&').forEach((p) => { const i = p.indexOf('='); let k = p, v = ''; try { k = decodeURIComponent(p.slice(0, i < 0 ? p.length : i)); v = i < 0 ? '' : decodeURIComponent(p.slice(i + 1).replace(/\+/g, ' ')); } catch (e) {} dBody.append(kv(k, v)); }); }
        else dBody.append(codeBox(t, 'js'));
      } else if (!(u && [...u.searchParams].length)) dBody.append(h('div', { class: 'dim', style: { padding: '12px' } }, r.method === 'GET' ? 'This request has no payload.' : 'The body of a form submitted by the page itself is not visible here. Requests made with fetch or XHR show it.'));
    } else if (tab === 'response' || tab === 'preview') {
      const b = await bodyOf(r);
      if (P.sel !== r) return;
      if (!b) { dBody.append(h('div', { style: { padding: '12px' } }, h('div', { class: 'dim', style: { marginBottom: '8px' } }, 'The response body was not captured.'), (r.method === 'GET' ? h('button', { class: 'b p', onclick: () => replay(r) }, 'Load response (repeats the request)') : null))); return; }
      const mime = mimeOf(r) || b.mime || '';
      if (b.b64 && /^image\//.test(b.mime || mime)) { dBody.append(h('div', { style: { padding: '10px', textAlign: 'center' } }, h('img', { src: 'data:' + (b.mime || mime) + ';base64,' + b.b64, style: { maxWidth: '100%' } }), h('div', { class: 'dim' }, fmtBytes(b.size)))); return; }
      if (b.text == null) { dBody.append(h('div', { class: 'dim', style: { padding: '12px' } }, 'Binary data, ' + fmtBytes(b.size) + (b.truncated ? ' (not loaded, too large)' : '') + '.')); return; }
      const lang = langOf(mime, r.url);
      if (tab === 'preview' && lang === 'json') { try { dBody.append(h('div', { class: 'cod' }, jsonTree(JSON.parse(b.text)))); return; } catch (e) {} }
      dBody.append(codeBox(b.text, lang));
    } else if (tab === 'cookies') {
      const rc = r.reqH.find((x) => /^cookie$/i.test(x[0]));
      dBody.append(section('Request cookies'));
      if (rc) rc[1].split(/;\s*/).forEach((c) => { const i = c.indexOf('='); dBody.append(kv(c.slice(0, i), c.slice(i + 1))); }); else dBody.append(h('div', { class: 'dim', style: { padding: '4px 8px' } }, 'None'));
      const rh = r.resH || (r.replay && r.replay.headers) || [];
      dBody.append(section('Set-Cookie'));
      const sc = rh.filter((x) => /^set-cookie$/i.test(x[0]));
      if (sc.length) sc.forEach((x) => dBody.append(h('div', { class: 'cod mono' }, x[1]))); else dBody.append(h('div', { class: 'dim', style: { padding: '4px 8px' } }, rh.length ? 'None' : 'Response headers not loaded'));
    } else if (tab === 'timing') {
      const p = r.perf;
      if (!p) { dBody.append(h('div', { class: 'dim', style: { padding: '12px' } }, 'No timing yet. It is available once the request has finished and the page allows it (cross-origin resources only expose the total).'));
        if (r.time != null) dBody.append(kv('Total', fmtMs(r.time))); return; }
      const total = p.d || 1;
      const ph = [['Queued / stalled', Math.max(0, (p.ds || p.cs || p.rq) - p.fs)], ['DNS lookup', p.de - p.ds], ['Initial connection', p.ce - p.cs - (p.ss > 0 ? p.ce - p.ss : 0)], ['TLS', p.ss > 0 ? p.ce - p.ss : 0], ['Waiting (TTFB)', p.rs - p.rq], ['Content download', p.re - p.rs]];
      dBody.append(section('Timing'));
      ph.forEach((x) => { if (!(x[1] > 0) && x[0] !== 'Waiting (TTFB)') return; dBody.append(h('div', { class: 'kv' }, h('span', { class: 'k' }, x[0]), h('span', { class: 'v' }, h('div', null, fmtMs(Math.max(0, x[1]))), h('div', { class: 'bar' }, h('i', { style: { left: '0', width: Math.min(100, Math.max(1, x[1] / total * 100)) + '%' } }))))); });
      dBody.append(kv('Total', fmtMs(p.d)), kv('Encoded / decoded size', fmtBytes(p.eb) + ' / ' + fmtBytes(p.db)), kv('Transferred', fmtBytes(p.ts)), kv('Protocol', p.np || '\u2014'));
    } else if (tab === 'frames') {
      if (!r.frames.length) dBody.append(h('div', { class: 'dim', style: { padding: '12px' } }, r.kind === 'ws' ? 'No frames yet.' : 'Only WebSocket requests have frames.'));
      r.frames.forEach((f) => dBody.append(h('div', { class: 'kv mono ' + (f.dir === 'in' ? 'ws-in' : f.dir === 'out' ? 'ws-out' : 'dim') }, h('span', { class: 'k', style: { flex: '0 0 22%' } }, (f.dir === 'in' ? '\u2193 ' : f.dir === 'out' ? '\u2191 ' : '\u00D7 ') + fmtClock(f.ts)), h('span', { class: 'v' }, f.d))));
    } else if (tab === 'repeater') renderRepeater(r);
  }
  function rawOf(r) {
    const skip = /^(host|content-length|connection|accept-encoding)$/i;
    let t = r.method + ' ' + r.url + ' HTTP/1.1\n';
    r.reqH.filter((x) => !skip.test(x[0])).forEach((x) => { t += x[0] + ': ' + x[1] + '\n'; });
    t += '\n' + (r.body && r.body.text && !/^\[/.test(r.body.text) ? r.body.text : '');
    return t;
  }
  function parseRaw(t) {
    const [head, ...rest] = t.replace(/\r\n/g, '\n').split('\n\n');
    const lines = head.split('\n');
    const m = /^(\S+)\s+(\S+)/.exec(lines[0] || '');
    if (!m) throw new Error('The first line must be: METHOD URL');
    const headers = lines.slice(1).filter(Boolean).map((l) => { const i = l.indexOf(':'); return [l.slice(0, i).trim(), l.slice(i + 1).trim()]; }).filter((x) => x[0]);
    return { method: m[1].toUpperCase(), url: m[2], headers, body: rest.join('\n\n') };
  }
  function renderRepeater(r) {
    const ta = h('textarea', { class: 'in', spellcheck: 'false', autocapitalize: 'off' });
    ta.value = r.raw || rawOf(r);
    const out = h('div');
    const follow = h('input', { type: 'checkbox' });
    const send = async () => {
      r.raw = ta.value;
      clear(out).append(h('div', { class: 'dim', style: { padding: '8px' } }, 'Sending\u2026'));
      try {
        const spec = parseRaw(ta.value);
        spec.follow = follow.checked;
        const res = await native('send', spec, 70000);
        clear(out);
        if (res.error) { out.append(h('div', { class: 'err', style: { padding: '8px' } }, res.error)); return; }
        out.append(section('Response  ' + res.status + ' ' + (res.statusText || '') + '   ' + fmtMs(res.ms) + '   ' + fmtBytes(res.size)));
        res.headers.forEach((x) => out.append(kv(x[0], x[1])));
        if (res.text != null) out.append(codeBox(res.text, langOf(((res.headers.find((x) => /^content-type$/i.test(x[0])) || [])[1]) || '', spec.url)));
        else if (res.b64) out.append(h('div', { class: 'dim', style: { padding: '8px' } }, 'Binary response, ' + fmtBytes(res.size)));
      } catch (e) { clear(out).append(h('div', { class: 'err', style: { padding: '8px' } }, e.message)); }
    };
    dBody.append(h('div', { class: 'tb' }, h('button', { class: 'b p', onclick: send }, 'Send'), h('label', { class: 'ck' }, follow, 'Follow redirects'),
      h('button', { class: 'b', onclick: () => { try { const s = parseRaw(ta.value); copyText(curl({ url: s.url, method: s.method, reqH: s.headers, body: { text: s.body } })); } catch (e) { toast(e.message); } } }, 'Copy cURL'),
      h('button', { class: 'b', onclick: () => { r.raw = null; ta.value = rawOf(r); } }, 'Reset')),
      h('div', { class: 'dim', style: { padding: '4px 8px' } }, 'Sent from the app through your proxy settings, with the same cookies.'), ta, out);
  }
  function openDetail(r) {
    const prev = P.sel;
    P.sel = r;
    if (prev && prev.el) paint(prev);
    paint(r);
    P.tab = r.kind === 'ws' ? 'frames' : 'headers';
    clear(dTitle).append(r.method + ' ' + baseName(r.url));
    detail.classList.add('on');
    renderDetail();
  }

  const P2 = {
    build(el) {
      const rec = h('button', { class: 'i on', title: 'Record', onclick: () => { P.rec = !P.rec; rec.classList.toggle('on', P.rec); toast(P.rec ? 'Recording' : 'Paused'); } }, '\u25CF');
      const q = h('input', { type: 'text', placeholder: 'Filter (URL, status, method)' });
      q.addEventListener('input', () => { P.q = q.value.trim().toLowerCase(); refilter(); });
      const cache = h('input', { type: 'checkbox' }), keep = h('input', { type: 'checkbox' });
      cache.addEventListener('change', () => native('setting', { key: 'noCache', value: cache.checked }));
      keep.addEventListener('change', () => native('setting', { key: 'preserve', value: keep.checked }));
      P2.cache = cache; P2.keep = keep;
      el.append(h('div', { class: 'tb' }, rec, h('button', { class: 'i', title: 'Clear', onclick: clearAll }, '\u2205'), q,
        h('label', { class: 'ck' }, keep, 'Preserve'), h('label', { class: 'ck' }, cache, 'No cache'), h('button', { class: 'i', title: 'Export HAR', onclick: exportHar }, '\u21E9')));
      el.append(h('div', { class: 'tb' }, h('div', { class: 'chips' }, TYPES.map((t) => h('button', { class: 'chip' + (t[0] === 'all' ? ' on' : ''), 'data-t': t[0], onclick: (e) => { P.type = t[0]; el.querySelectorAll('.chip').forEach((c) => c.classList.toggle('on', c === e.currentTarget)); refilter(); } }, t[1])))));
      list = h('div', { class: 'grow' });
      foot = h('div', { class: 'dim', style: { padding: '3px 8px', background: 'var(--s1)', borderTop: '1px solid var(--line)', flex: 'none' } });
      el.append(list, foot);
      dTitle = h('span', { style: { flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } });
      dTabs = h('div', { class: 'sub' }, [['headers', 'Headers'], ['payload', 'Payload'], ['preview', 'Preview'], ['response', 'Response'], ['cookies', 'Cookies'], ['timing', 'Timing'], ['frames', 'Frames'], ['repeater', 'Repeater']]
        .map((t) => h('button', { 'data-t': t[0], onclick: () => { P.tab = t[0]; renderDetail(); } }, t[1])));
      dBody = h('div', { class: 'grow' });
      detail = h('div', { class: 'overlay' }, h('div', { class: 'tb' }, h('button', { class: 'i', onclick: () => detail.classList.remove('on') }, '\u2190'), dTitle,
        h('button', { class: 'b', onclick: () => P.sel && copyText(P.sel.url) }, 'URL'), h('button', { class: 'b', onclick: () => P.sel && copyText(curl(P.sel)) }, 'cURL'),
        h('button', { class: 'b', onclick: () => P.sel && copyText(fetchCode(P.sel)) }, 'fetch'), h('button', { class: 'b', onclick: () => P.sel && native('open', { url: P.sel.url }) }, 'Open')), dTabs, dBody);
      el.append(detail);
    },
    show() { waterfall(); summary(); },
    reset() { clearAll(); },
    settings() { if (P2.cache) P2.cache.checked = !!NS.settings.noCache; if (P2.keep) P2.keep.checked = !!NS.settings.preserve; },
    rows: () => rows,
    text: async (r) => { let b = await bodyOf(r); if (!b && (r.method === 'GET' || r.method === 'HEAD')) { await replay(r); b = await bodyOf(r); } return b ? Object.assign({ mime: mimeOf(r) }, b) : null; }
  };
  NS.addPanel('network', 'Network', P2);
  NS.listen('req', onNative);
  NS.listen('net', onAgent);
  NS.listen('perf', onPerf);
  NS.listen('nres', onNativeResp);
  NS.listen('ws', (e) => { const r = byAgent.get(e.id); if (r) { r.frames.push(e); if (P.sel === r && P.tab === 'frames') renderDetail(); } });
  NS.listen('nav', (e) => { if (!NS.settings.preserve) clearAll(); P.navStart = e.ts || Date.now(); });
  NS.listen('life', (e) => { if (!e.top) return; if (e.k === 'load') P.load = e.v; if (e.k === 'dcl') P.dcl = e.v; schedule(); });
})();
