package utils

import besom.*
import besom.api.aws.ec2
import besom.api.aws.msk
import besom.api.kubernetes as k8s

case class KafkaInput(
  namePrefix: String = "zio-lucene",
  vpcId: Output[String],
  privateSubnet1Id: Output[String],
  privateSubnet2Id: Output[String],
  vpcCidrBlock: String = "10.0.0.0/16",
  kafkaVersion: String = "3.9.x",
  numberOfBrokerNodes: Int = 2,
  instanceType: String = "kafka.t3.small",
  volumeSize: Int = 10
)

case class KafkaOutput(
  securityGroup: ec2.SecurityGroup,
  cluster: msk.Cluster
)

case class KafkaLocalInput(
  namespace: Output[String],
  provider: Output[k8s.Provider]
)

case class KafkaLocalOutput(
  service: k8s.core.v1.Service,
  statefulSet: k8s.apps.v1.StatefulSet
)

object Kafka extends Resource[KafkaInput, KafkaOutput, KafkaLocalInput, KafkaLocalOutput] {

  override def make(inputParams: KafkaInput)(using Context): Output[KafkaOutput] =
    val securityGroup = createSecurityGroup(inputParams)
    val cluster = createCluster(inputParams, securityGroup.flatMap(_.id))

    securityGroup.zip(cluster).map { case (sg, cl) =>
      KafkaOutput(
        securityGroup = sg,
        cluster = cl
      )
    }

  override def makeLocal(inputParams: KafkaLocalInput)(using Context): Output[KafkaLocalOutput] =
    val name = "kafka"
    val serviceName = "kafka"

    val service = K8s.createHeadlessService(
      name = name,
      namespace = inputParams.namespace,
      selector = Map("app" -> name),
      port = 9092,
      portName = "kafka",
      provider = inputParams.provider
    )

    val statefulSet = inputParams.namespace.flatMap { ns =>
      K8s.createStatefulSet(
        name = name,
        namespace = inputParams.namespace,
        serviceName = serviceName,
        image = "apache/kafka:4.1.0",
        replicas = 1,
        storageSize = "1Gi",
        ports = Map("kafka" -> 9092),
        envVars = Map(
          "KAFKA_NODE_ID" -> "1",
          "KAFKA_PROCESS_ROLES" -> "broker,controller",
          "KAFKA_LISTENERS" -> "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093",
          "KAFKA_ADVERTISED_LISTENERS" -> s"PLAINTEXT://$name-0.$serviceName.$ns.svc.cluster.local:9092",
          "KAFKA_CONTROLLER_QUORUM_VOTERS" -> s"1@$name-0.$serviceName.$ns.svc.cluster.local:9093",
          "KAFKA_CONTROLLER_LISTENER_NAMES" -> "CONTROLLER",
          "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP" -> "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
          "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR" -> "1",
          "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR" -> "1",
          "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR" -> "1",
          "CLUSTER_ID" -> "zio-lucene-kafka-cluster"
        ),
        volumeMounts = Map("kafka-data" -> "/var/lib/kafka/data"),
        provider = inputParams.provider
      )
    }

    service.zip(statefulSet).map { case (svc, sts) =>
      KafkaLocalOutput(
        service = svc,
        statefulSet = sts
      )
    }

  private def createSecurityGroup(
    params: KafkaInput
  )(using Context): Output[ec2.SecurityGroup] =
    ec2.SecurityGroup(
      s"${params.namePrefix}-msk-sg",
      ec2.SecurityGroupArgs(
        vpcId = params.vpcId,
        description = "Security group for MSK cluster",
        ingress = List(
          ec2.inputs.SecurityGroupIngressArgs(
            protocol = "tcp",
            fromPort = 9092,
            toPort = 9092,
            cidrBlocks = List(params.vpcCidrBlock)
          )
        ),
        tags = Map("Name" -> s"${params.namePrefix}-msk-sg")
      )
    )

  private def createCluster(
    params: KafkaInput,
    securityGroupId: Output[String]
  )(using Context): Output[msk.Cluster] =
    msk.Cluster(
      s"${params.namePrefix}-msk",
      msk.ClusterArgs(
        clusterName = s"${params.namePrefix}-kafka",
        kafkaVersion = params.kafkaVersion,
        numberOfBrokerNodes = params.numberOfBrokerNodes,
        brokerNodeGroupInfo = msk.inputs.ClusterBrokerNodeGroupInfoArgs(
          instanceType = params.instanceType,
          clientSubnets = List(params.privateSubnet1Id, params.privateSubnet2Id),
          securityGroups = List(securityGroupId),
          storageInfo = msk.inputs.ClusterBrokerNodeGroupInfoStorageInfoArgs(
            ebsStorageInfo = msk.inputs.ClusterBrokerNodeGroupInfoStorageInfoEbsStorageInfoArgs(
              volumeSize = params.volumeSize
            )
          )
        ),
        tags = Map("Name" -> s"${params.namePrefix}-kafka")
      )
    )
}
