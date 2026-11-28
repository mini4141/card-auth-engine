const [base, card, n, strategy] = [process.argv[2], process.argv[3], +process.argv[4], process.argv[5] || 'redis-lua'];
const t0 = Date.now();
const lat = [];
let approved = 0, failed = 0;
await Promise.all(Array.from({ length: n }, async () => {
  const s = Date.now();
  try {
    const r = strategy === 'health' ? await fetch(base + '/actuator/health') : await fetch(base + '/authorizations', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(), 'X-Strategy': strategy },
      body: JSON.stringify({ cardId: card, amount: 1000, merchant: 'x', mcc: '1' }) });
    const j = await r.json(); if (j.decision === 'APPROVED') approved++;
  } catch (e) { failed++; }
  lat.push(Date.now() - s);
}));
lat.sort((a, b) => a - b);
console.log(JSON.stringify({ approved, failed, wall_ms: Date.now() - t0, p50: lat[n >> 1], p99: lat[Math.floor(n * 0.99)] }));
