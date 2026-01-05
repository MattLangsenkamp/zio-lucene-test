package utils

import besom.*
import besom.api.aws
import besom.api.aws.{Provider, ec2, eks, iam}

case class EksInput(
  namePrefix: String = "zio-lucene",
  vpcId: Output[String],
  subnetIds: List[Output[String]],
  nodeInstanceType: String = "t3.medium",
  desiredCapacity: Int = 2,
  minSize: Int = 1,
  maxSize: Int = 3
)

case class EksOutput(
  cluster: eks.Cluster,
  nodeGroup: eks.NodeGroup,
  nodeRoleArn: Output[String],
  clusterName: Output[String],
  oidcProviderUrl: Output[String],
  oidcProviderArn: Output[String],
  clusterEndpoint: Output[String],
  clusterCertificateAuthority: Output[String]
)

object EKS extends Resource[EksInput, EksOutput, Unit, Unit]:

  override def make(inputParams: EksInput)(using c: Context): Output[EksOutput] =
    for {
      clusterRole <- createClusterRole(inputParams)
      clusterPolicyAttachment <- attachClusterPolicy(inputParams, clusterRole)
      cluster <- createClusterResource(inputParams, clusterRole, clusterPolicyAttachment)
      nodeRole <- createNodeRole(inputParams)
      nodePolicyAttachments <- attachNodePolicies(inputParams, nodeRole)
      nodeGroup <- createNodeGroupResource(inputParams, cluster, nodeRole, nodePolicyAttachments)
    } yield {
      val oidcIssuerUrl = extractOidcIssuerUrl(cluster)
      val oidcProviderArn = cluster.arn.zip(oidcIssuerUrl).map { case (arn, issuerUrl) =>
        val parts = arn.split(":")
        val account = parts(4)
        val issuerPath = issuerUrl.stripPrefix("https://")
        s"arn:aws:iam::${account}:oidc-provider/${issuerPath}"
      }

      // Extract cluster endpoint
      val clusterEndpoint = cluster.endpoint

      // Extract certificate authority data
      val clusterCertificateAuthority = cluster.certificateAuthority.flatMap { certAuth =>
        certAuth.data.map(_.getOrElse {
          throw new RuntimeException("EKS cluster certificate authority data is missing")
        })
      }

      EksOutput(
        cluster = cluster,
        nodeGroup = nodeGroup,
        nodeRoleArn = nodeRole.arn,
        clusterName = cluster.name,
        oidcProviderUrl = oidcIssuerUrl,
        oidcProviderArn = oidcProviderArn,
        clusterEndpoint = clusterEndpoint,
        clusterCertificateAuthority = clusterCertificateAuthority
      )
    }

  override def makeLocal(inputParams: Unit)(using c: Context): Output[Unit] =
    throw new IllegalStateException("no eks needed locally due to usage of k3d")

  private def createClusterRole(params: EksInput)(using Context): Output[iam.Role] =
    iam.Role(
      s"${params.namePrefix}-eks-cluster-role",
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

  private def attachClusterPolicy(
    params: EksInput,
    clusterRole: iam.Role
  )(using Context): Output[iam.RolePolicyAttachment] =
    iam.RolePolicyAttachment(
      s"${params.namePrefix}-eks-cluster-policy",
      iam.RolePolicyAttachmentArgs(
        role = clusterRole.name,
        policyArn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
      ),
      opts(dependsOn = clusterRole)
    )

  private def createClusterResource(
    params: EksInput,
    clusterRole: iam.Role,
    clusterPolicyAttachment: iam.RolePolicyAttachment
  )(using Context): Output[eks.Cluster] =
    eks.Cluster(
      s"${params.namePrefix}-eks-cluster",
      eks.ClusterArgs(
        name = s"${params.namePrefix}-cluster",
        roleArn = clusterRole.arn,
        vpcConfig = eks.inputs.ClusterVpcConfigArgs(
          subnetIds = params.subnetIds,
          endpointPrivateAccess = true,
          endpointPublicAccess = true
        )
      ),
      opts(dependsOn = clusterPolicyAttachment)
    )

  private def createNodeRole(params: EksInput)(using Context): Output[iam.Role] =
    iam.Role(
      s"${params.namePrefix}-eks-node-role",
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

  private def attachNodePolicies(
    params: EksInput,
    nodeRole: iam.Role
  )(using Context): Output[List[iam.RolePolicyAttachment]] =
    val policies = List(
      iam.RolePolicyAttachment(
        s"${params.namePrefix}-eks-worker-node-policy",
        iam.RolePolicyAttachmentArgs(
          role = nodeRole.name,
          policyArn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
        ),
        opts(dependsOn = nodeRole)
      ),
      iam.RolePolicyAttachment(
        s"${params.namePrefix}-eks-cni-policy",
        iam.RolePolicyAttachmentArgs(
          role = nodeRole.name,
          policyArn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
        ),
        opts(dependsOn = nodeRole)
      ),
      iam.RolePolicyAttachment(
        s"${params.namePrefix}-eks-container-registry-policy",
        iam.RolePolicyAttachmentArgs(
          role = nodeRole.name,
          policyArn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
        ),
        opts(dependsOn = nodeRole)
      )
    )
    Output.sequence(policies)

  private def createNodeGroupResource(
    params: EksInput,
    cluster: eks.Cluster,
    nodeRole: iam.Role,
    nodePolicyAttachments: List[iam.RolePolicyAttachment]
  )(using Context): Output[eks.NodeGroup] =
    eks.NodeGroup(
      s"${params.namePrefix}-eks-node-group",
      eks.NodeGroupArgs(
        clusterName = cluster.name,
        nodeRoleArn = nodeRole.arn,
        subnetIds = params.subnetIds,
        scalingConfig = eks.inputs.NodeGroupScalingConfigArgs(
          desiredSize = params.desiredCapacity,
          maxSize = params.maxSize,
          minSize = params.minSize
        ),
        instanceTypes = List(params.nodeInstanceType)
      ),
      opts(
        parent = cluster,
        dependsOn = nodePolicyAttachments
      )
    )

  private def extractOidcIssuerUrl(cluster: eks.Cluster)(using Context): Output[String] =
    cluster.identities.flatMap { identities =>
      val identity = identities.headOption.getOrElse {
        throw new RuntimeException("EKS cluster has no identity configuration")
      }
      identity.oidcs
    }.flatMap { maybeOidcs =>
      val oidcs = maybeOidcs.getOrElse {
        throw new RuntimeException("EKS cluster identity has no OIDC configuration")
      }
      val firstOidc = oidcs.headOption.getOrElse {
        throw new RuntimeException("EKS cluster OIDC configuration is empty")
      }
      firstOidc.issuer
    }.map { maybeIssuer =>
      maybeIssuer.getOrElse {
        throw new RuntimeException("EKS cluster OIDC has no issuer URL")
      }
    }

