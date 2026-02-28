#!/bin/bash
# check-aws-resources.sh <region> <project_name>
#
# Scans AWS for resources associated with this project to identify anything
# that may have been left running after a "pulumi destroy". Checks:
#   tagged resources (Pulumi tags), EKS clusters, VPCs, S3 buckets,
#   MSK clusters, security groups, IAM roles, internet/NAT gateways,
#   elastic IPs, subnets, route tables, load balancers, OIDC providers.
#
# Args:
#   $1  region       : AWS region, defaults to "us-east-1"
#   $2  project_name : project name used in resource tags/names, defaults to "zio-lucene"

set -e

AWS_REGION="${1:-us-east-1}"
PROJECT_NAME="${2:-zio-lucene}"

echo "=== Checking AWS Resources for ${PROJECT_NAME} ==="
echo "Region: ${AWS_REGION}"
echo ""

# First try: Resource tagging API (catches most tagged resources)
echo "🏷️  Resources tagged by Pulumi:"
echo "--------------------------------"
TAGGED_RESOURCES=$(aws resourcegroupstaggingapi get-resources \
  --region "${AWS_REGION}" \
  --tag-filters \
    Key=pulumi:project,Values=zio-lucene-infra \
  --query 'ResourceTagMappingList[].ResourceARN' \
  --output text 2>/dev/null || echo "")

if [ -n "$TAGGED_RESOURCES" ]; then
  echo "$TAGGED_RESOURCES" | while read -r arn; do
    echo "  - $arn"
  done
else
  echo "  No resources found via tagging API"
fi
echo ""

# Also check for pulumi:stack tag
STACK_TAGGED=$(aws resourcegroupstaggingapi get-resources \
  --region "${AWS_REGION}" \
  --tag-filters \
    Key=pulumi:stack \
  --query 'ResourceTagMappingList[].{ARN:ResourceARN,Stack:Tags[?Key==`pulumi:stack`].Value|[0]}' \
  --output table 2>/dev/null || echo "")

if [ -n "$STACK_TAGGED" ] && [ "$STACK_TAGGED" != "None" ]; then
  echo "📦 Resources by Pulumi Stack:"
  echo "$STACK_TAGGED"
  echo ""
fi

echo "=== Service-Specific Resource Check ==="
echo ""

# EKS Clusters
echo "🔍 EKS Clusters:"
EKS_CLUSTERS=$(aws eks list-clusters --region "${AWS_REGION}" --query 'clusters' --output text 2>/dev/null || echo "")
if [ -n "$EKS_CLUSTERS" ]; then
  aws eks list-clusters --region "${AWS_REGION}" --query 'clusters' --output table
else
  echo "  ✅ No EKS clusters found"
fi
echo ""

