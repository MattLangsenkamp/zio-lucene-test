#!/bin/bash
# stop-local-env.sh <k3d_cluster_name>
#
# Tears down the local development environment by:
#   1. Stopping and removing the LocalStack container
#   2. Deleting the k3d cluster
# Does NOT delete the LocalStack volume — use delete-local-volume.sh for that.

set -e

K3D_CLUSTER_NAME="${1:-zio-lucene}"

echo "Stopping local environment..."
if docker ps -a | grep -q localstack; then
  echo "Stopping LocalStack container..."
  docker stop localstack 2>/dev/null || true
  echo "Removing LocalStack container..."
  docker rm localstack 2>/dev/null || true
else
  echo "LocalStack container not found"
fi

if k3d cluster list | grep -q "${K3D_CLUSTER_NAME}"; then
  echo "Deleting k3d cluster..."
  k3d cluster delete "${K3D_CLUSTER_NAME}" 2>/dev/null || true
else
  echo "k3d cluster not found"
fi

echo "✅ Local environment stopped"
