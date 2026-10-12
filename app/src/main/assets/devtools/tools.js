'use strict';
/* Sources, Application, Security, Performance and Device panels. */

// ================================================================ Sources
(function () {
  function wrapPref() { try { return localStorage.getItem('ns.wrap') !== '0'; } catch (e) { return true; } }
  const P = { files: [], cur: null, wrap: wrapPref(), pp: false, text: '', lang: 'js', hits: [], hi: 0 };
  let wrapBtn, listEl, viewer, vTitle, vBody, vSearch, vInfo, filterIn;

  async function collect() {
    const files = [{ label: 'Live DOM (current document)', sub: 'as the page looks now', kind: 'dom', lang: 'html' }];
    try {
      const info = await rpc('page.info');
      info.scripts.forEach((s) => { if (!s.src) files.push({ label: '(inline script ' + (s.i + 1) + ')', sub: s.len + ' characters', kind: 'inline', i: s.i, lang: 'js' }); });
      info.styles.forEach((s) => { if (s.inline) files.push({ label: '(inline style ' + (s.i + 1) + ')', sub: 'style element', kind: 'style', i: s.i, lang: 'css' }); });
    } catch (e) {}
    NS.panels.network.rows().filter((r) => ['doc', 'js', 'css', 'xhr'].includes(r.type) || /json/.test(extOf2(r.url))).forEach((r) => {
      let host = ''; try { host = new URL(r.url).host; } catch (e) {}
      files.push({ label: baseName(r.url), sub: host + '  \u00B7  ' + r.type, kind: 'net', row: r, lang: langOf('', r.url) });
    });
    return files;
  }
  const extOf2 = (u) => { try { return new URL(u).pathname.split('.').pop(); } catch (e) { return ''; } };
  async function draw() {
    P.files = await collect();
    const q = filterIn.value.trim().toLowerCase();
    clear(listEl);
    P.files.filter((f) => !q || f.label.toLowerCase().includes(q) || f.sub.toLowerCase().includes(q)).forEach((f) => {
      listEl.append(h('div', { class: 'row', onclick: () => openFile(f) }, h('div', { class: 'nm' }, h('div', null, f.label), h('div', { class: 'dim' }, f.sub))));
    });
    if (listEl.childElementCount < 2) listEl.append(h('div', { class: 'dim', style: { padding: '12px' } }, 'Network requests appear here as the page loads. Reload the page with developer tools on to capture them all.'));
  }
  async function openFile(f) {
    P.cur = f;
    clear(vTitle).append(f.label);
    clear(vBody).append(h('div', { class: 'dim', style: { padding: '12px' } }, 'Loading\u2026'));
    viewer.classList.add('on');
    try {
      let text = '';
      if (f.kind === 'dom') text = await rpc('src.dom');
      else if (f.kind === 'inline') text = await rpc('src.inline', { i: f.i });
      else if (f.kind === 'style') text = await rpc('src.style', { i: f.i });
      else { const b = await NS.panels.network.text(f.row); if (!b || b.text == null) throw new Error('This file could not be loaded as text.'); text = b.text; f.lang = langOf(b.mime, f.row.url); }
      P.text = text; P.lang = f.lang; P.hits = [];
      P.pp = text.length > 2000 && text.length / (text.split('\n').length) > 240;      // minified: lay it out readably first
      render();
    } catch (e) { clear(vBody).append(h('div', { class: 'err', style: { padding: '12px' } }, e.message)); }
  }
  function render(limit) {
    const text = P.pp ? pretty(P.text, P.lang) : P.text;
    const lines = hlLines(text, P.lang), max = limit || 3000;
    const box = h('div', { class: 'src' + (P.wrap ? ' wr' : '') });
    const digits = String(Math.min(lines.length, max)).length;
    const q = vSearch.value.toLowerCase();
    const plain = text.split('\n');
    P.hits = [];
    lines.slice(0, max).forEach((f, i) => {
      const d = h('div', { class: 'cd' });
      if (P.wrap) {                                           // wrapped lines hang under their own indentation
        const m = /^[ \t]*/.exec(plain[i] || '')[0];
        const ind = Math.min(30, m.replace(/\t/g, '    ').length);
        if (ind) { d.style.paddingLeft = ind + 'ch'; d.style.textIndent = '-' + ind + 'ch'; }
      }
      d.append(f);
      if (q && (plain[i] || '').toLowerCase().includes(q)) { d.classList.add('hit'); P.hits.push(d); }
      box.append(h('div', { class: 'sl' }, h('div', { class: 'ln', style: { minWidth: (digits + 1.5) + 'ch' } }, String(i + 1)), d));
    });
    clear(vBody).append(box);
    if (lines.length > max) vBody.append(h('button', { class: 'b', style: { margin: '8px' }, onclick: () => render(max + 4000) }, 'Show more (' + (lines.length - max) + ' lines left)'));
    clear(vInfo).append(lines.length + ' lines  \u00B7  ' + fmtBytes(text.length) + (q ? '  \u00B7  ' + P.hits.length + ' matches' : ''));
    if (q && P.hits.length) { P.hi = Math.min(P.hi, P.hits.length - 1); P.hits[P.hi].scrollIntoView({ block: 'center' }); }
  }
  NS.addPanel('sources', 'Sources', {
    build(el) {
      filterIn = h('input', { type: 'text', placeholder: 'Filter files' });
      filterIn.addEventListener('input', draw);
      el.append(h('div', { class: 'tb' }, filterIn, h('button', { class: 'i', onclick: draw }, '\u21BB')));
      listEl = h('div', { class: 'grow' });
      el.append(listEl);
      vTitle = h('span', { style: { flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } });
      vSearch = h('input', { type: 'text', placeholder: 'Find in file', style: { maxWidth: '38%' } });
      vSearch.addEventListener('keydown', (e) => { if (e.key === 'Enter') { P.hi = P.hits.length ? (P.hi + 1) % P.hits.length : 0; render(); } });
      vSearch.addEventListener('input', () => { P.hi = 0; render(); });
      vInfo = h('span', { class: 'dim' });
      vBody = h('div', { class: 'grow' });
      viewer = h('div', { class: 'overlay' }, h('div', { class: 'tb' }, h('button', { class: 'i', onclick: () => viewer.classList.remove('on') }, '\u2190'), vTitle,
        h('button', { class: 'b', onclick: () => { P.pp = !P.pp; render(); } }, '{}'), wrapBtn = h('button', { class: 'b' + (P.wrap ? ' p' : ''), onclick: () => { P.wrap = !P.wrap; try { localStorage.setItem('ns.wrap', P.wrap ? '1' : '0'); } catch (e) {} wrapBtn.className = 'b' + (P.wrap ? ' p' : ''); render(); } }, 'Wrap'),
        h('button', { class: 'b', onclick: () => copyText(P.text) }, 'Copy')), h('div', { class: 'tb' }, vSearch, vInfo), vBody);
      el.append(viewer);
    },
    show() { draw(); },
    reset() { clear(listEl); viewer.classList.remove('on'); }
  });
})();

