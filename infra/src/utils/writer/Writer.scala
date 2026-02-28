package utils.writer

import besom.*
import besom.api.kubernetes as k8s

object Writer:
  def createService(
      namespace: Output[String],
      port: Int = 8082,
      replicas: Int = 1,
      image: String = "writer-server:latest",
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.core.v1.Service] =
    provider.flatMap { prov =>
      k8s.core.v1.Service(
        "writer-service",
        k8s.core.v1.ServiceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "writer",
            namespace = namespace,
            labels = Map("app" -> "writer")
          ),
          spec = k8s.core.v1.inputs.ServiceSpecArgs(
            selector = Map("app" -> "writer"),
            ports = List(
              k8s.core.v1.inputs.ServicePortArgs(
                name = "http",
                port = port,
                targetPort = 8080
              )
            ),
            clusterIP = "None" // Headless service for StatefulSet
          )
        ),
        opts(provider = prov)
      )
    }

  def createStatefulSet(
      namespace: Output[String],
      bucketName: Output[String],
      messagingMode: String,
      kafkaBootstrapServers: Option[Output[String]] = None,
      sqsQueueUrl: Option[Output[String]] = None,
      sqsEndpointOverride: Option[String] = None,
      serviceName: String = "writer",
      replicas: Int = 1,
      image: String = "writer-server:latest",
      imagePullPolicy: String = "IfNotPresent",
      storageSize: String = "1Gi",
      storageClassName: String = "gp2",
      provider: Output[k8s.Provider],
      dependencies: Output[besom.api.aws.eks.Addon]*
  )(using Context): Output[k8s.apps.v1.StatefulSet] =
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
          case Some(_) =>
            List(
              k8s.core.v1.inputs.EnvVarArgs(
                name = "MESSAGING_MODE",
                value = "sqs"
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

    val awsEndpointEnvVars: List[k8s.core.v1.inputs.EnvVarArgs] = sqsEndpointOverride match
      case Some(endpoint) => List(
        k8s.core.v1.inputs.EnvVarArgs(name = "AWS_ENDPOINT_URL_SQS",  value = endpoint),
        k8s.core.v1.inputs.EnvVarArgs(name = "AWS_ACCESS_KEY_ID",     value = "test"),
        k8s.core.v1.inputs.EnvVarArgs(name = "AWS_SECRET_ACCESS_KEY", value = "test")
      )
      case None => List.empty

    val baseEnvVars = List(
      k8s.core.v1.inputs.EnvVarArgs(name = "S3_BUCKET_NAME", value = bucketName),
      k8s.core.v1.inputs.EnvVarArgs(name = "AWS_REGION",     value = "us-east-1")
    ) ++ awsEndpointEnvVars

    val configMapData: Output[Map[String, String]] = (messagingMode, sqsQueueUrl) match
      case ("sqs", Some(url)) => url.map(u => Map("SQS_QUEUE_URL" -> u))
      case _                  => Output(Map.empty[String, String])

    provider.flatMap { prov =>
      val configMap = k8s.core.v1.ConfigMap(
        "writer-config",
        k8s.core.v1.ConfigMapArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "writer-config",
            namespace = namespace
          ),
          data = configMapData
        ),
        opts(provider = prov)
      )

      configMap.flatMap { cm =>
        k8s.apps.v1.StatefulSet(
          "writer-statefulset",
          k8s.apps.v1.StatefulSetArgs(
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              name = "writer",
              namespace = namespace
            ),
            spec = k8s.apps.v1.inputs.StatefulSetSpecArgs(
              serviceName = serviceName,
              replicas = replicas,
              selector = k8s.meta.v1.inputs.LabelSelectorArgs(
                matchLabels = Map("app" -> "writer")
              ),
              template = k8s.core.v1.inputs.PodTemplateSpecArgs(
                metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                  labels = Map("app" -> "writer")
                ),
                spec = k8s.core.v1.inputs.PodSpecArgs(
                  containers = List(
                    k8s.core.v1.inputs.ContainerArgs(
                      name = "writer",
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
                      ),
                      volumeMounts = List(
                        k8s.core.v1.inputs.VolumeMountArgs(
                          name = "writer-data",
                          mountPath = "/data"
                        )
                      )
                    )
                  )
                )
              ),
              volumeClaimTemplates = List(
                k8s.core.v1.inputs.PersistentVolumeClaimArgs(
                  metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                    name = "writer-data"
                  ),
                  spec = k8s.core.v1.inputs.PersistentVolumeClaimSpecArgs(
                    accessModes = List("ReadWriteOnce"),
                    storageClassName = storageClassName,
                    resources = k8s.core.v1.inputs.VolumeResourceRequirementsArgs(
                      requests = Map("storage" -> storageSize)
                    )
                  )
                )
              )
            )
          ),
          opts(provider = prov, dependsOn = dependencies)
        )
      }
    }
