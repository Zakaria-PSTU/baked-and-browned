// QA-01 (ADR-0023 rev 4): does the REAL web app survive a navigation, reload or tab close during
// its session refresh? A local server on :4300 serves the production build (DIST) and proxies
// /api to the API on :8080. For chosen refreshes it holds (or drops) the response AFTER the API
// has rotated the token, which is exactly the QA-01 window.
//
// Prints statuses, booleans and timings only: never cookie or token values.
//
// Needs: the API on :8080 started with secure-cookies=false and raised login/register/refresh rate
// limits (process-only overrides, see ADR-0023), `ng build` output, and playwright-core 1.52.0
// next to this file (Firefox/WebKit from `npx playwright@1.52.0 install firefox webkit`).
//   DIST=<repo>/frontend/dist/baked-and-browned-web/browser node qa-01-spa-recovery-probe.mjs
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { chromium, firefox, webkit } from 'playwright-core';

if (!process.env.DIST) throw new Error('set DIST to the ng build browser folder');
const DIST = path.resolve(process.env.DIST); // normalized, so the containment check below works on Windows
if (!fs.existsSync(path.join(DIST, 'index.html'))) throw new Error('no index.html in DIST');
const API = { host: 'localhost', port: 8080 };
const PORT = 4300;
const BASE = `http://localhost:${PORT}`;
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.txt': 'text/plain' };

/** How the next refreshes are answered: pass, or hold/drop for `ms` (applies to `count` refreshes). */
let plan = { kind: 'pass', ms: 0, count: 0 };
let refreshes = [];

const server = http.createServer((req, res) => {
  if (!req.url.startsWith('/api/')) {
    const file = path.join(DIST, decodeURIComponent(req.url.split('?')[0]));
    const target = file.startsWith(DIST) && fs.existsSync(file) && fs.statSync(file).isFile() ? file : path.join(DIST, 'index.html');
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(target)] ?? 'application/octet-stream' });
    fs.createReadStream(target).pipe(res);
    return;
  }
  const headers = { ...req.headers, host: 'localhost:8080' };
  if (headers.origin) headers.origin = 'http://localhost:4200'; // an allowed origin for the CSRF guard
  const isRefresh = req.method === 'POST' && req.url.startsWith('/api/v1/auth/refresh');
  let mode = { kind: 'pass', ms: 0 };
  if (isRefresh && plan.count > 0) {
    mode = { kind: plan.kind, ms: plan.ms };
    plan.count -= 1;
  }
  const entry = isRefresh ? { at: Date.now(), mode: mode.kind, status: null, clientGone: false } : null;
  if (entry) refreshes.push(entry);
  res.on('close', () => { if (entry && !res.writableEnded) entry.clientGone = true; });
  const up = http.request({ ...API, path: req.url, method: req.method, headers }, (ur) => {
    const chunks = [];
    ur.on('data', (c) => chunks.push(c));
    ur.on('end', () => {
      const send = () => {
        if (entry) entry.status = ur.statusCode;
        if (mode.kind === 'drop') { req.socket.destroy(); return; }
        res.writeHead(ur.statusCode, ur.headers);
        res.end(Buffer.concat(chunks));
      };
      if (mode.kind === 'pass') send(); else setTimeout(send, mode.ms);
    });
  });
  up.on('error', () => res.destroy());
  req.pipe(up);
});
await new Promise((r) => server.listen(PORT, r));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(cond, ms, what) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (cond()) return; await sleep(25); }
  throw new Error(`timeout waiting for ${what}`);
}

