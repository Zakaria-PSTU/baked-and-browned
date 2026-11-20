// C' feasibility probes (QA-01): can the browser recover a refresh whose response arrives after the
// page navigated away? Runs against the unchanged API through a local proxy that can hold or drop
// the refresh response AFTER the server has rotated the token. Prints statuses, booleans and
// timings only: never cookie or token values.
import http from 'node:http';
import { chromium, firefox, webkit } from 'playwright-core';

const API = { host: 'localhost', port: 8080 };
const PORT = 4300;
const BASE = `http://localhost:${PORT}`;
let mode = { kind: 'pass', ms: 0 };
const proxyLog = [];

const proxy = http.createServer((req, res) => {
  if (req.url.startsWith('/__probe/blank')) {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end('<!doctype html><meta charset="utf-8"><title>probe</title><p>probe</p>');
    return;
  }
  const headers = { ...req.headers, host: 'localhost:8080' };
  if (headers.origin) headers.origin = 'http://localhost:4200'; // an allowed origin for the CSRF guard
  const isRefresh = req.method === 'POST' && req.url.startsWith('/api/v1/auth/refresh');
  const current = mode;
  let clientGone = false;
  res.on('close', () => { if (!res.writableEnded) clientGone = true; });
  const up = http.request({ ...API, path: req.url, method: req.method, headers }, (ur) => {
    const chunks = [];
    ur.on('data', (c) => chunks.push(c));
    ur.on('end', () => {
      const send = () => {
        const entry = { refresh: isRefresh, status: ur.statusCode, mode: current.kind, clientGoneBeforeReply: clientGone };
        if (isRefresh) proxyLog.push(entry);
        if (isRefresh && current.kind === 'drop') { req.socket.destroy(); return; }
        res.writeHead(ur.statusCode, ur.headers);
        res.end(Buffer.concat(chunks));
      };
      if (isRefresh && (current.kind === 'hold' || current.kind === 'drop')) setTimeout(send, current.ms);
      else send();
    });
  });
  up.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(up);
});
await new Promise((r) => proxy.listen(PORT, r));

