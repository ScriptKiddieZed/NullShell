'use strict';
/* Shared helpers, the bridge to the app, and the tab strip. */
const NS = window.NS = { on: {}, panels: {}, order: [], settings: {}, tab: { id: 0, url: '', title: '' } };
const $ = (s, r) => (r || document).querySelector(s);
function h(tag, a, ...kids) {
  const e = document.createElement(tag);
  if (a) for (const k in a) {
    const v = a[k];
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'text') e.textContent = v;
    else if (v === true) e.setAttribute(k, '');
    else e.setAttribute(k, v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) e.append(c.nodeType ? c : String(c));
  return e;
}
const clear = (e) => { while (e.firstChild) e.removeChild(e.firstChild); return e; };
const fmtBytes = (n) => n == null || n < 0 ? '\u2014' : n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' kB' : (n / 1048576).toFixed(1) + ' MB';
const fmtMs = (n) => n == null || isNaN(n) ? '\u2014' : n < 1000 ? Math.round(n) + ' ms' : (n / 1000).toFixed(2) + ' s';
const fmtClock = (ts) => { const d = new Date(ts); return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0'); };
const baseName = (u) => { try { const x = new URL(u); return (x.pathname.split('/').filter(Boolean).pop() || x.host) + x.search; } catch (e) { return u; } };
const shellQ = (s) => "'" + String(s).replace(/'/g, "'\\''") + "'";

let toastTimer = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('on'), 2200);
}
const copyText = (s) => { native('copy', { text: String(s) }); toast('Copied'); };

// ---------------------------------------------------------------- bridge
const pend = new Map();
let rid = 0;
function rpc(method, args, timeout) {
  return new Promise((res, rej) => {
    const id = 'r' + (++rid);
    pend.set(id, { res, rej });
    try { NSDT.call(id, method, JSON.stringify(args || {})); } catch (e) { pend.delete(id); rej(e); return; }
    setTimeout(() => { if (pend.has(id)) { pend.delete(id); rej(new Error('Timed out. Is the page still loading?')); } }, timeout || 30000);
  });
}
const native = (m, a, t) => rpc('native.' + m, a, t);
NS.rpcResult = (id, ok, json) => {
  const p = pend.get(id);
  if (!p) return;
  pend.delete(id);
  if (!ok) { p.rej(new Error(json)); return; }
  try { p.res(json === '' || json == null ? null : JSON.parse(json)); } catch (e) { p.res(json); }
};
NS.listen = (type, fn) => { (NS.on[type] = NS.on[type] || []).push(fn); };
NS.push = (json) => {
  let arr;
  try { arr = JSON.parse(json); } catch (e) { return; }
  for (const ev of arr) {
    const hs = NS.on[ev.t];
    if (hs) for (const f of hs) { try { f(ev); } catch (e) { console.error('devtools', ev.t, e); } }
  }
};
NS.reset = (json) => {
  try { NS.tab = JSON.parse(json); } catch (e) {}
  for (const id of NS.order) { const p = NS.panels[id]; if (p.reset) { try { p.reset(); } catch (e) { console.error(e); } } }
};
NS.state = (json) => {
  try { Object.assign(NS.settings, JSON.parse(json)); } catch (e) {}
  if (NS.onSettings) NS.onSettings();
  for (const id of NS.order) { const p = NS.panels[id]; if (p.settings) p.settings(); }
};

