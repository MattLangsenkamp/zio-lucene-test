#!/bin/bash
# rollout.sh <env> <namespace> [service] [cluster_name] [region]
#
# Performs a kubectl rollout restart for the given environment.
#
#   env=local  -> sets local kubeconfig, then restarts all three workloads:
#                   deployment/ingestion, deployment/reader, statefulset/writer
#                 cluster_name is the k3d cluster name
#   env=dev    -> updates kubeconfig via aws eks, then restarts the named service.
#                 service is required for dev. cluster_name is the EKS cluster name.
#
# Args:
#   $1  env          : local | dev  (required)
#   $2  namespace    : Kubernetes namespace  (required)
#   $3  service      : service name — required for env=dev, ignored for env=local
#   $4  cluster_name : k3d cluster name (local) or EKS cluster name (dev)
#   $5  region       : AWS region (dev only), defaults to "us-east-1"

set -e

ENV="${1:?Usage: rollout.sh <env> <namespace> [service] [cluster_name] [region]}"
NAMESPACE="${2:?Usage: rollout.sh <env> <namespace> [service] [cluster_name] [region]}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$ENV" in
  local)
    K3D_CLUSTER_NAME="${4:-zio-lucene}"
    "$SCRIPT_DIR/kubeconfig.sh" local "$K3D_CLUSTER_NAME"
    kubectl rollout restart deployment/ingestion   -n "$NAMESPACE"
    kubectl rollout restart deployment/reader      -n "$NAMESPACE"
    kubectl rollout restart statefulset/writer     -n "$NAMESPACE"
    ;;
  dev)
    SERVICE="${3:?SERVICE is required for env=dev. Usage: rollout.sh dev <namespace> <service> <cluster_name> [region]}"
    EKS_CLUSTER_NAME="${4:-zio-lucene-cluster}"
    AWS_REGION="${5:-us-east-1}"
    aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" > /dev/null 2>&1
    kubectl rollout restart deployment/"${SERVICE}" -n "$NAMESPACE"
    ;;
  *)
    echo "❌ Unknown env: ${ENV}. Must be one of: local, dev"
    exit 1
    ;;
esac
