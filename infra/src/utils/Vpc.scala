package utils

import besom.*
import besom.api.aws.ec2

case class VpcInput(
  namePrefix: String = "zio-lucene",
  cidrBlock: String = "10.0.0.0/16",
  publicSubnet1Cidr: String = "10.0.1.0/24",
  publicSubnet2Cidr: String = "10.0.2.0/24",
  privateSubnet1Cidr: String = "10.0.3.0/24",
  privateSubnet2Cidr: String = "10.0.4.0/24",
  availabilityZone1: String = "us-east-1a",
  availabilityZone2: String = "us-east-1b"
)

case class VpcOutput(
  vpc: ec2.Vpc,
  internetGateway: ec2.InternetGateway,
  publicSubnet1: ec2.Subnet,
  publicSubnet2: ec2.Subnet,
  privateSubnet1: ec2.Subnet,
  privateSubnet2: ec2.Subnet,
  natGateway: ec2.NatGateway,
  publicRouteTable: ec2.RouteTable,
  privateRouteTable: ec2.RouteTable
)

object Vpc extends Resource[VpcInput, VpcOutput, Unit, Unit] {

  override def make(inputParams: VpcInput)(using Context): Output[VpcOutput] =
    val vpc = createVpc(inputParams)
    val igw = createInternetGateway(inputParams, vpc.flatMap(_.id))
    val publicSubnet1 = createPublicSubnet(inputParams, vpc.flatMap(_.id), "1", inputParams.publicSubnet1Cidr, inputParams.availabilityZone1)
    val publicSubnet2 = createPublicSubnet(inputParams, vpc.flatMap(_.id), "2", inputParams.publicSubnet2Cidr, inputParams.availabilityZone2)
    val privateSubnet1 = createPrivateSubnet(inputParams, vpc.flatMap(_.id), "1", inputParams.privateSubnet1Cidr, inputParams.availabilityZone1)
    val privateSubnet2 = createPrivateSubnet(inputParams, vpc.flatMap(_.id), "2", inputParams.privateSubnet2Cidr, inputParams.availabilityZone2)
    val natGateway = createNatGateway(inputParams, publicSubnet1.flatMap(_.id))
    val publicRouteTable = createPublicRouting(inputParams, vpc.flatMap(_.id), igw.flatMap(_.id), publicSubnet1.flatMap(_.id), publicSubnet2.flatMap(_.id))
    val privateRouteTable = createPrivateRouting(inputParams, vpc.flatMap(_.id), natGateway.flatMap(_.id), privateSubnet1.flatMap(_.id), privateSubnet2.flatMap(_.id))

    for {
      v <- vpc
      ig <- igw
      ps1 <- publicSubnet1
      ps2 <- publicSubnet2
      prs1 <- privateSubnet1
      prs2 <- privateSubnet2
      nat <- natGateway
      pubRt <- publicRouteTable
      privRt <- privateRouteTable
    } yield VpcOutput(
      vpc = v,
      internetGateway = ig,
      publicSubnet1 = ps1,
      publicSubnet2 = ps2,
      privateSubnet1 = prs1,
      privateSubnet2 = prs2,
      natGateway = nat,
      publicRouteTable = pubRt,
      privateRouteTable = privRt
    )

  override def makeLocal(inputParams: Unit)(using Context): Output[Unit] =
    throw new IllegalStateException("no vpc needed locally due to usage of k3d")

  private def createVpc(params: VpcInput)(using Context): Output[ec2.Vpc] =
    ec2.Vpc(
      s"${params.namePrefix}-vpc",
      ec2.VpcArgs(
        cidrBlock = params.cidrBlock,
        enableDnsHostnames = true,
        enableDnsSupport = true,
        tags = Map("Name" -> s"${params.namePrefix}-vpc")
      )
    )

  private def createInternetGateway(params: VpcInput, vpcId: Output[String])(using Context): Output[ec2.InternetGateway] =
    ec2.InternetGateway(
      s"${params.namePrefix}-igw",
      ec2.InternetGatewayArgs(
        vpcId = vpcId,
        tags = Map("Name" -> s"${params.namePrefix}-igw")
      )
    )

