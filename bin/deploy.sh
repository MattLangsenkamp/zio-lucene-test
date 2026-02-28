#!/bin/bash
# deploy.sh <env> [k3d_cluster_name]
#
# Runs "pulumi up" for the given environment inside the infra/ directory.
#
#   env=local  -> sets local kubeconfig, detects LocalStack IP in the k3d
#                 network, then runs: pulumi stack select local &&
#                 LOCALSTACK_K3D_IP=<ip> pulumi up --yes --refresh
#   env=dev    -> pulumi stack select dev && pulumi up  (interactive confirm)
#   env=prod   -> pulumi stack select prod && pulumi up --yes
#
# Args:
#   $1  env              : local | dev | prod  (required)
#   $2  k3d_cluster_name : only used for env=local, defaults to "zio-lucene"

set -e

ENV="${1:?Usage: deploy.sh <env> [k3d_cluster_name]}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INFRA_DIR="$PROJECT_DIR/infra"

case "$ENV" in
  local)
    K3D_CLUSTER_NAME="${2:-zio-lucene}"
    echo "Deploying to local environment..."
    "$SCRIPT_DIR/kubeconfig.sh" local "$K3D_CLUSTER_NAME"

    LOCALSTACK_IP=$(docker inspect localstack \
      | python3 -c "import sys,json; d=json.load(sys.stdin)[0]; print(d['NetworkSettings']['Networks']['k3d-${K3D_CLUSTER_NAME}']['IPAddress'])" \
      2>/dev/null) || true

    if [ -n "$LOCALSTACK_IP" ]; then
      echo "LocalStack IP in k3d network: $LOCALSTACK_IP"
    else
      echo "⚠️  Could not detect LocalStack IP in k3d network — run './bin/start-local-env.sh' first"
    fi

    cd "$INFRA_DIR" && pulumi stack select local
    cd "$INFRA_DIR" && LOCALSTACK_K3D_IP="$LOCALSTACK_IP" pulumi up --yes --refresh
    ;;
  dev)
    echo "Deploying to dev environment..."
    cd "$INFRA_DIR" && pulumi stack select dev
    cd "$INFRA_DIR" && pulumi up
    ;;
  prod)
    echo "Deploying to prod environment..."
    cd "$INFRA_DIR" && pulumi stack select prod
    cd "$INFRA_DIR" && pulumi up --yes
    ;;
  *)
    echo "❌ Unknown env: ${ENV}. Must be one of: local, dev, prod"
    exit 1
    ;;
esac
