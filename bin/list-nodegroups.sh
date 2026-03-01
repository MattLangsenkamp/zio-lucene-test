#!/bin/bash
# list-nodegroups.sh <region> [cluster_name]
#
# Lists all node groups for an EKS cluster in the given AWS region.
#
# Args:
#   $1  region       : AWS region  (required)
#   $2  cluster_name : EKS cluster name, defaults to "zio-lucene-cluster"

set -e

AWS_REGION="${1:?Usage: list-nodegroups.sh <region> [cluster_name]}"
EKS_CLUSTER_NAME="${2:-zio-lucene-cluster}"

echo "Listing node groups for cluster: ${EKS_CLUSTER_NAME}..."
aws eks list-nodegroups --region "${AWS_REGION}" --cluster-name "${EKS_CLUSTER_NAME}" --output table