# VPCs
echo "🔍 VPCs (tagged with ${PROJECT_NAME}):"
VPC_COUNT=$(aws ec2 describe-vpcs --region "${AWS_REGION}" \
  --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(Vpcs)' --output text 2>/dev/null || echo "0")
if [ "$VPC_COUNT" -gt 0 ]; then
  aws ec2 describe-vpcs --region "${AWS_REGION}" \
    --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'Vpcs[].{VpcId:VpcId,Name:Tags[?Key==`Name`].Value|[0],CIDR:CidrBlock}' \
    --output table
else
  echo "  ✅ No VPCs found"
fi
echo ""

# S3 Buckets
echo "🔍 S3 Buckets (matching segments/${PROJECT_NAME}):"
S3_BUCKETS=$(aws s3 ls 2>/dev/null | grep -E "(segments|${PROJECT_NAME})" || echo "")
if [ -n "$S3_BUCKETS" ]; then
  echo "$S3_BUCKETS"
else
  echo "  ✅ No matching buckets found"
fi
echo ""

# MSK Clusters
echo "🔍 MSK Clusters:"
MSK_COUNT=$(aws kafka list-clusters --region "${AWS_REGION}" \
  --query 'length(ClusterInfoList)' --output text 2>/dev/null || echo "0")
if [ "$MSK_COUNT" -gt 0 ]; then
  aws kafka list-clusters --region "${AWS_REGION}" \
    --query 'ClusterInfoList[].{Name:ClusterName,State:State}' \
    --output table
else
  echo "  ✅ No MSK clusters found"
fi
echo ""

# Security Groups
echo "🔍 Security Groups (tagged with ${PROJECT_NAME}):"
SG_COUNT=$(aws ec2 describe-security-groups --region "${AWS_REGION}" \
  --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(SecurityGroups)' --output text 2>/dev/null || echo "0")
if [ "$SG_COUNT" -gt 0 ]; then
  aws ec2 describe-security-groups --region "${AWS_REGION}" \
    --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'SecurityGroups[].{GroupId:GroupId,Name:GroupName,VpcId:VpcId}' \
    --output table
else
  echo "  ✅ No security groups found"
fi
echo ""

# IAM Roles
echo "🔍 IAM Roles (containing ${PROJECT_NAME}):"
IAM_ROLES=$(aws iam list-roles \
  --query "Roles[?contains(RoleName, \`${PROJECT_NAME}\`)].{RoleName:RoleName,Created:CreateDate}" \
  --output text 2>/dev/null || echo "")
if [ -n "$IAM_ROLES" ]; then
  aws iam list-roles \
    --query "Roles[?contains(RoleName, \`${PROJECT_NAME}\`)].{RoleName:RoleName,Created:CreateDate}" \
    --output table
else
  echo "  ✅ No IAM roles found"
fi
echo ""

# Internet Gateways
echo "🔍 Internet Gateways:"
IGW_COUNT=$(aws ec2 describe-internet-gateways --region "${AWS_REGION}" \
  --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(InternetGateways)' --output text 2>/dev/null || echo "0")
if [ "$IGW_COUNT" -gt 0 ]; then
  aws ec2 describe-internet-gateways --region "${AWS_REGION}" \
    --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'InternetGateways[].{IGW:InternetGatewayId,State:Attachments[0].State,VPC:Attachments[0].VpcId}' \
    --output table
else
  echo "  ✅ No internet gateways found"
fi
echo ""

# NAT Gateways
echo "🔍 NAT Gateways:"
NAT_COUNT=$(aws ec2 describe-nat-gateways --region "${AWS_REGION}" \
  --filter "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(NatGateways)' --output text 2>/dev/null || echo "0")
if [ "$NAT_COUNT" -gt 0 ]; then
  aws ec2 describe-nat-gateways --region "${AWS_REGION}" \
    --filter "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'NatGateways[].{NatGatewayId:NatGatewayId,State:State,VpcId:VpcId}' \
    --output table
else
  echo "  ✅ No NAT gateways found"
fi
echo ""

# Elastic IPs
echo "🔍 Elastic IPs:"
EIP_COUNT=$(aws ec2 describe-addresses --region "${AWS_REGION}" \
  --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(Addresses)' --output text 2>/dev/null || echo "0")
if [ "$EIP_COUNT" -gt 0 ]; then
  aws ec2 describe-addresses --region "${AWS_REGION}" \
    --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'Addresses[].{PublicIp:PublicIp,AllocationId:AllocationId,Associated:AssociationId}' \
    --output table
else
  echo "  ✅ No elastic IPs found"
fi
echo ""

# Subnets
echo "🔍 Subnets:"
SUBNET_COUNT=$(aws ec2 describe-subnets --region "${AWS_REGION}" \
  --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(Subnets)' --output text 2>/dev/null || echo "0")
if [ "$SUBNET_COUNT" -gt 0 ]; then
  aws ec2 describe-subnets --region "${AWS_REGION}" \
    --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'Subnets[].{SubnetId:SubnetId,VpcId:VpcId,CIDR:CidrBlock,AZ:AvailabilityZone}' \
    --output table
else
  echo "  ✅ No subnets found"
fi
echo ""

# Route Tables
echo "🔍 Route Tables:"
RT_COUNT=$(aws ec2 describe-route-tables --region "${AWS_REGION}" \
  --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
  --query 'length(RouteTables)' --output text 2>/dev/null || echo "0")
if [ "$RT_COUNT" -gt 0 ]; then
  aws ec2 describe-route-tables --region "${AWS_REGION}" \
    --filters "Name=tag:Name,Values=*${PROJECT_NAME}*" \
    --query 'RouteTables[].{RouteTableId:RouteTableId,VpcId:VpcId}' \
    --output table
else
  echo "  ✅ No route tables found"
fi
echo ""

# Load Balancers
echo "🔍 Load Balancers:"
LB_COUNT=$(aws elbv2 describe-load-balancers --region "${AWS_REGION}" \
  --query "length(LoadBalancers[?contains(LoadBalancerName, '${PROJECT_NAME}')])" \
  --output text 2>/dev/null || echo "0")
if [ "$LB_COUNT" -gt 0 ]; then
  aws elbv2 describe-load-balancers --region "${AWS_REGION}" \
    --query "LoadBalancers[?contains(LoadBalancerName, '${PROJECT_NAME}')].{Name:LoadBalancerName,Type:Type,State:State.Code}" \
    --output table
else
  echo "  ✅ No load balancers found"
fi
echo ""

# OIDC Providers
echo "🔍 IAM OIDC Providers (for EKS):"
OIDC_PROVIDERS=$(aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text 2>/dev/null || echo "")
if [ -n "$OIDC_PROVIDERS" ]; then
  echo "$OIDC_PROVIDERS" | while read -r arn; do
    # Check if it's an EKS OIDC provider
    if echo "$arn" | grep -q "oidc.eks"; then
      echo "  - $arn"
    fi
  done
else
  echo "  ✅ No OIDC providers found"
fi
echo ""

echo "=== Check Complete ==="
echo ""
echo "💡 Tip: After 'pulumi destroy', all sections should show ✅"