/** A new customer, signed in through the API from the app's origin (cookies land in the context). */
async function signedInContext(browser) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${BASE}/__blank`);
  const phone = '+8801' + (3 + Math.floor(Math.random() * 7)) + String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
  const ok = await page.evaluate(async (phone) => {
    const post = (url, body) => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const reg = await post('/api/v1/auth/register', { fullName: 'Probe User', phone, password: 'probe-password-123' });
    const login = await post('/api/v1/auth/login', { identifier: phone, password: 'probe-password-123' });
    return reg.status === 201 && login.status === 200;
  }, phone);
  if (!ok) throw new Error('could not register/login');
  return { context, page };
}

/** Loads the app and reports whether it shows the signed-in header. */
async function signedIn(page) {
  await page.goto(`${BASE}/`);
  const button = page.getByRole('button', { name: 'Log out' });
  const link = page.getByRole('link', { name: 'Log in' });
  await Promise.race([button.waitFor({ timeout: 15000 }), link.waitFor({ timeout: 15000 })]).catch(() => {});
  return button.isVisible();
}

function newRefreshes(from) { return refreshes.slice(from).map((r) => `${r.mode}:${r.status ?? 'pending'}${r.clientGone ? '(page gone)' : ''}`); }

const scenarios = {
  // Control: nothing interrupted.
  async plainLoad(browser) {
    const { context, page } = await signedInContext(browser);
    const from = refreshes.length;
    const ok = await signedIn(page);
    await context.close();
    return { signedIn: ok, refreshes: newRefreshes(from) };
  },
  // QA-01: navigate 150 ms after the app's startup refresh was sent; its response is held 1.5 s.
  async navigationDuringRefresh(browser) {
    const { context, page } = await signedInContext(browser);
    const from = refreshes.length;
    plan = { kind: 'hold', ms: 1500, count: 1 };
    const t0 = Date.now();
    void page.goto(`${BASE}/`).catch(() => {});
    await waitFor(() => refreshes.length > from, 10000, 'first refresh');
    await sleep(150);
    const ok = await signedIn(page);
    const result = { signedIn: ok, refreshes: newRefreshes(from), ms: Date.now() - t0 };
    await context.close();
    return result;
  },
  // Three reloads 100 ms apart while the first refresh is held.
  async rapidReloads(browser) {
    const { context, page } = await signedInContext(browser);
    const from = refreshes.length;
    plan = { kind: 'hold', ms: 1500, count: 1 };
    void page.goto(`${BASE}/`).catch(() => {});
    await waitFor(() => refreshes.length > from, 10000, 'first refresh');
    for (let i = 0; i < 2; i++) { await sleep(100); void page.reload().catch(() => {}); }
    await sleep(100);
    const ok = await signedIn(page);
    await context.close();
    return { signedIn: ok, refreshes: newRefreshes(from) };
  },
  // The tab is closed while its refresh is held; a new tab opens the app.
  async tabClosed(browser) {
    const { context, page } = await signedInContext(browser);
    const from = refreshes.length;
    plan = { kind: 'hold', ms: 1500, count: 1 };
    void page.goto(`${BASE}/`).catch(() => {});
    await waitFor(() => refreshes.length > from, 10000, 'first refresh');
    await sleep(150);
    await page.close();
    const next = await context.newPage();
    const ok = await signedIn(next);
    await context.close();
    return { signedIn: ok, refreshes: newRefreshes(from) };
  },
  // Two tabs start at once: the Web Lock serializes their refreshes.
  async twoTabs(browser) {
    const { context, page } = await signedInContext(browser);
    const other = await context.newPage();
    const from = refreshes.length;
    const [a, b] = await Promise.all([signedIn(page), signedIn(other)]);
    await context.close();
    return { signedIn: a && b, refreshes: newRefreshes(from) };
  },
  // Known limit: the response is lost in the network. Expected to sign the user out.
  async responseDropped(browser) {
    const { context, page } = await signedInContext(browser);
    const from = refreshes.length;
    plan = { kind: 'drop', ms: 300, count: 1 };
    void page.goto(`${BASE}/`).catch(() => {});
    await waitFor(() => refreshes.length > from, 10000, 'first refresh');
    await sleep(150);
    const ok = await signedIn(page);
    await context.close();
    return { signedIn: ok, refreshes: newRefreshes(from), expected: 'signed out (ADR-0026)' };
  },
};

const browsers = [
  ['Chrome', () => chromium.launch({ channel: 'chrome', headless: true })],
  ['Firefox', () => firefox.launch({ headless: true })],
  ['WebKit', () => webkit.launch({ headless: true })],
];
// Optional filters: BROWSERS=Chrome,Firefox  SCENARIOS=plainLoad,tabClosed
const only = (env) => (process.env[env] ? process.env[env].split(',') : null);
const results = {};
for (const [name, launch] of browsers.filter(([n]) => !only('BROWSERS') || only('BROWSERS').includes(n))) {
  let browser;
  try { browser = await launch(); } catch (e) { results[name] = { unavailable: e.message.split('\n')[0] }; continue; }
  results[name] = { version: browser.version() };
  for (const [id, run] of Object.entries(scenarios).filter(([s]) => !only('SCENARIOS') || only('SCENARIOS').includes(s))) {
    plan = { kind: 'pass', ms: 0, count: 0 };
    const started = Date.now();
    try { results[name][id] = await run(browser); } catch (e) { results[name][id] = { error: e.message.split('\n')[0] }; }
    console.error(`[probe] ${name} ${id}: ${Date.now() - started} ms`);
    await sleep(2000); // let held responses finish before the next scenario
  }
  await browser.close();
}
console.log(JSON.stringify(results, null, 2));
server.close();
