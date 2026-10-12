'use strict';
/* Elements panel: live DOM tree, element picker, styles, computed values and box model. */
(function () {
  const VOID = /^(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)$/;
  const P = { nodes: new Map(), sel: null, selInfo: null, sub: 'styles', open: new Set(), results: [], ri: 0, picking: false };
  let tree, subBody, subBar, pickBtn, searchIn, countEl, pathEl;

  const isEl = (r) => r.info.nt === 1;
  function attrNode(rec, a) {
    const s = h('span', { class: 't-at' }, ' ', h('span', { class: 't-an' }, a[0]));
    if (a[1] !== '' || true) s.append('="', h('span', { class: 't-av' }, a[1]), '"');
    s.addEventListener('click', (e) => { if (P.sel === rec) { e.stopPropagation(); editAttr(rec, a[0], a[1]); } });
    return s;
  }
  function paint(rec) {
    const i = rec.info, row = clear(rec.row), close = clear(rec.close);
    const tw = h('span', { class: 'tw' }, i.hc ? (rec.open ? '\u25BC' : '\u25B6') : '');
    if (i.hc) tw.addEventListener('click', (e) => { e.stopPropagation(); toggle(rec); });
    // the toggle arrow and the markup are two columns, so long markup wraps inside its own column
    const nc = h('span', { class: 'nc' });
    row.append(tw, nc);
    if (i.nt === 1) {
      nc.append(h('span', { class: 't-tag' }, '<' + i.nm));
      i.at.forEach((a) => nc.append(attrNode(rec, a)));
      nc.append(h('span', { class: 't-tag' }, '>'));
      if (i.hc && !rec.open) nc.append(h('span', { class: 't-dt' }, '\u2026'), h('span', { class: 't-tag' }, '</' + i.nm + '>'));
      if (i.hc && rec.open && !VOID.test(i.nm)) close.append(h('span', { class: 'tw' }), h('span', { class: 'nc' }, h('span', { class: 't-tag' }, '</' + i.nm + '>')));
      close.hidden = !(i.hc && rec.open);
      if (i.sh) nc.append(h('span', { class: 't-dt' }, ' #shadow'));
    } else if (i.nt === 3) { nc.append(h('span', { class: 't-tx' }, JSON.stringify(i.tx)));
    } else if (i.nt === 8) { nc.append(h('span', { class: 't-cm' }, '<!--' + i.tx + '-->'));
    } else if (i.nt === 10) { nc.append(h('span', { class: 't-dt' }, i.tx));
    } else { nc.append(h('span', { class: 't-dt' }, i.nt === 11 ? '#shadow-root' : '#document')); }
  }
  function make(info) {
    const rec = { info, open: false, loaded: false };
    rec.box = h('div');
    rec.row = h('div', { class: 'nrow', onclick: () => select(rec) });
    rec.kids = h('div', { class: 'kids' });
    rec.close = h('div', { class: 'nrow', hidden: true });
    rec.box.append(rec.row, rec.kids, rec.close);
    P.nodes.set(info.id, rec);
    paint(rec);
    return rec;
  }
  async function loadKids(rec, off) {
    const r = await rpc('dom.children', { id: rec.info.id, off: off || 0 });
    if (!off) clear(rec.kids);
    else { const m = rec.kids.querySelector('.more'); if (m) m.remove(); }
    r.list.forEach((c) => rec.kids.append(make(c).box));
    if (r.more) rec.kids.append(h('div', { class: 'nrow more t-dt', onclick: () => loadKids(rec, (off || 0) + 300) }, '\u2026 ' + r.more + ' more'));
    rec.loaded = true;
  }
  async function toggle(rec) {
    if (rec.open) { rec.open = false; P.open.delete(rec.info.id); rec.kids.hidden = true; paint(rec); return; }
    rec.open = true;
    P.open.add(rec.info.id);
    rec.kids.hidden = false;
    try { if (!rec.loaded) await loadKids(rec); } catch (e) { toast(e.message); }
    paint(rec);
  }
  async function expandTo(rec) { if (!rec.open) await toggle(rec); }

  let loading = null;
  // Shown and a picked element can both ask for the root at once; share one in-flight load instead of racing.
  function loadRoot() {
    if (loading) return loading;
    loading = loadRootNow().finally(() => { loading = null; });
    return loading;
  }
  async function loadRootNow() {
    clear(tree);
    P.nodes.clear();
    P.sel = null;
    try {
      const list = await rpc('dom.root');
      let html = null;
      list.forEach((i) => { const r = make(i); tree.append(r.box); if (i.nm === 'html') html = r; });
      if (html) { await expandTo(html); const body = [...P.nodes.values()].find((r) => r.info.nm === 'body'); if (body) await expandTo(body); }
    } catch (e) { tree.append(h('div', { class: 'dim', style: { padding: '12px' } }, 'The page is not ready for inspection yet. Reload it with developer tools on. (' + e.message + ')')); }
  }
  async function refreshTree() {
    const keep = new Set(P.open), selId = P.sel && P.sel.info.id, top = tree.scrollTop;
    clear(tree);
    P.nodes.clear();
    P.open.clear();
    try {
      const list = await rpc('dom.root');
      list.forEach((i) => tree.append(make(i).box));
      for (let pass = 0; pass < 40; pass++) {
        let changed = false;
        for (const id of keep) { const r = P.nodes.get(id); if (r && !r.open && r.info.hc) { await toggle(r); changed = true; } }
        if (!changed) break;
      }
      const again = selId && P.nodes.get(selId);
      if (again) { P.sel = again; again.row.classList.add('sel'); }
      tree.scrollTop = top;
    } catch (e) {}
  }

  async function select(rec, scroll) {
    if (P.sel) P.sel.row.classList.remove('sel');
    P.sel = rec;
    rec.row.classList.add('sel');
    if (scroll) rec.row.scrollIntoView({ block: 'center' });
    try { P.selInfo = await rpc('dom.select', { id: rec.info.id }); } catch (e) { P.selInfo = null; toast(e.message); }
    clear(pathEl).append(P.selInfo && P.selInfo.sel ? P.selInfo.sel : '');
    renderSub();
  }
  async function reveal(nid) {
    try {
      const chain = await rpc('dom.path', { id: nid });
      if (!P.nodes.size || loading) await loadRoot();
      for (let i = 0; i < chain.length; i++) {
        const r = P.nodes.get(chain[i]);
        if (!r) continue;
        if (i < chain.length - 1) await expandTo(r); else await select(r, true);
      }
    } catch (e) { toast(e.message); }
  }

  // ---------------------------------------------------------------- editing
  async function editAttr(rec, name, value) {
    const v = await ask('Edit attribute', name + '="' + value + '"');
    if (v === null) return;
    const m = /^\s*([^\s=]+)\s*(?:=\s*(?:"([\s\S]*)"|'([\s\S]*)'|([\s\S]*)))?\s*$/.exec(v);
    try {
      if (!m) await rpc('dom.delAttr', { id: rec.info.id, name });
      else await rpc('dom.setAttr', { id: rec.info.id, old: name, name: m[1], value: m[2] !== undefined ? m[2] : m[3] !== undefined ? m[3] : m[4] || '' });
      await refreshInfo(rec);
    } catch (e) { toast(e.message); }
  }
  async function refreshInfo(rec) { rec.info = await rpc('dom.info', { id: rec.info.id }); paint(rec); if (rec === P.sel) select(rec); }
  async function nodeActions(btn) {
    const rec = P.sel;
    if (!rec) { toast('Select a node first'); return; }
    const id = rec.info.id, el = isEl(rec);
    const items = [];
    if (el) {
      items.push({ label: 'Add attribute', fn: async () => { const v = await ask('Add attribute', 'name="value"'); if (v) { const m = /^\s*([^\s=]+)\s*(?:=\s*"?([^"]*)"?)?$/.exec(v); if (m) { await rpc('dom.setAttr', { id, name: m[1], value: m[2] || '' }); refreshInfo(rec); } } } });
      items.push({ label: 'Edit as HTML', fn: async () => { const cur = await rpc('dom.outer', { id }); const v = await ask('Edit as HTML', cur, true); if (v !== null && v !== cur) { await rpc('dom.setOuter', { id, html: v }); refreshTree(); } } });
      items.push({ label: 'Hide / show', fn: () => rpc('dom.hide', { id }).catch((e) => toast(e.message)) });
      items.push({ label: 'Scroll into view', fn: () => rpc('dom.select', { id, scroll: true }) });
      items.push({ label: 'Copy outerHTML', fn: async () => copyText(await rpc('dom.outer', { id })) });
      items.push({ label: 'Copy selector', fn: () => copyText(P.selInfo ? P.selInfo.sel : '') });
      items.push({ label: 'Copy XPath', fn: () => copyText(P.selInfo ? P.selInfo.xpath : '') });
      items.push({ label: 'Use in console as $0', fn: () => { NS.show('console'); NS.panels.console.run('$0'); } });
    } else if (rec.info.nt === 3 || rec.info.nt === 8) {
      items.push({ label: 'Edit text', fn: async () => { const v = await ask('Edit text', rec.info.tx, true); if (v !== null) { await rpc('dom.setText', { id, text: v }); refreshInfo(rec); } } });
    }
    items.push('-');
    items.push({ label: 'Delete node', fn: async () => { if (await confirmBox('Delete this node from the page?')) { await rpc('dom.remove', { id }); P.sel = null; refreshTree(); } } });
    menuAt(btn, items);
  }

  // ---------------------------------------------------------------- details
  const hint = (t) => h('div', { class: 'dim', style: { padding: '14px' } }, t);
  async function renderSub() {
    for (const b of subBar.querySelectorAll('button[data-s]')) b.classList.toggle('on', b.dataset.s === P.sub);
    clear(subBody);
    const rec = P.sel;
    if (!rec || !isEl(rec)) { subBody.append(hint(rec ? 'Select an element to see its styles.' : 'Tap a node in the tree.')); return; }
    try {
      if (P.sub === 'styles') await renderStyles(rec);
      else if (P.sub === 'computed') await renderComputed(rec);
      else renderBox();
    } catch (e) { subBody.append(hint(e.message)); }
  }
  async function renderStyles(rec) {
    const id = rec.info.id, m = await rpc('css.matched', { id });
    const blocks = [{ title: 'element.style', inline: true, decls: m.inline }].concat(m.rules.map((r) => ({ title: r.sel, src: r.href, media: r.media, decls: r.d, ref: r.ref })));
    const seen = {};
    blocks.forEach((b) => b.decls.forEach((d) => {
      const prev = seen[d[0]];
      if (!prev) seen[d[0]] = d;
      else if (d[2] && !prev[2]) { prev.ov = true; seen[d[0]] = d; } else d.ov = true;
    }));
    const set = (b, name, value, imp) => b.inline ? rpc('css.setInline', { id, name, value, imp }) : rpc('css.setRule', { ref: b.ref, name, value, imp });
    const again = () => renderSub();
    blocks.forEach((b) => {
      const box = h('div', { class: 'rule' });
      box.append(b.src ? h('span', { class: 'src' }, baseName(b.src)) : null, h('span', { class: 'sel' }, b.title), b.media ? h('span', { class: 'dim' }, ' ' + b.media) : null, ' {');
      if (b.inline) box.append(h('button', { class: 'i', style: { float: 'right', padding: 0 }, onclick: async () => { const v = await ask('Add style', 'property: value'); if (v) { const k = v.indexOf(':'); if (k > 0) { await set(b, v.slice(0, k).trim(), v.slice(k + 1).replace(/;\s*$/, '').trim(), false); again(); } } } }, '+'));
      b.decls.forEach((d) => {
        const cb = h('input', { type: 'checkbox' });
        cb.checked = true;
        const line = h('div', { class: 'decl' + (d.ov ? ' ov' : '') });
        cb.addEventListener('change', async () => { try { await set(b, d[0], cb.checked ? d[1] : null, d[2]); line.classList.toggle('off', !cb.checked); } catch (e) { toast(e.message); cb.checked = !cb.checked; } });
        const name = h('span', { class: 'dn', onclick: async () => { const v = await ask('Rename property', d[0]); if (v && v !== d[0]) { try { await set(b, d[0], null, false); await set(b, v.trim(), d[1], d[2]); again(); } catch (e) { toast(e.message); } } } }, d[0]);
        const val = h('span', { class: 'dv', onclick: async () => { const v = await ask(d[0], d[1]); if (v !== null) { try { await set(b, d[0], v.replace(/\s*!important\s*$/i, ''), /!important\s*$/i.test(v) || d[2]); again(); } catch (e) { toast(e.message); } } } }, d[1] + (d[2] ? ' !important' : ''));
        line.append(cb, name, ':', val, ';');
        box.append(line);
      });
      box.append('}');
      subBody.append(box);
    });
    if (m.blocked) subBody.append(h('div', { class: 'dim', style: { padding: '8px' } }, m.blocked + ' stylesheet(s) from other origins cannot be read by the page, so their rules are not listed. Their effect still shows under Computed.'));
  }
  async function renderComputed(rec) {
    const all = await rpc('css.computed', { id: rec.info.id });
    const f = h('input', { type: 'text', placeholder: 'Filter', class: 'in', style: { margin: '6px 8px', width: 'calc(100% - 16px)' } });
    const box = h('div');
    const draw = () => { clear(box); const q = f.value.toLowerCase(); all.filter((p) => !q || p[0].includes(q) || p[1].toLowerCase().includes(q)).forEach((p) => box.append(h('div', { class: 'kv mono' }, h('span', { class: 'k' }, p[0]), h('span', { class: 'v' }, p[1])))); };
    f.addEventListener('input', draw);
    draw();
    subBody.append(f, box);
  }
  function renderBox() {
    const b = P.selInfo && P.selInfo.box;
    if (!b) { subBody.append(hint('No layout information.')); return; }
    const n = (v) => parseFloat(v) ? String(Math.round(parseFloat(v) * 10) / 10) : '-';
    const side = (cls, arr, inner) => h('div', { class: cls }, h('span', { class: 'l' }, { m: 'margin', bo: 'border', pd: 'padding' }[cls] || ''),
      h('b', { class: 't' }, n(arr[0])), h('b', { class: 'r' }, n(arr[1])), h('b', { class: 'btm' }, n(arr[2])), h('b', { class: 'lf' }, n(arr[3])), inner);
    const content = h('div', { class: 'ct' }, Math.round(b.w * 10) / 10 + ' \u00D7 ' + Math.round(b.h * 10) / 10);
    subBody.append(h('div', { class: 'bm' }, side('m', b.m, side('bo', b.b, side('pd', b.p, content)))),
      h('div', { class: 'kv mono' }, h('span', { class: 'k' }, 'display'), h('span', { class: 'v' }, b.display)),
      h('div', { class: 'kv mono' }, h('span', { class: 'k' }, 'position'), h('span', { class: 'v' }, b.position)),
      h('div', { class: 'kv mono' }, h('span', { class: 'k' }, 'offset'), h('span', { class: 'v' }, Math.round(b.x) + ', ' + Math.round(b.y))));
  }

  // ---------------------------------------------------------------- search and picker
  async function search() {
    const q = searchIn.value.trim();
    if (!q) { P.results = []; countEl.textContent = ''; return; }
    try { P.results = await rpc('dom.search', { q }); } catch (e) { toast(e.message); return; }
    P.ri = 0;
    countEl.textContent = P.results.length ? '1/' + P.results.length : '0';
    if (P.results.length) reveal(P.results[0]);
  }
  function step(d) {
    if (!P.results.length) return;
    P.ri = (P.ri + d + P.results.length) % P.results.length;
    countEl.textContent = (P.ri + 1) + '/' + P.results.length;
    reveal(P.results[P.ri]);
  }
  async function pick(on) {
    P.picking = on;
    pickBtn.classList.toggle('on', on);
    try { await rpc('dom.pick', { on }); } catch (e) { toast(e.message); P.picking = false; pickBtn.classList.remove('on'); }
    if (on) toast('Tap an element on the page');
  }

  const P2 = {
    build(el) {
      pickBtn = h('button', { class: 'i', title: 'Pick an element on the page', onclick: () => pick(!P.picking) }, '\u2316');
      searchIn = h('input', { type: 'text', placeholder: 'Find by text, selector or XPath' });
      searchIn.addEventListener('keydown', (e) => { if (e.key === 'Enter') search(); });
      countEl = h('span', { class: 'dim' });
      el.append(h('div', { class: 'tb' }, pickBtn, searchIn, countEl,
        h('button', { class: 'i', onclick: () => step(-1) }, '\u2191'), h('button', { class: 'i', onclick: () => step(1) }, '\u2193'),
        h('button', { class: 'i', title: 'Refresh', onclick: () => refreshTree() }, '\u21BB')));
      tree = h('div', { class: 'a' });
      subBar = h('div', { class: 'sub' }, ['styles', 'computed', 'layout'].map((s) => h('button', { 'data-s': s, onclick: () => { P.sub = s; renderSub(); } }, s[0].toUpperCase() + s.slice(1))),
        h('span', { style: { flex: 1 } }), h('button', { onclick: (e) => nodeActions(e.currentTarget) }, 'Actions \u22EF'));
      pathEl = h('div', { class: 'dim mono', style: { padding: '2px 8px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 'none' }, onclick: () => P.selInfo && copyText(P.selInfo.sel) });
      subBody = h('div', { class: 'grow' });
      el.append(h('div', { class: 'split' }, tree, h('div', { class: 'b' }, subBar, pathEl, subBody)));
    },
    async show() { if (!P.nodes.size) await loadRoot(); rpc('dom.watch', { on: true }).catch(() => {}); renderSub(); },
    hide() { rpc('dom.watch', { on: false }).catch(() => {}); rpc('dom.unhighlight').catch(() => {}); },
    reset() { clear(tree); P.nodes.clear(); P.open.clear(); P.sel = null; P.selInfo = null; clear(subBody); if (NS.cur === 'elements') loadRoot(); },
    reveal
  };
  NS.addPanel('elements', 'Elements', P2);
  let dcTimer = 0;
  NS.listen('domchange', () => { if (NS.cur !== 'elements') return; clearTimeout(dcTimer); dcTimer = setTimeout(refreshTree, 400); });
  NS.listen('picked', async (e) => { P.picking = false; pickBtn.classList.remove('on'); NS.show('elements'); if (loading) await loading; reveal(e.id); });
  NS.listen('life', (e) => { if (e.k === 'load' && e.top && NS.cur === 'elements') loadRoot(); });
})();
