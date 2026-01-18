package utils

import besom.*
import besom.api.kubernetes as k8s

object K8s:

  def createNamespace(
      name: String,
      cluster: Output[besom.api.aws.eks.Cluster],
      nodeGroup: Output[besom.api.aws.eks.NodeGroup],
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.core.v1.Namespace] =
    createNamespace(name, Some(cluster), Some(nodeGroup), provider)

  def createNamespace(
      name: String,
      cluster: Option[Output[besom.api.aws.eks.Cluster]],
      nodeGroup: Option[Output[besom.api.aws.eks.NodeGroup]] = None,
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.core.v1.Namespace] =
    provider.flatMap { prov =>
      val dependencies = List(cluster, nodeGroup).flatten
      k8s.core.v1.Namespace(
        NonEmptyString(name).getOrElse {
          throw new IllegalArgumentException(
            s"Namespace name cannot be empty. Provided: '$name'"
          )
        },
        k8s.core.v1.NamespaceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = name
          )
        ),
        opts(dependsOn = dependencies, provider = prov)
      )
    }

  def createHeadlessService(
      name: String,
      namespace: Output[String],
      selector: Map[String, String],
      port: Int,
      portName: String = "client",
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.core.v1.Service] =
    provider.flatMap { prov =>
      k8s.core.v1.Service(
        s"$name-service",
        k8s.core.v1.ServiceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = name,
            namespace = namespace,
            labels = selector
          ),
          spec = k8s.core.v1.inputs.ServiceSpecArgs(
            selector = selector,
            ports = List(
              k8s.core.v1.inputs.ServicePortArgs(
                name = portName,
                port = port,
                targetPort = port
              )
            ),
            clusterIP = "None"
          )
        ),
        opts(provider = prov)
      )
    }

  def createStatefulSet(
      name: String,
      namespace: Output[String],
      serviceName: String,
      image: String,
      replicas: Int = 1,
      storageSize: String = "1Gi",
      ports: Map[String, Int],
      envVars: Map[String, String],
      volumeMounts: Map[String, String],
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.apps.v1.StatefulSet] =
    provider.flatMap { prov =>
      k8s.apps.v1.StatefulSet(
        s"$name-statefulset",
        k8s.apps.v1.StatefulSetArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = name,
            namespace = namespace
          ),
          spec = k8s.apps.v1.inputs.StatefulSetSpecArgs(
            serviceName = serviceName,
            replicas = replicas,
            selector = k8s.meta.v1.inputs.LabelSelectorArgs(
              matchLabels = Map("app" -> name)
            ),
            template = k8s.core.v1.inputs.PodTemplateSpecArgs(
              metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                labels = Map("app" -> name)
              ),
              spec = k8s.core.v1.inputs.PodSpecArgs(
                containers = List(
                  k8s.core.v1.inputs.ContainerArgs(
                    name = name,
                    image = image,
                    ports = ports.map((name, value) =>
                      k8s.core.v1.inputs.ContainerPortArgs(
                        containerPort = value,
                        name = name
                      )
                    ),
                    env = envVars.map((name, value) =>
                      k8s.core.v1.inputs.EnvVarArgs(
                        name = name,
                        value = value
                      )
                    ),
                    volumeMounts = volumeMounts.map((name, value) =>
                      k8s.core.v1.inputs.VolumeMountArgs(
                        name = name,
                        mountPath = value
                      )
                    )
                  )
                )
              )
            ),
            volumeClaimTemplates = List(
              k8s.core.v1.inputs.PersistentVolumeClaimArgs(
                metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                  name = s"$name-data"
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
        ),
        opts(provider = prov)
      )
    }

  /** Creates the aws-auth ConfigMap for EKS to allow nodes to join the cluster.
    * This must be created BEFORE the node group so nodes can successfully join.
    */
  def createAwsAuthConfigMap(
      nodeRoleArn: Output[String],
      cluster: Output[besom.api.aws.eks.Cluster],
      provider: Output[k8s.Provider]
  )(using Context): Output[k8s.core.v1.ConfigMap] =
    provider.flatMap { prov =>
      cluster.flatMap { clusterResource =>
        k8s.core.v1.ConfigMap(
          "aws-auth",
          k8s.core.v1.ConfigMapArgs(
            metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
              annotations = Map("pulumi.com/patchForce" -> "true"),
              name = "aws-auth",
              namespace = "kube-system"
            ),
            data = nodeRoleArn.map(arn =>
              Map(
                "mapRoles" -> s"""- rolearn: $arn
  username: system:node:{{EC2PrivateDNSName}}
  groups:
    - system:bootstrappers
    - system:nodes"""
              )
            )
          ),
          opts(dependsOn = clusterResource, provider = prov)
        )
      }
    }
