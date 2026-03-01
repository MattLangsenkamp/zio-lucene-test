#!/bin/bash
# local-preview.sh <localstack_volume> <k3d_cluster_name>
#
# Starts the local environment and runs a Pulumi preview (dry-run) against it.
# Equivalent to running:
#   1. start-local-env.sh  (LocalStack + k3d + Pulumi stack init)
#   2. kubeconfig.sh local (switch kubectl context to local k3d cluster)
#   3. preview.sh local    (pulumi stack select local && pulumi preview)
#
# Args:
#   $1  localstack_volume : Docker volume name for LocalStack persistence, defaults to "zio-lucene-localstack-data"
#   $2  k3d_cluster_name  : k3d cluster name, defaults to "zio-lucene"

set -e

LOCALSTACK_VOLUME="${1:-zio-lucene-localstack-data}"
K3D_CLUSTER_NAME="${2:-zio-lucene}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/start-local-env.sh" "$LOCALSTACK_VOLUME" "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/kubeconfig.sh"      local "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/preview.sh"         local
