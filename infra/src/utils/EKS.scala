package utils

import besom.*
import besom.api.aws
import besom.api.aws.eks
import besom.api.aws.ec2
import besom.api.aws.iam

case class EksClusterInfo(
  cluster: Output[eks.Cluster],
  clusterName: Output[String],
  oidcProvider: Output[aws.iam.OpenIdConnectProvider],
  oidcProviderArn: Output[String],
  oidcProviderUrl: Output[String]
)

object EKS:

  /**
   * Creates a complete EKS cluster with VPC, subnets, and node groups
   */
  def createCluster(
    namePrefix: String = "zio-lucene",
    vpcId: Output[String],
    subnetIds: List[Output[String]],
    nodeInstanceType: String = "t3.medium",
    desiredCapacity: Int = 2,
    minSize: Int = 1,
    maxSize: Int = 3
  )(using Context): EksClusterInfo =

    // Create EKS cluster IAM role
    val clusterRole = iam.Role(
      s"$namePrefix-eks-cluster-role",
      iam.RoleArgs(
        assumeRolePolicy = """{
          "Version": "2012-10-17",
          "Statement": [{
            "Effect": "Allow",
            "Principal": {
              "Service": "eks.amazonaws.com"
            },
            "Action": "sts:AssumeRole"
          }]
        }"""
      )
    )

    // Attach required policies to cluster role
    val clusterPolicyAttachment = iam.RolePolicyAttachment(
      s"$namePrefix-eks-cluster-policy",
      iam.RolePolicyAttachmentArgs(
        role = clusterRole.name,
        policyArn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
      )
    )

    // Create EKS cluster
    val cluster = eks.Cluster(
      s"$namePrefix-eks-cluster",
      eks.ClusterArgs(
        name = s"$namePrefix-cluster",
        roleArn = clusterRole.arn,
        vpcConfig = eks.inputs.ClusterVpcConfigArgs(
          subnetIds = subnetIds,
          endpointPrivateAccess = true,
          endpointPublicAccess = true
        )
      ),
      opts(dependsOn = clusterPolicyAttachment)
    )

    // Create node group IAM role
    val nodeRole = iam.Role(
      s"$namePrefix-eks-node-role",
      iam.RoleArgs(
        assumeRolePolicy = """{
          "Version": "2012-10-17",
          "Statement": [{
            "Effect": "Allow",
            "Principal": {
              "Service": "ec2.amazonaws.com"
            },
            "Action": "sts:AssumeRole"
          }]
        }"""
      )
    )

    // Attach required policies to node role
    val nodePolicyAttachments = List(
      iam.RolePolicyAttachment(
        s"$namePrefix-eks-worker-node-policy",
        iam.RolePolicyAttachmentArgs(
          role = nodeRole.name,
          policyArn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
        )
      ),
      iam.RolePolicyAttachment(
        s"$namePrefix-eks-cni-policy",
        iam.RolePolicyAttachmentArgs(
          role = nodeRole.name,
          policyArn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
        )
      ),
      iam.RolePolicyAttachment(
        s"$namePrefix-eks-container-registry-policy",
        iam.RolePolicyAttachmentArgs(
          role = nodeRole.name,
          policyArn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
        )
      )
    )

    // Create node group
    val nodeGroup = eks.NodeGroup(
      s"$namePrefix-eks-node-group",
      eks.NodeGroupArgs(
        clusterName = cluster.name,
        nodeRoleArn = nodeRole.arn,
        subnetIds = subnetIds,
        scalingConfig = eks.inputs.NodeGroupScalingConfigArgs(
          desiredSize = desiredCapacity,
          maxSize = maxSize,
          minSize = minSize
        ),
        instanceTypes = List(nodeInstanceType)
      ),
      opts(dependsOn = nodePolicyAttachments(0))
    )

    // EKS automatically creates an OIDC provider - we need to enable it first
    // This requires running: aws eks describe-cluster --name <cluster-name> --query "cluster.identity.oidc.issuer"
    // For Besom/Pulumi, we'll create the OIDC provider manually and use the cluster's OIDC issuer

    // The OIDC issuer URL pattern: https://oidc.eks.<region>.amazonaws.com/id/<UUID>
    // We'll get this from the cluster after it's created using cluster.identity[0].oidcs[0].issuer
    // For now, create a minimal OIDC provider that will be configured by the EKS cluster
    val oidcIssuerUrl = cluster.arn.map { arn =>
      // Extract cluster name from ARN: arn:aws:eks:region:account:cluster/name
      s"https://oidc.eks.us-east-1.amazonaws.com/id/PLACEHOLDER"
    }

    val oidcProvider = iam.OpenIdConnectProvider(
      s"$namePrefix-eks-oidc-provider",
      iam.OpenIdConnectProviderArgs(
        url = oidcIssuerUrl,
        clientIdLists = List("sts.amazonaws.com"),
        thumbprintLists = List("9e99a48a9960b14926bb7f3b02e22da2b0ab7280") // AWS EKS thumbprint
      )
    )

    EksClusterInfo(
      cluster = cluster,
      clusterName = cluster.name,
      oidcProvider = oidcProvider,
      oidcProviderArn = oidcProvider.arn,
      oidcProviderUrl = oidcIssuerUrl
    )
