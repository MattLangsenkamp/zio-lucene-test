package utils

import besom.*
import besom.api.aws.ec2
import besom.api.aws.msk

object MSK:
  def createVpc(
    name: String,
    cidrBlock: String = "10.0.0.0/16"
  )(using Context): Output[ec2.Vpc] =
    ec2.Vpc(
      name,
      ec2.VpcArgs(
        cidrBlock = cidrBlock,
        enableDnsHostnames = true,
        enableDnsSupport = true
      )
    )

  def createSubnet(
    name: String,
    vpcId: Output[String],
    cidrBlock: String,
    availabilityZone: String
  )(using Context): Output[ec2.Subnet] =
    ec2.Subnet(
      name,
      ec2.SubnetArgs(
        vpcId = vpcId,
        cidrBlock = cidrBlock,
        availabilityZone = availabilityZone
      )
    )

  def createSecurityGroup(
    name: String,
    vpcId: Output[String],
    description: String = "Security group for MSK cluster"
  )(using Context): Output[ec2.SecurityGroup] =
    ec2.SecurityGroup(
      name,
      ec2.SecurityGroupArgs(
        vpcId = vpcId,
        description = description,
        ingress = List(
          ec2.inputs.SecurityGroupIngressArgs(
            protocol = "tcp",
            fromPort = 9092,
            toPort = 9092,
            cidrBlocks = List("10.0.0.0/16")
          )
        )
      )
    )

  def createCluster(
    name: String,
    clusterName: String,
    kafkaVersion: String = "4.1.0",
    numberOfBrokerNodes: Int = 2,
    instanceType: String = "kafka.t3.small",
    clientSubnets: List[Output[String]],
    securityGroups: List[Output[String]],
    volumeSize: Int = 10
  )(using Context): Output[msk.Cluster] =
    msk.Cluster(
      name,
      msk.ClusterArgs(
        clusterName = clusterName,
        kafkaVersion = kafkaVersion,
        numberOfBrokerNodes = numberOfBrokerNodes,
        brokerNodeGroupInfo = msk.inputs.ClusterBrokerNodeGroupInfoArgs(
          instanceType = instanceType,
          clientSubnets = clientSubnets,
          securityGroups = securityGroups,
          storageInfo = msk.inputs.ClusterBrokerNodeGroupInfoStorageInfoArgs(
            ebsStorageInfo = msk.inputs.ClusterBrokerNodeGroupInfoStorageInfoEbsStorageInfoArgs(
              volumeSize = volumeSize
            )
          )
        )
      )
    )

  case class MskInfrastructure(
    vpc: Output[ec2.Vpc],
    subnet1: Output[ec2.Subnet],
    subnet2: Output[ec2.Subnet],
    securityGroup: Output[ec2.SecurityGroup],
    cluster: Output[msk.Cluster]
  )

  def createMskInfrastructure(
    namePrefix: String = "zio-lucene",
    kafkaVersion: String = "4.1.0",
    numberOfBrokerNodes: Int = 2
  )(using Context): MskInfrastructure =
    val vpc = createVpc(s"$namePrefix-vpc")

    val subnet1 = createSubnet(
      s"$namePrefix-subnet-1",
      vpc.id,
      "10.0.1.0/24",
      "us-east-1a"
    )

    val subnet2 = createSubnet(
      s"$namePrefix-subnet-2",
      vpc.id,
      "10.0.2.0/24",
      "us-east-1b"
    )

    val securityGroup = createSecurityGroup(
      s"$namePrefix-msk-sg",
      vpc.id
    )

    val cluster = createCluster(
      s"$namePrefix-msk",
      s"$namePrefix-kafka",
      kafkaVersion = kafkaVersion,
      numberOfBrokerNodes = numberOfBrokerNodes,
      clientSubnets = List(subnet1.id, subnet2.id),
      securityGroups = List(securityGroup.id)
    )

    MskInfrastructure(vpc, subnet1, subnet2, securityGroup, cluster)