// ================================================================ Application
(function () {
  const P = { sec: 'cookies', db: null, store: null, off: 0 };
  let body, chips;
  const SECS = [['cookies', 'Cookies'], ['local', 'Local storage'], ['session', 'Session storage'], ['idb', 'IndexedDB'], ['cache', 'Cache'], ['sw', 'Service workers']];
  const note = (t) => h('div', { class: 'dim', style: { padding: '12px' } }, t);
  const row = (cells, actions) => h('div', { class: 'kv mono' }, h('span', { class: 'k', style: { flex: '0 0 32%' } }, cells[0]), h('span', { class: 'v' }, cells[1]), actions || null);
  const act = (label, fn, cls) => h('button', { class: 'i ' + (cls || ''), onclick: fn }, label);
  const err = (e) => toast(e.message);

  async function cookies() {
    const list = await native('cookies', { url: NS.tab.url });
    const bar = h('div', { class: 'tb' }, h('button', { class: 'b p', onclick: () => cookieForm() }, 'Add cookie'), h('button', { class: 'b d', onclick: async () => { if (await confirmBox('Delete all cookies for this site?')) { await native('clearCookies', { url: NS.tab.url }); show(); } } }, 'Clear'),
      h('span', { class: 'dim' }, list.length + ' cookies. The app lists name and value; flags and expiry are not exposed by Android.'));
    clear(body).append(bar);
    if (!list.length) body.append(note('No cookies for ' + (NS.tab.url || 'this page')));
    list.forEach((c) => body.append(row([c.name, c.value], h('span', null, act('\u270E', () => cookieForm(c)), act('\u2715', async () => { await native('delCookie', { url: NS.tab.url, name: c.name }); show(); }, 'err')))));
  }
  function cookieForm(c) {
    const f = { name: h('input', { type: 'text', value: c ? c.name : '' }), value: h('input', { type: 'text', value: c ? c.value : '' }), path: h('input', { type: 'text', value: '/' }), age: h('input', { type: 'text', placeholder: 'seconds (empty = session)' }),
      secure: h('input', { type: 'checkbox' }), http: h('input', { type: 'checkbox' }), same: h('select', null, ['', 'Lax', 'Strict', 'None'].map((v) => h('option', { value: v }, v || 'SameSite: default'))) };
    const L = (t, e) => h('div', { style: { margin: '6px 0' } }, h('div', { class: 'dim' }, t), e);
    modal(c ? 'Edit cookie' : 'New cookie', h('div', null, L('Name', f.name), L('Value', f.value), L('Path', f.path), L('Max-Age', f.age),
      h('label', { class: 'ck' }, f.secure, 'Secure'), ' ', h('label', { class: 'ck' }, f.http, 'HttpOnly'), L('', f.same)),
      [{ label: 'Cancel' }, { label: 'Save', cls: 'p', fn: async () => {
        let s = f.name.value.trim() + '=' + f.value.value + '; Path=' + (f.path.value || '/');
        if (f.age.value.trim()) s += '; Max-Age=' + f.age.value.trim();
        if (f.secure.checked) s += '; Secure';
        if (f.http.checked) s += '; HttpOnly';
        if (f.same.value) s += '; SameSite=' + f.same.value;
        try { await native('setCookie', { url: NS.tab.url, header: s }); show(); } catch (e) { err(e); }
      } }]);
  }
  async function web(area) {
    const list = await rpc('ls.list', { area });
    clear(body).append(h('div', { class: 'tb' }, h('button', { class: 'b p', onclick: async () => { const k = await ask('Key', ''); if (!k) return; const v = await ask('Value', '', true); if (v === null) return; await rpc('ls.set', { area, key: k, value: v }); show(); } }, 'Add'),
      h('button', { class: 'b d', onclick: async () => { if (await confirmBox('Clear all entries?')) { await rpc('ls.clear', { area }); show(); } } }, 'Clear'), h('span', { class: 'dim' }, list.length + ' items')));
    if (!list.length) body.append(note('Empty'));
    list.forEach((e) => body.append(row([e[0], e[1]], h('span', null, act('\u270E', async () => { const k = await ask('Key', e[0]); if (k === null) return; const v = await ask('Value', e[1], true); if (v === null) return; await rpc('ls.set', { area, old: e[0], key: k, value: v }); show(); }), act('\u2715', async () => { await rpc('ls.del', { area, key: e[0] }); show(); }, 'err')))));
  }
  async function idb() {
    clear(body);
    if (!P.db) {
      const dbs = await rpc('idb.dbs');
      body.append(h('div', { class: 'tb' }, h('span', { class: 'dim' }, dbs.length + ' databases')));
      if (!dbs.length) body.append(note('No IndexedDB databases'));
      dbs.forEach((d) => body.append(h('div', { class: 'row', onclick: () => { P.db = d.name; P.store = null; P.off = 0; idb(); } }, h('div', { class: 'nm' }, d.name), h('span', { class: 'dim' }, 'v' + d.version))));
      return;
    }
    const info = await rpc('idb.info', { name: P.db });
    body.append(h('div', { class: 'tb' }, h('button', { class: 'b', onclick: () => { if (P.store) P.store = null; else P.db = null; idb().catch(err); } }, '\u2190 Back'), h('b', null, P.db + (P.store ? ' / ' + P.store : '')),
      h('span', { style: { flex: 1 } }), P.store ? h('button', { class: 'b d', onclick: async () => { if (await confirmBox('Clear this object store?')) { await rpc('idb.clear', { name: P.db, store: P.store }); idb(); } } }, 'Clear') : h('button', { class: 'b d', onclick: async () => { if (await confirmBox('Delete database ' + P.db + '?')) { await rpc('idb.deleteDb', { name: P.db }); P.db = null; idb(); } } }, 'Delete DB')));
    if (!P.store) {
      info.stores.forEach((s) => body.append(h('div', { class: 'row', onclick: () => { P.store = s.name; P.off = 0; idb().catch(err); } }, h('div', { class: 'nm' }, s.name, h('div', { class: 'dim' }, 'key: ' + JSON.stringify(s.keyPath) + (s.auto ? ' (auto)' : '') + (s.indexes.length ? '  \u00B7  indexes: ' + s.indexes.join(', ') : ''))), h('span', { class: 'dim' }, s.count))));
      return;
    }
    const rows = await rpc('idb.rows', { name: P.db, store: P.store, off: P.off, limit: 50 });
    body.append(h('div', { class: 'tb' }, h('button', { class: 'b', disabled: P.off === 0, onclick: () => { P.off = Math.max(0, P.off - 50); idb(); } }, '\u2039 Prev'), h('span', { class: 'dim' }, (P.off + 1) + '\u2013' + (P.off + rows.length)), h('button', { class: 'b', disabled: rows.length < 50, onclick: () => { P.off += 50; idb(); } }, 'Next \u203A')));
    rows.forEach((r) => body.append(row([r.k, r.v], act('\u2398', () => copyText(r.v)))));
    if (!rows.length) body.append(note('Empty'));
  }
  async function cache() {
    const caches = await rpc('cache.list');
    clear(body);
    if (!caches.length) body.append(note('No Cache Storage entries'));
    caches.forEach((c) => {
      body.append(h('div', { class: 'sec' }, c.name + '  (' + c.count + ')', h('button', { class: 'i err', onclick: async () => { await rpc('cache.delete', { name: c.name }); cache(); } }, 'delete')));
      c.items.forEach((i) => body.append(h('div', { class: 'row mono' }, h('div', { class: 'nm' }, i.url), act('\u2715', async () => { await rpc('cache.delete', { name: c.name, url: i.url }); cache(); }, 'err'))));
    });
  }
  async function sw() {
    const list = await rpc('sw.list');
    clear(body);
    if (!list.length) body.append(note('No service workers'));
    list.forEach((s) => body.append(h('div', { class: 'row' }, h('div', { class: 'nm' }, s.scope, h('div', { class: 'dim' }, s.script + '  \u00B7  ' + s.state)), h('button', { class: 'b d', onclick: async () => { await rpc('sw.unregister', { scope: s.scope }); sw(); } }, 'Unregister'))));
  }
  async function show() {
    for (const b of chips.children) b.classList.toggle('on', b.dataset.s === P.sec);
    clear(body).append(note('Loading\u2026'));
    try { await ({ cookies, local: () => web('local'), session: () => web('session'), idb, cache, sw }[P.sec])(); } catch (e) { clear(body).append(h('div', { class: 'err', style: { padding: '12px' } }, e.message)); }
  }
  NS.addPanel('application', 'Application', {
    build(el) {
      chips = h('div', { class: 'chips' }, SECS.map((s) => h('button', { class: 'chip', 'data-s': s[0], onclick: () => { P.sec = s[0]; P.db = null; show(); } }, s[1])));
      el.append(h('div', { class: 'tb' }, chips, h('button', { class: 'i', onclick: show }, '\u21BB'),
        h('button', { class: 'b d', onclick: async () => { if (await confirmBox('Clear cookies, storage, caches and service workers for this site?')) { await native('clearSite', { url: NS.tab.url }); for (const m of ['ls.clear']) { await rpc(m, { area: 'local' }).catch(() => {}); await rpc(m, { area: 'session' }).catch(() => {}); } toast('Site data cleared'); show(); } } }, 'Clear site data')));
      body = h('div', { class: 'grow' });
      el.append(body);
    },
    show, reset() { P.db = null; if (NS.cur === 'application') show(); }
  });
})();

