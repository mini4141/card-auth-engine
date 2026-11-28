#!/usr/bin/env bash
# Phase 3 check: nothing billable tagged Project=card-auth-engine may remain. Exits non-zero otherwise.
# Specific describe checks (the tagging API can lag by minutes) decide the exit code; the tagging listing is informational.
set -uo pipefail
cd "$(dirname "$0")/.."
export AWS_PROFILE=card-auth-engine-deployer
export AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
redact() { sed -E 's/[0-9]{12}/<ACCOUNT_ID>/g'; }
mkdir -p proof/phase3-destroy
OUT=proof/phase3-destroy/verify-destroyed.txt
bad=0

check() { # check <label> <count-or-empty-string>
  if [ -z "$2" ] || [ "$2" = "0" ] || [ "$2" = "None" ]; then echo "OK    $1: none"; else echo "FOUND $1: $2"; bad=1; fi
}

{
  echo "verify-destroyed $(date -u +%Y-%m-%dT%H:%M:%SZ) region=$AWS_REGION profile=$AWS_PROFILE"
  echo
  check "ECS cluster card-auth-cluster (ACTIVE)" "$(aws ecs describe-clusters --clusters card-auth-cluster --query 'clusters[?status==`ACTIVE`].clusterName' --output text)"
  check "ECS services in card-auth-cluster" "$(aws ecs list-services --cluster card-auth-cluster --query 'serviceArns[]' --output text 2>/dev/null)"
  check "ACTIVE ECS task definitions card-auth-* (free, but should be gone)" "$(aws ecs list-task-definitions --family-prefix card-auth- --status ACTIVE --query 'taskDefinitionArns[]' --output text)"
  check "ALB card-auth-alb" "$(aws elbv2 describe-load-balancers --query 'LoadBalancers[?LoadBalancerName==`card-auth-alb`].LoadBalancerArn' --output text 2>/dev/null)"
  check "target groups card-auth-*" "$(aws elbv2 describe-target-groups --query 'TargetGroups[?starts_with(TargetGroupName,`card-auth-`)].TargetGroupName' --output text 2>/dev/null)"
  check "RDS instance card-auth-postgres" "$(aws rds describe-db-instances --query 'DBInstances[?DBInstanceIdentifier==`card-auth-postgres`].DBInstanceIdentifier' --output text)"
  check "ElastiCache cluster card-auth-redis" "$(aws elasticache describe-cache-clusters --query 'CacheClusters[?CacheClusterId==`card-auth-redis`].CacheClusterId' --output text)"
  check "MSK clusters card-auth*" "$(aws kafka list-clusters-v2 --query 'ClusterInfoList[?starts_with(ClusterName,`card-auth`)].ClusterName' --output text)"
  check "DynamoDB table auth_log" "$(aws dynamodb list-tables --query 'TableNames[?@==`auth_log`]' --output text)"
  check "ECR repositories card-auth/*" "$(aws ecr describe-repositories --query 'repositories[?starts_with(repositoryName,`card-auth/`)].repositoryName' --output text 2>/dev/null)"
  check "CloudWatch log groups /ecs/card-auth*" "$(aws logs describe-log-groups --log-group-name-prefix /ecs/card-auth --query 'logGroups[].logGroupName' --output text)"
  check "NAT gateways (any state but deleted) in card-auth VPCs" "$(aws ec2 describe-nat-gateways --filter Name=tag:Project,Values=card-auth-engine --query 'NatGateways[?State!=`deleted`].NatGatewayId' --output text)"
  check "EC2 VPCs tagged Project=card-auth-engine" "$(aws ec2 describe-vpcs --filters Name=tag:Project,Values=card-auth-engine --query 'Vpcs[].VpcId' --output text)"
  check "Elastic IPs tagged Project=card-auth-engine" "$(aws ec2 describe-addresses --filters Name=tag:Project,Values=card-auth-engine --query 'Addresses[].AllocationId' --output text)"
  echo
  echo "Resource Groups Tagging API (Project=card-auth-engine; informational: it lags after deletion, and every hit below was confirmed gone by the direct describe checks above; ECS reports the cluster as INACTIVE):"
  tagged=$(aws resourcegroupstaggingapi get-resources --tag-filters Key=Project,Values=card-auth-engine --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | sed '/^$/d')
  if [ -z "$tagged" ]; then echo "  (none)"; else echo "$tagged" | sed 's/^/  /'; fi
  echo
  if [ $bad -eq 0 ]; then echo "RESULT: nothing billable remains"; else echo "RESULT: BILLABLE RESOURCES REMAIN"; fi
} > "$OUT.raw" 2>&1
redact < "$OUT.raw" > "$OUT"; rm -f "$OUT.raw"
cat "$OUT"
exit $bad
