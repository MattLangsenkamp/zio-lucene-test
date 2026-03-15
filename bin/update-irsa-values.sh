#!/bin/bash
# update-irsa-values.sh
#
# Patches IRSA ARNs from SSM ParameterStore into values.{stack}.yaml for all three services.
# Called automatically by infra-up after a successful pulumi up on non-local stacks.
#
# Usage: ./bin/update-irsa-values.sh <stack>
#   stack — Pulumi stack name (dev | prod)

set -e

STACK="${1:?Usage: $0 <stack>}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

for svc in ingestion reader writer; do
  ARN=$(aws ssm get-parameter \
    --name "/zio-lucene/${STACK}/irsa/${svc}" \
    --query 'Parameter.Value' \
    --output text 2>/dev/null)
  if [ -n "$ARN" ]; then
    echo "Patching ${svc} IRSA ARN: ${ARN}"
    sed -i "s|irsaRoleArn:.*|irsaRoleArn: ${ARN}|" "${REPO_ROOT}/${svc}/k8s/values/values.${STACK}.yaml"
  else
    echo "WARNING: no SSM param found for /zio-lucene/${STACK}/irsa/${svc} — skipping"
  fi
done
