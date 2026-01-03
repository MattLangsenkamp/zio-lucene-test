package utils.ingestion

import besom.*
import besom.api.kubernetes as k8s

object Ingestion:
  def createService(
    namespace: Output[String],
    port: Int = 80,
    replicas: Int = 1,
    image: String = "ingestion-server:latest",
    provider: Output[k8s.Provider]
  )(using Context): Output[k8s.core.v1.Service] =
    provider.flatMap { prov =>
      k8s.core.v1.Service(
        "ingestion-service",
        k8s.core.v1.ServiceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "ingestion",
            namespace = namespace,
            labels = Map("app" -> "ingestion")
          ),
          spec = k8s.core.v1.inputs.ServiceSpecArgs(
            selector = Map("app" -> "ingestion"),
            ports = List(
              k8s.core.v1.inputs.ServicePortArgs(
                name = "http",
                port = port,
                targetPort = 8080
              )
            ),
            `type` = "ClusterIP"
          )
        ),
        opts(provider = prov)
      )
    }

  def createDeployment(
    namespace: Output[String],
    kafkaBootstrapServers: Output[String],
    bucketName: Output[String],
    replicas: Int = 1,
    image: String = "ingestion-server:latest",
    imagePullPolicy: String = "IfNotPresent",
    provider: Output[k8s.Provider]
  )(using Context): Output[k8s.apps.v1.Deployment] =
    provider.flatMap { prov =>
      k8s.apps.v1.Deployment(
        "ingestion-deployment",
        k8s.apps.v1.DeploymentArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "ingestion",
            namespace = namespace
          ),
          spec = k8s.apps.v1.inputs.DeploymentSpecArgs(
            replicas = replicas,
            selector = k8s.meta.v1.inputs.LabelSelectorArgs(
              matchLabels = Map("app" -> "ingestion")
            ),
            template = k8s.core.v1.inputs.PodTemplateSpecArgs(
              metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                labels = Map("app" -> "ingestion")
              ),
              spec = k8s.core.v1.inputs.PodSpecArgs(
                containers = List(
                  k8s.core.v1.inputs.ContainerArgs(
                    name = "ingestion",
                    image = image,
                    imagePullPolicy = imagePullPolicy,
                    ports = List(
                      k8s.core.v1.inputs.ContainerPortArgs(
                        containerPort = 8080,
                        name = "http"
                      )
                    ),
                    env = List(
                      k8s.core.v1.inputs.EnvVarArgs(
                        name = "KAFKA_BOOTSTRAP_SERVERS",
                        value = kafkaBootstrapServers
                      ),
                      k8s.core.v1.inputs.EnvVarArgs(
                        name = "S3_BUCKET_NAME",
                        value = bucketName
                      )
                    )
                  )
                )
              )
            )
          )
        ),
        opts(provider = prov)
      )
    }
