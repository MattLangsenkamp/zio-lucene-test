package platform

import besom.*
import besom.api.aws.iam
import besom.api.aws.ssm
import besom.api.aws.Provider as AwsProvider
import utils.OidcProviderOutput

// Consolidates WriterIrsa, IngestionIrsa, ReaderIrsa, ExternalSecretsIrsa.
//
// Each make* method creates:
//   1. IAM role with OIDC trust policy (for IRSA)
//   2. Inline IAM role policy with resource-level permissions
//   3. SSM String parameter storing the role ARN at /zio-lucene/{env}/irsa/{service}
//      → consumed by 'make update-irsa-values STACK=<env>' to patch values.{env}.yaml
//
// k8s ServiceAccounts are NOT created here — they live in each service's helm chart
// and are annotated with the IRSA ARN via values.{env}.yaml.
//
// ExternalSecretsIrsa DOES create a ServiceAccount (ESO is platform-managed, not ArgoCD-managed).

case class ServiceIrsaInput(
  env: String,
  oidcProvider: Output[OidcProviderOutput],
  namespace: Output[String],
  awsProvider: Output[AwsProvider],
  sqsQueueArns: List[Output[String]] = List.empty,    // queues the service reads from
  sqsWriteQueueArns: List[Output[String]] = List.empty, // queues the service writes to
  bucketArns: List[Output[String]] = List.empty,
  allowS3Write: Boolean = false
)

case class IrsaRoleOutput(
  role: iam.Role,
  rolePolicy: iam.RolePolicy,
  ssmParam: ssm.Parameter
)

// ESO gets a ServiceAccount (platform component, not ArgoCD-managed)
case class EsoIrsaOutput(
  role: iam.Role,
  rolePolicy: iam.RolePolicy,
  roleArn: Output[String],
  ssmParam: ssm.Parameter
)

