#!/bin/bash
# destroy.sh <env> [k3d_cluster_name]
#
# Runs "pulumi destroy" for the given environment inside the infra/ directory.
#
#   env=local  -> switches to local k3d kubeconfig first, then:
#                 pulumi stack select local && pulumi destroy
#   env=dev    -> pulumi stack select dev && pulumi destroy
#   env=prod   -> pulumi stack select prod && pulumi destroy
#
# Args:
#   $1  env              : local | dev | prod  (required)
#   $2  k3d_cluster_name : only used for env=local, defaults to "zio-lucene"

set -e

ENV="${1:?Usage: destroy.sh <env> [k3d_cluster_name]}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INFRA_DIR="$PROJECT_DIR/infra"

case "$ENV" in
  local)
    K3D_CLUSTER_NAME="${2:-zio-lucene}"
    echo "Destroying local stack..."
    kubectl config use-context "k3d-${K3D_CLUSTER_NAME}" 2>/dev/null || true
    cd "$INFRA_DIR" && pulumi stack select local
    cd "$INFRA_DIR" && pulumi destroy
    ;;
  dev)
    echo "Destroying dev environment..."
    cd "$INFRA_DIR" && pulumi stack select dev
    cd "$INFRA_DIR" && pulumi destroy
    ;;
  prod)
    echo "Destroying prod environment..."
    cd "$INFRA_DIR" && pulumi stack select prod
    cd "$INFRA_DIR" && pulumi destroy
    ;;
  *)
    echo "❌ Unknown env: ${ENV}. Must be one of: local, dev, prod"
    exit 1
    ;;
esac
