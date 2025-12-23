package utils

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s

case class AlbIngressOutput(
  policy: aws.iam.Policy,
  oidcProvider: aws.iam.OpenIdConnectProvider,
  role: aws.iam.Role,
  serviceAccount: k8s.core.v1.ServiceAccount,
  helmRelease: k8s.helm.v3.Release,
  ingress: k8s.networking.v1.Ingress
)

object AlbIngress:

  // AWS Load Balancer Controller IAM policy JSON
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

  def setup(
    clusterName: Output[String],
    clusterOidcIssuer: Output[String],
    clusterOidcIssuerArn: Output[String],
    namespace: Output[String],
    readerServiceName: Output[String],
    stackName: String
  )(using Context): Output[AlbIngressOutput] =
    for
      // 1. Create IAM Policy for AWS Load Balancer Controller
      policy <- aws.iam.Policy(
        "alb-controller-policy",
        aws.iam.PolicyArgs(
          name = s"AWSLoadBalancerControllerPolicy-$stackName",
          policy = albControllerPolicyDocument,
          description = "IAM policy for AWS Load Balancer Controller"
        )
      )

      // 2. Create OIDC Provider (if not exists - typically created with EKS cluster)
      oidcProvider <- aws.iam.OpenIdConnectProvider(
        "eks-oidc-provider",
        aws.iam.OpenIdConnectProviderArgs(
          url = clusterOidcIssuer,
          clientIdLists = List("sts.amazonaws.com"),
          thumbprintLists = List("9e99a48a9960b14926bb7f3b02e22da2b0ab7280") // AWS EKS thumbprint
        )
      )

      // 3. Create IAM Role with OIDC trust relationship
      role <- aws.iam.Role(
        "alb-controller-role",
        aws.iam.RoleArgs(
          name = s"AWSLoadBalancerControllerRole-$stackName",
          assumeRolePolicy = for
            issuer <- clusterOidcIssuer
            ns <- namespace
          yield s"""{
            "Version": "2012-10-17",
            "Statement": [
              {
                "Effect": "Allow",
                "Principal": {
                  "Federated": "${clusterOidcIssuerArn}"
                },
                "Action": "sts:AssumeRoleWithWebIdentity",
                "Condition": {
                  "StringEquals": {
                    "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:$ns:aws-load-balancer-controller",
                    "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
                  }
                }
              }
            ]
          }""",
          description = "IAM role for AWS Load Balancer Controller with OIDC"
        )
      )

      // 4. Attach policy to role
      _ <- aws.iam.RolePolicyAttachment(
        "alb-controller-policy-attachment",
        aws.iam.RolePolicyAttachmentArgs(
          role = role.name,
          policyArn = policy.arn
        )
      )

      // 5. Create Kubernetes ServiceAccount with IAM role annotation
      serviceAccount <- k8s.core.v1.ServiceAccount(
        "aws-load-balancer-controller-sa",
        k8s.core.v1.ServiceAccountArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "aws-load-balancer-controller",
            namespace = namespace,
            annotations = role.arn.map(arn => Map("eks.amazonaws.com/role-arn" -> arn))
          )
        )
      )

      // 6. Install AWS Load Balancer Controller via Helm
      helmRelease <- k8s.helm.v3.Release(
        "aws-load-balancer-controller",
        k8s.helm.v3.ReleaseArgs(
          name = "aws-load-balancer-controller",
          namespace = namespace,
          chart = "aws-load-balancer-controller",
          repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
            repo = "https://aws.github.io/eks-charts"
          ),
          values = for
            cn <- clusterName
            sa <- serviceAccount.metadata.name
            ns <- namespace
          yield Map[String, Any](
            "clusterName" -> cn,
            "serviceAccount" -> Map[String, Any](
              "create" -> false,
              "name" -> sa.getOrElse("aws-load-balancer-controller")
            ),
            "region" -> "us-east-1", // TODO: Make this configurable
            "vpcId" -> "" // This will be auto-discovered by the controller
          ).asInstanceOf[Map[String, Input[besom.types.PulumiAny]]]
        )
      )

      // 7. Create Ingress resource with ALB annotations
      ingress <- k8s.networking.v1.Ingress(
        "zio-lucene-ingress",
        k8s.networking.v1.IngressArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "zio-lucene-ingress",
            namespace = namespace,
            annotations = Map(
              "kubernetes.io/ingress.class" -> "alb",
              "alb.ingress.kubernetes.io/scheme" -> "internet-facing",
              "alb.ingress.kubernetes.io/target-type" -> "ip",
              "alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}]""",
              "alb.ingress.kubernetes.io/healthcheck-path" -> "/health",
              "alb.ingress.kubernetes.io/healthcheck-interval-seconds" -> "15",
              "alb.ingress.kubernetes.io/healthcheck-timeout-seconds" -> "5",
              "alb.ingress.kubernetes.io/healthy-threshold-count" -> "2",
              "alb.ingress.kubernetes.io/unhealthy-threshold-count" -> "2"
            )
          ),
          spec = k8s.networking.v1.inputs.IngressSpecArgs(
            rules = List(
              k8s.networking.v1.inputs.IngressRuleArgs(
                http = k8s.networking.v1.inputs.HttpIngressRuleValueArgs(
                  paths = List(
                    k8s.networking.v1.inputs.HttpIngressPathArgs(
                      path = "/search",
                      pathType = "Prefix",
                      backend = k8s.networking.v1.inputs.IngressBackendArgs(
                        service = k8s.networking.v1.inputs.IngressServiceBackendArgs(
                          name = readerServiceName,
                          port = k8s.networking.v1.inputs.ServiceBackendPortArgs(
                            number = 80
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    yield AlbIngressOutput(policy, oidcProvider, role, serviceAccount, helmRelease, ingress)
