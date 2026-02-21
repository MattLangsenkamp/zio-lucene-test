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
      bucketName: Output[String],
      messagingMode: String,
      kafkaBootstrapServers: Option[Output[String]] = None,
      sqsQueueUrl: Option[Output[String]] = None,
      replicas: Int = 1,
      image: String = "ingestion-server:latest",
      imagePullPolicy: String = "IfNotPresent",
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.apps.v1.Deployment] =
    val messagingEnvVars: List[k8s.core.v1.inputs.EnvVarArgs] = messagingMode match
      case "kafka" =>
        kafkaBootstrapServers match
          case Some(servers) =>
            List(
              k8s.core.v1.inputs.EnvVarArgs(
                name = "MESSAGING_MODE",
                value = "kafka"
              ),
              k8s.core.v1.inputs.EnvVarArgs(
                name = "KAFKA_BOOTSTRAP_SERVERS",
                value = servers
              )
            )
          case None =>
            throw new IllegalArgumentException(
              "kafkaBootstrapServers required when messagingMode is 'kafka'"
            )
      case "sqs" =>
        sqsQueueUrl match
          case Some(url) =>
            List(
              k8s.core.v1.inputs.EnvVarArgs(
                name = "MESSAGING_MODE",
                value = "sqs"
              ),
              k8s.core.v1.inputs.EnvVarArgs(
                name = "SQS_QUEUE_URL",
                value = url
              )
            )
          case None =>
            throw new IllegalArgumentException(
              "sqsQueueUrl required when messagingMode is 'sqs'"
            )
      case other =>
        throw new IllegalArgumentException(
          s"Unknown messagingMode: $other. Expected 'kafka' or 'sqs'"
        )

    val baseEnvVars = List(
      k8s.core.v1.inputs.EnvVarArgs(
        name = "S3_BUCKET_NAME",
        value = bucketName
      )
    )

    provider.flatMap { prov =>
      val configMap = k8s.core.v1.ConfigMap(
        "ingestion-wiki-config",
        k8s.core.v1.ConfigMapArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "ingestion-wiki-config",
            namespace = namespace
          ),
          data = Map(
            "WIKI_LANG"                -> "en",
            "WIKI_STREAM"              -> "recentchange",
            "WIKI_BACKOFF_START_MS"    -> "1000",
            "WIKI_BACKOFF_INCREMENT_MS" -> "1000",
            "WIKI_BACKOFF_MAX_MS"      -> "30000"
          )
        ),
        opts(provider = prov)
      )

      configMap.flatMap { cm =>
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
                      env = baseEnvVars ++ messagingEnvVars,
                      envFrom = List(
                        k8s.core.v1.inputs.EnvFromSourceArgs(
                          configMapRef = k8s.core.v1.inputs.ConfigMapEnvSourceArgs(
                            name = cm.metadata.name
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          ),
          opts(provider = prov, dependsOn = cm)
        )
      }
    }
