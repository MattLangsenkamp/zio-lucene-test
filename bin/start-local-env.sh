#!/bin/bash
# start-local-env.sh <localstack_volume> <k3d_cluster_name>
#
# Starts the full local development environment: LocalStack + k3d cluster.
# Runs check-local-deps.sh first, then:
#   1. Creates the LocalStack Docker volume if it doesn't exist
#   2. Starts (or resumes) the LocalStack container with persistence enabled
#   3. Creates the k3d cluster if it doesn't exist
#   4. Connects LocalStack to the k3d Docker network
#   5. Initializes or selects the Pulumi "local" stack

set -e

LOCALSTACK_VOLUME="${1:-zio-lucene-localstack-data}"
K3D_CLUSTER_NAME="${2:-zio-lucene}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/check-local-deps.sh" "$K3D_CLUSTER_NAME"

echo "Starting local development environment..."
if ! docker volume ls | grep -q "${LOCALSTACK_VOLUME}"; then
  echo "Creating LocalStack volume..."
  docker volume create "${LOCALSTACK_VOLUME}"
fi

if docker ps -a | grep -q localstack; then
  if docker ps | grep -q localstack; then
    echo "✅ LocalStack already running"
  else
    echo "Removing stopped LocalStack container..."
    docker rm localstack 2>/dev/null || true
    echo "Starting LocalStack..."
    docker run -d \
      --name localstack \
      -p 4566:4566 \
      -v "${LOCALSTACK_VOLUME}:/var/lib/localstack" \
      -e SERVICES=s3,kafka,iam,secretsmanager,ec2,sqs \
      -e PERSISTENCE=1 \
      localstack/localstack
    echo "Waiting for LocalStack to be ready..."
    sleep 5
  fi
else
  echo "Starting LocalStack..."
  docker run -d \
    --name localstack \
    -p 4566:4566 \
    -v "${LOCALSTACK_VOLUME}:/var/lib/localstack" \
    -e SERVICES=s3,kafka,iam,secretsmanager,ec2,sqs \
    -e PERSISTENCE=1 \
    localstack/localstack
  echo "Waiting for LocalStack to be ready..."
  sleep 5
fi

if ! k3d cluster list | grep -q "${K3D_CLUSTER_NAME}"; then
  echo "Creating k3d cluster..."
  k3d cluster create "${K3D_CLUSTER_NAME}" \
    --api-port 6550 \
    --port "8080:80@loadbalancer" \
    --port "8443:443@loadbalancer"
else
  echo "✅ k3d cluster '${K3D_CLUSTER_NAME}' already exists"
fi

echo "Connecting LocalStack to k3d network..."
docker network connect "k3d-${K3D_CLUSTER_NAME}" localstack 2>/dev/null || echo "✅ LocalStack already connected to k3d network"
echo ""

echo "Initializing Pulumi local stack..."
cd "$(dirname "$SCRIPT_DIR")/infra" && (pulumi stack select local 2>/dev/null || pulumi stack init local)
echo ""

echo "✅ Local environment ready!"
echo "   LocalStack: http://localhost:4566"
echo "   Kubernetes: k3d-${K3D_CLUSTER_NAME}"
echo "   Pulumi stack: local"
echo ""
