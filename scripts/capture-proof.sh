#!/usr/bin/env bash
# Captures proof artefacts: scripts/capture-proof.sh phase1-local | phase2-cloud
# phase1-local needs the compose stack up (scripts/dev-up.sh) and a prior scripts/bench-strategies.sh run.
# phase2-cloud needs the deployed stack (scripts/deploy.sh calls this).
set -euo pipefail
cd "$(dirname "$0")/.."
PHASE="${1:?usage: capture-proof.sh phase1-local|phase2-cloud}"
OUT="proof/$PHASE"
mkdir -p "$OUT/screenshots" "$OUT/logs"
redact() { sed -E 's/[0-9]{12}/<ACCOUNT_ID>/g; s/(AKIA|ASIA)[A-Z0-9]{16}/<ACCESS_KEY>/g'; }

(cd scripts/proof && { [ -d node_modules ] || npm install --silent; npx playwright install chromium >/dev/null 2>&1 || true; })

if [ "$PHASE" = "phase1-local" ]; then
  BASE="${BASE_URL:-http://localhost:8080}"
  export MSYS_NO_PATHCONV=1
  COMPOSE="docker compose -f local/docker-compose.yml"

  # a fresh redis-lua card: authorise, then show the Kafka stream, the DynamoDB log item and the reconcile result
  card=$(curl -fsS -X POST "$BASE/cards" -H 'Content-Type: application/json' -d '{"holder":"Proof Card","limit":50000}' | sed 's/.*"id":"\([^"]*\)".*/\1/' | tr -d '\r\n')
  for amt in 12000 9000 30000 5000; do
    curl -fsS -X POST "$BASE/authorizations" -H 'Content-Type: application/json' -H "Idempotency-Key: proof-$amt-$card" -H 'X-Strategy: redis-lua' \
      -d "{\"cardId\":\"$card\",\"amount\":$amt,\"merchant\":\"ProofMart\",\"mcc\":\"5411\"}" >/dev/null
  done
  sleep 5
  curl -fsS "$BASE/cards/$card/authorizations?limit=10" | node -e 'console.log(JSON.stringify(JSON.parse(require("fs").readFileSync(0,"utf8")),null,2))' > "$OUT/logs/dynamodb-auth-log-query.json"
  { echo "# kafka-console-consumer on card.authorizations (key = cardId), first 8 records read"
    $COMPOSE exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic card.authorizations \
      --from-beginning --max-messages 8 --property print.key=true --property print.partition=true --timeout-ms 15000 2>&1 | tail -10 || true
    echo; echo "# topic partitions"
    $COMPOSE exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic card.authorizations 2>&1 | head -3 || true
  } > "$OUT/logs/kafka-consume.txt"
  scripts/reconcile.sh --ignore-naive > "$OUT/logs/reconcile.txt" 2>&1 || true
  curl -fsS "$BASE/admin/reconcile" > "$OUT/logs/reconcile.json"
  $COMPOSE logs --no-color settlement-service 2>&1 | grep -E "posted ledger|batch" | tail -5 > "$OUT/logs/settlement-ledger-posts.txt" || true

  # the naive race: interleaved READ/WRITE lines from both instances (auth-service logs NaiveStrategy at DEBUG)
  scripts/capture-naive-race.sh "$OUT/logs/naive-race-interleaving.log"

  node scripts/proof/capture.mjs local "$BASE" "$OUT/screenshots"
else
  BASE="${BASE_URL:?set BASE_URL to the ALB url}"
  node scripts/proof/capture.mjs cloud "$BASE" "$OUT/screenshots"
fi
for f in "$OUT"/logs/*.txt "$OUT"/logs/*.json "$OUT"/logs/*.log; do [ -f "$f" ] && redact < "$f" > "$f.tmp" && mv "$f.tmp" "$f"; done
echo "proof captured in $OUT"
