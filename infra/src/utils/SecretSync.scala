package utils

import besom.*
import besom.api.kubernetes as k8s
import besom.json.*

// ============================================================================
// Resource Inputs/Outputs
// ============================================================================

case class SecretSyncInput(
  namespace: Output[String],
  k8sProvider: Output[k8s.Provider],
  irsaServiceAccountName: Option[Output[String]] = None,  // For EKS with IRSA
  region: String = "us-east-1",
  helmChart: Option[Output[k8s.helm.v3.Release]] = None  // Helm release to wait for
)

case class SecretSyncOutput(
  secretStore: k8s.apiextensions.CustomResource[?],  // ClusterSecretStore or SecretStore
  externalSecret: k8s.apiextensions.CustomResource[SecretSync.ExternalSecretSpec]
)

/**
 * Creates External Secrets resources to sync secrets from AWS Secrets Manager (or LocalStack)
 * into Kubernetes secrets.
 *
 * This resource creates:
 * - ClusterSecretStore (for EKS with IRSA) or SecretStore (for k3d with LocalStack credentials)
 * - ExternalSecret to sync the Datadog API key
 */
object SecretSync extends Resource[SecretSyncInput, SecretSyncOutput, SecretSyncInput, SecretSyncOutput]:

  // ============================================================================
  // Spec Types for CustomResources
  // ============================================================================

  // ClusterSecretStore spec for AWS Secrets Manager (EKS with IRSA)
  case class ClusterSecretStoreSpec(
    provider: ClusterSecretStoreProvider
  ) derives Encoder, Decoder

  case class ClusterSecretStoreProvider(
    aws: AwsSecretsManagerProvider
  ) derives Encoder, Decoder

  case class AwsSecretsManagerProvider(
    service: String,  // "SecretsManager"
    region: String,
    auth: Option[AwsAuth] = None
  ) derives Encoder, Decoder

  case class AwsAuth(
    jwt: Option[JwtAuth] = None
  ) derives Encoder, Decoder

  case class JwtAuth(
    serviceAccountRef: Option[ServiceAccountRef] = None
  ) derives Encoder, Decoder

  case class ServiceAccountRef(
    name: String
  ) derives Encoder, Decoder

  // SecretStore spec for LocalStack (k3d)
  case class SecretStoreSpec(
    provider: SecretStoreProvider
  ) derives Encoder, Decoder

  case class SecretStoreProvider(
    aws: LocalAwsSecretsManagerProvider
  ) derives Encoder, Decoder

  case class LocalAwsSecretsManagerProvider(
    service: String,  // "SecretsManager"
    region: String,
    auth: Option[LocalAwsAuth] = None
  ) derives Encoder, Decoder

  case class LocalAwsAuth(
    secretRef: Option[SecretKeyRef] = None
  ) derives Encoder, Decoder

  case class SecretKeyRef(
    accessKeyIDSecretRef: KeySelector,
    secretAccessKeySecretRef: KeySelector
  ) derives Encoder, Decoder

  case class KeySelector(
    name: String,
    key: String
  ) derives Encoder, Decoder

  // ExternalSecret spec
  case class ExternalSecretSpec(
    refreshInterval: String,
    secretStoreRef: SecretStoreRef,
    target: ExternalSecretTarget,
    data: List[ExternalSecretData]
  ) derives Encoder, Decoder

  case class SecretStoreRef(
    name: String,
    kind: String  // "ClusterSecretStore" or "SecretStore"
  ) derives Encoder, Decoder

  case class ExternalSecretTarget(
    name: String,
    creationPolicy: String  // "Owner"
  ) derives Encoder, Decoder

  case class ExternalSecretData(
    secretKey: String,
    remoteRef: RemoteRef
  ) derives Encoder, Decoder

  case class RemoteRef(
    key: String
  ) derives Encoder, Decoder

  // ============================================================================
  // Resource Implementation
  // ============================================================================

  override def make(inputParams: SecretSyncInput)(using Context): Output[SecretSyncOutput] =
    // Create ClusterSecretStore for EKS with IRSA
    for {
      store <- createClusterSecretStore(inputParams)
      externalSecret <- createExternalSecret(inputParams, Output(store), "ClusterSecretStore", "aws-secretsmanager")
    } yield SecretSyncOutput(
      secretStore = store,
      externalSecret = externalSecret
    )

  override def makeLocal(inputParams: SecretSyncInput)(using Context): Output[SecretSyncOutput] =
    // Create LocalStack credentials secret, then SecretStore, then ExternalSecret
    for {
      _ <- createLocalStackCredentials(inputParams)
      store <- createLocalSecretStore(inputParams)
      externalSecret <- createExternalSecret(inputParams, Output(store), "SecretStore", "localstack-secretsmanager")
    } yield SecretSyncOutput(
      secretStore = store,
      externalSecret = externalSecret
    )

  // ============================================================================
  // Private Helper Functions
  // ============================================================================

  private def createLocalStackCredentials(
    params: SecretSyncInput
  )(using Context): Output[k8s.core.v1.Secret] =
    params.k8sProvider.flatMap { prov =>
      params.namespace.flatMap { ns =>
        k8s.core.v1.Secret(
          "localstack-credentials",
          k8s.core.v1.SecretArgs(
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "localstack-credentials",
              namespace = ns
            ),
            `type` = "Opaque",
            stringData = Map(
              "access-key" -> "test",
              "secret-key" -> "test"
            )
          ),
          opts(provider = prov)
        )
      }
    }

  private def createClusterSecretStore(
    params: SecretSyncInput
  )(using Context): Output[k8s.apiextensions.CustomResource[ClusterSecretStoreSpec]] =
    params.k8sProvider.flatMap { prov =>
      val spec = params.irsaServiceAccountName match {
        case Some(saName) =>
          saName.map { name =>
            ClusterSecretStoreSpec(
              provider = ClusterSecretStoreProvider(
                aws = AwsSecretsManagerProvider(
                  service = "SecretsManager",
                  region = params.region,
                  auth = Some(AwsAuth(
                    jwt = Some(JwtAuth(
                      serviceAccountRef = Some(ServiceAccountRef(name = name))
                    ))
                  ))
                )
              )
            )
          }
        case None =>
          // No IRSA - use default credentials (for local testing or EC2 instance role)
          Output(ClusterSecretStoreSpec(
            provider = ClusterSecretStoreProvider(
              aws = AwsSecretsManagerProvider(
                service = "SecretsManager",
                region = params.region,
                auth = None
              )
            )
          ))
      }

      spec.flatMap { s =>
        k8s.apiextensions.CustomResource[ClusterSecretStoreSpec](
          "aws-secretsmanager",
          k8s.apiextensions.CustomResourceArgs[ClusterSecretStoreSpec](
            apiVersion = "external-secrets.io/v1beta1",
            kind = "ClusterSecretStore",
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "aws-secretsmanager"
            ),
            spec = s
          ),
          ComponentResourceOptions(
            providers = List(prov),
            dependsOn = params.helmChart.toList
          )
        )
      }
    }

  private def createLocalSecretStore(
    params: SecretSyncInput
  )(using Context): Output[k8s.apiextensions.CustomResource[SecretStoreSpec]] =
    params.k8sProvider.flatMap { prov =>
      params.namespace.flatMap { ns =>
        val spec = SecretStoreSpec(
          provider = SecretStoreProvider(
            aws = LocalAwsSecretsManagerProvider(
              service = "SecretsManager",
              region = params.region,
              auth = Some(LocalAwsAuth(
                secretRef = Some(SecretKeyRef(
                  accessKeyIDSecretRef = KeySelector(
                    name = "localstack-credentials",
                    key = "access-key"
                  ),
                  secretAccessKeySecretRef = KeySelector(
                    name = "localstack-credentials",
                    key = "secret-key"
                  )
                ))
              ))
            )
          )
        )

        k8s.apiextensions.CustomResource[SecretStoreSpec](
          "localstack-secretsmanager",
          k8s.apiextensions.CustomResourceArgs[SecretStoreSpec](
            apiVersion = "external-secrets.io/v1beta1",
            kind = "SecretStore",
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "localstack-secretsmanager",
              namespace = ns
            ),
            spec = spec
          ),
          ComponentResourceOptions(
            providers = List(prov)
          )
        )
      }
    }

  private def createExternalSecret(
    params: SecretSyncInput,
    secretStore: Output[k8s.apiextensions.CustomResource[?]],
    secretStoreKind: String,
    secretStoreName: String
  )(using Context): Output[k8s.apiextensions.CustomResource[ExternalSecretSpec]] =
    params.k8sProvider.flatMap { prov =>
      params.namespace.flatMap { ns =>
        val spec = ExternalSecretSpec(
          refreshInterval = "1h",
          secretStoreRef = SecretStoreRef(
            name = secretStoreName,
            kind = secretStoreKind
          ),
          target = ExternalSecretTarget(
            name = "datadog-api-key",
            creationPolicy = "Owner"
          ),
          data = List(
            ExternalSecretData(
              secretKey = "api-key",
              remoteRef = RemoteRef(
                key = "zio-lucene/datadog-api-key"
              )
            )
          )
        )

        k8s.apiextensions.CustomResource[ExternalSecretSpec](
          "datadog-api-key",
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
            dependsOn = List(secretStore)
          )
        )
      }
    }
