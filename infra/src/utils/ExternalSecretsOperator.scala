package utils

import besom.*
import besom.api.kubernetes as k8s

case class ExternalSecretsOperatorInput(
    k8sProvider: Output[k8s.Provider],
    cluster: Option[Output[besom.api.aws.eks.Cluster]] = None,
    nodeGroup: Option[Output[besom.api.aws.eks.NodeGroup]] = None
)

case class ExternalSecretsOperatorOutput(
    namespace: k8s.core.v1.Namespace,
    helmRelease: k8s.helm.v3.Release
)

object ExternalSecretsOperator
    extends Resource[
      ExternalSecretsOperatorInput,
      ExternalSecretsOperatorOutput,
      ExternalSecretsOperatorInput,
      ExternalSecretsOperatorOutput
    ]:

  override def make(
      inputParams: ExternalSecretsOperatorInput
  )(using Context): Output[ExternalSecretsOperatorOutput] =
    for {
      namespace <- createNamespace(inputParams)
      helmRelease <- installHelmRelease(namespace, inputParams)
    } yield ExternalSecretsOperatorOutput(
      namespace = namespace,
      helmRelease = helmRelease
    )

  override def makeLocal(inputParams: ExternalSecretsOperatorInput)(using
      Context
  ): Output[ExternalSecretsOperatorOutput] =
    make(inputParams)

  private def createNamespace(params: ExternalSecretsOperatorInput)(using
      Context
  ): Output[k8s.core.v1.Namespace] =
    params.k8sProvider.flatMap { prov =>
      val dependencies = List(params.cluster, params.nodeGroup).flatten
      k8s.core.v1.Namespace(
        "external-secrets-namespace",
        k8s.core.v1.NamespaceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "external-secrets"
          )
        ),
        opts(provider = prov, dependsOn = dependencies)
      )
    }

  private def installHelmRelease(
      namespace: k8s.core.v1.Namespace,
      params: ExternalSecretsOperatorInput
  )(using Context): Output[k8s.helm.v3.Release] =
    params.k8sProvider.flatMap { prov =>
      k8s.helm.v3.Release(
        "external-secrets-operator",
        k8s.helm.v3.ReleaseArgs(
          name = "external-secrets",
          chart = "external-secrets",
          version = "0.11.0", // Chart version for app v1.2.1
          namespace = namespace.metadata.name,
          repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
            repo = "https://charts.external-secrets.io"
          ),
          values = {
            import besom.json.*
            Map[String, JsValue](
              "installCRDs" -> JsBoolean(true)
            )
          }
        ),
        opts(provider = prov, dependsOn = namespace)
      )
    }
