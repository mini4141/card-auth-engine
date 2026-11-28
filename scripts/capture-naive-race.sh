#!/usr/bin/env bash
# Reproduces the naive lost-update race at small scale and saves the interleaved READ/WRITE log lines of both
# auth-service instances, ordered by time. usage: capture-naive-race.sh <out-file>
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:?out file}"
BASE="${BASE_URL:-http://localhost:8080}"
COMPOSE="docker compose -f local/docker-compose.yml"
mkdir -p "$(dirname "$OUT")"

# enable DEBUG on the naive strategy, then restore
NAIVE_LOG_LEVEL=DEBUG $COMPOSE up -d --scale auth-service=2 --force-recreate auth-service >/dev/null 2>&1
for i in $(seq 1 60); do curl -fs "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 2; done
$COMPOSE exec -T nginx nginx -s reload >/dev/null 2>&1; sleep 6

card=$(curl -fsS -X POST "$BASE/cards" -H 'Content-Type: application/json' -d '{"holder":"Race","limit":5000}' | sed 's/.*"id":"\([^"]*\)".*/\1/' | tr -d '\r\n')
# 40 requests of 1000 yen at once against a 5000 yen card: only 5 may be approved
node scripts/proof/burst.mjs "$BASE" "$card" 40 naive >/dev/null
sleep 2
{
  echo "# naive strategy, card=$card limit=5000, 40 concurrent requests of 1000 (max 5 may be approved)"
  echo "# time (UTC)                 instance  event"
  $COMPOSE logs --no-color auth-service 2>&1 | grep -E "naive (READ|WRITE)" | grep "card=$card" | node -e '
      const lines = require("fs").readFileSync(0, "utf8").split("\n").filter(Boolean);
      const rows = [];
      for (const l of lines) { try { const j = JSON.parse(l.slice(l.indexOf("{"))); rows.push({ t: j["@timestamp"], who: l.slice(0, l.indexOf("|")).trim(), m: j.message }); } catch (e) {} }
      rows.sort((a, b) => a.t.localeCompare(b.t));
      for (const r of rows) console.log(r.t, r.who.padEnd(15), r.m);'
  echo
  echo "# result: card state after the burst (naive wrote stale balances)"
  curl -fsS "$BASE/admin/reconcile" | node -e '
    const r = JSON.parse(require("fs").readFileSync(0, "utf8")); const c = r.cards.find(x => x.cardId === process.argv[1]);
    console.log(JSON.stringify(c)); console.log("approved total (ledger+holds) =", c.dbLedger + c.redisLedger, "vs limit", c.limit);' "$card"
} > "$OUT"
$COMPOSE up -d --scale auth-service=2 --force-recreate auth-service >/dev/null 2>&1
for i in $(seq 1 60); do curl -fs "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 2; done
$COMPOSE exec -T nginx nginx -s reload >/dev/null 2>&1
echo "saved $OUT"
