package utils

import besom.*
import besom.api.kubernetes as k8s

object K8s:

  def createNamespace(
    name: String,
    cluster: besom.api.aws.eks.Cluster,
    nodeGroup: besom.api.aws.eks.NodeGroup
  )(using Context): Output[k8s.core.v1.Namespace] =
    createNamespace(name, Some(cluster), Some(nodeGroup))

  def createNamespace(
    name: String,
    cluster: Option[besom.api.aws.eks.Cluster],
    nodeGroup: Option[besom.api.aws.eks.NodeGroup] = None
  )(using Context): Output[k8s.core.v1.Namespace] =
    val dependencies = List(cluster, nodeGroup).flatten
    k8s.core.v1.Namespace(
      NonEmptyString(name).getOrElse {
        throw new IllegalArgumentException(s"Namespace name cannot be empty. Provided: '$name'")
      },
      k8s.core.v1.NamespaceArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = name
        )
      ),
      if (dependencies.nonEmpty) opts(dependsOn = dependencies) else opts()
    )

  def createHeadlessService(
    name: String,
    namespace: Output[String],
    selector: Map[String, String],
    port: Int,
    portName: String = "client"
  )(using Context): Output[k8s.core.v1.Service] =
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
      )
    )

  def createKafkaStatefulSet(
    name: String,
    namespace: Output[String],
    serviceName: String,
    image: String = "apache/kafka:4.1.0",
    replicas: Int = 1,
    storageSize: String = "1Gi"
  )(using Context): Output[k8s.apps.v1.StatefulSet] =

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
                  ports = List(
                    k8s.core.v1.inputs.ContainerPortArgs(
                      containerPort = 9092,
                      name = "kafka"
                    )
                  ),
                  env = List(
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_NODE_ID",
                      value = "1"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_PROCESS_ROLES",
                      value = "broker,controller"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_LISTENERS",
                      value = "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_ADVERTISED_LISTENERS",
                      value = namespace.map(ns => s"PLAINTEXT://$name-0.$serviceName.$ns.svc.cluster.local:9092")
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_CONTROLLER_QUORUM_VOTERS",
                      value = namespace.map(ns => s"1@$name-0.$serviceName.$ns.svc.cluster.local:9093")
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_CONTROLLER_LISTENER_NAMES",
                      value = "CONTROLLER"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                      value = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR",
                      value = "1"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR",
                      value = "1"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR",
                      value = "1"
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "CLUSTER_ID",
                      value = "zio-lucene-kafka-cluster"
                    )
                  ),
                  volumeMounts = List(
                    k8s.core.v1.inputs.VolumeMountArgs(
                      name = s"$name-data",
                      mountPath = "/var/lib/kafka/data"
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
      )
    )

  /**
   * Creates the aws-auth ConfigMap for EKS to allow nodes to join the cluster
   */
  def createAwsAuthConfigMap(
    nodeRoleArn: Output[String],
    nodeGroup: besom.api.aws.eks.NodeGroup
  )(using Context): Output[k8s.core.v1.ConfigMap] =
      k8s.core.v1.ConfigMap(
        "aws-auth",
        k8s.core.v1.ConfigMapArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "aws-auth",
            namespace = "kube-system"
          ),
          data = nodeRoleArn.map(arn => Map(
            "mapRoles" -> s"""- rolearn: $arn
  username: system:node:{{EC2PrivateDNSName}}
  groups:
    - system:bootstrappers
    - system:nodes"""
          ))
        ),
        opts(dependsOn = nodeGroup)
      )
