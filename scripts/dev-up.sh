#!/usr/bin/env bash
# Build the jars and start the whole stack locally (auth-service x2 behind nginx).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :services:auth-service:bootJar :services:settlement-service:bootJar :services:notification-service:bootJar -q
docker compose -f local/docker-compose.yml up -d --build --scale auth-service=2
echo "waiting for http://localhost:8080/actuator/health ..."
for i in $(seq 1 90); do
  if curl -fs http://localhost:8080/actuator/health >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fs http://localhost:8080/actuator/health && echo
# nginx resolved the auth-service replicas at its start; reload so both are in the pool
docker compose -f local/docker-compose.yml exec -T nginx nginx -s reload >/dev/null 2>&1 || true
if [ "${SEED:-1}" = "1" ]; then scripts/seed.sh; fi
echo "dashboard: http://localhost:8080/dashboard/   grafana: http://localhost:3000   prometheus: http://localhost:9090"
