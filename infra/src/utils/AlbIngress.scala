package utils

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s

case class AlbIngressInput(
  eksCluster: besom.api.aws.eks.Cluster,
  clusterName: Output[String],
  clusterOidcIssuer: Output[String],
  clusterOidcIssuerArn: Output[String],
  namespace: Output[String],
  readerServiceName: Output[String],
  stackName: String,
  hostedZoneIdConfig: Output[Option[String]],
  baseDomainConfig: Output[Option[String]],
  certificateArnConfig: Output[Option[String]]
)

case class AlbIngressOutput(
  policy: aws.iam.Policy,
  oidcProvider: aws.iam.OpenIdConnectProvider,
  role: aws.iam.Role,
  serviceAccount: k8s.core.v1.ServiceAccount,
  helmRelease: k8s.helm.v3.Release,
  ingress: k8s.networking.v1.Ingress,
  dnsRecord: Option[aws.route53.Record]
)

object AlbIngress extends Resource[AlbIngressInput, AlbIngressOutput, Unit, Unit]:

  override def make(inputParams: AlbIngressInput)(using Context): Output[AlbIngressOutput] =
    for {
      policy <- createAlbControllerPolicy(inputParams)
      oidcProvider <- createOidcProvider(inputParams)
      role <- createRole(inputParams)
      _ <- attachPolicyToRole(role, policy)
      serviceAccount <- createServiceAccount(inputParams, role)
      helmRelease <- installHelmRelease(inputParams, serviceAccount)
      ingress <- createIngress(inputParams)
      dnsRecord <- createDnsRecord(inputParams, ingress)
    } yield AlbIngressOutput(
      policy = policy,
      oidcProvider = oidcProvider,
      role = role,
      serviceAccount = serviceAccount,
      helmRelease = helmRelease,
      ingress = ingress,
      dnsRecord = dnsRecord
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("no alb ingress needed locally due to usage of k3d")

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

  private def createAlbControllerPolicy(params: AlbIngressInput)(using Context): Output[aws.iam.Policy] =
    aws.iam.Policy(
      "alb-controller-policy",
      aws.iam.PolicyArgs(
        name = s"AWSLoadBalancerControllerPolicy-${params.stackName}",
        policy = albControllerPolicyDocument,
        description = "IAM policy for AWS Load Balancer Controller"
      )
    )

  private def createOidcProvider(params: AlbIngressInput)(using Context): Output[aws.iam.OpenIdConnectProvider] =
    aws.iam.OpenIdConnectProvider(
      "eks-oidc-provider",
      aws.iam.OpenIdConnectProviderArgs(
        url = params.clusterOidcIssuer,
        clientIdLists = Output(List("sts.amazonaws.com")),
        thumbprintLists = Output(List("9e99a48a9960b14926bb7f3b02e22da2b0ab7280")) // AWS EKS thumbprint
      )
    )

  private def createRole(params: AlbIngressInput)(using Context): Output[aws.iam.Role] =
    aws.iam.Role(
      "alb-controller-role",
      aws.iam.RoleArgs(
        name = s"AWSLoadBalancerControllerRole-${params.stackName}",
        assumeRolePolicy = for
          issuer <- params.clusterOidcIssuer
          arn <- params.clusterOidcIssuerArn
          ns <- params.namespace
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
    params: AlbIngressInput,
    role: aws.iam.Role
  )(using Context): Output[k8s.core.v1.ServiceAccount] =
    k8s.core.v1.ServiceAccount(
      "aws-load-balancer-controller-sa",
      k8s.core.v1.ServiceAccountArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = "aws-load-balancer-controller",
          namespace = params.namespace,
          annotations = role.arn.map(arn => Map("eks.amazonaws.com/role-arn" -> arn))
        )
      ),
      opts(dependsOn = params.eksCluster)
    )

  private def installHelmRelease(
    params: AlbIngressInput,
    serviceAccount: k8s.core.v1.ServiceAccount
  )(using Context): Output[k8s.helm.v3.Release] =
    k8s.helm.v3.Release(
      "aws-load-balancer-controller",
      k8s.helm.v3.ReleaseArgs(
        name = "aws-load-balancer-controller",
        namespace = params.namespace,
        chart = "aws-load-balancer-controller",
        repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
          repo = "https://aws.github.io/eks-charts"
        ),
        values = for
          cn <- params.clusterName
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
            "region" -> JsString("us-east-1"), // TODO: Make this configurable
            "vpcId" -> JsString("") // This will be auto-discovered by the controller
          )
        }
      ),
      opts(dependsOn = params.eksCluster)
    )

  private def createIngress(
    params: AlbIngressInput
  )(using Context): Output[k8s.networking.v1.Ingress] =
    params.certificateArnConfig.flatMap { certArnOpt =>
      // Build annotations based on whether certificate is configured
      val baseAnnotations = Map(
        "kubernetes.io/ingress.class" -> "alb",
        "alb.ingress.kubernetes.io/scheme" -> "internet-facing",
        "alb.ingress.kubernetes.io/target-type" -> "ip",
        "alb.ingress.kubernetes.io/healthcheck-path" -> "/health",
        "alb.ingress.kubernetes.io/healthcheck-interval-seconds" -> "15",
        "alb.ingress.kubernetes.io/healthcheck-timeout-seconds" -> "5",
        "alb.ingress.kubernetes.io/healthy-threshold-count" -> "2",
        "alb.ingress.kubernetes.io/unhealthy-threshold-count" -> "2"
      )

      val annotations = certArnOpt match
        case Some(certArn) =>
          // HTTPS with SSL certificate
          baseAnnotations ++ Map(
            "alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}, {"HTTPS": 443}]""",
            "alb.ingress.kubernetes.io/certificate-arn" -> certArn,
            "alb.ingress.kubernetes.io/ssl-redirect" -> "443"
          )
        case None =>
          // HTTP only (for testing/local)
          baseAnnotations + ("alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}]""")

      k8s.networking.v1.Ingress(
        "zio-lucene-ingress",
        k8s.networking.v1.IngressArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "zio-lucene-ingress",
            namespace = params.namespace,
            annotations = annotations
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
                          name = params.readerServiceName,
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
        ),
        opts(dependsOn = params.eksCluster)
      )
    }

  private def createDnsRecord(
    params: AlbIngressInput,
    ingress: k8s.networking.v1.Ingress
  )(using Context): Output[Option[aws.route53.Record]] =
    params.hostedZoneIdConfig.flatMap { zoneIdOpt =>
      params.baseDomainConfig.flatMap { domainOpt =>
        (zoneIdOpt, domainOpt) match
          case (Some(zoneId), Some(baseDomain)) =>
            // Determine full domain name based on stack
            val domain = if (params.stackName == "prod") baseDomain else s"${params.stackName}.$baseDomain"

            // Extract ALB hostname from ingress status
            ingress.status.loadBalancer.ingress.flatMap { maybeIngressList =>
              maybeIngressList
                .flatMap(_.headOption)  // Get first element from Iterable
                .map(_.hostname)         // Get the Output[Option[String]] field
                .getOrElse(Output(None)) // If no ingress, return None
                .flatMap {
                  case Some(hostname) =>
                    // Create Route53 A record (alias) pointing to the ALB
                    aws.route53.Record(
                      "alb-dns-record",
                      aws.route53.RecordArgs(
                        zoneId = zoneId,
                        name = domain,
                        `type` = "A",
                        aliases = List(
                          aws.route53.inputs.RecordAliasArgs(
                            name = hostname,
                            zoneId = "Z35SXDOTRQ7X7K", // us-east-1 ALB hosted zone ID
                            evaluateTargetHealth = true
                          )
                        )
                      )
                    ).map(Some(_))
                  case None =>
                    // ALB hostname not available yet, skip DNS record for now
                    Output(None)
                }
            }
          case _ =>
            Output(None)
      }
    }
