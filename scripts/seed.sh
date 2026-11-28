#!/usr/bin/env bash
# Loads demo cards. BASE_URL defaults to the local nginx. Card ids are written to .local/seed-cards.json
set -euo pipefail
cd "$(dirname "$0")/.."
BASE_URL="${BASE_URL:-http://localhost:8080}"
mkdir -p .local
out_file=".local/seed-cards.json"
: > "$out_file"
items=()
for spec in "Alice Tanaka:100000" "Bob Suzuki:500000" "Carol Sato:1000000"; do
  holder="${spec%%:*}"; limit="${spec##*:}"
  out=$(curl -fsS -X POST "$BASE_URL/cards" -H 'Content-Type: application/json' -d "{\"holder\":\"$holder\",\"limit\":$limit}")
  items+=("$out")
  echo "seeded: $out"
done
( IFS=,; echo "[${items[*]}]" ) > "$out_file"
