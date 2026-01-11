package utils

import besom.*
import besom.api.aws.iam
import besom.api.aws.Provider as AwsProvider

case class ExternalSecretsIrsaInput(
  externalSecretsNamespace: Output[String],
  clusterOidcIssuer: Output[String],
  clusterOidcIssuerArn: Output[String],
  awsProvider: Output[AwsProvider]
)

case class ExternalSecretsIrsaOutput(
  role: iam.Role,
  rolePolicy: iam.RolePolicy,
  roleArn: Output[String]
)

object ExternalSecretsIrsa extends Resource[
  ExternalSecretsIrsaInput,
  ExternalSecretsIrsaOutput,
  Unit,
  Unit
]:

  override def make(inputParams: ExternalSecretsIrsaInput)(using Context): Output[ExternalSecretsIrsaOutput] =
    for {
      role <- createIrsaRole(inputParams)
      rolePolicy <- attachSecretsManagerPolicy(role, inputParams)
    } yield ExternalSecretsIrsaOutput(
      role = role,
      rolePolicy = rolePolicy,
      roleArn = role.arn
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    // No IRSA needed for local - LocalStack doesn't use IAM roles for authentication
    Output(())

  private def createIrsaRole(params: ExternalSecretsIrsaInput)(using Context): Output[iam.Role] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    val assumeRolePolicy = for {
      issuer <- params.clusterOidcIssuer
      arn <- params.clusterOidcIssuerArn
      ns <- params.externalSecretsNamespace
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
              "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:$ns:external-secrets",
              "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
            }
          }
        }
      ]
    }"""

    iam.Role(
      "external-secrets-irsa-role",
      iam.RoleArgs(
        assumeRolePolicy = assumeRolePolicy,
        description = "IAM role for External Secrets Operator to access Secrets Manager"
      ),
      opts(provider = providerResource)
    )

  private def attachSecretsManagerPolicy(
    role: iam.Role,
    params: ExternalSecretsIrsaInput
  )(using Context): Output[iam.RolePolicy] =
    val providerResource: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(provider => provider.provider)

    iam.RolePolicy(
      "external-secrets-secretsmanager-policy",
      iam.RolePolicyArgs(
        role = role.id,
        policy = """{
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Action": [
                "secretsmanager:GetSecretValue",
                "secretsmanager:DescribeSecret",
                "secretsmanager:ListSecrets"
              ],
              "Resource": "arn:aws:secretsmanager:us-east-1:*:secret:zio-lucene/*"
            }
          ]
        }"""
      ),
      opts(provider = providerResource, dependsOn = role)
    )
