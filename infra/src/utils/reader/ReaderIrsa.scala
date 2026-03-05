package utils.reader

import besom.*
import besom.api.aws.iam
import besom.api.aws.Provider as AwsProvider
import besom.api.kubernetes as k8s
import utils.OidcProviderOutput

case class ReaderIrsaInput(
  oidcProvider: Output[OidcProviderOutput],
  namespace: Output[String],
  bucketArn: Output[String],
  awsProvider: Output[AwsProvider],
  k8sProvider: Output[k8s.Provider]
)

case class ReaderIrsaOutput(
  role: iam.Role,
  rolePolicy: iam.RolePolicy,
  serviceAccount: k8s.core.v1.ServiceAccount,
  serviceAccountName: String
)

object ReaderIrsa:

  val serviceAccountName = "reader"

  def make(params: ReaderIrsaInput)(using Context): Output[ReaderIrsaOutput] =
    for {
      role           <- createIrsaRole(params)
      rolePolicy     <- attachPolicy(role, params)
      serviceAccount <- createServiceAccount(params, role)
    } yield ReaderIrsaOutput(
      role = role,
      rolePolicy = rolePolicy,
      serviceAccount = serviceAccount,
      serviceAccountName = serviceAccountName
    )

  private def createIrsaRole(params: ReaderIrsaInput)(using Context): Output[iam.Role] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    val assumeRolePolicy = for {
      oidc   <- params.oidcProvider
      issuer <- oidc.issuerUrl
      arn    <- oidc.providerArn
      ns     <- params.namespace
    } yield s"""{
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
              "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:$ns:$serviceAccountName",
              "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
            }
          }
        }
      ]
    }"""

    iam.Role(
      "reader-irsa-role",
      iam.RoleArgs(
        assumeRolePolicy = assumeRolePolicy,
        description = "IAM role for reader service to access S3"
      ),
      opts(provider = providerResource)
    )

  private def attachPolicy(
    role: iam.Role,
    params: ReaderIrsaInput
  )(using Context): Output[iam.RolePolicy] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    val policy = params.bucketArn.map { bucketArn =>
      s"""{
        "Version": "2012-10-17",
        "Statement": [
          {
            "Effect": "Allow",
            "Action": [
              "s3:GetObject"
            ],
            "Resource": "$bucketArn/*"
          },
          {
            "Effect": "Allow",
            "Action": [
              "s3:ListBucket"
            ],
            "Resource": "$bucketArn"
          }
        ]
      }"""
    }

    iam.RolePolicy(
      "reader-irsa-policy",
      iam.RolePolicyArgs(
        role   = role.id,
        policy = policy
      ),
      opts(provider = providerResource, dependsOn = role)
    )

  private def createServiceAccount(
    params: ReaderIrsaInput,
    role: iam.Role
  )(using Context): Output[k8s.core.v1.ServiceAccount] =
    params.k8sProvider.flatMap { prov =>
      k8s.core.v1.ServiceAccount(
        "reader-service-account",
        k8s.core.v1.ServiceAccountArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name      = serviceAccountName,
            namespace = params.namespace,
            annotations = role.arn.map(arn => Map("eks.amazonaws.com/role-arn" -> arn))
          )
        ),
        opts(provider = prov, dependsOn = role)
      )
    }
