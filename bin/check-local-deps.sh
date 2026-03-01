#!/bin/bash
# check-local-deps.sh <k3d_cluster_name>
#
# Verifies all local development dependencies are installed and running.
# Checks: pulumi, k3d, docker, kubectl, aws cli, coursier, mill
# Also checks Docker daemon is up, LocalStack status, and k3d cluster existence.
# Exits non-zero if any required binary is missing.

set -e

K3D_CLUSTER_NAME="${1:-zio-lucene}"

echo "Checking local development dependencies..."
command -v pulumi >/dev/null 2>&1 || { echo "❌ pulumi is not installed. Install: https://www.pulumi.com/docs/install/"; exit 1; }
command -v k3d >/dev/null 2>&1 || { echo "❌ k3d is not installed. Install: curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash"; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "❌ docker is not installed. Install: https://docs.docker.com/engine/install/"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "❌ kubectl is not installed. Install: snap install kubectl --classic"; exit 1; }
command -v aws >/dev/null 2>&1 || { echo "❌ aws cli is not installed. Install: snap install aws-cli --classic"; exit 1; }
command -v coursier >/dev/null 2>&1 || { echo "❌ coursier is not installed. Visit: https://get-coursier.io/"; exit 1; }
command -v ./mill >/dev/null 2>&1 || { echo "❌ mill is not installed. Install: https://mill-build.com/mill/Intro_to_Mill.html#_installation"; exit 1; }
echo "✅ All dependencies installed"
echo ""

echo "Checking Docker daemon..."
docker info >/dev/null 2>&1 || { echo "❌ Docker daemon is not running."; exit 1; }
echo "✅ Docker daemon is running"
echo ""

echo "Checking LocalStack..."
docker ps | grep -q localstack || { echo "⚠️  LocalStack is not running."; }
echo ""

echo "Checking k3d cluster..."
k3d cluster list | grep -q "${K3D_CLUSTER_NAME}" || { echo "⚠️  k3d cluster '${K3D_CLUSTER_NAME}' does not exist. Run 'make start-local-env'"; }
echo ""
