#!/usr/bin/env bash
# For every strategy: (1) a burst of USERS concurrent auths of AMOUNT yen against one LIMIT yen card, and
# (2) a steady-rate run. Writes docs/benchmarks.md + docs/benchmarks.json.
#   scripts/bench-strategies.sh [--vt-compare] [--out DIR] [--proof DIR] [--strategies "a b"]
# env: BASE_URL USERS AMOUNT LIMIT STEADY_RATE STEADY_SECONDS
set -uo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERS="${USERS:-1000}"
AMOUNT="${AMOUNT:-1000}"
LIMIT="${LIMIT:-100000}"
export STEADY_RATE="${STEADY_RATE:-100}"
export STEADY_SECONDS="${STEADY_SECONDS:-20}"
OUT="docs"
PROOF="proof/phase1-local/tests"
VT_COMPARE=0
STRATEGIES="naive db-pessimistic db-optimistic redis-lock redis-lua"
while [ $# -gt 0 ]; do
  case "$1" in
    --vt-compare) VT_COMPARE=1 ;;
    --out) OUT="$2"; shift ;;
    --proof) PROOF="$2"; shift ;;
    --strategies) STRATEGIES="$2"; shift ;;
  esac
  shift
done

for i in $(seq 1 60); do curl -fs "$BASE_URL/actuator/health" >/dev/null 2>&1 && break; sleep 2; done

WORK=".local/bench"
rm -rf "$WORK"; mkdir -p "$WORK" "$OUT" "$PROOF"

# run_one <strategy> <label> [burst|steady]; steady uses RATE/DURATION and a limit that never binds
run_one() {
  local strategy="$1" label="$2" mode="${3:-burst}" card
  local amount="$AMOUNT" limit="$LIMIT"
  if [ "$mode" = "steady" ]; then amount=10; limit=1000000000; fi
  card=$(curl -fsS -X POST "$BASE_URL/cards" -H 'Content-Type: application/json' \
         -d "{\"holder\":\"bench-$label\",\"limit\":$limit}" | sed 's/.*"id":"\([^"]*\)".*/\1/' | tr -d '\r\n')
  echo "== $label ($mode): card $card"
  BASE_URL="$BASE_URL" STRATEGY="$strategy" CARD_ID="$card" USERS="$USERS" AMOUNT="$amount" LIMIT="$limit" \
    MODE="$mode" RATE="$STEADY_RATE" DURATION="$STEADY_SECONDS" \
    RESULT_FILE="$PWD/$WORK/$label.json" ./gradlew :loadtest:gatling:gatlingRun --simulation=cardauth.AuthBurstSimulation \
    > "$WORK/$label.log" 2>&1 || echo "   (gatling assertions failed for $label, see $WORK/$label.log)"
  local report; report=$(ls -1t loadtest/gatling/build/reports/gatling | head -1)
  rm -rf "$PROOF/gatling-$label"; cp -r "loadtest/gatling/build/reports/gatling/$report" "$PROOF/gatling-$label"
  echo "{\"label\":\"$label\",\"report\":\"$PROOF/gatling-$label\",\"card\":\"$card\"}" > "$WORK/$label.meta.json"
  cat "$WORK/$label.json"; echo
  sleep 3
}

restart_auth() { # restart_auth: recreate both auth instances (env VIRTUAL_THREADS honoured) and wait until healthy
  docker compose -f local/docker-compose.yml up -d --scale auth-service=2 --force-recreate auth-service >/dev/null 2>&1
  sleep 5
  for i in $(seq 1 60); do curl -fs "$BASE_URL/actuator/health" >/dev/null 2>&1 && break; sleep 2; done
  docker compose -f local/docker-compose.yml exec -T nginx nginx -s reload >/dev/null 2>&1
  sleep 8
}

for s in $STRATEGIES; do run_one "$s" "$s"; done
for s in $STRATEGIES; do run_one "$s" "steady-$s" steady; done

if [ "$VT_COMPARE" = "1" ]; then
  echo "== virtual threads OFF vs ON for db-pessimistic"
  export VIRTUAL_THREADS=false
  restart_auth
  run_one db-pessimistic db-pessimistic-platform-threads
  unset VIRTUAL_THREADS
  restart_auth
fi

node scripts/bench-report.mjs "$WORK" "$OUT" "$USERS" "$AMOUNT" "$LIMIT"