// ================================================================ Security
(function () {
  let body;
  const CHECKS = [
    ['strict-transport-security', 'HSTS', 'Makes the browser use HTTPS for later visits', (v) => /max-age=\d{6,}/i.test(v)],
    ['content-security-policy', 'Content-Security-Policy', 'Limits where scripts and other content may load from', (v) => !/unsafe-inline|\*\s*(;|$)/.test(v)],
    ['x-content-type-options', 'X-Content-Type-Options', 'Stops the browser guessing content types', (v) => /nosniff/i.test(v)],
    ['x-frame-options', 'X-Frame-Options', 'Protects against clickjacking (or use CSP frame-ancestors)', (v) => /deny|sameorigin/i.test(v)],
    ['referrer-policy', 'Referrer-Policy', 'Controls what the Referer header leaks', () => true],
    ['permissions-policy', 'Permissions-Policy', 'Turns off powerful features the site does not use', () => true],
    ['cross-origin-opener-policy', 'Cross-Origin-Opener-Policy', 'Isolates the page from other windows', () => true],
    ['cross-origin-resource-policy', 'Cross-Origin-Resource-Policy', 'Stops other sites embedding your resources', () => true]];
  function mainRow() { return NS.panels.network.rows().find((r) => r.main && (r.status || (r.replay && r.replay.status))) || NS.panels.network.rows().find((r) => r.type === 'doc'); }
  async function show() {
    clear(body).append(h('div', { class: 'dim', style: { padding: '12px' } }, 'Checking\u2026'));
    const out = [];
    let c = null;
    try { c = await native('cert', {}); } catch (e) {}
    const url = NS.tab.url || '';
    const https = url.startsWith('https://');
    out.push(h('div', { class: 'card' }, h('h4', null, 'Connection'),
      h('div', { class: https ? 'good' : 'poor' }, https ? '\uD83D\uDD12 Encrypted (HTTPS)' : '\u26A0 Not encrypted'),
      c && c.proxy ? h('div', { class: 'warn' }, 'Traffic goes through your proxy: ' + c.proxy) : null,
      h('div', { class: 'dim mono', style: { wordBreak: 'break-all' } }, url)));
    if (c && c.cert) {
      const k = c.cert;
      out.push(h('div', { class: 'card' }, h('h4', null, 'Certificate'), kvs([['Issued to', k.subject], ['Issued by', k.issuer], ['Valid from', k.from], ['Valid until', k.to], ['Signature', k.sigAlg], ['Serial', k.serial], ['SHA-256', k.sha256], ['Names', (k.sans || []).join(', ')]]),
        h('div', { class: k.trust === 'system' ? 'good' : 'warn', style: { marginTop: '6px' } }, k.trust === 'system' ? 'Trusted by Android' : k.trust === 'private' ? 'Accepted through a CA you imported in NullShell' : 'Trust could not be verified')));
    } else if (https) out.push(h('div', { class: 'card dim' }, 'Certificate details are not available for this page.'));
    const r = mainRow();
    const hs = r && (r.resH || (r.replay && r.replay.headers));
    const hc = h('div', { class: 'card' }, h('h4', null, 'Security headers (main document)'));
    if (!hs) hc.append(h('div', { class: 'dim' }, r ? 'Response headers were not captured.' : 'No main document request recorded. Reload the page.'), r ? h('button', { class: 'b p', style: { marginTop: '6px' }, onclick: async () => { toast('Requesting\u2026'); try { r.replay = await native('replay', { url: r.url, headers: r.reqH, method: 'GET' }, 70000); r.resH = r.resH || r.replay.headers; show(); } catch (e) { toast(e.message); } } }, 'Check (repeats the request)') : null);
    else {
      const get = (n) => { const f = hs.find((x) => x[0].toLowerCase() === n); return f ? f[1] : null; };
      CHECKS.forEach((k) => { const v = get(k[0]); const ok = v !== null && k[3](v); hc.append(h('div', { style: { margin: '4px 0' } }, h('b', { class: v === null ? 'poor' : ok ? 'good' : 'mid' }, (v === null ? '\u2717 ' : ok ? '\u2713 ' : '\u26A0 ') + k[1]), h('div', { class: 'dim' }, k[2]), v ? h('div', { class: 'mono', style: { wordBreak: 'break-all' } }, v) : null)); });
      ['server', 'x-powered-by', 'x-aspnet-version', 'x-generator'].forEach((n) => { const v = get(n); if (v) hc.append(h('div', { class: 'warn', style: { margin: '4px 0' } }, 'Reveals technology: ' + n + ': ' + v)); });
      const cookies = hs.filter((x) => x[0].toLowerCase() === 'set-cookie');
      if (cookies.length) { hc.append(h('div', { class: 'sec', style: { margin: '8px 0 2px' } }, 'Cookies set')); cookies.forEach((x) => { const v = x[1]; hc.append(h('div', { class: 'mono', style: { margin: '3px 0', wordBreak: 'break-all' } }, v.split(';')[0].split('=')[0], '  ', h('span', { class: /;\s*secure/i.test(v) ? 'good' : 'poor' }, 'Secure'), ' ', h('span', { class: /;\s*httponly/i.test(v) ? 'good' : 'poor' }, 'HttpOnly'), ' ', h('span', { class: /samesite/i.test(v) ? 'good' : 'mid' }, /samesite=(\w+)/i.exec(v) ? 'SameSite=' + /samesite=(\w+)/i.exec(v)[1] : 'SameSite?'))); }); }
    }
    out.push(hc);
    if (https) {
      const mixed = NS.panels.network.rows().filter((x) => x.url.startsWith('http://'));
      out.push(h('div', { class: 'card' }, h('h4', null, 'Mixed content'), mixed.length ? [h('div', { class: 'poor' }, mixed.length + ' insecure requests on a secure page'), mixed.slice(0, 20).map((x) => h('div', { class: 'mono dim', style: { wordBreak: 'break-all' } }, x.url))] : h('div', { class: 'good' }, 'None seen')));
    }
    clear(body).append(...out);
  }
  const kvs = (pairs) => pairs.filter((p) => p[1]).map((p) => h('div', { class: 'kv' }, h('span', { class: 'k' }, p[0]), h('span', { class: 'v mono' }, p[1])));
  NS.addPanel('security', 'Security', { build(el) { body = h('div', { class: 'grow' }); el.append(body); }, show, reset() { if (NS.cur === 'security') show(); } });
})();

