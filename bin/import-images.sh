#!/bin/bash
# import-images.sh <k3d_cluster_name>
#
# Imports all application Docker images into the k3d cluster so they can be
# used by Kubernetes without a registry. Imports:
#   - ingestion-server:latest
#   - reader-server:latest
#   - writer-server:latest
# Warns (but does not fail) if an image is not found locally.
#
# Args:
#   $1  k3d_cluster_name : name of the k3d cluster, defaults to "zio-lucene"

set -e

K3D_CLUSTER_NAME="${1:-zio-lucene}"

echo "Importing Docker images into k3d cluster..."
k3d image import ingestion-server:latest -c "${K3D_CLUSTER_NAME}" 2>/dev/null || echo "⚠️  ingestion-server:latest not found"
k3d image import reader-server:latest    -c "${K3D_CLUSTER_NAME}" 2>/dev/null || echo "⚠️  reader-server:latest not found"
k3d image import writer-server:latest    -c "${K3D_CLUSTER_NAME}" 2>/dev/null || echo "⚠️  writer-server:latest not found"
echo "✅ Images imported"
