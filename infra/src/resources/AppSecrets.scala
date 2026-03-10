package resources

import besom.*
import besom.api.aws.ssm
import besom.api.aws.Provider as AwsProvider
import besom.api.kubernetes as k8s
import besom.json.*

// Replaces SecretStore.scala + SecretSync.scala.
// Migrates Datadog API key from SecretsManager to SSM SecureString.
// The ClusterSecretStore (pointing to SSM ParameterStore) is managed by platform.ExternalSecrets.

case class AppSecretsInput(
  datadogApiKey: Output[String],
  env: String,
  namespace: Output[String],
  awsProvider: Output[AwsProvider],
  k8sProvider: Output[k8s.Provider],
  // Depends on ESO helm release being ready before creating ExternalSecret CRD
  esoHelmRelease: Option[Output[k8s.helm.v3.Release]] = None
)

case class AppSecretsOutput(
  datadogSsmParam: ssm.Parameter,
  datadogExternalSecret: k8s.apiextensions.CustomResource[AppSecrets.ExternalSecretSpec]
)

object AppSecrets:

  // ── Spec types for the ExternalSecret CRD ────────────────────────────────

  case class ExternalSecretSpec(
    refreshInterval: String,
    secretStoreRef: SecretStoreRef,
    target: ExternalSecretTarget,
    data: List[ExternalSecretData]
  ) derives Encoder, Decoder

  case class SecretStoreRef(name: String, kind: String) derives Encoder, Decoder

  case class ExternalSecretTarget(
    name: String,
    creationPolicy: String
  ) derives Encoder, Decoder

  case class ExternalSecretData(
    secretKey: String,
    remoteRef: RemoteRef
  ) derives Encoder, Decoder

  case class RemoteRef(key: String) derives Encoder, Decoder

  // ── Public API ────────────────────────────────────────────────────────────

  def make(params: AppSecretsInput)(using Context): Output[AppSecretsOutput] =
    createResources(params, secretStoreKind = "ClusterSecretStore", secretStoreName = "aws-ssm-store")

  def makeLocal(params: AppSecretsInput)(using Context): Output[AppSecretsOutput] =
    createResources(params, secretStoreKind = "ClusterSecretStore", secretStoreName = "aws-ssm-store")

  // ── Private helpers ───────────────────────────────────────────────────────

  private def createResources(
    params: AppSecretsInput,
    secretStoreKind: String,
    secretStoreName: String
  )(using Context): Output[AppSecretsOutput] =
    for {
      ssmParam      <- createDatadogSsmParam(params)
      externalSecret <- createDatadogExternalSecret(params, ssmParam, secretStoreKind, secretStoreName)
    } yield AppSecretsOutput(
      datadogSsmParam = ssmParam,
      datadogExternalSecret = externalSecret
    )

  private def createDatadogSsmParam(params: AppSecretsInput)(using Context): Output[ssm.Parameter] =
    for {
      awsProv <- params.awsProvider
      apiKey  <- params.datadogApiKey
      param   <- ssm.Parameter(
        "datadog-api-key-ssm",
        ssm.ParameterArgs(
          name = s"/zio-lucene/${params.env}/secrets/datadog-api-key",
          `type` = "SecureString",
          value = apiKey,
          description = s"Datadog API key (${params.env})"
        ),
        opts(provider = awsProv)
      )
    } yield param

  private def createDatadogExternalSecret(
    params: AppSecretsInput,
    ssmParam: ssm.Parameter,
    secretStoreKind: String,
    secretStoreName: String
  )(using Context): Output[k8s.apiextensions.CustomResource[ExternalSecretSpec]] =
    params.k8sProvider.flatMap { prov =>
      params.namespace.flatMap { ns =>
        val spec = ExternalSecretSpec(
          refreshInterval = "1h",
          secretStoreRef = SecretStoreRef(name = secretStoreName, kind = secretStoreKind),
          target = ExternalSecretTarget(name = "datadog-api-key", creationPolicy = "Owner"),
          data = List(
            ExternalSecretData(
              secretKey = "api-key",
              remoteRef = RemoteRef(key = s"/zio-lucene/${params.env}/secrets/datadog-api-key")
            )
          )
        )

        k8s.apiextensions.CustomResource[ExternalSecretSpec](
          "datadog-api-key-external-secret",
          k8s.apiextensions.CustomResourceArgs[ExternalSecretSpec](
            apiVersion = "external-secrets.io/v1beta1",
            kind = "ExternalSecret",
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "datadog-api-key",
              namespace = ns
            ),
            spec = spec
          ),
          ComponentResourceOptions(
            providers = List(prov),
            dependsOn = params.esoHelmRelease.toList ++ List(ssmParam)
          )
        )
      }
    }