// ================================================================ Performance
(function () {
  let body, timer = 0;
  const grade = (v, good, poor) => v <= good ? 'good' : v >= poor ? 'poor' : 'mid';
  async function show() {
    let s;
    try { s = await rpc('perf.snapshot', {}, 8000); } catch (e) { clear(body).append(h('div', { class: 'dim', style: { padding: '12px' } }, 'Not available yet: ' + e.message)); return; }
    const v = s.vitals, out = [];
    const metric = (n, val, f, cls) => h('div', { class: 'kv' }, h('span', { class: 'k' }, n), h('span', { class: 'v ' + cls }, val ? f(val) : '\u2014'));
    out.push(h('div', { class: 'card' }, h('h4', null, 'Web vitals'),
      metric('First contentful paint', v.fcp, fmtMs, grade(v.fcp, 1800, 3000)), metric('Largest contentful paint', v.lcp, fmtMs, grade(v.lcp, 2500, 4000)),
      metric('Layout shift (CLS)', v.cls, (x) => x.toFixed(3), grade(v.cls, 0.1, 0.25)), metric('Blocking time (approx.)', v.tbt, fmtMs, grade(v.tbt, 200, 600)), metric('First input delay', v.fid, fmtMs, grade(v.fid, 100, 300)),
      h('div', { class: 'dim' }, 'Long tasks seen: ' + v.lt)));
    if (s.nav) {
      const n = s.nav, total = n.load || n.dcl || 1;
      const ph = [['Redirects / queue', n.ds - n.start], ['DNS', n.de - n.ds], ['Connect', n.ce - n.cs], ['Request + wait (TTFB)', n.rs - n.rq], ['Download', n.re - n.rs], ['DOM processing', n.dom - n.re], ['DOMContentLoaded', n.dcl - n.dom], ['Load event', n.load - n.dcl]].filter((p) => p[1] > 0);
      const card = h('div', { class: 'card' }, h('h4', null, 'Navigation  (' + (n.proto || '?') + ', ' + n.type + ')'));
      let acc = 0;
      ph.forEach((p) => { card.append(h('div', { style: { margin: '4px 0' } }, h('div', { style: { display: 'flex', justifyContent: 'space-between' } }, h('span', null, p[0]), h('span', { class: 'dim' }, fmtMs(p[1]))), h('div', { class: 'bar' }, h('i', { style: { left: (acc / total * 100) + '%', width: Math.max(0.8, p[1] / total * 100) + '%' } })))); acc += p[1]; });
      card.append(h('div', { class: 'dim' }, 'Total ' + fmtMs(total) + '  \u00B7  document ' + fmtBytes(n.size)));
      out.push(card);
    }
    const rows = NS.panels.network.rows(), byType = {};
    rows.forEach((r) => { const t = byType[r.type] = byType[r.type] || { n: 0, b: 0, t: 0 }; t.n++; t.b += Math.max(0, r.size || 0); t.t += r.time || 0; });
    const rc = h('div', { class: 'card' }, h('h4', null, 'Resources  (' + rows.length + ' requests)'));
    Object.keys(byType).sort((a, b) => byType[b].b - byType[a].b).forEach((t) => rc.append(h('div', { class: 'kv' }, h('span', { class: 'k' }, t), h('span', { class: 'v' }, byType[t].n + ' requests  \u00B7  ' + fmtBytes(byType[t].b)))));
    out.push(rc);
    const slow = rows.filter((r) => r.time).sort((a, b) => b.time - a.time).slice(0, 5);
    if (slow.length) out.push(h('div', { class: 'card' }, h('h4', null, 'Slowest requests'), slow.map((r) => h('div', { class: 'kv' }, h('span', { class: 'k', style: { flex: '0 0 22%' } }, fmtMs(r.time)), h('span', { class: 'v mono' }, baseName(r.url))))));
    const mc = h('div', { class: 'card' }, h('h4', null, 'Page'));
    mc.append(h('div', { class: 'kv' }, h('span', { class: 'k' }, 'DOM nodes'), h('span', { class: 'v' }, s.nodes)));
    if (s.mem) { mc.append(h('div', { class: 'kv' }, h('span', { class: 'k' }, 'JS heap'), h('span', { class: 'v' }, fmtBytes(s.mem.used) + ' of ' + fmtBytes(s.mem.total) + ' (limit ' + fmtBytes(s.mem.limit) + ')')), h('div', { class: 'bar' }, h('i', { style: { left: 0, width: (s.mem.used / s.mem.limit * 100) + '%' } }))); }
    out.push(mc);
    clear(body).append(h('div', { class: 'tb' }, h('button', { class: 'b', onclick: show }, 'Refresh'), h('span', { class: 'dim' }, 'Values update while this tab is open')), ...out);
  }
  NS.addPanel('performance', 'Performance', {
    build(el) { body = h('div', { class: 'grow' }); el.append(body); },
    show() { show(); clearInterval(timer); timer = setInterval(() => { if (NS.cur === 'performance') show(); }, 4000); },
    hide() { clearInterval(timer); },
    reset() { if (NS.cur === 'performance') show(); }
  });
})();

