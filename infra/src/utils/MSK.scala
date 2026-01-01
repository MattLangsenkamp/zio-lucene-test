package utils

import besom.*
import besom.api.aws.ec2
import besom.api.aws.msk

case class MskInput(
  namePrefix: String = "zio-lucene",
  vpcId: Output[String],
  privateSubnet1Id: Output[String],
  privateSubnet2Id: Output[String],
  vpcCidrBlock: String = "10.0.0.0/16",
  kafkaVersion: String = "4.1.x.kraft",
  numberOfBrokerNodes: Int = 2,
  instanceType: String = "kafka.m5.large",
  volumeSize: Int = 10
)

case class MskOutput(
  securityGroup: ec2.SecurityGroup,
  cluster: msk.Cluster
)

object MSK extends Resource[MskInput, MskOutput, Unit, Unit] {

  override def make(inputParams: MskInput)(using Context): Output[MskOutput] =
    val securityGroup = createSecurityGroup(inputParams)
    val cluster = createCluster(inputParams, securityGroup.flatMap(_.id))

    securityGroup.zip(cluster).map { case (sg, cl) =>
      MskOutput(
        securityGroup = sg,
        cluster = cl
      )
    }

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("no msk needed locally due to usage of k3d kafka")

  private def createSecurityGroup(
    params: MskInput
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
    params: MskInput,
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
