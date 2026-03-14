package platform

import besom.*
import besom.api.kubernetes as k8s
import besom.json.*

/**
 * Installs the External Secrets Operator (ESO) via Helm and creates a ClusterSecretStore
 * backed by AWS SSM ParameterStore. The single ClusterSecretStore is shared across all
 * services; each service's ExternalSecret objects reference it by name ("aws-ssm-store").
 *
 * Cloud: ESO uses IRSA for AWS authentication.
 * Local: ESO is bypassed — Pulumi creates k8s Secrets directly (see Main.scala).
 */

case class ExternalSecretsInput(
  k8sProvider: Output[k8s.Provider],
  env: String,
  // EKS only — IRSA service account name for the ESO pods
  irsaServiceAccountName: Option[Output[String]] = None,
  // Wait for cluster/nodegroup before installing
  cluster: Option[Output[besom.api.aws.eks.Cluster]] = None,
  nodeGroup: Option[Output[besom.api.aws.eks.NodeGroup]] = None
)

case class ExternalSecretsOutput(
  namespace: k8s.core.v1.Namespace,
  helmRelease: k8s.helm.v3.Release,
  secretStore: k8s.apiextensions.CustomResource[ExternalSecrets.ClusterSecretStoreSpec]
)

object ExternalSecrets:

  // ── Spec types ────────────────────────────────────────────────────────────

  case class ClusterSecretStoreSpec(provider: StoreProvider) derives Encoder, Decoder
  case class StoreProvider(aws: AwsSsmProvider) derives Encoder, Decoder

  case class AwsSsmProvider(
    service: String,
    region: String,
    auth: Option[SsmAuth] = None
  ) derives Encoder, Decoder

  // auth.jwt  — used for IRSA (cloud)
  // auth.secretRef — used for static credentials (local/LocalStack)
  case class SsmAuth(
    jwt: Option[JwtAuth] = None,
    secretRef: Option[SecretRefAuth] = None
  ) derives Encoder, Decoder
  case class JwtAuth(serviceAccountRef: Option[ServiceAccountRef] = None) derives Encoder, Decoder
  case class ServiceAccountRef(name: String, namespace: String) derives Encoder, Decoder
  case class SecretRefAuth(
    accessKeyIDSecretRef: SecretKeyRef,
    secretAccessKeySecretRef: SecretKeyRef
  ) derives Encoder, Decoder
  case class SecretKeyRef(name: String, key: String, namespace: String) derives Encoder, Decoder

  // ── Public API ────────────────────────────────────────────────────────────

  // Cloud (EKS) — standard AWS SSM with IRSA
  def make(params: ExternalSecretsInput)(using Context): Output[ExternalSecretsOutput] =
    for {
      ns          <- createNamespace(params)
      helm        <- installHelmRelease(ns, params, localMode = false)
      secretStore <- createClusterSecretStore(params, helm, localMode = false)
    } yield ExternalSecretsOutput(namespace = ns, helmRelease = helm, secretStore = secretStore)

  // Local (k3d + LocalStack) — fake creds, LocalStack SSM endpoint
  def makeLocal(params: ExternalSecretsInput)(using Context): Output[ExternalSecretsOutput] =
    for {
      ns               <- createNamespace(params)
      localStackCreds  <- createLocalStackCredentials(params)
      helm             <- installHelmRelease(ns, params, localMode = true)
      secretStore      <- createClusterSecretStore(params, helm, localMode = true)
    } yield ExternalSecretsOutput(namespace = ns, helmRelease = helm, secretStore = secretStore)

  // ── Private helpers ───────────────────────────────────────────────────────

  private def createNamespace(params: ExternalSecretsInput)(using Context): Output[k8s.core.v1.Namespace] =
    params.k8sProvider.flatMap { prov =>
      val deps = List(params.cluster, params.nodeGroup).flatten
      k8s.core.v1.Namespace(
        "external-secrets-namespace",
        k8s.core.v1.NamespaceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(name = "external-secrets")
        ),
        opts(provider = prov, dependsOn = deps)
      )
    }

  private def createLocalStackCredentials(params: ExternalSecretsInput)(using Context): Output[k8s.core.v1.Secret] =
    params.k8sProvider.flatMap { prov =>
      k8s.core.v1.Secret(
        "eso-localstack-credentials",
        k8s.core.v1.SecretArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "eso-localstack-credentials",
            namespace = "external-secrets"
          ),
          `type` = "Opaque",
          stringData = Map("access-key" -> "test", "secret-key" -> "test")
        ),
        opts(provider = prov)
      )
    }

  private def installHelmRelease(
    ns: k8s.core.v1.Namespace,
    params: ExternalSecretsInput,
    localMode: Boolean
  )(using Context): Output[k8s.helm.v3.Release] =
    params.k8sProvider.flatMap { prov =>
      val extraValues: Map[String, JsValue] =
        if (localMode)
          Map(
            // In local mode ESO calls LocalStack.
            // Use extraEnv (list format) — more reliable than the env map for arbitrary keys.
            // AWS_ENDPOINT_URL routes ALL AWS SDK calls (including SSM) to LocalStack.
            "extraEnv" -> JsArray(
              JsObject("name" -> JsString("AWS_ENDPOINT_URL"),     "value" -> JsString("http://host.k3d.internal:4566")),
              JsObject("name" -> JsString("AWS_ACCESS_KEY_ID"),    "value" -> JsString("test")),
              JsObject("name" -> JsString("AWS_SECRET_ACCESS_KEY"),"value" -> JsString("test"))
            )
          )
        else
          Map.empty

      k8s.helm.v3.Release(
        "external-secrets-operator",
        k8s.helm.v3.ReleaseArgs(
          name = "external-secrets",
          chart = "external-secrets",
          version = "0.11.0",
          namespace = ns.metadata.name,
          timeout = 300,
          repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
            repo = "https://charts.external-secrets.io"
          ),
          values = Map[String, JsValue]("installCRDs" -> JsBoolean(true)) ++ extraValues
        ),
        opts(provider = prov, dependsOn = ns)
      )
    }

  private def createClusterSecretStore(
    params: ExternalSecretsInput,
    helmRelease: k8s.helm.v3.Release,
    localMode: Boolean
  )(using Context): Output[k8s.apiextensions.CustomResource[ClusterSecretStoreSpec]] =
    params.k8sProvider.flatMap { prov =>
      // specOutput resolves the IRSA service account name (Output[String]) before building the spec
      val specOutput: Output[ClusterSecretStoreSpec] = params.irsaServiceAccountName match
        case Some(saNameOut) if !localMode =>
          saNameOut.map { saName =>
            ClusterSecretStoreSpec(
              provider = StoreProvider(
                aws = AwsSsmProvider(
                  service = "ParameterStore",
                  region = "us-east-1",
                  auth = Some(SsmAuth(jwt = Some(JwtAuth(
                    serviceAccountRef = Some(ServiceAccountRef(name = saName, namespace = "external-secrets"))
                  ))))
                )
              )
            )
          }
        case _ =>
          // Local: static credentials from eso-localstack-credentials Secret.
          // Endpoint routing to LocalStack is via AWS_ENDPOINT_URL env var on ESO pods.
          Output(ClusterSecretStoreSpec(
            provider = StoreProvider(
              aws = AwsSsmProvider(
                service = "ParameterStore",
                region = "us-east-1",
                auth = Some(SsmAuth(secretRef = Some(SecretRefAuth(
                  accessKeyIDSecretRef     = SecretKeyRef("eso-localstack-credentials", "access-key", "external-secrets"),
                  secretAccessKeySecretRef = SecretKeyRef("eso-localstack-credentials", "secret-key", "external-secrets")
                ))))
              )
            )
          ))

      specOutput.flatMap { spec =>
        k8s.apiextensions.CustomResource[ClusterSecretStoreSpec](
          "aws-ssm-store",
          k8s.apiextensions.CustomResourceArgs[ClusterSecretStoreSpec](
            apiVersion = "external-secrets.io/v1beta1",
            kind = "ClusterSecretStore",
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(name = "aws-ssm-store"),
            spec = spec
          ),
          ComponentResourceOptions(
            providers = List(prov),
            dependsOn = List(helmRelease)
          )
        )
      }
    }
