package utils.reader

import besom.*
import besom.api.kubernetes as k8s

object Reader:
  def createService(
    namespace: Output[String],
    port: Int = 80,
    replicas: Int = 1,
    image: String = "reader-server:latest"
  )(using Context): Output[k8s.core.v1.Service] =
    k8s.core.v1.Service(
      "reader-service",
      k8s.core.v1.ServiceArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = "reader",
          namespace = namespace,
          labels = Map("app" -> "reader")
        ),
        spec = k8s.core.v1.inputs.ServiceSpecArgs(
          selector = Map("app" -> "reader"),
          ports = List(
            k8s.core.v1.inputs.ServicePortArgs(
              name = "http",
              port = port,
              targetPort = 8080
            )
          ),
          `type` = "ClusterIP"
        )
      )
    )

  def createDeployment(
    namespace: Output[String],
    bucketName: Output[String],
    replicas: Int = 1,
    image: String = "reader-server:latest",
    imagePullPolicy: String = "IfNotPresent"
  )(using Context): Output[k8s.apps.v1.Deployment] =
    k8s.apps.v1.Deployment(
      "reader-deployment",
      k8s.apps.v1.DeploymentArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = "reader",
          namespace = namespace
        ),
        spec = k8s.apps.v1.inputs.DeploymentSpecArgs(
          replicas = replicas,
          selector = k8s.meta.v1.inputs.LabelSelectorArgs(
            matchLabels = Map("app" -> "reader")
          ),
          template = k8s.core.v1.inputs.PodTemplateSpecArgs(
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              labels = Map("app" -> "reader")
            ),
            spec = k8s.core.v1.inputs.PodSpecArgs(
              containers = List(
                k8s.core.v1.inputs.ContainerArgs(
                  name = "reader",
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
                      name = "S3_BUCKET_NAME",
                      value = bucketName
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
