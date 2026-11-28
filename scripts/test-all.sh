#!/usr/bin/env bash
# Runs every local test layer and exits non-zero on failure:
#   1. JUnit unit tests (Lua semantics, strategy selection, settlement posting)
#   2. Testcontainers integration + the 2-instance concurrency correctness test
#   3. Gatling simulation (with assertions) against the compose stack, redis-lua safe strategy
# Output (JUnit XML, Gatling HTML, logs) is copied to proof/<phase>/tests. PHASE defaults to phase1-local.
set -uo pipefail
cd "$(dirname "$0")/.."
PHASE="${PHASE:-phase1-local}"
OUT="proof/$PHASE/tests"
mkdir -p "$OUT/junit" "$OUT/logs"
fail=0

if [ "${ONLY_GATLING:-0}" != "1" ]; then
  echo "== 1+2: gradle test (unit + Testcontainers + concurrency correctness)"
  ./gradlew clean test --continue 2>&1 | tee "$OUT/logs/gradle-test.log" || fail=1
  rm -rf "$OUT/junit"; mkdir -p "$OUT/junit"
  for d in services/auth-service services/settlement-service integration-tests; do
    name=$(basename "$d")
    mkdir -p "$OUT/junit/$name"
    cp "$d"/build/test-results/test/*.xml "$OUT/junit/$name/" 2>/dev/null || true
  done
fi

if [ "${SKIP_GATLING:-0}" = "1" ]; then
  echo "== 3: gatling skipped (SKIP_GATLING=1)"
else
  echo "== 3: gatling simulation against the compose stack"
  if ! curl -fs http://localhost:8080/actuator/health >/dev/null 2>&1; then SEED=0 scripts/dev-up.sh || fail=1; fi
  card=$(curl -fsS -X POST http://localhost:8080/cards -H 'Content-Type: application/json' -d '{"holder":"gatling-test","limit":100000}' \
         | sed 's/.*"id":"\([^"]*\)".*/\1/' | tr -d '\r\n')
  BASE_URL="${BASE_URL:-http://localhost:8080}" STRATEGY=redis-lua CARD_ID="$card" USERS=1000 AMOUNT=1000 LIMIT=100000 \
    RESULT_FILE="$PWD/.local/gatling-test.json" \
    ./gradlew :loadtest:gatling:gatlingRun --simulation=cardauth.AuthBurstSimulation 2>&1 | grep -vE "DEBUG" | tee "$OUT/logs/gatling.log" | tail -5 || fail=1
  latest=$(ls -1t loadtest/gatling/build/reports/gatling | head -1)
  rm -rf "$OUT/gatling"; cp -r "loadtest/gatling/build/reports/gatling/$latest" "$OUT/gatling"
  # the safe strategy must approve exactly limit/amount and overspend nothing
  node -e '
    const r = JSON.parse(require("fs").readFileSync(".local/gatling-test.json", "utf8"));
    if (r.approved_count !== 100 || r.overspend_amount !== 0) { console.error("correctness FAILED", r); process.exit(1); }
    console.log("gatling correctness OK", r);' || fail=1
fi

if [ $fail -ne 0 ]; then echo "TESTS FAILED"; exit 1; fi
echo "ALL TESTS GREEN"
