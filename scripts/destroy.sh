#!/usr/bin/env bash
# Phase 3: tear everything down. Idempotent and safe to re-run. Order:
#   scale ECS services to 0 -> empty ECR repos -> terraform destroy (MSK Serverless deletion takes minutes) -> verify
set -uo pipefail
cd "$(dirname "$0")/.."
export AWS_PROFILE=card-auth-engine-deployer
export AWS_REGION=ap-southeast-2 AWS_DEFAULT_REGION=ap-southeast-2
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1
TF="terraform -chdir=infra"

echo "== scale ECS services to 0 (so nothing blocks the destroy)"
if aws ecs describe-clusters --clusters card-auth-cluster --query 'clusters[?status==`ACTIVE`].clusterName' --output text | grep -q card-auth-cluster; then
  for svc in $(aws ecs list-services --cluster card-auth-cluster --query 'serviceArns[]' --output text); do
    aws ecs update-service --cluster card-auth-cluster --service "$svc" --desired-count 0 >/dev/null && echo "scaled $svc to 0"
  done
fi

echo "== empty ECR repositories"
for repo in $(aws ecr describe-repositories --query 'repositories[?starts_with(repositoryName,`card-auth/`)].repositoryName' --output text); do
  ids=$(aws ecr list-images --repository-name "$repo" --query 'imageIds[]' --output json)
  if [ "$ids" != "[]" ]; then aws ecr batch-delete-image --repository-name "$repo" --image-ids "$ids" >/dev/null && echo "emptied $repo"; fi
done

echo "== terraform destroy"
$TF init -input=false >/dev/null 2>&1 || true
for attempt in 1 2 3; do
  if $TF destroy -input=false -auto-approve; then break; fi
  echo "destroy attempt $attempt failed, retrying in 30s"; sleep 30
done

echo "== deregister leftover ECS task definition revisions (free, but keep the account tidy)"
for td in $(aws ecs list-task-definitions --family-prefix card-auth- --status ACTIVE --query 'taskDefinitionArns[]' --output text); do
  aws ecs deregister-task-definition --task-definition "$td" >/dev/null && echo "deregistered $td"
done

echo "== verify"
scripts/verify-destroyed.sh
