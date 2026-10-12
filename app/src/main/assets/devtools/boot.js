'use strict';
/* Header controls, settings menu and start-up. */
(function () {
  const S = () => NS.settings;
  const set = (key, value) => native('setting', { key, value }).catch((e) => toast(e.message));
  const menu = (btn) => menuAt(btn, [
    { label: 'Preserve log', check: S().preserve, fn: (v) => set('preserve', v) },
    { label: 'Disable cache', check: S().noCache, fn: (v) => set('noCache', v) },
    { label: 'Disable JavaScript (reloads)', check: S().noJs, fn: (v) => set('noJs', v) },
    { label: 'Block images (reloads)', check: S().noImages, fn: (v) => set('noImages', v) },
    { label: 'Remote debugging (chrome://inspect)', check: S().remote, fn: (v) => set('remote', v) },
    '-',
    { label: 'Reload page', fn: () => native('reload', { hard: false }) },
    { label: 'Hard reload (empty cache)', fn: () => native('reload', { hard: true }) },
    { label: 'Screenshot (visible area)', fn: async () => { try { toast('Saved ' + await native('screenshot', {})); } catch (e) { toast(e.message); } } },
    { label: 'Copy page URL', fn: () => copyText(NS.tab.url) },
    '-',
    { label: 'Dock at bottom', fn: () => native('dock', { mode: 'bottom' }) },
    { label: 'Dock at right', fn: () => native('dock', { mode: 'right' }) },
    { label: 'Full screen', fn: () => native('dock', { mode: 'full' }) },
    { label: 'Smaller / larger', fn: () => native('dock', { mode: 'cycle' }) },
    '-',
    { label: 'Turn developer tools off', fn: () => native('off', {}) }
  ]);
  const ctl = $('#ctl');
  const gear = h('button', { title: 'Settings', onclick: (e) => menu(e.currentTarget) }, '\u2699');
  ctl.append(gear, h('button', { title: 'Resize', onclick: () => native('dock', { mode: 'cycle' }) }, '\u2922'), h('button', { title: 'Close', onclick: () => native('close', {}) }, '\u2715'));
  ctl.style.position = 'relative';
  NS.onSettings = () => {};
  window.addEventListener('error', (e) => { try { NSDT.log('devtools UI error: ' + e.message); } catch (x) {} });
  let first = 'console';
  try { first = localStorage.getItem('nsdt.tab') || first; } catch (e) {}
  NS.show(NS.panels[first] ? first : 'elements');
  NSDT.ready();
})();
