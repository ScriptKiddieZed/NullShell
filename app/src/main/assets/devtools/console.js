'use strict';
/* Console panel: log output with expandable objects, filters and a REPL. */
(function () {
  const P = { depth: 0, hist: [], hi: 0, errs: 0, warns: 0, total: 0, last: null };
  let list, lvl, txt, input, sugg;

  function kindClass(k) { return 'v-' + k; }
  function propRow(r) {
    const row = h('div', { class: 'tr' });
    const head = h('div', { class: 'r' });
    const val = h('span', { class: r.k === 'g' ? 'dim' : kindClass(r.k) }, r.p);
    const tw = h('span', { class: 'tw' }, r.id ? '\u25B6' : '');
    head.append(tw, h('span', { class: 'pn' + (r.n.startsWith('[[') || r.n === '__proto__' ? ' g' : '') }, r.n), ': ', val);
    row.append(head);
    if (r.id) head.addEventListener('click', () => expand(tw, row, r.id));
    return row;
  }
  async function expand(tw, row, id, extra) {
    let kids = row.querySelector(':scope > .kids');
    if (kids) { kids.hidden = !kids.hidden; tw.textContent = kids.hidden ? '\u25B6' : '\u25BC'; return; }
    tw.textContent = '\u25BC';
    kids = h('div', { class: 'kids' });
    row.append(kids);
    if (extra) kids.append(extra);
    try { (await rpc('obj.props', { id })).forEach((r) => kids.append(propRow(r))); } catch (e) { kids.append(h('div', { class: 'err' }, String(e.message))); }
  }
  function arg(d) {
    switch (d.k) {
      case 's': return h('span', null, d.v);
      case 'n': case 'b': case 'u': case 'null': case 'y': return h('span', { class: kindClass(d.k) }, d.v);
      default: {
        const wrap = h('span', { class: 'obj' });
        const tw = h('span', { class: 'tw' }, '\u25B6');
        const pv = h('span', { class: d.k === 'el' ? 't-tag' : kindClass(d.k) }, d.p);
        wrap.append(tw, pv);
        if (d.k === 'el') pv.addEventListener('click', (e) => { e.stopPropagation(); NS.inspectNode(d.nid); });
        const holder = h('span');
        wrap.append(holder);
        tw.addEventListener('click', () => expand(tw, holder, d.id, d.k === 'e' && d.stack ? h('pre', { class: 'dim' }, d.stack) : null));
        pv.addEventListener('click', (e) => { if (d.k !== 'el') tw.click(); });
        return wrap;
      }
    }
  }
  function visible(r) {
    const l = lvl.value, f = txt.value.trim().toLowerCase();
    if (l !== 'all' && r.lvl !== l && r.lvl !== 'in' && r.lvl !== 'res') return false;
    return !f || r.text.toLowerCase().includes(f);
  }
  function addRow(ev) {
    if (ev.g === -1) { P.depth = Math.max(0, P.depth - 1); return; }
    const lv = ev.l === 'warning' ? 'warn' : ev.l;
    const text = ev.a.map((a) => a.v !== undefined ? a.v : a.p).join(' ');
    const key = lv + '|' + text + '|' + (ev.file || '');
    if (P.last && P.last.key === key && !ev.stack && !ev.g && P.last.depth === P.depth) {
      P.last.n++;
      let b = P.last.el.querySelector('.n');
      if (!b) { b = h('span', { class: 'n' }); P.last.el.prepend(b); }
      b.textContent = P.last.n;
      count(lv);
      return;
    }
    const icon = lv === 'error' ? '\u2716' : lv === 'warn' ? '\u26A0' : lv === 'info' ? '\u24D8' : lv === 'in' ? '\u203A' : lv === 'res' ? '\u2190' : '';
    const body = h('div', { class: 'bd mono' });
    ev.a.forEach((a, i) => { if (i) body.append(' '); body.append(arg(a)); });
    if (ev.state) body.prepend(h('span', { class: 'dim' }, ev.state + ': '));
    if (ev.stack) {
      const st = h('pre', { class: 'dim', hidden: true }, ev.stack);
      const t = h('span', { class: 'dim', style: { marginLeft: '6px', textDecoration: 'underline' }, onclick: () => { st.hidden = !st.hidden; } }, 'stack');
      body.append(t, st);
    }
    const loc = ev.file || ev.src ? h('span', { class: 'loc' }, ev.file || ev.src) : null;
    const el = h('div', { class: 'cl ' + lv + (lv === 'res' ? ' res' : '') + (lv === 'in' ? ' in' : '') , style: { paddingLeft: (6 + P.depth * 12) + 'px' } }, h('span', { class: 'ic' }, icon), body, loc);
    const row = { el, lvl: lv, text, key, n: 1, depth: P.depth };
    if (!visible(row)) el.hidden = true;
    list.append(el);
    while (list.childElementCount > 2500) list.firstChild.remove();
    P.last = ev.g ? null : row;
    if (ev.g === 1) P.depth++;
    count(lv);
    if (NS.cur === 'console' && list.scrollHeight - list.scrollTop - list.clientHeight < 140) list.scrollTop = list.scrollHeight;
  }
  function count(lv) {
    if (lv === 'error') P.errs++; else if (lv === 'warn') P.warns++;
    NS.badge('console', P.errs || P.warns, P.errs > 0);
  }
  function refilter() {
    for (const el of list.children) {
      const cls = el.className.split(' ')[1];
      const text = el.textContent;
      const l = lvl.value, f = txt.value.trim().toLowerCase();
      el.hidden = !((l === 'all' || cls === l || cls === 'res' || cls === 'in') && (!f || text.toLowerCase().includes(f)));
    }
  }
  function clearAll() { clear(list); P.depth = 0; P.errs = P.warns = 0; P.last = null; NS.badge('console', 0); rpc('obj.release').catch(() => {}); }

  async function run(code) {
    if (!code.trim()) return;
    P.hist.push(code);
    P.hi = P.hist.length;
    P.last = null;
    addRow({ l: 'in', a: [{ k: 's', v: code }] });
    try {
      const r = await rpc('eval', { code }, 15000);
      if (r.r) addRow({ l: 'res', a: [r.r], state: r.s ? 'Promise ' + r.s : '' });
      else addRow({ l: 'res', a: [{ k: 'o', p: r.p || 'Promise {<pending>}', c: 'Promise' }] });
    } catch (e) {
      addRow({ l: 'error', a: [{ k: 's', v: String(e.message) }] });
    }
  }
  let suggTimer = 0;
  function suggest() {
    clearTimeout(suggTimer);
    suggTimer = setTimeout(async () => {
      const expr = input.value.slice(0, input.selectionStart);
      clear(sugg);
      if (!/[\w$.]$/.test(expr)) return;
      try {
        const res = await rpc('eval.complete', { expr }, 3000);
        res.slice(0, 20).forEach((s) => sugg.append(h('button', { class: 'chip mono', onclick: () => { input.value = s + input.value.slice(input.selectionStart); input.focus(); suggest(); } }, s.split('.').pop() || s)));
      } catch (e) {}
    }, 150);
  }

  const P2 = {
    build(el) {
      lvl = h('select', null, ['all', 'error', 'warn', 'info', 'log', 'debug'].map((v) => h('option', { value: v }, v === 'all' ? 'All levels' : v)));
      txt = h('input', { type: 'text', placeholder: 'Filter' });
      const keep = h('input', { type: 'checkbox' });
      keep.addEventListener('change', () => native('setting', { key: 'preserve', value: keep.checked }));
      P2.keep = keep;
      el.append(h('div', { class: 'tb' }, h('button', { class: 'i', title: 'Clear', onclick: clearAll }, '\u2205'), lvl, txt, h('label', { class: 'ck' }, keep, 'Preserve')));
      list = h('div', { class: 'grow' });
      sugg = h('div', { id: 'sugg' });
      input = h('textarea', { rows: 1, placeholder: 'Run JavaScript', spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off' });
      el.append(list, sugg, h('div', { id: 'cin' }, h('span', { class: 'pr' }, '\u203A'), input));
      lvl.addEventListener('change', refilter);
      txt.addEventListener('input', refilter);
      input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = Math.min(90, input.scrollHeight) + 'px'; suggest(); });
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); const c = input.value; input.value = ''; input.style.height = 'auto'; clear(sugg); run(c); }
        else if (e.key === 'ArrowUp' && !input.value.includes('\n') && P.hi > 0) { e.preventDefault(); input.value = P.hist[--P.hi]; }
        else if (e.key === 'ArrowDown' && !input.value.includes('\n') && P.hi < P.hist.length) { e.preventDefault(); input.value = ++P.hi < P.hist.length ? P.hist[P.hi] : ''; }
      });
    },
    show() { list.scrollTop = list.scrollHeight; },
    reset() { clearAll(); },
    settings() { if (P2.keep) P2.keep.checked = !!NS.settings.preserve; },
    run
  };
  NS.addPanel('console', 'Console', P2);
  NS.listen('con', addRow);
  NS.listen('ncon', (e) => addRow({ l: e.l, a: [{ k: 's', v: e.text }], file: e.src ? baseName(e.src) + (e.line ? ':' + e.line : '') : '' }));
  NS.listen('conclear', () => { if (!NS.settings.preserve) clearAll(); });
  NS.listen('nav', () => { if (!NS.settings.preserve) clearAll(); });
})();
