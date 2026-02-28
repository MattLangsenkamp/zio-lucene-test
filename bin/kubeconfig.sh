#!/bin/bash
# kubeconfig.sh <env> [cluster_name] [region]
#
# Sets the active kubeconfig context for a given environment, then prints the
# current context and cluster nodes to confirm the switch.
#
#   env=local  -> kubectl config use-context k3d-<cluster_name>
#                 cluster_name defaults to "zio-lucene"
#   env=dev    -> aws eks update-kubeconfig for the named EKS cluster
#   env=prod   -> aws eks update-kubeconfig for the named EKS cluster
#
# Args:
#   $1  env           : local | dev | prod  (required)
#   $2  cluster_name  : k3d cluster name (local) or EKS cluster name (dev/prod)
#   $3  region        : AWS region (dev/prod only), defaults to "us-east-1"

set -e

ENV="${1:?Usage: kubeconfig.sh <env> [cluster_name] [region]}"

case "$ENV" in
  local)
    K3D_CLUSTER_NAME="${2:-zio-lucene}"
    echo "Setting kubeconfig for local k3d cluster..."
    kubectl config use-context "k3d-${K3D_CLUSTER_NAME}"
    echo "✅ Kubeconfig updated for local cluster"
    ;;
  dev|prod)
    EKS_CLUSTER_NAME="${2:-zio-lucene-cluster}"
    AWS_REGION="${3:-us-east-1}"
    echo "Setting kubeconfig for ${ENV} EKS cluster..."
    aws eks update-kubeconfig --region "${AWS_REGION}" --name "${EKS_CLUSTER_NAME}"
    echo "✅ Kubeconfig updated for ${ENV} cluster"
    ;;
  *)
    echo "❌ Unknown env: ${ENV}. Must be one of: local, dev, prod"
    exit 1
    ;;
esac

echo ""
kubectl config current-context
echo ""
kubectl get nodes
