package utils

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s

case class AlbControllerInput(
  eksCluster: besom.api.aws.eks.Cluster,
  nodeGroup: besom.api.aws.eks.NodeGroup,
  vpcId: Output[String],
  clusterName: Output[String],
  oidcProvider: OidcProviderOutput,
  stackName: String,
  k8sProvider: Output[k8s.Provider]
)

case class AlbControllerOutput(
  policy: aws.iam.Policy,
  role: aws.iam.Role,
  serviceAccount: k8s.core.v1.ServiceAccount,
  helmRelease: k8s.helm.v3.Release
)

object AlbController extends Resource[AlbControllerInput, AlbControllerOutput, Unit, Unit]:

  override def make(inputParams: AlbControllerInput)(using Context): Output[AlbControllerOutput] =
    for {
      policy <- createAlbControllerPolicy(inputParams)
      role <- createRole(inputParams)
      _ <- attachPolicyToRole(role, policy)
      serviceAccount <- createServiceAccount(inputParams, role)
      helmRelease <- installHelmRelease(inputParams, serviceAccount)
    } yield AlbControllerOutput(
      policy = policy,
      role = role,
      serviceAccount = serviceAccount,
      helmRelease = helmRelease
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("no alb controller needed locally due to usage of k3d")

  /**
   * AWS Load Balancer Controller IAM Policy
   *
   * This is the official IAM policy required by the AWS Load Balancer Controller.
   * We use a raw JSON string instead of structured policy builders because:
   *
   * 1. This is AWS's official policy document - easier to keep in sync with updates
   * 2. The policy contains complex conditional statements that would be verbose to express structurally
   * 3. We can copy-paste updated versions directly from AWS documentation
   *
   * Source: https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json
   * Documentation: https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/deploy/installation/
   */
  private val albControllerPolicyDocument = """{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": [
          "iam:CreateServiceLinkedRole"
        ],
        "Resource": "*",
        "Condition": {
          "StringEquals": {
            "iam:AWSServiceName": "elasticloadbalancing.amazonaws.com"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "ec2:DescribeAccountAttributes",
          "ec2:DescribeAddresses",
          "ec2:DescribeAvailabilityZones",
          "ec2:DescribeInternetGateways",
          "ec2:DescribeVpcs",
          "ec2:DescribeVpcPeeringConnections",
          "ec2:DescribeSubnets",
          "ec2:DescribeSecurityGroups",
          "ec2:DescribeInstances",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DescribeTags",
          "ec2:GetCoipPoolUsage",
          "ec2:DescribeCoipPools",
          "elasticloadbalancing:DescribeLoadBalancers",
          "elasticloadbalancing:DescribeLoadBalancerAttributes",
          "elasticloadbalancing:DescribeListeners",
          "elasticloadbalancing:DescribeListenerAttributes",
          "elasticloadbalancing:DescribeListenerCertificates",
          "elasticloadbalancing:DescribeSSLPolicies",
          "elasticloadbalancing:DescribeRules",
          "elasticloadbalancing:DescribeTargetGroups",
          "elasticloadbalancing:DescribeTargetGroupAttributes",
          "elasticloadbalancing:DescribeTargetHealth",
          "elasticloadbalancing:DescribeTags"
        ],
        "Resource": "*"
      },
      {
        "Effect": "Allow",
        "Action": [
          "cognito-idp:DescribeUserPoolClient",
          "acm:ListCertificates",
          "acm:DescribeCertificate",
          "iam:ListServerCertificates",
          "iam:GetServerCertificate",
          "waf-regional:GetWebACL",
          "waf-regional:GetWebACLForResource",
          "waf-regional:AssociateWebACL",
          "waf-regional:DisassociateWebACL",
          "wafv2:GetWebACL",
          "wafv2:GetWebACLForResource",
          "wafv2:AssociateWebACL",
          "wafv2:DisassociateWebACL",
          "shield:GetSubscriptionState",
          "shield:DescribeProtection",
          "shield:CreateProtection",
          "shield:DeleteProtection"
        ],
        "Resource": "*"
      },
      {
        "Effect": "Allow",
        "Action": [
          "ec2:AuthorizeSecurityGroupIngress",
          "ec2:RevokeSecurityGroupIngress"
        ],
        "Resource": "*"
      },
      {
        "Effect": "Allow",
        "Action": [
          "ec2:CreateSecurityGroup"
        ],
        "Resource": "*"
      },
      {
        "Effect": "Allow",
        "Action": [
          "ec2:CreateTags"
        ],
        "Resource": "arn:aws:ec2:*:*:security-group/*",
        "Condition": {
          "StringEquals": {
            "ec2:CreateAction": "CreateSecurityGroup"
          },
          "Null": {
            "aws:RequestTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "ec2:CreateTags",
          "ec2:DeleteTags"
        ],
        "Resource": "arn:aws:ec2:*:*:security-group/*",
        "Condition": {
          "Null": {
            "aws:RequestTag/elbv2.k8s.aws/cluster": "true",
            "aws:ResourceTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "ec2:AuthorizeSecurityGroupIngress",
          "ec2:RevokeSecurityGroupIngress",
          "ec2:DeleteSecurityGroup"
        ],
        "Resource": "*",
        "Condition": {
          "Null": {
            "aws:ResourceTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:CreateLoadBalancer",
          "elasticloadbalancing:CreateTargetGroup"
        ],
        "Resource": "*",
        "Condition": {
          "Null": {
            "aws:RequestTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:CreateListener",
          "elasticloadbalancing:DeleteListener",
          "elasticloadbalancing:CreateRule",
          "elasticloadbalancing:DeleteRule"
        ],
        "Resource": "*"
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:AddTags",
          "elasticloadbalancing:RemoveTags"
        ],
        "Resource": [
          "arn:aws:elasticloadbalancing:*:*:targetgroup/*/*",
          "arn:aws:elasticloadbalancing:*:*:loadbalancer/net/*/*",
          "arn:aws:elasticloadbalancing:*:*:loadbalancer/app/*/*"
        ],
        "Condition": {
          "Null": {
            "aws:RequestTag/elbv2.k8s.aws/cluster": "true",
            "aws:ResourceTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:AddTags",
          "elasticloadbalancing:RemoveTags"
        ],
        "Resource": [
          "arn:aws:elasticloadbalancing:*:*:listener/net/*/*/*",
          "arn:aws:elasticloadbalancing:*:*:listener/app/*/*/*",
          "arn:aws:elasticloadbalancing:*:*:listener-rule/net/*/*/*",
          "arn:aws:elasticloadbalancing:*:*:listener-rule/app/*/*/*"
        ]
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:ModifyLoadBalancerAttributes",
          "elasticloadbalancing:SetIpAddressType",
          "elasticloadbalancing:SetSecurityGroups",
          "elasticloadbalancing:SetSubnets",
          "elasticloadbalancing:DeleteLoadBalancer",
          "elasticloadbalancing:ModifyTargetGroup",
          "elasticloadbalancing:ModifyTargetGroupAttributes",
          "elasticloadbalancing:DeleteTargetGroup"
        ],
        "Resource": "*",
        "Condition": {
          "Null": {
            "aws:ResourceTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:AddTags"
        ],
        "Resource": [
          "arn:aws:elasticloadbalancing:*:*:targetgroup/*/*",
          "arn:aws:elasticloadbalancing:*:*:loadbalancer/net/*/*",
          "arn:aws:elasticloadbalancing:*:*:loadbalancer/app/*/*"
        ],
        "Condition": {
          "StringEquals": {
            "elasticloadbalancing:CreateAction": [
              "CreateTargetGroup",
              "CreateLoadBalancer"
            ]
          },
          "Null": {
            "aws:RequestTag/elbv2.k8s.aws/cluster": "false"
          }
        }
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:RegisterTargets",
          "elasticloadbalancing:DeregisterTargets"
        ],
        "Resource": "arn:aws:elasticloadbalancing:*:*:targetgroup/*/*"
      },
      {
        "Effect": "Allow",
        "Action": [
          "elasticloadbalancing:SetWebAcl",
          "elasticloadbalancing:ModifyListener",
          "elasticloadbalancing:AddListenerCertificates",
          "elasticloadbalancing:RemoveListenerCertificates",
          "elasticloadbalancing:ModifyRule"
        ],
        "Resource": "*"
      }
    ]
  }"""

  private def createAlbControllerPolicy(params: AlbControllerInput)(using Context): Output[aws.iam.Policy] =
    aws.iam.Policy(
      "alb-controller-policy",
      aws.iam.PolicyArgs(
        name = s"AWSLoadBalancerControllerPolicy-${params.stackName}",
        policy = albControllerPolicyDocument,
        description = "IAM policy for AWS Load Balancer Controller"
      )
    )

  private def createRole(params: AlbControllerInput)(using Context): Output[aws.iam.Role] =
    aws.iam.Role(
      "alb-controller-role",
      aws.iam.RoleArgs(
        name = s"AWSLoadBalancerControllerRole-${params.stackName}",
        assumeRolePolicy = for
          issuer <- params.oidcProvider.issuerUrl
          arn <- params.oidcProvider.providerArn
        yield s"""{
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Principal": {
                "Federated": "$arn"
              },
              "Action": "sts:AssumeRoleWithWebIdentity",
              "Condition": {
                "StringEquals": {
                  "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:kube-system:aws-load-balancer-controller",
                  "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
                }
              }
            }
          ]
        }""",
        description = "IAM role for AWS Load Balancer Controller with OIDC"
      ),
      opts(dependsOn = params.oidcProvider.provider)
    )

  private def attachPolicyToRole(
    role: aws.iam.Role,
    policy: aws.iam.Policy
  )(using Context): Output[aws.iam.RolePolicyAttachment] =
    aws.iam.RolePolicyAttachment(
      "alb-controller-policy-attachment",
      aws.iam.RolePolicyAttachmentArgs(
        role = role.name,
        policyArn = policy.arn
      )
    )

  private def createServiceAccount(
    params: AlbControllerInput,
    role: aws.iam.Role
  )(using Context): Output[k8s.core.v1.ServiceAccount] =
    params.k8sProvider.flatMap { prov =>
      k8s.core.v1.ServiceAccount(
        "aws-load-balancer-controller-sa",
        k8s.core.v1.ServiceAccountArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "aws-load-balancer-controller",
            namespace = "kube-system",
            annotations = role.arn.map(arn => Map("eks.amazonaws.com/role-arn" -> arn))
          )
        ),
        opts(dependsOn = params.eksCluster, provider = prov)
      )
    }

  private def installHelmRelease(
    params: AlbControllerInput,
    serviceAccount: k8s.core.v1.ServiceAccount
  )(using Context): Output[k8s.helm.v3.Release] =
    params.k8sProvider.flatMap { prov =>
      k8s.helm.v3.Release(
        "aws-load-balancer-controller",
        k8s.helm.v3.ReleaseArgs(
          name = "aws-load-balancer-controller",
          namespace = "kube-system",
          chart = "aws-load-balancer-controller",
          repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
            repo = "https://aws.github.io/eks-charts"
          ),
          timeout = 300,
          waitForJobs = true,
          values = for
            cn <- params.clusterName
            vpc <- params.vpcId
            sa <- serviceAccount.metadata.name
          yield {
            import besom.json.*
            val innerMap: JsObject = JsObject(
              "create" -> JsBoolean(false),
              "name" -> JsString(sa.getOrElse {
                throw new RuntimeException("Failed to get service account name from created ServiceAccount resource")
              })
            )
            Map[String, JsValue](
              "clusterName" -> JsString(cn),
              "serviceAccount" -> innerMap,
              "region" -> JsString("us-east-1"),
              "vpcId" -> JsString(vpc)
            )
          }
        ),
        opts(dependsOn = List(params.eksCluster, params.nodeGroup), provider = prov)
      )
    }