object IamRoles:

  /** Create an IRSA role for a single application service. serviceName must be unique
   *  within the stack (it's used as the Pulumi resource name prefix and SSM path segment). */
  def makeServiceIrsa(serviceName: String, params: ServiceIrsaInput)(using Context): Output[IrsaRoleOutput] =
    makeServiceIrsaImpl(serviceName, params)

  def makeExternalSecretsIrsa(
    env: String,
    esoNamespace: Output[String],
    oidcProvider: Output[OidcProviderOutput],
    awsProvider: Output[AwsProvider]
  )(using Context): Output[EsoIrsaOutput] =
    val saName = "external-secrets"

    val assumeRolePolicy = for {
      oidc   <- oidcProvider
      issuer <- oidc.issuerUrl
      arn    <- oidc.providerArn
      ns     <- esoNamespace
    } yield buildAssumeRolePolicy(issuer, arn, ns, saName)

    for {
      awsProv <- awsProvider
      role <- iam.Role(
        "external-secrets-irsa-role",
        iam.RoleArgs(
          assumeRolePolicy = assumeRolePolicy,
          description = "IRSA role for External Secrets Operator - SSM ParameterStore read access"
        ),
        opts(provider = awsProv)
      )
      policy <- iam.RolePolicy(
        "external-secrets-irsa-policy",
        iam.RolePolicyArgs(
          role = role.id,
          policy = """{
            "Version": "2012-10-17",
            "Statement": [{
              "Effect": "Allow",
              "Action": [
                "ssm:GetParameter",
                "ssm:GetParameters",
                "ssm:GetParametersByPath",
                "ssm:DescribeParameters"
              ],
              "Resource": "arn:aws:ssm:*:*:parameter/zio-lucene/*"
            }]
          }"""
        ),
        opts(provider = awsProv, dependsOn = role)
      )
      ssmParam <- ssm.Parameter(
        "external-secrets-irsa-arn-ssm",
        ssm.ParameterArgs(
          name = s"/zio-lucene/$env/irsa/external-secrets",
          `type` = "String",
          value = role.arn,
          description = s"IRSA role ARN for External Secrets Operator ($env)"
        ),
        opts(provider = awsProv, dependsOn = role)
      )
    } yield EsoIrsaOutput(
      role = role,
      rolePolicy = policy,
      roleArn = role.arn,
      ssmParam = ssmParam
    )

  // ── Private helpers ───────────────────────────────────────────────────────

  private def makeServiceIrsaImpl(
    serviceName: String,
    params: ServiceIrsaInput
  )(using Context): Output[IrsaRoleOutput] =
    val assumeRolePolicy = for {
      oidc   <- params.oidcProvider
      issuer <- oidc.issuerUrl
      arn    <- oidc.providerArn
      ns     <- params.namespace
    } yield buildAssumeRolePolicy(issuer, arn, ns, serviceName)

    for {
      awsProv  <- params.awsProvider
      role <- iam.Role(
        s"$serviceName-irsa-role",
        iam.RoleArgs(
          assumeRolePolicy = assumeRolePolicy,
          description = s"IRSA role for $serviceName service"
        ),
        opts(provider = awsProv)
      )
      policy <- attachServicePolicy(serviceName, role, params, awsProv)
      ssmParam <- ssm.Parameter(
        s"$serviceName-irsa-arn-ssm",
        ssm.ParameterArgs(
          name = s"/zio-lucene/${params.env}/irsa/$serviceName",
          `type` = "String",
          value = role.arn,
          description = s"IRSA role ARN for $serviceName (${params.env})"
        ),
        opts(provider = awsProv, dependsOn = role)
      )
    } yield IrsaRoleOutput(role = role, rolePolicy = policy, ssmParam = ssmParam)

  private def attachServicePolicy(
    serviceName: String,
    role: iam.Role,
    params: ServiceIrsaInput,
    awsProv: AwsProvider
  )(using Context): Output[iam.RolePolicy] =
    val policyJson: Output[String] = buildPolicyJson(params)

    iam.RolePolicy(
      s"$serviceName-irsa-policy",
      iam.RolePolicyArgs(role = role.id, policy = policyJson),
      opts(provider = awsProv, dependsOn = role)
    )

  private def buildPolicyJson(params: ServiceIrsaInput): Output[String] =
    val readArnsOuts  = params.sqsQueueArns
    val writeArnsOuts = params.sqsWriteQueueArns
    val bucketOuts    = params.bucketArns

    // Combine all Output[String] values
    val allSqsArns  = (readArnsOuts ++ writeArnsOuts).foldLeft(Output(List.empty[String])) { (acc, arn) =>
      acc.flatMap(list => arn.map(a => list :+ a))
    }
    val allBuckets = bucketOuts.foldLeft(Output(List.empty[String])) { (acc, arn) =>
      acc.flatMap(list => arn.map(a => list :+ a))
    }

    for {
      sqsArns   <- allSqsArns
      buckets   <- allBuckets
    } yield {
      val statements = scala.collection.mutable.ListBuffer.empty[String]

      if (params.sqsQueueArns.nonEmpty) {
        val readArnsStr = params.sqsQueueArns.zipWithIndex.map { (_, i) => s""""${sqsArns(i)}"""" }.mkString(", ")
        statements += s"""
          {
            "Effect": "Allow",
            "Action": ["sqs:ReceiveMessage","sqs:DeleteMessage","sqs:ChangeMessageVisibility","sqs:GetQueueAttributes"],
            "Resource": [$readArnsStr]
          }"""
      }

      if (params.sqsWriteQueueArns.nonEmpty) {
        val offset = params.sqsQueueArns.length
        val writeArnsStr = params.sqsWriteQueueArns.indices.map { i => s""""${sqsArns(offset + i)}"""" }.mkString(", ")
        statements += s"""
          {
            "Effect": "Allow",
            "Action": ["sqs:SendMessage","sqs:GetQueueUrl","sqs:GetQueueAttributes"],
            "Resource": [$writeArnsStr]
          }"""
      }

      if (buckets.nonEmpty) {
        val s3Actions = if (params.allowS3Write)
          """["s3:GetObject","s3:PutObject","s3:DeleteObject"]"""
        else
          """["s3:GetObject"]"""

        val objectArns = buckets.map(b => s""""$b/*"""").mkString(", ")
        val bucketArns = buckets.map(b => s""""$b"""").mkString(", ")

        statements += s"""
          {
            "Effect": "Allow",
            "Action": $s3Actions,
            "Resource": [$objectArns]
          }"""
        statements += s"""
          {
            "Effect": "Allow",
            "Action": ["s3:ListBucket"],
            "Resource": [$bucketArns]
          }"""
      }

      val stmtsJson = statements.mkString(",")
      s"""{"Version":"2012-10-17","Statement":[$stmtsJson]}"""
    }

  private def buildAssumeRolePolicy(
    issuerUrl: String,
    providerArn: String,
    namespace: String,
    serviceAccountName: String
  ): String =
    val issuerHost = issuerUrl.stripPrefix("https://")
    s"""{
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": { "Federated": "$providerArn" },
        "Action": "sts:AssumeRoleWithWebIdentity",
        "Condition": {
          "StringEquals": {
            "$issuerHost:sub": "system:serviceaccount:$namespace:$serviceAccountName",
            "$issuerHost:aud": "sts.amazonaws.com"
          }
        }
      }]
    }"""
