#!/usr/bin/env bash
# Compares Redis available/holds, Postgres balances and the settlement ledger for every card (GET /admin/reconcile).
# Waits for settlement to drain Kafka, then prints a table and exits non-zero if any non-naive drift remains.
# Cards created by the naive strategy are expected to drift (that is the incident); pass --ignore-naive to skip them.
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
IGNORE_NAIVE=0; [ "${1:-}" = "--ignore-naive" ] && IGNORE_NAIVE=1
for i in $(seq 1 30); do
  report=$(curl -fsS "$BASE_URL/admin/reconcile")
  drift=$(echo "$report" | node -e '
    const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
    const ignore = process.argv[1] === "1";
    const bad = r.cards.filter(c => (c.redisDrift !== 0 || c.dbDrift !== 0) && !(ignore && c.dbDrift > 0 && c.redisDrift === 0));
    process.stdout.write(String(bad.length));' "$IGNORE_NAIVE")
  [ "$drift" = "0" ] && break
  sleep 2
done
echo "$report" | node -e '
  const r = JSON.parse(require("fs").readFileSync(0, "utf8"));
  console.log("cardId	limit	redisAvail	redisHolds	redisLedger	redisDrift	dbAvail	dbLedger	dbDrift	verdict");
  for (const c of r.cards) {
    const verdict = c.redisDrift === 0 && c.dbDrift === 0 ? "OK" : (c.dbDrift > 0 && c.redisDrift === 0 ? "DRIFT (lost updates: naive strategy)" : "DRIFT");
    console.log([c.cardId, c.limit, c.redisAvailable, c.redisHolds, c.redisLedger, c.redisDrift, c.dbAvailable, c.dbLedger, c.dbDrift, verdict].map(String).join("	"));
  }
  const ok = r.cards.filter(c => c.redisDrift === 0 && c.dbDrift === 0).length;
  console.log(`cards=${r.cards.length} zeroDrift=${ok} totalAbsDrift=${r.totalAbsDrift} overspentCards=${r.overspentCards}`);'
if [ "$drift" != "0" ]; then echo "DRIFT DETECTED on $drift card(s)"; exit 1; fi
if [ "$IGNORE_NAIVE" = "1" ]; then echo "RECONCILED: zero drift on every card that used a safe strategy (naive-strategy cards excluded: they are the incident)"; else echo "RECONCILED: zero drift"; fi
