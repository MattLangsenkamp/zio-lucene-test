#!/bin/bash
# local-clean.sh <k3d_cluster_name> <localstack_volume>
#
# Fully tears down the local environment: destroys the Pulumi stack and then
# shuts down all local infrastructure. Equivalent to running:
#   1. destroy.sh local   (pulumi destroy on the local stack)
#   2. stop-local-env.sh  (stop LocalStack container + delete k3d cluster)
#
# Args:
#   $1  k3d_cluster_name  : k3d cluster name, defaults to "zio-lucene"
#   $2  localstack_volume : Docker volume name (not deleted — use delete-local-volume.sh)

set -e

K3D_CLUSTER_NAME="${1:-zio-lucene}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/destroy.sh"       local "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/stop-local-env.sh" "$K3D_CLUSTER_NAME"

echo "✅ Local environment cleaned up"
