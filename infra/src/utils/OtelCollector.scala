package utils

import besom.*
import besom.api.kubernetes as k8s

case class OtelCollectorInput(
  k8sProvider: Output[k8s.Provider],
  grafanaCloudConfig: GrafanaCloudConfig,
  cluster: Option[besom.api.aws.eks.Cluster] = None,
  nodeGroup: Option[besom.api.aws.eks.NodeGroup] = None
)

case class GrafanaCloudConfig(
  instanceId: Output[String],
  apiKey: Output[String],
  otlpEndpoint: Output[String]
)

case class OtelCollectorOutput(
  namespace: k8s.core.v1.Namespace,
  grafanaSecret: k8s.core.v1.Secret,
  helmRelease: k8s.helm.v3.Release
)

object OtelCollector extends Resource[OtelCollectorInput, OtelCollectorOutput, OtelCollectorInput, OtelCollectorOutput]:

  override def make(inputParams: OtelCollectorInput)(using Context): Output[OtelCollectorOutput] =
    for {
      ns <- createNamespace(inputParams)
      secret <- createGrafanaSecret(inputParams, ns)
      helmRelease <- installHelmChart(inputParams, ns, secret)
    } yield OtelCollectorOutput(
      namespace = ns,
      grafanaSecret = secret,
      helmRelease = helmRelease
    )

  override def makeLocal(inputParams: OtelCollectorInput)(using Context): Output[OtelCollectorOutput] =
    // For local, use the same Helm chart approach
    make(inputParams)

  // ============================================================================
  // Private Helper Functions
  // ============================================================================

  private def createNamespace(
    params: OtelCollectorInput
  )(using Context): Output[k8s.core.v1.Namespace] =
    params.k8sProvider.flatMap { prov =>
      val dependencies = List(params.cluster, params.nodeGroup).flatten
      k8s.core.v1.Namespace(
        "otel-namespace",
        k8s.core.v1.NamespaceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "opentelemetry-operator-system"
          )
        ),
        opts(provider = prov, dependsOn = dependencies)
      )
    }

  private def createGrafanaSecret(
    params: OtelCollectorInput,
    namespace: k8s.core.v1.Namespace
  )(using Context): Output[k8s.core.v1.Secret] =
    for {
      prov <- params.k8sProvider
      nsName <- namespace.metadata.name.map(_.getOrElse("opentelemetry-operator-system"))
      instanceId <- params.grafanaCloudConfig.instanceId
      apiKey <- params.grafanaCloudConfig.apiKey
      endpoint <- params.grafanaCloudConfig.otlpEndpoint
      secret <- {
        val dependencies = List(params.cluster, params.nodeGroup).flatten :+ namespace
        k8s.core.v1.Secret(
          "grafana-cloud-auth",
          k8s.core.v1.SecretArgs(
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "grafana-cloud-auth",
              namespace = nsName
            ),
            `type` = "Opaque",
            stringData = Map(
              "GRAFANA_CLOUD_INSTANCE_ID" -> instanceId,
              "GRAFANA_CLOUD_API_KEY" -> apiKey,
              "GRAFANA_CLOUD_OTLP_ENDPOINT" -> endpoint
            )
          ),
          opts(provider = prov, dependsOn = dependencies)
        )
      }
    } yield secret

  private def installHelmChart(
    params: OtelCollectorInput,
    namespace: k8s.core.v1.Namespace,
    secret: k8s.core.v1.Secret
  )(using Context): Output[k8s.helm.v3.Release] =
    params.k8sProvider.flatMap { prov =>
      namespace.metadata.name.flatMap { nsNameOpt =>
        val nsName = nsNameOpt.getOrElse("opentelemetry-operator-system")
        val dependencies = List(namespace, secret) ++ List(params.cluster, params.nodeGroup).flatten

        k8s.helm.v3.Release(
          "opentelemetry-stack",
          k8s.helm.v3.ReleaseArgs(
            name = "opentelemetry-stack",
            namespace = nsName,
            chart = "opentelemetry-kube-stack",
            repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
              repo = "https://open-telemetry.github.io/opentelemetry-helm-charts"
            ),
            values = Map(
              // Disable built-in Grafana since we're using Grafana Cloud
              "grafana" -> besom.json.JsObject(
                "enabled" -> besom.json.JsBoolean(false)
              ),
              // Disable cert-manager dependency - operator will use self-signed certs
              "opentelemetry-operator" -> besom.json.JsObject(
                "admissionWebhooks" -> besom.json.JsObject(
                  "certManager" -> besom.json.JsObject(
                    "enabled" -> besom.json.JsBoolean(false)
                  )
                )
              ),
              // Configure the DaemonSet collector to send to Grafana Cloud
              "opentelemetry-collector" -> besom.json.JsObject(
                "mode" -> besom.json.JsString("daemonset"),
                "config" -> besom.json.JsObject(
                  "receivers" -> besom.json.JsObject(
                    "otlp" -> besom.json.JsObject(
                      "protocols" -> besom.json.JsObject(
                        "grpc" -> besom.json.JsObject(
                          "endpoint" -> besom.json.JsString("0.0.0.0:4317")
                        ),
                        "http" -> besom.json.JsObject(
                          "endpoint" -> besom.json.JsString("0.0.0.0:4318")
                        )
                      )
                    )
                  ),
                  "processors" -> besom.json.JsObject(
                    "batch" -> besom.json.JsObject()
                  ),
                  "exporters" -> besom.json.JsObject(
                    "otlphttp/grafana" -> besom.json.JsObject(
                      "endpoint" -> besom.json.JsString("${env:GRAFANA_CLOUD_OTLP_ENDPOINT}"),
                      "headers" -> besom.json.JsObject(
                        "Authorization" -> besom.json.JsString("Basic ${env:GRAFANA_CLOUD_INSTANCE_ID}:${env:GRAFANA_CLOUD_API_KEY}")
                      )
                    ),
                    "debug" -> besom.json.JsObject(
                      "verbosity" -> besom.json.JsString("detailed")
                    )
                  ),
                  "service" -> besom.json.JsObject(
                    "pipelines" -> besom.json.JsObject(
                      "traces" -> besom.json.JsObject(
                        "receivers" -> besom.json.JsArray(Vector(besom.json.JsString("otlp"))),
                        "processors" -> besom.json.JsArray(Vector(besom.json.JsString("batch"))),
                        "exporters" -> besom.json.JsArray(Vector(besom.json.JsString("otlphttp/grafana"), besom.json.JsString("debug")))
                      ),
                      "metrics" -> besom.json.JsObject(
                        "receivers" -> besom.json.JsArray(Vector(besom.json.JsString("otlp"))),
                        "processors" -> besom.json.JsArray(Vector(besom.json.JsString("batch"))),
                        "exporters" -> besom.json.JsArray(Vector(besom.json.JsString("otlphttp/grafana"), besom.json.JsString("debug")))
                      ),
                      "logs" -> besom.json.JsObject(
                        "receivers" -> besom.json.JsArray(Vector(besom.json.JsString("otlp"))),
                        "processors" -> besom.json.JsArray(Vector(besom.json.JsString("batch"))),
                        "exporters" -> besom.json.JsArray(Vector(besom.json.JsString("otlphttp/grafana"), besom.json.JsString("debug")))
                      )
                    )
                  )
                ),
                "env" -> besom.json.JsArray(Vector(
                  besom.json.JsObject(
                    "name" -> besom.json.JsString("GRAFANA_CLOUD_OTLP_ENDPOINT"),
                    "valueFrom" -> besom.json.JsObject(
                      "secretKeyRef" -> besom.json.JsObject(
                        "name" -> besom.json.JsString("grafana-cloud-auth"),
                        "key" -> besom.json.JsString("GRAFANA_CLOUD_OTLP_ENDPOINT")
                      )
                    )
                  ),
                  besom.json.JsObject(
                    "name" -> besom.json.JsString("GRAFANA_CLOUD_INSTANCE_ID"),
                    "valueFrom" -> besom.json.JsObject(
                      "secretKeyRef" -> besom.json.JsObject(
                        "name" -> besom.json.JsString("grafana-cloud-auth"),
                        "key" -> besom.json.JsString("GRAFANA_CLOUD_INSTANCE_ID")
                      )
                    )
                  ),
                  besom.json.JsObject(
                    "name" -> besom.json.JsString("GRAFANA_CLOUD_API_KEY"),
                    "valueFrom" -> besom.json.JsObject(
                      "secretKeyRef" -> besom.json.JsObject(
                        "name" -> besom.json.JsString("grafana-cloud-auth"),
                        "key" -> besom.json.JsString("GRAFANA_CLOUD_API_KEY")
                      )
                    )
                  )
                ))
              )
            )
          ),
          opts(provider = prov, dependsOn = dependencies)
        )
      }
    }
