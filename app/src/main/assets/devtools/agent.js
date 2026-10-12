/* NullShell DevTools page agent. Injected at document start into every page while developer tools are on.
   It reports console output, errors, network calls and timing to the app, and answers inspection requests (DOM, CSS, storage). */
(function () {
  'use strict';
  if (window.__nsdt_agent) return;
  var TOP = window === window.top;
  var PFX = Math.random().toString(36).slice(2, 6);
  var seq = 0;
  var nid = function () { return PFX + (++seq); };

  function post(o) {
    try {
      var s = JSON.stringify(o);
      if (window.__nsdt && window.__nsdt.postMessage) window.__nsdt.postMessage(s);
      else if (window.__nsdtj) window.__nsdtj.post(s);
    } catch (e) { /* the page may have replaced JSON; nothing to do */ }
  }
  var abs = function (u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } };
  var clip = function (s, n) { s = String(s); return s.length > n ? s.slice(0, n) + '\u2026' : s; };

  // ------------------------------------------------------------------ object registry and serialisation
  var objs = new Map(), objSeq = 0;
  function reg(o) {
    var id = 'o' + (++objSeq);
    objs.set(id, o);
    if (objs.size > 4000) objs.delete(objs.keys().next().value);
    return id;
  }
  function cls(v) {
    try { return (v && v.constructor && v.constructor.name) || Object.prototype.toString.call(v).slice(8, -1); } catch (e) { return 'Object'; }
  }
  function short(v, depth) {            // one-line preview used inside other previews
    switch (typeof v) {
      case 'string': return JSON.stringify(clip(v, 60));
      case 'number': case 'boolean': case 'undefined': return String(v);
      case 'bigint': return v + 'n';
      case 'symbol': return v.toString();
      case 'function': return '\u0192 ' + (v.name || '(anonymous)') + '()';
    }
    if (v === null) return 'null';
    try {
      if (v instanceof Element) return '<' + v.localName + (v.id ? '#' + v.id : '') + (v.classList && v.classList.length ? '.' + Array.prototype.join.call(v.classList, '.') : '') + '>';
      if (v instanceof Node) return v.nodeName;
      if (v instanceof Error) return clip(v.name + ': ' + v.message, 80);
      if (v instanceof Date) return isNaN(v) ? 'Invalid Date' : v.toISOString();
      if (v instanceof RegExp) return String(v);
      if (Array.isArray(v)) return depth > 0 ? 'Array(' + v.length + ')' : 'Array(' + v.length + ') [' + Array.prototype.slice.call(v, 0, 6).map(function (x) { return short(x, 1); }).join(', ') + (v.length > 6 ? ', \u2026' : '') + ']';
      if (v instanceof Map) return 'Map(' + v.size + ')';
      if (v instanceof Set) return 'Set(' + v.size + ')';
      if (typeof Promise !== 'undefined' && v instanceof Promise) return 'Promise';
      var name = cls(v);
      if (depth > 0) return name === 'Object' ? '{\u2026}' : name;
      var keys = Object.keys(v).slice(0, 5);
      var body = keys.map(function (k) { var x; try { x = short(v[k], 1); } catch (e) { x = '(unreadable)'; } return k + ': ' + x; }).join(', ');
      return (name === 'Object' ? '' : name + ' ') + '{' + body + (Object.keys(v).length > 5 ? ', \u2026' : '') + '}';
    } catch (e) { return '(unreadable)'; }
  }
  function ser(v, top) {
    var t = typeof v;
    if (t === 'string') return { k: 's', v: clip(v, top ? 20000 : 200) };
    if (t === 'number' || t === 'boolean' || t === 'undefined' || v === null) return { k: v === null ? 'null' : t === 'undefined' ? 'u' : t === 'number' ? 'n' : 'b', v: String(v) };
    if (t === 'bigint') return { k: 'n', v: v + 'n' };
    if (t === 'symbol') return { k: 'y', v: v.toString() };
    var d = { k: t === 'function' ? 'f' : 'o', p: short(v, 0), c: cls(v) };
    try {
      if (v instanceof Error) { d.k = 'e'; d.stack = clip(v.stack || '', 4000); }
      else if (v instanceof Element) { d.k = 'el'; d.nid = nodeId(v); }
    } catch (e) {}
    d.id = reg(v);
    return d;
  }
  function props(id) {
    var o = objs.get(id);
    if (o === undefined) return [];
    var out = [], names = [];
    try { names = Object.getOwnPropertyNames(o); } catch (e) {}
    if (Array.isArray(o)) names = names.filter(function (n) { return n !== 'length'; }).slice(0, 200).concat(['length']);
    else names = names.slice(0, 300);
    names.forEach(function (n) {
      var row = { n: n };
      try {
        var desc = Object.getOwnPropertyDescriptor(o, n);
        if (desc && (desc.get || desc.set)) { row.g = 1; row.k = 'g'; row.p = (desc.get ? '(...)' : '') + (desc.set ? ' set' : ''); }
        else { var s = ser(o[n], false); row.k = s.k; row.p = s.p !== undefined ? s.p : s.v; row.id = s.id; if (s.k === 's') row.p = JSON.stringify(s.v); }
      } catch (e) { row.k = 'x'; row.p = '(' + e.message + ')'; }
      out.push(row);
    });
    try { if (o instanceof Map) { var i = 0; o.forEach(function (val, key) { if (i++ < 100) out.push({ n: '[[Map ' + short(key, 1) + ']]', k: 'o', p: short(val, 1), id: typeof val === 'object' && val ? reg(val) : undefined }); }); }
          if (o instanceof Set) { var j = 0; o.forEach(function (val) { if (j++ < 100) out.push({ n: '[[Set ' + j + ']]', k: 'o', p: short(val, 1), id: typeof val === 'object' && val ? reg(val) : undefined }); }); } } catch (e) {}
    var proto = null;
    try { proto = Object.getPrototypeOf(o); } catch (e) {}
    if (proto) out.push({ n: '[[Prototype]]', k: 'o', p: cls(proto) === 'Object' ? 'Object' : cls(proto), id: reg(proto) });
    return out;
  }

  // ------------------------------------------------------------------ console
  var origConsole = {};
  function stackNow() {
    try { return String(new Error().stack || '').split('\n').slice(3).join('\n'); } catch (e) { return ''; }
  }
  function format(args) {
    var a = Array.prototype.slice.call(args);
    if (typeof a[0] === 'string' && a[0].indexOf('%') >= 0 && a.length > 1) {
      var rest = a.slice(1), out = '';
      var f = a[0].replace(/%([sdifoOc%])/g, function (m, c) {
        if (c === '%') return '%';
        if (!rest.length) return m;
        var x = rest.shift();
        if (c === 'c') return '';
        if (c === 's') return typeof x === 'string' ? x : short(x, 1);
        if (c === 'd' || c === 'i') return typeof x === 'number' ? String(c === 'i' ? Math.trunc(x) : x) : 'NaN';
        if (c === 'f') return String(parseFloat(x));
        return short(x, 0);
      });
      a = [f].concat(rest);
    }
    return a.map(function (x) { return ser(x, true); });
  }
  function emit(level, args, extra) {
    var o = { t: 'con', l: level, a: format(args), ts: Date.now() };
    if (extra) for (var k in extra) o[k] = extra[k];
    post(o);
  }
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (l) {
    var orig = console[l];
    origConsole[l] = orig;
    console[l] = function () {
      try { emit(l, arguments, l === 'error' ? { stack: stackNow() } : null); } catch (e) {}
      return orig.apply(this, arguments);
    };
  });
  var wrap = function (name, fn) {
    var orig = console[name];
    console[name] = function () { try { fn.apply(null, arguments); } catch (e) {} return orig && orig.apply(this, arguments); };
  };
  wrap('trace', function () { emit('log', arguments.length ? arguments : ['console.trace'], { stack: stackNow() }); });
  wrap('assert', function (c) { if (!c) emit('error', ['Assertion failed:'].concat(Array.prototype.slice.call(arguments, 1)), { stack: stackNow() }); });
  wrap('dir', function (o) { emit('log', [o]); });
  wrap('table', function (d) { emit('log', [d]); });
  wrap('clear', function () { post({ t: 'conclear' }); });
  wrap('group', function () { emit('log', arguments.length ? arguments : ['console.group'], { g: 1 }); });
  wrap('groupCollapsed', function () { emit('log', arguments.length ? arguments : ['console.group'], { g: 1 }); });
  wrap('groupEnd', function () { post({ t: 'con', l: 'log', a: [], g: -1, ts: Date.now() }); });
  var counts = {}, timers = {};
  wrap('count', function (l) { l = l === undefined ? 'default' : String(l); counts[l] = (counts[l] || 0) + 1; emit('log', [l + ': ' + counts[l]]); });
  wrap('countReset', function (l) { counts[l === undefined ? 'default' : String(l)] = 0; });
  wrap('time', function (l) { timers[l === undefined ? 'default' : String(l)] = performance.now(); });
  wrap('timeEnd', function (l) { l = l === undefined ? 'default' : String(l); if (timers[l] !== undefined) { emit('log', [l + ': ' + (performance.now() - timers[l]).toFixed(3) + ' ms']); delete timers[l]; } });
  wrap('timeLog', function (l) { l = l === undefined ? 'default' : String(l); if (timers[l] !== undefined) emit('log', [l + ': ' + (performance.now() - timers[l]).toFixed(3) + ' ms']); });

  window.addEventListener('error', function (e) {
    try {
      if (e.target && e.target !== window && e.target.nodeType === 1) {
        var u = e.target.src || e.target.href || e.target.currentSrc || '';
        post({ t: 'con', l: 'error', a: [{ k: 's', v: 'Failed to load resource: ' + u }], src: 'resource', ts: Date.now() });
        return;
      }
      post({ t: 'con', l: 'error', a: [{ k: 's', v: 'Uncaught ' + (e.error && e.error.name ? e.error.name + ': ' : '') + e.message }], file: (e.filename || '') + ':' + e.lineno + ':' + e.colno,
             stack: e.error && e.error.stack ? clip(e.error.stack, 4000) : '', ts: Date.now() });
    } catch (x) {}
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    try {
      var r = e.reason;
      post({ t: 'con', l: 'error', a: [{ k: 's', v: 'Uncaught (in promise) ' }, ser(r, true)], stack: r && r.stack ? clip(r.stack, 4000) : '', ts: Date.now() });
    } catch (x) {}
  });
  document.addEventListener('securitypolicyviolation', function (e) {
    post({ t: 'con', l: 'error', a: [{ k: 's', v: 'Content Security Policy blocked ' + e.blockedURI + ' (' + e.violatedDirective + ')' }], src: 'csp', ts: Date.now() });
  });

  // ------------------------------------------------------------------ network
  var bodies = new Map();
  function keepBody(id, text, size) {
    bodies.set(id, { text: text, size: size });
    if (bodies.size > 60) bodies.delete(bodies.keys().next().value);
  }
  function hdrList(h) {
    var out = [];
    try {
      if (!h) return out;
      if (typeof Headers !== 'undefined' && h instanceof Headers) h.forEach(function (v, k) { out.push([k, v]); });
      else if (Array.isArray(h)) h.forEach(function (p) { out.push([String(p[0]), String(p[1])]); });
      else Object.keys(h).forEach(function (k) { out.push([k, String(h[k])]); });
    } catch (e) {}
    return out;
  }
  function bodyInfo(b) {
    try {
      if (b == null) return null;
      if (typeof b === 'string') return { text: clip(b, 20000), size: b.length };
      if (b instanceof URLSearchParams) return { text: b.toString(), size: b.toString().length };
      if (typeof FormData !== 'undefined' && b instanceof FormData) { var parts = []; b.forEach(function (v, k) { parts.push(k + '=' + (typeof v === 'string' ? clip(v, 200) : '[file ' + (v.name || '') + ']')); }); return { text: parts.join('&'), size: -1, form: 1 }; }
      if (typeof Blob !== 'undefined' && b instanceof Blob) return { text: '[Blob ' + b.size + ' bytes ' + b.type + ']', size: b.size };
      if (b instanceof ArrayBuffer || ArrayBuffer.isView(b)) return { text: '[binary ' + b.byteLength + ' bytes]', size: b.byteLength };
    } catch (e) {}
    return { text: String(b), size: -1 };
  }
  var TEXTUAL = /(text\/|json|xml|javascript|ecmascript|html|x-www-form-urlencoded|svg|graphql)/i;
  function recordBody(id, text, size) {
    keepBody(id, text, size);
    post({ t: 'net', ph: 'body', id: id, size: size, prev: clip(text, 1500) });
  }
  var origFetch = window.fetch;
  if (typeof origFetch === 'function') {
    window.fetch = function (input, init) {
      var id = nid(), method = 'GET', url = '', headers = [], body = null;
      try {
        var isReq = typeof Request !== 'undefined' && input instanceof Request;
        url = abs(isReq ? input.url : String(input));
        method = String((init && init.method) || (isReq && input.method) || 'GET').toUpperCase();
        headers = hdrList((init && init.headers) || (isReq ? input.headers : null));
        body = bodyInfo(init && init.body);
      } catch (e) {}
      var start = performance.now();
      post({ t: 'net', ph: 'start', id: id, kind: 'fetch', method: method, url: url, rh: headers, body: body, ts: Date.now(), top: TOP ? 1 : 0 });
      var p = origFetch.apply(this, arguments);
      p.then(function (res) {
        try {
          post({ t: 'net', ph: 'end', id: id, status: res.status, st: res.statusText, h: hdrList(res.headers), rt: res.type, redir: res.redirected, fu: res.url, dur: performance.now() - start });
          var ct = res.headers.get('content-type') || '', cl = +res.headers.get('content-length') || 0;
          if (/event-stream/i.test(ct) || cl > 3e6 || !res.body) return;
          var c = res.clone();
          if (TEXTUAL.test(ct) || !ct) c.text().then(function (txt) { recordBody(id, txt.length > 1500000 ? txt.slice(0, 1500000) : txt, txt.length); }, function () {});
          else c.blob().then(function (bl) { post({ t: 'net', ph: 'body', id: id, size: bl.size, prev: '' }); }, function () {});
        } catch (e) {}
      }, function (err) { post({ t: 'net', ph: 'fail', id: id, e: String(err), dur: performance.now() - start }); });
      return p;
    };
  }
  var xhrs = new WeakMap();
  if (window.XMLHttpRequest) {
    var XP = XMLHttpRequest.prototype, xOpen = XP.open, xSend = XP.send, xSet = XP.setRequestHeader;
    XP.open = function (m, u) { try { xhrs.set(this, { id: nid(), method: String(m).toUpperCase(), url: abs(String(u)), h: [] }); } catch (e) {} return xOpen.apply(this, arguments); };
    XP.setRequestHeader = function (k, v) { try { var n = xhrs.get(this); if (n) n.h.push([k, String(v)]); } catch (e) {} return xSet.apply(this, arguments); };
    XP.send = function (b) {
      var n = xhrs.get(this), x = this;
      if (n) {
        n.start = performance.now();
        post({ t: 'net', ph: 'start', id: n.id, kind: 'xhr', method: n.method, url: n.url, rh: n.h, body: bodyInfo(b), ts: Date.now(), top: TOP ? 1 : 0 });
        x.addEventListener('loadend', function () {
          try {
            var hs = [];
            String(x.getAllResponseHeaders() || '').trim().split(/\r?\n/).forEach(function (line) { var i = line.indexOf(':'); if (i > 0) hs.push([line.slice(0, i).trim(), line.slice(i + 1).trim()]); });
            if (x.status === 0) { post({ t: 'net', ph: 'fail', id: n.id, e: 'Request failed (network error, CORS or blocked)', dur: performance.now() - n.start }); return; }
            post({ t: 'net', ph: 'end', id: n.id, status: x.status, st: x.statusText, h: hs, fu: x.responseURL, dur: performance.now() - n.start });
            if (x.responseType === '' || x.responseType === 'text') recordBody(n.id, (x.responseText || '').slice(0, 1500000), (x.responseText || '').length);
            else if (x.responseType === 'json') { var js = JSON.stringify(x.response); recordBody(n.id, js || '', js ? js.length : 0); }
          } catch (e) {}
        });
      }
      return xSend.apply(this, arguments);
    };
  }
  if (window.WebSocket) {
    var NativeWS = window.WebSocket;
    var wsd = function (d) { return typeof d === 'string' ? clip(d, 4000) : (d && d.byteLength !== undefined ? '[binary ' + d.byteLength + ' bytes]' : (typeof Blob !== 'undefined' && d instanceof Blob ? '[blob ' + d.size + ' bytes]' : String(d))); };
    var WS = function (url, protocols) {
      var ws = protocols === undefined ? new NativeWS(url) : new NativeWS(url, protocols);
      var id = nid();
      post({ t: 'net', ph: 'start', id: id, kind: 'ws', method: 'GET', url: abs(String(url)), rh: [], ts: Date.now(), top: TOP ? 1 : 0 });
      ws.addEventListener('open', function () { post({ t: 'net', ph: 'end', id: id, status: 101, st: 'Switching Protocols', h: [], ws: 1 }); });
      ws.addEventListener('message', function (e) { post({ t: 'ws', id: id, dir: 'in', d: wsd(e.data), ts: Date.now() }); });
      ws.addEventListener('error', function () { post({ t: 'ws', id: id, dir: 'err', d: 'error', ts: Date.now() }); });
      ws.addEventListener('close', function (e) { post({ t: 'ws', id: id, dir: 'close', d: e.code + ' ' + (e.reason || ''), ts: Date.now() }); });
      var send = ws.send;
      ws.send = function (d) { post({ t: 'ws', id: id, dir: 'out', d: wsd(d), ts: Date.now() }); return send.apply(ws, arguments); };
      return ws;
    };
    WS.prototype = NativeWS.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { WS[k] = NativeWS[k]; });
    window.WebSocket = WS;
  }
  if (navigator.sendBeacon) {
    var beacon = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function (u, d) {
      try { post({ t: 'net', ph: 'start', id: nid(), kind: 'beacon', method: 'POST', url: abs(String(u)), rh: [], body: bodyInfo(d), ts: Date.now(), top: TOP ? 1 : 0 }); } catch (e) {}
      return beacon(u, d);
    };
  }

  // ------------------------------------------------------------------ timing and web vitals
  var perfQueue = [], perfTimer = 0;
  function entryRow(e) {
    return { n: e.name, i: e.initiatorType, s: e.startTime, d: e.duration, ts: e.transferSize, eb: e.encodedBodySize, db: e.decodedBodySize, st: e.responseStatus || 0, np: e.nextHopProtocol,
             fs: e.fetchStart, ds: e.domainLookupStart, de: e.domainLookupEnd, cs: e.connectStart, ce: e.connectEnd, ss: e.secureConnectionStart, rq: e.requestStart, rs: e.responseStart, re: e.responseEnd };
  }
  function flushPerf() { perfTimer = 0; if (perfQueue.length) { post({ t: 'perf', e: perfQueue.splice(0, 200) }); } }
  var vitals = { cls: 0, lcp: 0, fcp: 0, tbt: 0, fid: 0, lt: 0 };
  function observe(type, cb) {
    try { new PerformanceObserver(function (l) { l.getEntries().forEach(cb); }).observe({ type: type, buffered: true }); } catch (e) {}
  }
  observe('resource', function (e) { perfQueue.push(entryRow(e)); if (!perfTimer) perfTimer = setTimeout(flushPerf, 400); });
  observe('paint', function (e) { if (e.name === 'first-contentful-paint') { vitals.fcp = e.startTime; post({ t: 'vital', k: 'fcp', v: e.startTime }); } });
  observe('largest-contentful-paint', function (e) { vitals.lcp = e.startTime; post({ t: 'vital', k: 'lcp', v: e.startTime }); });
  observe('layout-shift', function (e) { if (!e.hadRecentInput) { vitals.cls += e.value; post({ t: 'vital', k: 'cls', v: vitals.cls }); } });
  observe('longtask', function (e) { vitals.lt++; if (e.duration > 50) vitals.tbt += e.duration - 50; post({ t: 'vital', k: 'tbt', v: vitals.tbt }); });
  observe('first-input', function (e) { vitals.fid = e.processingStart - e.startTime; post({ t: 'vital', k: 'fid', v: vitals.fid }); });
  window.addEventListener('DOMContentLoaded', function () { post({ t: 'life', k: 'dcl', v: performance.now(), ts: Date.now(), url: location.href, top: TOP ? 1 : 0 }); });
  window.addEventListener('load', function () { setTimeout(flushPerf, 50); post({ t: 'life', k: 'load', v: performance.now(), ts: Date.now(), url: location.href, title: document.title, top: TOP ? 1 : 0 }); });

  // ------------------------------------------------------------------ DOM inspection
  if (!TOP) { window.__nsdt_agent = { frame: true }; return; }
  var nodeMap = new Map(), nodeIds = new WeakMap(), nodeSeq = 0;
  function nodeId(n) {
    var id = nodeIds.get(n);
    if (!id) { id = ++nodeSeq; nodeIds.set(n, id); nodeMap.set(id, new WeakRef(n)); }
    return id;
  }
  function N(id) {
    var r = nodeMap.get(id), n = r && r.deref();
    if (!n) throw new Error('That node no longer exists');
    return n;
  }
  function kids(n) {
    var out = [];
    try {
      if (n.shadowRoot) out.push(n.shadowRoot);
      if (n.localName === 'iframe' || n.localName === 'frame') { try { if (n.contentDocument) out.push(n.contentDocument); } catch (e) {} }
      var cn = n.childNodes;
      for (var i = 0; i < cn.length; i++) {
        var c = cn[i];
        if (c.nodeType === 3 && !/\S/.test(c.nodeValue)) continue;
        out.push(c);
      }
    } catch (e) {}
    return out;
  }
  function info(n) {
    var o = { id: nodeId(n), nt: n.nodeType, nm: n.nodeName.toLowerCase() };
    if (n.nodeType === 1) {
      o.at = Array.prototype.map.call(n.attributes, function (a) { return [a.name, clip(a.value, 800)]; });
      o.hc = kids(n).length > 0;
      if (n.shadowRoot) o.sh = 1;
    } else if (n.nodeType === 3 || n.nodeType === 8) {
      o.tx = clip(n.nodeValue, 600);
    } else if (n.nodeType === 10) {
      o.tx = '<!DOCTYPE ' + n.name + (n.publicId ? ' PUBLIC "' + n.publicId + '"' : '') + '>';
    } else { o.hc = kids(n).length > 0; }
    return o;
  }
  var overlay = null;
  function hideOverlay() { if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay); overlay = null; }
  function showOverlay(el) {
    hideOverlay();
    if (!el || el.nodeType !== 1) return;
    var r = el.getBoundingClientRect(), cs = getComputedStyle(el);
    var px = function (p) { return parseFloat(cs.getPropertyValue(p)) || 0; };
    var m = { t: px('margin-top'), r: px('margin-right'), b: px('margin-bottom'), l: px('margin-left') };
    var b = { t: px('border-top-width'), r: px('border-right-width'), b: px('border-bottom-width'), l: px('border-left-width') };
    var p = { t: px('padding-top'), r: px('padding-right'), b: px('padding-bottom'), l: px('padding-left') };
    overlay = document.createElement('div');
    overlay.setAttribute('style', 'all:initial;position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483647');
    var box = function (x, y, w, h, color) {
      var d = document.createElement('div');
      d.setAttribute('style', 'all:initial;position:fixed;pointer-events:none;box-sizing:border-box;left:' + x + 'px;top:' + y + 'px;width:' + Math.max(0, w) + 'px;height:' + Math.max(0, h) + 'px;background:' + color);
      overlay.appendChild(d);
    };
    box(r.left - m.l, r.top - m.t, r.width + m.l + m.r, r.height + m.t + m.b, 'rgba(246,178,107,.45)');
    box(r.left, r.top, r.width, r.height, 'rgba(255,229,153,.55)');
    box(r.left + b.l, r.top + b.t, r.width - b.l - b.r, r.height - b.t - b.b, 'rgba(147,196,125,.55)');
    box(r.left + b.l + p.l, r.top + b.t + p.t, r.width - b.l - b.r - p.l - p.r, r.height - b.t - b.b - p.t - p.b, 'rgba(111,168,220,.6)');
    var tag = document.createElement('div');
    tag.setAttribute('style', 'all:initial;position:fixed;pointer-events:none;font:11px monospace;color:#fff;background:#202124;padding:2px 6px;border-radius:3px;left:' + Math.max(2, Math.min(r.left, innerWidth - 150)) + 'px;top:' + (r.top > 22 ? r.top - 20 : r.bottom + 2) + 'px');
    tag.textContent = el.localName + (el.id ? '#' + el.id : '') + '  ' + Math.round(r.width) + ' \u00D7 ' + Math.round(r.height);
    overlay.appendChild(tag);
    document.documentElement.appendChild(overlay);
  }
  function cssPath(el) {
    if (el.nodeType !== 1) return '';
    var parts = [];
    while (el && el.nodeType === 1 && el !== document.documentElement) {
      var s = el.localName;
      if (el.id && document.querySelectorAll('#' + CSS.escape(el.id)).length === 1) { parts.unshift('#' + CSS.escape(el.id)); break; }
      var sib = el.parentNode ? Array.prototype.filter.call(el.parentNode.children, function (c) { return c.localName === el.localName; }) : [];
      if (sib.length > 1) s += ':nth-of-type(' + (sib.indexOf(el) + 1) + ')';
      parts.unshift(s);
      el = el.parentNode;
    }
    return parts.length && parts[0].charAt(0) !== '#' ? 'html > ' + parts.join(' > ') : parts.join(' > ');
  }
  function xpath(el) {
    var parts = [];
    while (el && el.nodeType === 1) {
      var i = 1, s = el.previousElementSibling;
      while (s) { if (s.localName === el.localName) i++; s = s.previousElementSibling; }
      parts.unshift(el.localName + '[' + i + ']');
      el = el.parentNode;
    }
    return '/' + parts.join('/');
  }

  // CSS: matched rules in cascade order
  function splitSel(text) {
    var out = [], depth = 0, cur = '', q = '';
    for (var i = 0; i < text.length; i++) {
      var c = text[i];
      if (q) { cur += c; if (c === q && text[i - 1] !== '\\') q = ''; continue; }
      if (c === '"' || c === "'") { q = c; cur += c; continue; }
      if (c === '(' || c === '[') depth++;
      if (c === ')' || c === ']') depth--;
      if (c === ',' && depth === 0) { out.push(cur.trim()); cur = ''; } else cur += c;
    }
    if (cur.trim()) out.push(cur.trim());
    return out;
  }
  function spec(sel) {
    var s = sel.replace(/:(?:not|is|has)\(([^)]*)\)/g, ' $1 ').replace(/:where\([^)]*\)/g, '').replace(/\[[^\]]*\]/g, '[a]').replace(/"[^"]*"|'[^']*'/g, '');
    var a = (s.match(/#[\w-]+/g) || []).length;
    var pe = (s.match(/::[\w-]+|:(?:before|after|first-line|first-letter)/g) || []).length;
    var b = (s.match(/\.[\w-]+|\[a\]|:(?!:)[\w-]+/g) || []).length - (s.match(/:(?:before|after|first-line|first-letter)/g) || []).length;
    var c = (s.replace(/#[\w-]+|\.[\w-]+|\[a\]|::?[\w-]+(\([^)]*\))?/g, ' ').match(/(^|[\s>+~])[a-zA-Z][\w-]*/g) || []).length + pe;
    return a * 10000 + b * 100 + c;
  }
  function declList(text) {
    var out = [], cur = '', depth = 0, q = '';
    function flush() {
      var i = cur.indexOf(':');
      if (i > 0) {
        var name = cur.slice(0, i).trim(), val = cur.slice(i + 1).trim(), imp = /\s*!important\s*$/i.test(val);
        if (imp) val = val.replace(/\s*!important\s*$/i, '');
        out.push([name, val, imp ? 1 : 0]);
      }
      cur = '';
    }
    for (var i = 0; i < text.length; i++) {
      var c = text[i];
      if (q) { cur += c; if (c === q && text[i - 1] !== '\\') q = ''; continue; }
      if (c === '"' || c === "'") { q = c; cur += c; continue; }
      if (c === '(') depth++;
      if (c === ')') depth--;
      if (c === ';' && depth === 0) flush(); else cur += c;
    }
    flush();
    return out;
  }
  var SKIP = /^(CSSKeyframesRule|CSSFontFaceRule|CSSPageRule|CSSNamespaceRule|CSSPropertyRule|CSSCounterStyleRule|CSSKeyframeRule)$/;
  function matched(el) {
    var res = [];
    function walk(rules, path, sheetHref, sheetIdx, media) {
      for (var i = 0; i < rules.length; i++) {
        var r = rules[i], name = r.constructor && r.constructor.name;
        if (SKIP.test(name)) continue;
        if (r.selectorText !== undefined && r.style) {
          var best = -1;
          splitSel(r.selectorText).forEach(function (p) { try { if (el.matches(p)) best = Math.max(best, spec(p)); } catch (e) {} });
          if (best >= 0) res.push({ sel: r.selectorText, spec: best, href: sheetHref, ref: [sheetIdx].concat(path, [i]), media: media, d: declList(r.style.cssText) });
        } else if (name === 'CSSImportRule' && r.styleSheet) {
          try { walk(r.styleSheet.cssRules, [], r.styleSheet.href, sheetIdx, media); } catch (e) {}
        } else if (r.cssRules) {
          var cond = r.conditionText !== undefined ? r.conditionText : (r.media ? r.media.mediaText : '');
          var ok = true;
          try {
            if (name === 'CSSMediaRule') ok = matchMedia(cond || 'all').matches;
            else if (name === 'CSSSupportsRule') ok = CSS.supports(cond);
          } catch (e) {}
          if (ok) walk(r.cssRules, path.concat([i]), sheetHref, sheetIdx, name === 'CSSMediaRule' ? '@media ' + cond : media);
        }
      }
    }
    var blocked = 0;
    Array.prototype.forEach.call(document.styleSheets, function (sh, idx) {
      try { walk(sh.cssRules, [], sh.href || (sh.ownerNode ? '(inline <style>)' : '(constructed)'), idx, ''); } catch (e) { blocked++; }
    });
    res.forEach(function (x, i) { x.o = i; });
    res.sort(function (a, b) { return b.spec - a.spec || b.o - a.o; });
    return { inline: declList(el.getAttribute('style') || ''), rules: res.slice(0, 80), blocked: blocked };
  }
  function ruleAt(ref) {
    var rules = document.styleSheets[ref[0]].cssRules;
    for (var i = 1; i < ref.length - 1; i++) rules = rules[ref[i]].cssRules;
    return rules[ref[ref.length - 1]];
  }
  function boxModel(el) {
    var cs = getComputedStyle(el), r = el.getBoundingClientRect();
    var g = function (p) { return cs.getPropertyValue(p); };
    return { w: r.width, h: r.height, x: r.left + scrollX, y: r.top + scrollY, display: g('display'), position: g('position'),
      m: [g('margin-top'), g('margin-right'), g('margin-bottom'), g('margin-left')], b: [g('border-top-width'), g('border-right-width'), g('border-bottom-width'), g('border-left-width')],
      p: [g('padding-top'), g('padding-right'), g('padding-bottom'), g('padding-left')], cw: g('width'), ch: g('height') };
  }

  // element picker
  var picking = false, pickHandlers = null;
  function stopPick() {
    if (!picking) return;
    picking = false;
    ['touchstart', 'touchmove', 'touchend', 'mousedown', 'mouseup', 'click', 'pointerdown', 'pointerup'].forEach(function (t) { window.removeEventListener(t, pickHandlers, true); });
  }
  function startPick() {
    stopPick();
    picking = true;
    var last = null;
    pickHandlers = function (e) {
      var pt = e.changedTouches ? e.changedTouches[0] : e;
      var el = document.elementFromPoint(pt.clientX, pt.clientY);
      if (el && overlay && overlay.contains(el)) el = last;
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      if (!el) return;
      last = el;
      if (e.type === 'touchstart' || e.type === 'touchmove' || e.type === 'mousedown') showOverlay(el);
      if (e.type === 'touchend' || e.type === 'click') { stopPick(); showOverlay(el); post({ t: 'picked', id: nodeId(el) }); }
    };
    ['touchstart', 'touchmove', 'touchend', 'mousedown', 'mouseup', 'click', 'pointerdown', 'pointerup'].forEach(function (t) { window.addEventListener(t, pickHandlers, { capture: true, passive: false }); });
  }

  var mo = null, moTimer = 0;
  function watch(on) {
    if (mo) { mo.disconnect(); mo = null; }
    if (!on) return;
    mo = new MutationObserver(function () { if (!moTimer) moTimer = setTimeout(function () { moTimer = 0; post({ t: 'domchange' }); }, 300); });
    mo.observe(document, { attributes: true, childList: true, characterData: true, subtree: true });
  }

  // ------------------------------------------------------------------ evaluation
  function evalCode(code) {
    var $0v = window.__nsdt_sel;
    if (!('$0' in window)) { try { Object.defineProperty(window, '$0', { configurable: true, get: function () { return window.__nsdt_sel; } }); } catch (e) {} }
    if (typeof window.$ === 'undefined') window.$ = function (s, r) { return (r || document).querySelector(s); };
    if (typeof window.$$ === 'undefined') window.$$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
    var ev = (0, eval);
    var isSyntax = function (e) { return e && e.name === 'SyntaxError'; };
    if (/\bawait\b/.test(code)) { try { return ev('(async () => (' + code + '\n))()'); } catch (e) { if (!isSyntax(e)) throw e; } }
    try { return ev('(' + code + '\n)'); } catch (e) { if (!isSyntax(e)) throw e; }
    return ev(code);
  }
  function resolveResult(val) {
    if (val && typeof val.then === 'function') {
      return Promise.race([val.then(function (v) { return { v: v, state: 'fulfilled' }; }, function (e) { return { v: e, state: 'rejected' }; }), new Promise(function (r) { setTimeout(function () { r({ state: 'pending' }); }, 4000); })])
        .then(function (r) { return r.state === 'pending' ? { k: 'o', p: 'Promise {<pending>}', c: 'Promise' } : { s: r.state, r: ser(r.v, true) }; });
    }
    return Promise.resolve({ r: ser(val, true) });
  }
  function complete(expr) {
    var m = /^(.*?)([\w$]*)$/.exec(expr), head = m[1], tail = m[2], base = window;
    if (head) {
      if (!/\.$/.test(head)) return [];
      try { base = evalCode(head.slice(0, -1)); } catch (e) { return []; }
    }
    var names = new Set();
    try { for (var o = Object(base); o && names.size < 600; o = Object.getPrototypeOf(o)) Object.getOwnPropertyNames(o).forEach(function (n) { names.add(n); }); } catch (e) {}
    return Array.from(names).filter(function (n) { return n.indexOf(tail) === 0 && n !== tail; }).sort().slice(0, 40).map(function (n) { return head + n; });
  }

  // ------------------------------------------------------------------ RPC
  function fromPath(id) { var n = N(id), chain = []; while (n) { chain.unshift(nodeId(n)); n = n.parentNode || (n.host || null); } return chain; }
  function sizeOf(v) { try { return JSON.stringify(v).length; } catch (e) { return 0; } }
  var R = {
    'page.info': function () {
      var scripts = Array.prototype.map.call(document.scripts, function (s, i) { return { i: i, src: s.src || '', len: s.src ? 0 : s.text.length, type: s.type || '', module: s.type === 'module', async: s.async, defer: s.defer }; });
      var styles = Array.prototype.map.call(document.styleSheets, function (s, i) { return { i: i, href: s.href || '', inline: !s.href, disabled: s.disabled, media: s.media && s.media.mediaText || '' }; });
      return { url: location.href, title: document.title, charset: document.characterSet, mode: document.compatMode, ready: document.readyState, referrer: document.referrer, ua: navigator.userAgent, lang: navigator.language,
               dpr: devicePixelRatio, vw: innerWidth, vh: innerHeight, secure: window.isSecureContext, online: navigator.onLine, frames: window.frames.length, cookies: navigator.cookieEnabled,
               scripts: scripts, styles: styles, size: document.documentElement.outerHTML.length, origin: location.origin, doctype: document.doctype ? '<!DOCTYPE ' + document.doctype.name + '>' : '' };
    },
    'dom.root': function () { return Array.prototype.map.call(document.childNodes, info); },
    'dom.children': function (a) { var n = N(a.id), k = kids(n), off = a.off || 0; return { list: k.slice(off, off + 300).map(info), more: Math.max(0, k.length - off - 300) }; },
    'dom.path': function (a) { return fromPath(a.id); },
    'dom.info': function (a) { return info(N(a.id)); },
    'dom.highlight': function (a) { var n = N(a.id); showOverlay(n.nodeType === 1 ? n : n.parentElement); return 1; },
    'dom.unhighlight': function () { hideOverlay(); return 1; },
    'dom.select': function (a) {
      var n = N(a.id);
      window.__nsdt_sel = n;
      if (a.scroll && n.scrollIntoView) { try { n.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {} }
      if (n.nodeType === 1) { showOverlay(n); return { sel: cssPath(n), xpath: xpath(n), box: boxModel(n) }; }
      return { sel: '', xpath: '' };
    },
    'dom.outer': function (a) { var n = N(a.id); return n.nodeType === 1 ? clip(n.outerHTML, 200000) : String(n.nodeValue); },
    'dom.setOuter': function (a) { var n = N(a.id); if (n.nodeType === 1) n.outerHTML = a.html; else n.nodeValue = a.html; return 1; },
    'dom.setAttr': function (a) { var n = N(a.id); if (a.old && a.old !== a.name) n.removeAttribute(a.old); n.setAttribute(a.name, a.value); return 1; },
    'dom.delAttr': function (a) { N(a.id).removeAttribute(a.name); return 1; },
    'dom.setText': function (a) { N(a.id).nodeValue = a.text; return 1; },
    'dom.remove': function (a) { var n = N(a.id); n.parentNode && n.parentNode.removeChild(n); return 1; },
    'dom.hide': function (a) { var n = N(a.id); n.style.setProperty('visibility', n.style.visibility === 'hidden' ? '' : 'hidden'); return 1; },
    'dom.search': function (a) {
      var q = String(a.q || '').trim(), ids = [];
      if (!q) return ids;
      try {
        if (q.charAt(0) === '/') {
          var snap = document.evaluate(q, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
          for (var i = 0; i < snap.snapshotLength && i < 200; i++) ids.push(nodeId(snap.snapshotItem(i)));
          return ids;
        }
      } catch (e) {}
      try { Array.prototype.slice.call(document.querySelectorAll(q), 0, 200).forEach(function (n) { ids.push(nodeId(n)); }); } catch (e) {}
      if (ids.length) return ids;
      var lq = q.toLowerCase(), all = document.getElementsByTagName('*');
      for (var j = 0; j < all.length && ids.length < 200; j++) {
        var el = all[j], hit = el.localName.indexOf(lq) >= 0;
        if (!hit) for (var k = 0; k < el.attributes.length; k++) { var at = el.attributes[k]; if (at.name.indexOf(lq) >= 0 || at.value.toLowerCase().indexOf(lq) >= 0) { hit = true; break; } }
        if (!hit) for (var c = el.firstChild; c; c = c.nextSibling) if (c.nodeType === 3 && c.nodeValue.toLowerCase().indexOf(lq) >= 0) { hit = true; break; }
        if (hit) ids.push(nodeId(el));
      }
      return ids;
    },
    'dom.pick': function (a) { if (a.on) startPick(); else { stopPick(); hideOverlay(); } return 1; },
    'dom.watch': function (a) { watch(a.on); return 1; },
    'css.matched': function (a) { var n = N(a.id); return n.nodeType === 1 ? matched(n) : { inline: [], rules: [], blocked: 0 }; },
    'css.computed': function (a) { var cs = getComputedStyle(N(a.id)), out = []; for (var i = 0; i < cs.length; i++) out.push([cs[i], cs.getPropertyValue(cs[i])]); return out; },
    'css.setInline': function (a) { var n = N(a.id); if (a.value === null) n.style.removeProperty(a.name); else n.style.setProperty(a.name, a.value, a.imp ? 'important' : ''); return 1; },
    'css.setRule': function (a) { var r = ruleAt(a.ref); if (a.value === null) r.style.removeProperty(a.name); else r.style.setProperty(a.name, a.value, a.imp ? 'important' : ''); return 1; },
    'eval': function (a) { return resolveResult(evalCode(a.code)); },
    'eval.complete': function (a) { return complete(a.expr); },
    'obj.props': function (a) { return props(a.id); },
    'obj.release': function () { objs.clear(); return 1; },
    'net.body': function (a) { var b = bodies.get(a.id); return b ? { text: b.text, size: b.size } : null; },
    'ls.list': function (a) { var s = window[a.area === 'session' ? 'sessionStorage' : 'localStorage'], out = []; for (var i = 0; i < s.length; i++) { var k = s.key(i); out.push([k, clip(s.getItem(k), 20000)]); } return out; },
    'ls.set': function (a) { var s = window[a.area === 'session' ? 'sessionStorage' : 'localStorage']; if (a.old && a.old !== a.key) s.removeItem(a.old); s.setItem(a.key, a.value); return 1; },
    'ls.del': function (a) { window[a.area === 'session' ? 'sessionStorage' : 'localStorage'].removeItem(a.key); return 1; },
    'ls.clear': function (a) { window[a.area === 'session' ? 'sessionStorage' : 'localStorage'].clear(); return 1; },
    'idb.dbs': function () { return indexedDB.databases ? indexedDB.databases() : []; },
    'idb.info': function (a) {
      return new Promise(function (res, rej) {
        var rq = indexedDB.open(a.name);
        rq.onerror = function () { rej(rq.error); };
        rq.onsuccess = function () {
          var db = rq.result, names = Array.prototype.slice.call(db.objectStoreNames), out = [], left = names.length;
          if (!left) { db.close(); res({ version: db.version, stores: [] }); return; }
          try {
            var tx = db.transaction(names, 'readonly');
            names.forEach(function (n) {
              var st = tx.objectStore(n), c = st.count();
              c.onsuccess = function () {
                out.push({ name: n, keyPath: st.keyPath, auto: st.autoIncrement, count: c.result, indexes: Array.prototype.slice.call(st.indexNames) });
                if (--left === 0) { db.close(); res({ version: db.version, stores: out }); }
              };
            });
          } catch (e) { db.close(); rej(e); }
        };
      });
    },
    'idb.rows': function (a) {
      return new Promise(function (res, rej) {
        var rq = indexedDB.open(a.name);
        rq.onerror = function () { rej(rq.error); };
        rq.onsuccess = function () {
          var db = rq.result, out = [];
          try {
            var cur = db.transaction(a.store, 'readonly').objectStore(a.store).openCursor(), skipped = false;
            cur.onerror = function () { db.close(); rej(cur.error); };
            cur.onsuccess = function () {
              var c = cur.result;
              if (c && a.off && !skipped) { skipped = true; c.advance(a.off); return; }
              if (!c || out.length >= (a.limit || 50)) { db.close(); res(out); return; }
              var v; try { v = JSON.stringify(c.value); } catch (e) { v = String(c.value); }
              out.push({ k: clip(JSON.stringify(c.key), 300), v: clip(v === undefined ? 'undefined' : v, 3000) });
              c.continue();
            };
          } catch (e) { db.close(); rej(e); }
        };
      });
    },
    'idb.clear': function (a) {
      return new Promise(function (res, rej) {
        var rq = indexedDB.open(a.name);
        rq.onerror = function () { rej(rq.error); };
        rq.onsuccess = function () { var db = rq.result; var tx = db.transaction(a.store, 'readwrite'); tx.objectStore(a.store).clear(); tx.oncomplete = function () { db.close(); res(1); }; tx.onerror = function () { db.close(); rej(tx.error); }; };
      });
    },
    'idb.deleteDb': function (a) { return new Promise(function (res, rej) { var rq = indexedDB.deleteDatabase(a.name); rq.onsuccess = function () { res(1); }; rq.onerror = function () { rej(rq.error); }; rq.onblocked = function () { res(1); }; }); },
    'cache.list': function () {
      if (!window.caches) return [];
      return caches.keys().then(function (names) {
        return Promise.all(names.map(function (n) { return caches.open(n).then(function (c) { return c.keys(); }).then(function (rs) { return { name: n, items: rs.slice(0, 200).map(function (r) { return { url: r.url, method: r.method }; }), count: rs.length }; }); }));
      });
    },
    'cache.delete': function (a) { return caches.open(a.name).then(function (c) { return a.url ? c.delete(a.url) : caches.delete(a.name); }).then(function () { return 1; }); },
    'sw.list': function () {
      if (!navigator.serviceWorker) return [];
      return navigator.serviceWorker.getRegistrations().then(function (rs) { return rs.map(function (r) { var w = r.active || r.waiting || r.installing; return { scope: r.scope, script: w ? w.scriptURL : '', state: w ? w.state : '' }; }); });
    },
    'sw.unregister': function (a) { return navigator.serviceWorker.getRegistrations().then(function (rs) { return Promise.all(rs.filter(function (r) { return r.scope === a.scope; }).map(function (r) { return r.unregister(); })); }).then(function () { return 1; }); },
    'src.inline': function (a) { var s = document.scripts[a.i]; return s ? s.text : ''; },
    'src.style': function (a) { var s = document.styleSheets[a.i]; if (!s) return ''; try { return Array.prototype.map.call(s.cssRules, function (r) { return r.cssText; }).join('\n'); } catch (e) { return '(this stylesheet comes from another origin; load it from the network list)'; } },
    'src.dom': function () { return '<!DOCTYPE html>\n' + document.documentElement.outerHTML; },
    'perf.snapshot': function () {
      var nav = performance.getEntriesByType('navigation')[0], out = { vitals: vitals };
      if (nav) out.nav = { start: nav.startTime, ds: nav.domainLookupStart, de: nav.domainLookupEnd, cs: nav.connectStart, ce: nav.connectEnd, ss: nav.secureConnectionStart, rq: nav.requestStart, rs: nav.responseStart, re: nav.responseEnd,
        dcl: nav.domContentLoadedEventEnd, load: nav.loadEventEnd, dom: nav.domInteractive, proto: nav.nextHopProtocol, type: nav.type, redirect: nav.redirectCount, size: nav.transferSize };
      if (performance.memory) out.mem = { used: performance.memory.usedJSHeapSize, total: performance.memory.totalJSHeapSize, limit: performance.memory.jsHeapSizeLimit };
      out.nodes = document.getElementsByTagName('*').length;
      return out;
    },
    'util.fromPoint': function (a) { var el = document.elementFromPoint(a.x, a.y); return el ? nodeId(el) : null; }
  };
  window.__nsdt_agent = {
    rpc: function (id, method, argsJson) {
      var reply = function (ok, r) { post({ t: 'rpc', id: id, ok: ok, r: r }); };
      try {
        var fn = R[method];
        if (!fn) throw new Error('Unknown method ' + method);
        Promise.resolve(fn(argsJson ? JSON.parse(argsJson) : {})).then(function (r) { reply(true, r === undefined ? null : r); }, function (e) { reply(false, String(e && e.message || e)); });
      } catch (e) { reply(false, String(e && e.message || e)); }
    }
  };
  post({ t: 'ready', url: location.href, ts: Date.now() });
})();
