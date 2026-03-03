package utils.ingestion

import besom.*
import besom.api.aws.iam
import besom.api.aws.Provider as AwsProvider
import besom.api.kubernetes as k8s
import utils.OidcProviderOutput

case class IngestionIrsaInput(
  oidcProvider: Output[OidcProviderOutput],
  namespace: Output[String],
  bucketArn: Output[String],
  sqsQueueArn: Option[Output[String]],
  awsProvider: Output[AwsProvider],
  k8sProvider: Output[k8s.Provider]
)

case class IngestionIrsaOutput(
  role: iam.Role,
  rolePolicy: iam.RolePolicy,
  serviceAccount: k8s.core.v1.ServiceAccount,
  serviceAccountName: String
)

object IngestionIrsa:

  val serviceAccountName = "ingestion"

  def make(params: IngestionIrsaInput)(using Context): Output[IngestionIrsaOutput] =
    for {
      role           <- createIrsaRole(params)
      rolePolicy     <- attachPolicy(role, params)
      serviceAccount <- createServiceAccount(params, role)
    } yield IngestionIrsaOutput(
      role = role,
      rolePolicy = rolePolicy,
      serviceAccount = serviceAccount,
      serviceAccountName = serviceAccountName
    )

  private def createIrsaRole(params: IngestionIrsaInput)(using Context): Output[iam.Role] =
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
      "ingestion-irsa-role",
      iam.RoleArgs(
        assumeRolePolicy = assumeRolePolicy,
        description = "IAM role for ingestion service to access S3 and SQS"
      ),
      opts(provider = providerResource)
    )

  private def attachPolicy(
    role: iam.Role,
    params: IngestionIrsaInput
  )(using Context): Output[iam.RolePolicy] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    val policy = params.sqsQueueArn match
      case Some(sqsArn) =>
        for {
          bucketArn   <- params.bucketArn
          sqsQueueArn <- sqsArn
        } yield s"""{
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Action": ["s3:PutObject", "s3:GetObject"],
              "Resource": "$bucketArn/*"
            },
            {
              "Effect": "Allow",
              "Action": ["s3:ListBucket"],
              "Resource": "$bucketArn"
            },
            {
              "Effect": "Allow",
              "Action": [
                "sqs:SendMessage",
                "sqs:GetQueueUrl",
                "sqs:GetQueueAttributes"
              ],
              "Resource": "$sqsQueueArn"
            }
          ]
        }"""
      case None =>
        params.bucketArn.map { bucketArn =>
          s"""{
            "Version": "2012-10-17",
            "Statement": [
              {
                "Effect": "Allow",
                "Action": ["s3:PutObject", "s3:GetObject"],
                "Resource": "$bucketArn/*"
              },
              {
                "Effect": "Allow",
                "Action": ["s3:ListBucket"],
                "Resource": "$bucketArn"
              }
            ]
          }"""
        }

    iam.RolePolicy(
      "ingestion-irsa-policy",
      iam.RolePolicyArgs(
        role   = role.id,
        policy = policy
      ),
      opts(provider = providerResource, dependsOn = role)
    )

  private def createServiceAccount(
    params: IngestionIrsaInput,
    role: iam.Role
  )(using Context): Output[k8s.core.v1.ServiceAccount] =
    params.k8sProvider.flatMap { prov =>
      k8s.core.v1.ServiceAccount(
        "ingestion-service-account",
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
