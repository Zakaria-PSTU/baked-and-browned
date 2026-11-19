// Drives the online payment flow against the fake gateway's dev page. See SKILL.md.
// Usage: node online-payment.mjs [all|success|fail|lost]   (screenshots go to $SHOTS_DIR or ./shots)
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from 'playwright-core';

const BASE = process.env.BASE_URL ?? 'http://localhost:4200';
const OUT = process.env.SHOTS_DIR ?? join(process.cwd(), 'shots');
mkdirSync(OUT, { recursive: true });
const scenario = process.argv[2] ?? 'all';

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await (await browser.newContext({ viewport: { width: 1100, height: 900 } })).newPage();
page.on('console', (m) => m.type() === 'error' && console.log('  [console]', m.text()));
const shot = async (name) => {
  await page.screenshot({ path: join(OUT, `${name}.png`), fullPage: true });
  console.log('  shot', name, '@', page.url());
};

async function register() {
  await page.goto(`${BASE}/register`);
  await page.fill('#fullName', 'Rahim Test');
  await page.fill('#phone', '01711' + String(Date.now()).slice(-6));
  await page.fill('#email', `rahim${Date.now()}@example.com`);
  await page.fill('#password', 'brownies-are-great');
  await page.fill('#confirmPassword', 'brownies-are-great');
  await page.click('button[type=submit]');
  await page.waitForURL((u) => !u.pathname.startsWith('/register'), { timeout: 15000 });
  console.log('registered, now at', page.url());
}

async function placeOnlineOrder() {
  await page.goto(`${BASE}/products/fudge-box`);
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await page.goto(`${BASE}/checkout`);
  await page.fill('#address', 'House 1, Road 2, Dhanmondi');
  await page.selectOption('#area', 'DHANMONDI');
  await page.selectOption('#date', { index: 2 });
  await page.locator('input[name=slot]').first().check();
  await page.getByLabel(/Card or mobile wallet/).check();
  await page.getByRole('button', { name: 'Review total' }).click();
  await page.getByTestId('quote').waitFor();
  await shot('checkout-quote');
  await page.getByRole('button', { name: /Place order · pay .* online/ }).click();
  await page.waitForURL(/\/dev\/fake-gateway\?session=/, { timeout: 15000 });
  await page.getByTestId('pay').waitFor();
}

async function run(name, finish) {
  console.log(`== ${name}`);
  await placeOnlineOrder();
  await shot(`${name}-1-gateway`);
  await finish();
  await page.waitForURL(/\/orders\/[0-9a-f-]+(\/payment)?$/, { timeout: 15000 });
  await shot(`${name}-2-returned`);
}

await register();

if (scenario === 'all' || scenario === 'success') {
  await run('success', () => page.getByTestId('pay').click());
  await page.waitForURL(/\/orders\/[0-9a-f-]+$/, { timeout: 20000 });
  await page.getByTestId('paid-online').waitFor({ timeout: 10000 });
  await shot('success-3-order');
}

if (scenario === 'all' || scenario === 'fail') {
  await run('fail', () => page.getByTestId('fail').click());
  await page.waitForURL(/\/orders\/[0-9a-f-]+$/, { timeout: 20000 });
  await page.getByTestId('attempt-failed').waitFor({ timeout: 10000 });
  await shot('fail-3-order');
  // Retry from the order page and pay this time.
  await page.getByTestId('pay-online').click();
  await page.waitForURL(/\/dev\/fake-gateway\?session=/, { timeout: 15000 });
  await page.getByTestId('pay').click();
  await page.waitForURL(/\/orders\/[0-9a-f-]+$/, { timeout: 30000 });
  await page.getByTestId('paid-online').waitFor({ timeout: 10000 });
  await shot('fail-4-retried-and-paid');
}

if (scenario === 'all' || scenario === 'lost') {
  await run('lost', async () => {
    await page.getByTestId('notify-shop').uncheck();
    await page.getByTestId('pay').click();
  });
  await page.getByTestId('slow').waitFor({ timeout: 90000 });
  await shot('lost-3-slow');
  await page.getByTestId('view-order').click();
  await page.getByTestId('order-status').waitFor();
  console.log('  countdown after ~1 min:', await page.getByTestId('time-left').textContent());
  await shot('lost-3b-order-unpaid');
  const deadline = Date.now() + 5 * 60_000;
  while (Date.now() < deadline) {
    await page.waitForTimeout(15000);
    await page.reload();
    await page.getByTestId('order-status').waitFor();
    if (await page.getByTestId('paid-online').count()) break;
  }
  await shot('lost-4-after-reconciliation');
  console.log('  paid after reconciliation:', (await page.getByTestId('paid-online').count()) > 0);
}

await browser.close();
