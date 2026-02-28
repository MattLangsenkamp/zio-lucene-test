#!/bin/bash
# local-dev.sh <localstack_volume> <k3d_cluster_name>
#
# Full local development bootstrap: brings up the environment, loads images,
# and deploys the stack. Equivalent to running these steps in order:
#   1. start-local-env.sh  (LocalStack + k3d + Pulumi stack init)
#   2. import-images.sh    (load app images into k3d)
#   3. deploy.sh local     (set kubeconfig, detect LocalStack IP, pulumi up)
#
# Args:
#   $1  localstack_volume : Docker volume name for LocalStack persistence, defaults to "zio-lucene-localstack-data"
#   $2  k3d_cluster_name  : k3d cluster name, defaults to "zio-lucene"

set -e

LOCALSTACK_VOLUME="${1:-zio-lucene-localstack-data}"
K3D_CLUSTER_NAME="${2:-zio-lucene}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/start-local-env.sh" "$LOCALSTACK_VOLUME" "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/import-images.sh"   "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/deploy.sh"          local "$K3D_CLUSTER_NAME"
