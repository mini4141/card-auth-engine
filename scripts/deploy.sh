#!/usr/bin/env bash
# Phase 2: deploy the showcase to AWS (ap-southeast-2, profile card-auth-engine-deployer) from this PC, run the
# cloud benchmark + reconcile, capture proof into proof/phase2-cloud. Does NOT destroy: run scripts/destroy.sh after.
set -euo pipefail
cd "$(dirname "$0")/.."
export AWS_PROFILE=card-auth-engine-deployer
export AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
export AWS_PAGER=""
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

echo "== AWS-side evidence"
$TF output -json | redact > "$OUT/logs/terraform-outputs.json"
MSK_ARN=$($TF output -raw msk_cluster_arn)
aws kafka describe-cluster-v2 --cluster-arn "$MSK_ARN" | redact > "$OUT/logs/msk-describe.json"
{
  echo "# Deployed resources ($(date -u +%Y-%m-%dT%H:%M:%SZ)), region $AWS_REGION"
  echo; echo "## ECS services"
  aws ecs describe-services --cluster card-auth-cluster --services auth-service settlement-service notification-service \
    --query 'services[].{service:serviceName,desired:desiredCount,running:runningCount,status:status,launchType:launchType}' --output table
  echo; echo "## Load balancer"
  aws elbv2 describe-load-balancers --names card-auth-alb --query 'LoadBalancers[].{name:LoadBalancerName,type:Type,scheme:Scheme,state:State.Code,dns:DNSName}' --output table
  echo; echo "## RDS"
  aws rds describe-db-instances --db-instance-identifier card-auth-postgres --query 'DBInstances[].{id:DBInstanceIdentifier,engine:Engine,version:EngineVersion,class:DBInstanceClass,status:DBInstanceStatus,public:PubliclyAccessible,multiAz:MultiAZ}' --output table
  echo; echo "## ElastiCache"
  aws elasticache describe-cache-clusters --cache-cluster-id card-auth-redis --query 'CacheClusters[].{id:CacheClusterId,engine:Engine,version:EngineVersion,node:CacheNodeType,status:CacheClusterStatus}' --output table
  echo; echo "## DynamoDB"
  aws dynamodb describe-table --table-name auth_log --query 'Table.{name:TableName,status:TableStatus,billing:BillingModeSummary.BillingMode,items:ItemCount,keys:KeySchema}' --output json
  echo; echo "## MSK Serverless"
  aws kafka list-clusters-v2 --query 'ClusterInfoList[?contains(ClusterName,`card-auth`)].{name:ClusterName,type:ClusterType,state:State}' --output table
  echo; echo "## ECR"
  aws ecr describe-repositories --query 'repositories[?starts_with(repositoryName,`card-auth/`)].repositoryName' --output table
  echo; echo "## Log groups"
  aws logs describe-log-groups --log-group-name-prefix /ecs/card-auth --query 'logGroups[].{name:logGroupName,retentionDays:retentionInDays}' --output table
  echo; echo "## Tagged resources (Project=card-auth-engine)"
  aws resourcegroupstaggingapi get-resources --tag-filters Key=Project,Values=card-auth-engine --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n'
} 2>&1 | redact > "$OUT/resources.txt"

echo "== CloudWatch latency graph"
START=$(cat .local/deploy-started-at)
aws cloudwatch list-metrics --namespace card-auth-engine --metric-name auth.latency.max --output json > .local/cw-metrics.json || true
node scripts/proof/cloudwatch-widget.mjs .local/cw-metrics.json "$START" > .local/cw-widget.json || true
if [ -s .local/cw-widget.json ]; then
  aws cloudwatch get-metric-widget-image --metric-widget file://.local/cw-widget.json --output text --query MetricWidgetImage | base64 -d > "$OUT/screenshots/02-cloudwatch-latency.png" \
    || echo "metric widget image failed"
fi
aws logs tail /ecs/card-auth/auth-service --since 30m --format short 2>/dev/null | grep -E "authorisation|reconciliation" | tail -30 | redact > "$OUT/logs/cloud-auth-service-sample.log" || true
echo "deploy + proof done. ALB: $BASE   (now run scripts/destroy.sh)"
