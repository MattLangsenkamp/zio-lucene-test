package utils

import besom.*
import besom.api.aws.ec2
import besom.api.aws.ec2.{NatGateway, Subnet, Vpc as BesomVpc}

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
    for {
      vpc <- createVpc(inputParams)
      igw <- createInternetGateway(inputParams, vpc.id)
      publicSubnet1 <- createPublicSubnet(
        inputParams,
        Output(vpc),
        "1",
        inputParams.publicSubnet1Cidr,
        inputParams.availabilityZone1
      )
      publicSubnet2 <- createPublicSubnet(
        inputParams,
        Output(vpc),
        "2",
        inputParams.publicSubnet2Cidr,
        inputParams.availabilityZone2
      )
      privateSubnet1 <- createPrivateSubnet(
        inputParams,
        Output(vpc),
        "1",
        inputParams.privateSubnet1Cidr,
        inputParams.availabilityZone1
      )
      privateSubnet2 <- createPrivateSubnet(
        inputParams,
        Output(vpc),
        "2",
        inputParams.privateSubnet2Cidr,
        inputParams.availabilityZone2
      )
      natGateway <- createNatGateway(inputParams, publicSubnet1.id)
      publicRouteTable <- createPublicRouting(
        inputParams,
        Output(vpc),
        igw.id,
        publicSubnet1.id,
        publicSubnet2.id
      )
      privateRouteTable <- createPrivateRouting(
        inputParams,
        vpc.id,
        Output(natGateway),
        Output(privateSubnet1),
        Output(privateSubnet2)
      )
    } yield VpcOutput(
      vpc = vpc,
      internetGateway = igw,
      publicSubnet1 = publicSubnet1,
      publicSubnet2 = publicSubnet2,
      privateSubnet1 = privateSubnet1,
      privateSubnet2 = privateSubnet2,
      natGateway = natGateway,
      publicRouteTable = publicRouteTable,
      privateRouteTable = privateRouteTable
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

  private def createInternetGateway(params: VpcInput, vpcId: Output[String])(
      using Context
  ): Output[ec2.InternetGateway] =
    ec2.InternetGateway(
      s"${params.namePrefix}-igw",
      ec2.InternetGatewayArgs(
        vpcId = vpcId,
        tags = Map("Name" -> s"${params.namePrefix}-igw")
      )
    )

  private def createPublicSubnet(
      params: VpcInput,
      vpc: Output[BesomVpc],
      suffix: String,
      cidrBlock: String,
      availabilityZone: String
  )(using Context): Output[ec2.Subnet] =
    ec2.Subnet(
      s"${params.namePrefix}-public-subnet-$suffix",
      ec2.SubnetArgs(
        vpcId = vpc.flatMap(_.id),
        cidrBlock = cidrBlock,
        availabilityZone = availabilityZone,
        mapPublicIpOnLaunch = true,
        tags = Map(
          "Name" -> s"${params.namePrefix}-public-subnet-$suffix",
          "kubernetes.io/role/elb" -> "1"
        )
      ),
      opts(dependsOn = vpc)
    )

  private def createPrivateSubnet(
      params: VpcInput,
      vpc: Output[BesomVpc],
      suffix: String,
      cidrBlock: String,
      availabilityZone: String
  )(using Context): Output[ec2.Subnet] =
    ec2.Subnet(
      s"${params.namePrefix}-private-subnet-$suffix",
      ec2.SubnetArgs(
        vpcId = vpc.flatMap(_.id),
        cidrBlock = cidrBlock,
        availabilityZone = availabilityZone,
        tags = Map(
          "Name" -> s"${params.namePrefix}-private-subnet-$suffix",
          "kubernetes.io/role/internal-elb" -> "1"
        )
      ),
      opts(dependsOn = vpc)
    )

  private def createNatGateway(
      params: VpcInput,
      publicSubnetId: Output[String]
  )(using Context): Output[ec2.NatGateway] =
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
        ),
        opts(dependsOn = eip)
      )
    } yield natGateway

  private def createPublicRouting(
      params: VpcInput,
      vpc: Output[BesomVpc],
      igwId: Output[String],
      publicSubnet1Id: Output[String],
      publicSubnet2Id: Output[String]
  )(using Context): Output[ec2.RouteTable] =
    for {
      routeTable <- ec2.RouteTable(
        s"${params.namePrefix}-public-rt",
        ec2.RouteTableArgs(
          vpcId = vpc.flatMap(_.id),
          tags = Map("Name" -> s"${params.namePrefix}-public-rt")
        ),
        opts = opts(dependsOn = vpc)
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
      natGateway: Output[NatGateway],
      privateSubnet1: Output[Subnet],
      privateSubnet2: Output[Subnet]
  )(using Context): Output[ec2.RouteTable] =
    for {
      nat <- natGateway
      sub1 <- privateSubnet1
      sub2 <- privateSubnet2
      routeTable <- ec2.RouteTable(
        s"${params.namePrefix}-private-rt",
        ec2.RouteTableArgs(
          vpcId = vpcId,
          tags = Map("Name" -> s"${params.namePrefix}-private-rt")
        ),
        opts(dependsOn = List(nat, sub1, sub2))
      )
      _ <- ec2.Route(
        s"${params.namePrefix}-private-route",
        ec2.RouteArgs(
          routeTableId = routeTable.id,
          destinationCidrBlock = "0.0.0.0/0",
          natGatewayId = nat.id
        ),
        opts(dependsOn = routeTable)
      )
      _ <- ec2.RouteTableAssociation(
        s"${params.namePrefix}-private-rta-1",
        ec2.RouteTableAssociationArgs(
          subnetId = sub1.id,
          routeTableId = routeTable.id
        ),
        opts(dependsOn = routeTable)
      )
      _ <- ec2.RouteTableAssociation(
        s"${params.namePrefix}-private-rta-2",
        ec2.RouteTableAssociationArgs(
          subnetId = sub2.id,
          routeTableId = routeTable.id
        ),
        opts(dependsOn = routeTable)
      )
    } yield routeTable
}