  private def createPublicSubnet(
    params: VpcInput,
    vpcId: Output[String],
    suffix: String,
    cidrBlock: String,
    availabilityZone: String
  )(using Context): Output[ec2.Subnet] =
    ec2.Subnet(
      s"${params.namePrefix}-public-subnet-$suffix",
      ec2.SubnetArgs(
        vpcId = vpcId,
        cidrBlock = cidrBlock,
        availabilityZone = availabilityZone,
        mapPublicIpOnLaunch = true,
        tags = Map(
          "Name" -> s"${params.namePrefix}-public-subnet-$suffix",
          "kubernetes.io/role/elb" -> "1"
        )
      )
    )

  private def createPrivateSubnet(
    params: VpcInput,
    vpcId: Output[String],
    suffix: String,
    cidrBlock: String,
    availabilityZone: String
  )(using Context): Output[ec2.Subnet] =
    ec2.Subnet(
      s"${params.namePrefix}-private-subnet-$suffix",
      ec2.SubnetArgs(
        vpcId = vpcId,
        cidrBlock = cidrBlock,
        availabilityZone = availabilityZone,
        tags = Map(
          "Name" -> s"${params.namePrefix}-private-subnet-$suffix",
          "kubernetes.io/role/internal-elb" -> "1"
        )
      )
    )

  private def createNatGateway(params: VpcInput, publicSubnetId: Output[String])(using Context): Output[ec2.NatGateway] =
    for {
      eip <- ec2.Eip(
        s"${params.namePrefix}-nat-eip",
        ec2.EipArgs(
          domain = "vpc",
          tags = Map("Name" -> s"${params.namePrefix}-nat-eip")
        )
      )
      natGateway <- ec2.NatGateway(
        s"${params.namePrefix}-nat",
        ec2.NatGatewayArgs(
          subnetId = publicSubnetId,
          allocationId = eip.id,
          tags = Map("Name" -> s"${params.namePrefix}-nat")
        )
      )
    } yield natGateway

  private def createPublicRouting(
    params: VpcInput,
    vpcId: Output[String],
    igwId: Output[String],
    publicSubnet1Id: Output[String],
    publicSubnet2Id: Output[String]
  )(using Context): Output[ec2.RouteTable] =
    for {
      routeTable <- ec2.RouteTable(
        s"${params.namePrefix}-public-rt",
        ec2.RouteTableArgs(
          vpcId = vpcId,
          tags = Map("Name" -> s"${params.namePrefix}-public-rt")
        )
      )
      _ <- ec2.Route(
        s"${params.namePrefix}-public-route",
        ec2.RouteArgs(
          routeTableId = routeTable.id,
          destinationCidrBlock = "0.0.0.0/0",
          gatewayId = igwId
        )
      )
      _ <- ec2.RouteTableAssociation(
        s"${params.namePrefix}-public-rta-1",
        ec2.RouteTableAssociationArgs(
          subnetId = publicSubnet1Id,
          routeTableId = routeTable.id
        )
      )
      _ <- ec2.RouteTableAssociation(
        s"${params.namePrefix}-public-rta-2",
        ec2.RouteTableAssociationArgs(
          subnetId = publicSubnet2Id,
          routeTableId = routeTable.id
        )
      )
    } yield routeTable

  private def createPrivateRouting(
    params: VpcInput,
    vpcId: Output[String],
    natGatewayId: Output[String],
    privateSubnet1Id: Output[String],
    privateSubnet2Id: Output[String]
  )(using Context): Output[ec2.RouteTable] =
    for {
      routeTable <- ec2.RouteTable(
        s"${params.namePrefix}-private-rt",
        ec2.RouteTableArgs(
          vpcId = vpcId,
          tags = Map("Name" -> s"${params.namePrefix}-private-rt")
        )
      )
      _ <- ec2.Route(
        s"${params.namePrefix}-private-route",
        ec2.RouteArgs(
          routeTableId = routeTable.id,
          destinationCidrBlock = "0.0.0.0/0",
          natGatewayId = natGatewayId
        )
      )
      _ <- ec2.RouteTableAssociation(
        s"${params.namePrefix}-private-rta-1",
        ec2.RouteTableAssociationArgs(
          subnetId = privateSubnet1Id,
          routeTableId = routeTable.id
        )
      )
      _ <- ec2.RouteTableAssociation(
        s"${params.namePrefix}-private-rta-2",
        ec2.RouteTableAssociationArgs(
          subnetId = privateSubnet2Id,
          routeTableId = routeTable.id
        )
      )
    } yield routeTable
}