// ---------------------------------------------------------------- dialogs
function modal(title, body, buttons) {
  const bg = h('div', { class: 'modalbg' });
  const close = () => bg.remove();
  const bar = h('div', { class: 'mf' }, buttons.map((b) => h('button', { class: 'b ' + (b.cls || ''), onclick: () => { if (b.fn) b.fn(); close(); } }, b.label)));
  bg.append(h('div', { class: 'modal' }, h('h3', null, title), h('div', { class: 'mb' }, body), bar));
  bg.addEventListener('click', (e) => { if (e.target === bg) close(); });
  $('#modalRoot').append(bg);
  return { close, el: bg };
}
function ask(title, value, multiline) {
  return new Promise((res) => {
    const f = multiline ? h('textarea', { rows: 8, spellcheck: 'false' }) : h('input', { type: 'text', spellcheck: 'false', autocapitalize: 'off' });
    f.value = value == null ? '' : value;
    const m = modal(title, f, [{ label: 'Cancel', fn: () => res(null) }, { label: 'OK', cls: 'p', fn: () => res(f.value) }]);
    setTimeout(() => f.focus(), 50);
    if (!multiline) f.addEventListener('keydown', (e) => { if (e.key === 'Enter') { res(f.value); m.close(); } });
  });
}
function confirmBox(msg) {
  return new Promise((res) => modal('Confirm', h('div', { style: { padding: '4px 0 8px' } }, msg), [{ label: 'Cancel', fn: () => res(false) }, { label: 'OK', cls: 'p', fn: () => res(true) }]));
}
function menuAt(anchor, items) {
  document.querySelectorAll('.menu').forEach((m) => m.remove());
  const m = h('div', { class: 'menu' });
  for (const it of items) {
    if (it === '-') { m.append(h('hr')); continue; }
    if (it.check !== undefined) {
      const cb = h('input', { type: 'checkbox' });
      cb.checked = !!it.check;
      cb.addEventListener('change', () => { it.fn(cb.checked); });
      m.append(h('label', null, cb, it.label));
    } else m.append(h('button', { onclick: () => { m.remove(); it.fn(); } }, it.label));
  }
  const parent = anchor.offsetParent || document.body;
  parent.style.position = 'relative';
  parent.append(m);
  const off = (e) => { if (!m.contains(e.target)) { m.remove(); document.removeEventListener('click', off, true); } };
  setTimeout(() => document.addEventListener('click', off, true), 0);
}

// ---------------------------------------------------------------- panels and tabs
NS.addPanel = (id, label, p) => {
  p.id = id;
  p.label = label;
  p.el = h('section', { class: 'panel', id: 'p-' + id });
  p.btn = h('button', { onclick: () => NS.show(id) }, label);
  NS.panels[id] = p;
  NS.order.push(id);
  $('#tabs').append(p.btn);
  $('#main').append(p.el);
  if (p.build) p.build(p.el);
};
NS.badge = (id, n, err) => {
  const p = NS.panels[id];
  if (!p) return;
  let b = p.btn.querySelector('.bd');
  if (!n) { if (b) b.remove(); return; }
  if (!b) { b = h('span', { class: 'bd' }); p.btn.append(b); }
  b.textContent = n > 999 ? '999+' : n;
  b.className = 'bd' + (err ? ' e' : '');
};
NS.show = (id) => {
  const prev = NS.cur;
  NS.cur = id;
  for (const k of NS.order) { const p = NS.panels[k]; p.el.classList.toggle('on', k === id); p.btn.classList.toggle('on', k === id); }
  const p = NS.panels[id];
  if (prev && prev !== id && NS.panels[prev] && NS.panels[prev].hide) NS.panels[prev].hide();
  if (p.show) p.show();
  try { localStorage.setItem('nsdt.tab', id); } catch (e) {}
};
NS.inspectNode = (nid) => { NS.show('elements'); NS.panels.elements.reveal(nid); };

