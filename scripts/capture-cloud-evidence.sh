#!/usr/bin/env bash
# Phase 2 evidence from the AWS side (CLI describe output, CloudWatch graph, DynamoDB audit query).
# usage: scripts/capture-cloud-evidence.sh http://<alb-dns>
set -uo pipefail
cd "$(dirname "$0")/.."
BASE="${1:?alb base url}"
export AWS_PROFILE=card-auth-engine-deployer
export AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
OUT=proof/phase2-cloud
TF="terraform -chdir=infra"
redact() { sed -E 's/[0-9]{12}/<ACCOUNT_ID>/g; s/(AKIA|ASIA)[A-Z0-9]{16}/<ACCESS_KEY>/g'; }
mkdir -p "$OUT/logs" "$OUT/screenshots"
echo "== DynamoDB audit log through the API (newest items of the most recent cloud card)"
card=$(curl -fsS "$BASE/cards" | node -e 'const c=JSON.parse(require("fs").readFileSync(0,"utf8")); const x=c.filter(k=>k.holder.startsWith("Proof")||k.holder.startsWith("Alice")).pop()||c[c.length-1]; console.log(x.id)' | tr -d '
')
curl -fsS "$BASE/cards/$card/authorizations?limit=10" | redact > "$OUT/logs/cloud-dynamodb-auth-log-query.json" || true
aws dynamodb scan --table-name auth_log --select COUNT --query '{scannedItems:Count}' > "$OUT/logs/cloud-dynamodb-scan-count.json" 2>&1 || true

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
  aws cloudwatch get-metric-widget-image --metric-widget file://.local/cw-widget.json --output text --query MetricWidgetImage | tr -d "
" | base64 -d > "$OUT/screenshots/02-cloudwatch-latency.png" \
    || echo "metric widget image failed"
fi
aws logs tail /ecs/card-auth/auth-service --since 30m --format short 2>/dev/null | grep -E "authorisation|reconciliation" | tail -30 | redact > "$OUT/logs/cloud-auth-service-sample.log" || true