// ------------------------------------------------------------------ page-side helpers
const PAGE_HELPERS = () => {
  window.__xsrf = () => (document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || null;
  window.__refresh = (keepalive) => fetch('/api/v1/auth/refresh', {
    method: 'POST', credentials: 'include', keepalive,
    headers: { 'X-CSRF-Token': window.__xsrf() ?? '' },
  }).then((r) => r.status, (e) => 'network-error');
  // C' marker: written before a refresh, holding the XSRF value seen at that moment (not a secret).
  window.__mark = () => localStorage.setItem('bb-refresh-inflight', JSON.stringify({ ts: Date.now(), xsrf: window.__xsrf() }));
  // C' on load: if a recent marker exists, wait (up to maxWait) for the XSRF cookie to change.
  window.__awaitInflight = async (maxWait) => {
    const raw = localStorage.getItem('bb-refresh-inflight');
    const start = Date.now();
    if (!raw) return { marker: false, waited: 0, changed: false };
    const m = JSON.parse(raw);
    const age = Date.now() - m.ts;
    if (age > maxWait) { localStorage.removeItem('bb-refresh-inflight'); return { marker: 'stale', waited: 0, changed: false }; }
    while (Date.now() - m.ts < maxWait) {
      if (window.__xsrf() !== m.xsrf) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    localStorage.removeItem('bb-refresh-inflight');
    return { marker: true, waited: Date.now() - start, changed: window.__xsrf() !== m.xsrf };
  };
};

let seq = 0;
async function freshSession(browser) {
  const ctx = await browser.newContext();
  await ctx.addInitScript(PAGE_HELPERS);
  const page = await ctx.newPage();
  await page.goto(`${BASE}/__probe/blank?n=0`);
  const phone = `+88017${String(Date.now()).slice(-6)}${String(seq++ % 100).padStart(2, '0')}`;
  const ok = await page.evaluate(async (phone) => {
    const body = (o) => ({ method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(o) });
    const r1 = await fetch('/api/v1/auth/register', body({ fullName: 'Probe User', phone, password: 'probe-password-123' }));
    const r2 = await fetch('/api/v1/auth/login', body({ identifier: phone, password: 'probe-password-123' }));
    return r1.status === 201 && r2.status === 200;
  }, phone);
  if (!ok) throw new Error('could not register/login');
  return { ctx, page };
}

const nav = async (page, n, delay) => {
  await page.evaluate(([n, d]) => setTimeout(() => { location.href = `/__probe/blank?n=${n}`; }, d), [n, delay]);
  await page.waitForURL(new RegExp(`n=${n}$`), { timeout: 10000 });
};
const check = (page) => { mode = { kind: 'pass', ms: 0 }; return page.evaluate(() => window.__refresh(false)); };

const scenarios = {
  // 1. Plain fetch, navigation while the response is held: reproduces QA-01 at protocol level.
  async navPlainFetch(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'hold', ms: 1500 };
    await page.evaluate(() => { window.__mark(); window.__refresh(false); });
    await nav(page, 1, 150);
    const wait = await page.evaluate(() => window.__awaitInflight(3000));
    const after = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 2. keepalive fetch, same navigation: is Set-Cookie applied after the page unloaded?
  async navKeepalive(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'hold', ms: 1500 };
    await page.evaluate(() => { window.__mark(); window.__refresh(true); });
    await nav(page, 1, 150);
    const wait = await page.evaluate(() => window.__awaitInflight(5000));
    const after = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 3. keepalive, then three navigations 100 ms apart.
  async rapidNavigations(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'hold', ms: 1500 };
    await page.evaluate(() => { window.__mark(); window.__refresh(true); });
    await nav(page, 1, 100); await nav(page, 2, 100); await nav(page, 3, 100);
    const wait = await page.evaluate(() => window.__awaitInflight(5000));
    const after = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 4a. Two tabs refresh at once WITHOUT coordination (control): expect reuse -> session ends.
  async twoTabsUncoordinated(b) {
    const { ctx, page } = await freshSession(b);
    const page2 = await ctx.newPage(); await page2.goto(`${BASE}/__probe/blank?n=0`);
    mode = { kind: 'hold', ms: 300 };
    const [s1, s2] = await Promise.all([page.evaluate(() => window.__refresh(false)), page2.evaluate(() => window.__refresh(false))]);
    const after = await check(page);
    await ctx.close();
    return { tab1: s1, tab2: s2, nextRefresh: after };
  },
  // 4b. Two tabs, serialized with a Web Lock and reading XSRF inside the lock (as the SPA does).
  async twoTabsWebLock(b) {
    const { ctx, page } = await freshSession(b);
    const page2 = await ctx.newPage(); await page2.goto(`${BASE}/__probe/blank?n=0`);
    mode = { kind: 'hold', ms: 300 };
    const locked = () => navigator.locks.request('bb-auth-refresh', () => window.__refresh(false));
    const [s1, s2] = await Promise.all([page.evaluate(locked), page2.evaluate(locked)]);
    const after = await check(page);
    await ctx.close();
    return { tab1: s1, tab2: s2, nextRefresh: after };
  },
  // 5. keepalive, then the tab is closed before the response; a new tab opens.
  async tabClosedBeforeResponse(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'hold', ms: 1500 };
    await page.evaluate(() => { window.__mark(); window.__refresh(true); });
    await page.waitForTimeout(150);
    await page.close();
    const page2 = await ctx.newPage(); await page2.goto(`${BASE}/__probe/blank?n=9`);
    const wait = await page2.evaluate(() => window.__awaitInflight(5000));
    const after = await check(page2);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 6. Lost network response: the server rotated, the connection is dropped (keepalive does not help).
  async lostNetworkResponse(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'drop', ms: 200 };
    const first = await page.evaluate(() => { window.__mark(); return window.__refresh(true); });
    const wait = await page.evaluate(() => window.__awaitInflight(3000));
    const after = await check(page);
    await ctx.close();
    return { firstRefresh: first, ...wait, nextRefresh: after };
  },
  // 7a. Slow connection: response held 3.5 s, wait budget 5 s.
  async slowWithinBudget(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'hold', ms: 3500 };
    await page.evaluate(() => { window.__mark(); window.__refresh(true); });
    await nav(page, 1, 150);
    const wait = await page.evaluate(() => window.__awaitInflight(5000));
    const after = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 7b. Slower than the budget: response held 7 s, wait budget 5 s.
  async slowBeyondBudget(b) {
    const { ctx, page } = await freshSession(b);
    mode = { kind: 'hold', ms: 7000 };
    await page.evaluate(() => { window.__mark(); window.__refresh(true); });
    await nav(page, 1, 150);
    const wait = await page.evaluate(() => window.__awaitInflight(5000));
    const after = await check(page);
    await page.waitForTimeout(2500); // let the held response finish
    const later = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after, refreshAfterLateResponse: later };
  },
  // 8a. Stale marker (60 s old): must be ignored without waiting.
  async staleMarker(b) {
    const { ctx, page } = await freshSession(b);
    await page.evaluate(() => localStorage.setItem('bb-refresh-inflight', JSON.stringify({ ts: Date.now() - 60000, xsrf: window.__xsrf() })));
    await nav(page, 1, 0);
    const wait = await page.evaluate(() => window.__awaitInflight(5000));
    const after = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 8b. Fresh marker but no refresh actually in flight: costs the full wait, then works.
  async orphanMarker(b) {
    const { ctx, page } = await freshSession(b);
    await page.evaluate(() => window.__mark());
    await nav(page, 1, 0);
    const wait = await page.evaluate(() => window.__awaitInflight(3000));
    const after = await check(page);
    await ctx.close();
    return { ...wait, nextRefresh: after };
  },
  // 9. XSRF synchronization: the readable cookie changes on every successful refresh.
  async xsrfRotates(b) {
    const { ctx, page } = await freshSession(b);
    const r = await page.evaluate(async () => {
      const a = window.__xsrf(); const s1 = await window.__refresh(false);
      const b2 = window.__xsrf(); const s2 = await window.__refresh(false);
      const c = window.__xsrf();
      return { s1, s2, changedEachTime: a !== b2 && b2 !== c && a !== c };
    });
    await ctx.close();
    return r;
  },
};

const browsers = [
  ['Chrome (Chromium)', () => chromium.launch({ channel: 'chrome', headless: true })],
  ['Firefox 137', () => firefox.launch({ headless: true })],
  ['WebKit 18.4', () => webkit.launch({ headless: true })],
];
const results = {};
for (const [name, launch] of browsers) {
  let browser;
  try { browser = await launch(); } catch (e) { results[name] = { unavailable: e.message.split('\n')[0] }; continue; }
  results[name] = { version: browser.version() };
  for (const [id, fn] of Object.entries(scenarios)) {
    proxyLog.length = 0;
    try {
      const r = await fn(browser);
      const refreshes = proxyLog.map((e) => `${e.mode}:${e.status}${e.clientGoneBeforeReply ? '(client gone)' : ''}`);
      results[name][id] = { ...r, proxy: refreshes };
    } catch (e) { results[name][id] = { error: e.message.split('\n')[0] }; }
    console.log(`${name} ${id}: ${JSON.stringify(results[name][id])}`);
  }
  await browser.close();
}
proxy.close();
console.log('JSON ' + JSON.stringify(results));