// ================================================================ Device
(function () {
  const UAS = [['This device (default)', ''],
    ['Chrome on Android (Pixel 8)', 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'],
    ['Safari on iPhone', 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'],
    ['Safari on iPad', 'Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'],
    ['Chrome on Linux', 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'],
    ['Chrome on Windows', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'],
    ['Firefox on Linux', 'Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0'],
    ['Safari on Mac', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'],
    ['Googlebot', 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'],
    ['curl', 'curl/8.7.1']];
  const SIZES = [['Fill the screen', 0, 0], ['iPhone SE  375\u00D7667', 375, 667], ['iPhone 14  390\u00D7844', 390, 844], ['Pixel 7  412\u00D7915', 412, 915], ['Galaxy S20  360\u00D7800', 360, 800],
    ['iPad Mini  768\u00D71024', 768, 1024], ['iPad Air  820\u00D71180', 820, 1180], ['iPad Pro  1024\u00D71366', 1024, 1366], ['Laptop  1366\u00D7768', 1366, 768], ['Desktop  1920\u00D71080', 1920, 1080]];
  const UA = (n) => UAS.find((u) => u[0] === n)[1];
  const DEVICES = [['iPhone SE', UA('Safari on iPhone'), 375, 667], ['iPhone 14', UA('Safari on iPhone'), 390, 844], ['Pixel 7', UA('Chrome on Android (Pixel 8)'), 412, 915],
    ['Galaxy S20', UA('Chrome on Android (Pixel 8)').replace('Pixel 8', 'SM-G981B'), 360, 800], ['iPad Mini', UA('Safari on iPad'), 768, 1024], ['iPad Air', UA('Safari on iPad'), 820, 1180],
    ['iPad Pro', UA('Safari on iPad'), 1024, 1366], ['Laptop', UA('Chrome on Windows'), 1366, 768], ['Desktop', UA('Chrome on Windows'), 1920, 1080]];
  let body, devSel, uaSel, uaIn, szSel, wIn, hIn, info;
  function sync() {
    const s = NS.settings;
    if (!uaSel) return;
    const di = DEVICES.findIndex((d) => d[1] === (s.ua || '') && d[2] === (s.vpW || 0) && d[3] === (s.vpH || 0));
    devSel.value = di >= 0 ? String(di) : (s.ua || s.vpW) ? 'custom' : 'none';
    const i = UAS.findIndex((u) => u[1] === (s.ua || ''));
    uaSel.value = i < 0 ? 'custom' : String(i);
    uaIn.value = s.ua || '';
    wIn.value = s.vpW || ''; hIn.value = s.vpH || '';
    const j = SIZES.findIndex((z) => z[1] === (s.vpW || 0) && z[2] === (s.vpH || 0));
    szSel.value = j < 0 ? 'custom' : String(j);
    clear(info).append(s.vpW ? 'Page area: ' + s.vpW + ' \u00D7 ' + s.vpH + ' CSS px' : 'Page area: full screen', '  \u00B7  ', s.ua ? 'custom user agent' : 'default user agent');
  }
  async function apply() {
    try {
      await native('ua', { ua: uaIn.value.trim() });
      await native('viewport', { w: parseInt(wIn.value, 10) || 0, h: parseInt(hIn.value, 10) || 0 });
      toast('Applied. The page is reloading.'); sync();
    } catch (e) { toast(e.message); }
  }
  NS.addPanel('device', 'Device', {
    build(el) {
      devSel = h('select', { class: 'in', style: { width: '100%' } }, h('option', { value: 'none' }, 'This device (no emulation)'), DEVICES.map((d, i) => h('option', { value: String(i) }, d[0] + '  ' + d[2] + '\u00D7' + d[3])), h('option', { value: 'custom' }, 'Custom (set below)'));
      devSel.addEventListener('change', () => {
        if (devSel.value === 'custom') return;
        const d = devSel.value === 'none' ? ['', '', 0, 0] : DEVICES[+devSel.value];
        uaIn.value = d[1]; wIn.value = d[2] || ''; hIn.value = d[3] || '';
        apply();
      });
      uaSel = h('select', { class: 'in', style: { width: '100%' } }, UAS.map((u, i) => h('option', { value: String(i) }, u[0])), h('option', { value: 'custom' }, 'Custom'));
      uaSel.addEventListener('change', () => { if (uaSel.value !== 'custom') { uaIn.value = UAS[+uaSel.value][1]; apply(); } });
      uaIn = h('textarea', { class: 'in', style: { minHeight: '60px', margin: '6px 0 0', width: '100%' }, spellcheck: 'false', placeholder: 'User agent string' });
      szSel = h('select', { class: 'in', style: { width: '100%' } }, SIZES.map((z, i) => h('option', { value: String(i) }, z[0])), h('option', { value: 'custom' }, 'Custom'));
      szSel.addEventListener('change', () => { if (szSel.value !== 'custom') { const z = SIZES[+szSel.value]; wIn.value = z[1] || ''; hIn.value = z[2] || ''; apply(); } });
      wIn = h('input', { class: 'in', type: 'text', inputmode: 'numeric', placeholder: 'Width', style: { width: '90px' } });
      hIn = h('input', { class: 'in', type: 'text', inputmode: 'numeric', placeholder: 'Height', style: { width: '90px' } });
      info = h('div', { class: 'dim', style: { margin: '6px 0' } });
      body = h('div', { class: 'grow' }, h('div', { class: 'card' }, h('h4', null, 'Device'), devSel, h('div', { class: 'dim', style: { marginTop: '6px' } }, 'Picks a matching user agent and screen size and reloads the page.')),
        h('div', { class: 'card' }, h('h4', null, 'User agent'), uaSel, uaIn),
        h('div', { class: 'card' }, h('h4', null, 'Screen size'), szSel, h('div', { style: { display: 'flex', gap: '8px', alignItems: 'center', margin: '8px 0' } }, wIn, '\u00D7', hIn,
          h('button', { class: 'b', onclick: () => { const a = wIn.value; wIn.value = hIn.value; hIn.value = a; apply(); } }, 'Rotate')),
          h('div', { class: 'dim' }, 'The page is laid out at this many CSS pixels and scaled to fit your screen.')),
        h('div', { class: 'card' }, info, h('div', { style: { display: 'flex', gap: '8px' } }, h('button', { class: 'b p', onclick: apply }, 'Apply and reload'),
          h('button', { class: 'b', onclick: () => { uaIn.value = ''; wIn.value = ''; hIn.value = ''; apply(); } }, 'Reset all'))));
      el.append(body);
    },
    show: sync, settings: sync
  });
})();
