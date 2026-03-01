#!/bin/bash
# list-eks-clusters.sh <region>
#
# Lists all EKS clusters in the given AWS region using the AWS CLI.
#
# Args:
#   $1  region : AWS region, defaults to "us-east-1"

set -e

AWS_REGION="${1:-us-east-1}"

echo "Listing all EKS clusters in region ${AWS_REGION}..."
aws eks list-clusters --region "${AWS_REGION}" --output table