// ---------------------------------------------------------------- syntax colouring
const KW = /^(?:break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|finally|for|from|function|if|import|in|instanceof|let|new|of|return|static|super|switch|this|throw|try|typeof|var|void|while|with|yield|async|await|null|true|false|undefined|NaN)$/;
function tokens(code, lang) {
  let re, cls;
  if (lang === 'json') { re = /("(?:\\.|[^"\\])*")(\s*:)?|(-?\d+\.?\d*(?:[eE][+-]?\d+)?)|\b(true|false|null)\b/g; cls = (m) => m[1] ? (m[2] ? 'k-p' : 'k-s') : m[3] ? 'k-n' : 'k-k'; }
  else if (lang === 'css') { re = /(\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(@[\w-]+)|(#[\da-fA-F]{3,8}\b|-?\d*\.?\d+(?:px|em|rem|%|vh|vw|s|ms|deg)?\b)|([\w-]+)(?=\s*:)/g; cls = (m) => m[1] ? 'k-c' : m[2] ? 'k-s' : m[3] ? 'k-k' : m[4] ? 'k-n' : 'k-p'; }
  else if (lang === 'html') { re = /(<!--[\s\S]*?-->)|(<\/?[\w:-]+)|("(?:[^"]*)"|'(?:[^']*)')|(\s[\w:@.-]+)(?==)|(\/?>)/g; cls = (m) => m[1] ? 'k-c' : m[2] ? 'k-t' : m[3] ? 'k-s' : m[4] ? 'k-a' : 'k-t'; }
  else { re = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)|(\b0x[\da-fA-F]+\b|\b\d[\d_]*\.?\d*(?:[eE][+-]?\d+)?n?\b)|([A-Za-z_$][\w$]*)/g; cls = (m) => m[1] ? 'k-c' : m[2] ? 'k-s' : m[3] ? 'k-n' : (KW.test(m[4]) ? 'k-k' : ''); }
  const out = [];
  let last = 0, m;
  re.lastIndex = 0;
  while ((m = re.exec(code))) {
    if (m[0] === '') { re.lastIndex++; continue; }
    if (m.index > last) out.push(['', code.slice(last, m.index)]);
    out.push([cls(m), m[0]]);
    last = m.index + m[0].length;
  }
  if (last < code.length) out.push(['', code.slice(last)]);
  return out;
}
/** Colours code and splits it into one fragment per line. */
function hlLines(code, lang) {
  const lines = [document.createDocumentFragment()];
  const toks = code.length > 400000 ? [['', code]] : tokens(code, lang);
  for (const [c, t] of toks) {
    const parts = t.split('\n');
    parts.forEach((p, i) => {
      if (i > 0) lines.push(document.createDocumentFragment());
      if (p) lines[lines.length - 1].append(c ? h('span', { class: c }, p) : document.createTextNode(p));
    });
  }
  return lines;
}
function langOf(mime, url) {
  const m = (mime || '').toLowerCase(), u = (url || '').split('?')[0].toLowerCase();
  if (/json/.test(m) || /\.(json|map|webmanifest)$/.test(u)) return 'json';
  if (/css/.test(m) || /\.css$/.test(u)) return 'css';
  if (/html|xml|svg/.test(m) || /\.(html?|xml|svg)$/.test(u)) return 'html';
  return 'js';
}
/** Approximate pretty printer; good enough to make minified code readable. */
function pretty(code, lang) {
  if (lang === 'json') { try { return JSON.stringify(JSON.parse(code), null, 2); } catch (e) { /* fall through */ } }
  if (lang === 'html') {
    const VOID = /^(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)$/i;
    let depth = 0, out = '';
    for (const part of code.replace(/>\s*</g, '>\n<').split('\n')) {
      const t = part.trim();
      if (!t) continue;
      const close = /^<\//.test(t);
      if (close) depth = Math.max(0, depth - 1);
      out += '  '.repeat(depth) + t + '\n';
      const open = /^<([\w:-]+)(\s[^>]*)?>$/.exec(t);
      if (!close && open && !VOID.test(open[1]) && !/\/>$/.test(t) && !/<\/[\w:-]+>$/.test(t)) depth++;
    }
    return out;
  }
  let out = '', depth = 0, i = 0, q = '', paren = 0;
  const nl = () => { out = out.replace(/[ \t]+$/, '') + '\n' + '  '.repeat(depth); };
  while (i < code.length) {
    const c = code[i], two = code.substr(i, 2);
    if (q) { out += c; if (c === '\\') { out += code[++i] || ''; } else if (c === q) q = ''; i++; continue; }
    if (c === '"' || c === "'" || c === '`') { q = c; out += c; i++; continue; }
    if (two === '//') { const e = code.indexOf('\n', i); const end = e < 0 ? code.length : e; out += code.slice(i, end); nl(); i = end + 1; continue; }
    if (two === '/*') { const e = code.indexOf('*/', i + 2); const end = e < 0 ? code.length : e + 2; out += code.slice(i, end); i = end; continue; }
    if (c === '(') paren++;
    if (c === ')') paren = Math.max(0, paren - 1);
    if (c === '{') { out += (lang === 'css' ? ' {' : ' {'); depth++; nl(); i++; while (/\s/.test(code[i] || '')) i++; continue; }
    if (c === '}') { depth = Math.max(0, depth - 1); nl(); out += '}'; i++; if (code[i] !== ';' && code[i] !== ',' && code[i] !== ')') { nl(); } while (/\s/.test(code[i] || '')) i++; continue; }
    if (c === ';' && paren === 0) { out += ';'; nl(); i++; while (/\s/.test(code[i] || '')) i++; continue; }
    if (c === '\n' || c === '\r') { i++; continue; }
    out += c;
    i++;
  }
  return out.replace(/\n\s*\n+/g, '\n').trim() + '\n';
}
