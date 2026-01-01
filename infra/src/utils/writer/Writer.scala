package utils.writer

import besom.*
import besom.api.kubernetes as k8s

object Writer:
  def createService(
    namespace: Output[String],
    port: Int = 8082,
    replicas: Int = 1,
    image: String = "writer-server:latest"
  )(using Context): Output[k8s.core.v1.Service] =
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
          clusterIP = "None"  // Headless service for StatefulSet
        )
      )
    )

  def createStatefulSet(
    namespace: Output[String],
    kafkaBootstrapServers: Output[String],
    bucketName: Output[String],
    serviceName: String = "writer",
    replicas: Int = 1,
    image: String = "writer-server:latest",
    imagePullPolicy: String = "IfNotPresent",
    storageSize: String = "1Gi"
  )(using Context): Output[k8s.apps.v1.StatefulSet] =
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
                  env = List(
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_BOOTSTRAP_SERVERS",
                      value = kafkaBootstrapServers
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "S3_BUCKET_NAME",
                      value = bucketName
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
                resources = k8s.core.v1.inputs.VolumeResourceRequirementsArgs(
                  requests = Map("storage" -> storageSize)
                )
              )
            )
          )
        )
      )
    )
