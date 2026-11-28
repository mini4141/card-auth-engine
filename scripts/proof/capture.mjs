// Headless screenshots for the proof folder. Usage:
//   node capture.mjs local  <baseUrl> <outDir>
//   node capture.mjs cloud  <baseUrl> <outDir>
// Drives live traffic at the system while the dashboard is open so the screenshots show real data flowing.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const [mode, base, outDir] = process.argv.slice(2);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
fs.mkdirSync(outDir, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function post(path, body, headers = {}) {
  const r = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  return r.json();
}

async function freshCard(holder, limit) {
  return (await post('/cards', { holder, limit })).id;
}

// ~rate authorisations/s for `seconds`, random amounts, on the given cards
async function traffic(cards, strategy, rate, seconds) {
  const end = Date.now() + seconds * 1000;
  const jobs = [];
  while (Date.now() < end) {
    for (let i = 0; i < rate / 10; i++) {
      const cardId = cards[Math.floor(Math.random() * cards.length)];
      jobs.push(post('/authorizations', { cardId, amount: 200 + Math.floor(Math.random() * 1800), merchant: 'DemoMart', mcc: '5411' },
        { 'Idempotency-Key': crypto.randomUUID(), 'X-Strategy': strategy }).catch(() => {}));
    }
    await sleep(100);
  }
  await Promise.all(jobs);
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
const shot = async (name, target = page) => { await target.screenshot({ path: path.join(outDir, name) }); console.log('saved', name); };

// demo cards with limits that visibly drain under the traffic below
const demo = [await freshCard('Alice Tanaka', 150000), await freshCard('Bob Suzuki', 400000), await freshCard('Carol Sato', 900000)];

await page.goto(base + '/dashboard/', { waitUntil: 'load' });
const t = traffic(demo, 'redis-lua', 40, 14);
await sleep(9000);
await shot(mode === 'local' ? '01-dashboard-live.png' : '01-cloud-dashboard-live.png');
if (mode === 'local') {
  await shot('02-card-gauges.png', page.locator('#gauges').locator('xpath=..'));
  await shot('03-benchmark-table.png', page.locator('#bench').locator('xpath=..'));
}
await t;

if (mode === 'local') {
  // feed Grafana with every strategy so latency-by-strategy has one line per strategy
  for (const s of ['db-pessimistic', 'db-optimistic', 'redis-lock', 'redis-lua', 'naive']) {
    const cards = [await freshCard('grafana-' + s, 1_000_000_000)];
    await traffic(cards, s, 60, 12);
  }
  await sleep(12000);
  await page.goto('http://localhost:3000/d/auth/card-auth-engine?orgId=1&kiosk&from=now-15m&to=now', { waitUntil: 'load' });
  await sleep(4000);
  await shot('04-grafana-latency-by-strategy.png');
  const gatling = pathToFileURL(path.join(root, 'proof', 'phase1-local', 'tests', 'gatling-redis-lua', 'index.html')).href;
  await page.goto(gatling, { waitUntil: 'load' });
  await sleep(1500);
  await page.screenshot({ path: path.join(outDir, '05-gatling-redis-lua.png'), fullPage: true });
  console.log('saved 05-gatling-redis-lua.png');
}
await browser.close();
