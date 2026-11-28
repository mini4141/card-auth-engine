#!/usr/bin/env bash
# Phase 2: deploy the showcase to AWS (ap-southeast-2, profile card-auth-engine-deployer) from this PC, run the
# cloud benchmark + reconcile, capture proof into proof/phase2-cloud. Does NOT destroy: run scripts/destroy.sh after.
set -euo pipefail
cd "$(dirname "$0")/.."
export AWS_PROFILE=card-auth-engine-deployer
export AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1   # Git Bash must not rewrite /ecs/... style arguments
OUT=proof/phase2-cloud
TF="terraform -chdir=infra"
redact() { sed -E 's/[0-9]{12}/<ACCOUNT_ID>/g; s/(AKIA|ASIA)[A-Z0-9]{16}/<ACCESS_KEY>/g'; }
mkdir -p "$OUT/screenshots" "$OUT/logs" "$OUT/tests" .local
date -u +%Y-%m-%dT%H:%M:%SZ > .local/deploy-started-at

aws sts get-caller-identity >/dev/null || { echo "AWS identity check failed"; exit 1; }
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com"

echo "== build jars"
./gradlew :services:auth-service:bootJar :services:settlement-service:bootJar :services:notification-service:bootJar -q

echo "== terraform: ECR repositories first"
$TF init -input=false >/dev/null
$TF apply -input=false -auto-approve -target=aws_ecr_repository.svc >/dev/null

echo "== build + push images (linux/amd64)"
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
for svc in auth-service settlement-service notification-service; do
  image="$REGISTRY/card-auth/$svc:latest"
  docker build -q -f services/Dockerfile --build-arg "JAR=services/$svc/build/libs/$svc-0.1.0.jar" -t "$image" . >/dev/null
  docker push -q "$image" >/dev/null
  echo "pushed $svc"
done

echo "== terraform apply (VPC, RDS, ElastiCache, DynamoDB, MSK Serverless, ECS, ALB) - MSK takes a few minutes"
$TF apply -input=false -auto-approve 2>&1 | tail -5
ALB=$($TF output -raw alb_dns_name)
BASE="http://$ALB"
echo "ALB: $BASE"

echo "== wait for healthy auth-service + notification-service behind the ALB"
for i in $(seq 1 120); do
  a=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health" || true)
  n=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/dashboard/" || true)
  [ "$a" = "200" ] && [ "$n" = "200" ] && break
  echo "  auth=$a dashboard=$n ($i/120)"; sleep 10
done
[ "$a" = "200" ] || { echo "auth-service never became healthy"; exit 1; }
sleep 20

echo "== seed cards"
BASE_URL="$BASE" scripts/seed.sh

echo "== cloud benchmark (lighter: 300 concurrent, 50 req/s steady) through the ALB"
BASE_URL="$BASE" USERS=300 STEADY_RATE=50 STEADY_SECONDS=15 \
  scripts/bench-strategies.sh --out .local/cloud-bench --proof "$OUT/tests/gatling-cloud" || true
cp .local/cloud-bench/benchmarks.json "$OUT/logs/cloud-benchmarks.json"
cp .local/cloud-bench/benchmarks.md "$OUT/logs/cloud-benchmarks.md"

echo "== reconcile"
sleep 10
BASE_URL="$BASE" scripts/reconcile.sh --ignore-naive 2>&1 | tee "$OUT/logs/cloud-reconcile.txt" || true

echo "== dashboard screenshots (live traffic through the ALB)"
BASE_URL="$BASE" scripts/capture-proof.sh phase2-cloud

scripts/capture-cloud-evidence.sh "$BASE"
echo "deploy + proof done. ALB: $BASE   (now run scripts/destroy.sh)"
