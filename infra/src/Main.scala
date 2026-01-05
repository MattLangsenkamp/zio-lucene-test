//> using jvm "21"
//> using scala "3.7.4"
//> using dep "org.virtuslab::besom-core:0.5.0"
//> using dep "org.virtuslab::besom-aws:7.7.0-core.0.5"
//> using dep "org.virtuslab::besom-kubernetes:4.19.0-core.0.5"

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s
import explicit.providers.{
  K8Provider,
  K8sInputs,
  AwsProvider,
  AwsProviderInputs
}
import utils.*
import utils.ingestion.Ingestion
import utils.reader.Reader
import utils.writer.Writer

@main def main = Pulumi.run {
  // Get stack name - PULUMI_STACK env var is set by Pulumi CLI
  val stackName = sys.env.getOrElse(
    "PULUMI_STACK",
    throw new RuntimeException(
      "PULUMI_STACK environment variable is not set. This should be set automatically by Pulumi CLI."
    )
  )
  val isLocal = stackName == "local"

  // Get Pulumi config (using project name as namespace)
  val config = Config("zio-lucene-infra")
  val hostedZoneId = config.get[String]("hostedZoneId")
  val baseDomain = config.get[String]("domain")
  val certificateArn = config.get[String]("certificateArn")

  // Only create MSK resources for non-local environments (dev/prod)
  // For local development, use plain Kafka in k3d instead
  if (!isLocal) {

    // Create explicit Kubernetes provider from EKS outputs
    val awsProvider = AwsProvider.make(AwsProviderInputs(stackName))

    // 1. Create S3 bucket
    val bucket = S3.make(S3Input("segments", awsProvider))
    val bucketId = bucket.flatMap(_.bucket.id)

    // 2. Create VPC with public/private subnets, IGW, NAT
    val vpcOutput = Vpc.make(VpcInput())

    // 3. Create MSK cluster in private subnets
    val mskOutput =
      for {
        vpcDetails <- vpcOutput
        mskDetails <- MSK.make(
          MskInput(
            vpcId = vpcDetails.vpc.id,
            privateSubnet1Id = vpcDetails.privateSubnet1.id,
            privateSubnet2Id = vpcDetails.privateSubnet2.id
          )
        )
      } yield mskDetails
    val bootstrapServers = mskOutput.flatMap(_.cluster.bootstrapBrokers)

    // 4. Create EKS cluster with public subnets (for worker nodes)
    val eksCluster = for {
      vpcOut <- vpcOutput
      eks <- EKS.make(
        EksInput(
          namePrefix = "zio-lucene",
          vpcId = vpcOut.vpc.id,
          subnetIds = List(vpcOut.publicSubnet1.id, vpcOut.publicSubnet2.id)
        )
      )
    } yield eks
    val clusterName = eksCluster.map(_.clusterName)

    // Create explicit Kubernetes provider from EKS outputs
    val k8sProvider = K8Provider.make(K8sInputs(eksCluster))

    // 5. Create aws-auth ConfigMap so nodes can join the cluster
    val awsAuthConfigMap =
      for {
        eks <- eksCluster
        configMap <- K8s.createAwsAuthConfigMap(
          eks.nodeRoleArn,
          eks.nodeGroup,
          k8sProvider
        )
      } yield configMap

    // 5b. Install EBS CSI driver for persistent volume support
    val ebsCsiDriver =
      for {
        eks <- eksCluster
        driver <- EbsCsiDriver.make(
          EbsCsiDriverInput(
            eksCluster = eks.cluster,
            clusterOidcIssuer = eks.oidcProviderUrl,
            clusterOidcIssuerArn = eks.oidcProviderArn,
            k8sProvider = k8sProvider
          )
        )
      } yield driver

    // 6. Create Kubernetes namespace (for EKS cluster)
    val namespace =
      for {
        eks <- eksCluster
        ns <- K8s.createNamespace(
          "zio-lucene",
          eks.cluster,
          eks.nodeGroup,
          k8sProvider
        )
      } yield ns
    val namespaceNameOpt = namespace.flatMap(_.metadata.flatMap(_.name))
    val namespaceNameOutput = namespaceNameOpt.getOrElse {
      throw new RuntimeException(
        "Failed to get namespace name from created namespace resource"
      )
    }

    // 7. Deploy application services with Docker Hub images
    val ingestionService = Ingestion.createService(
      namespace = namespaceNameOutput,
      provider = k8sProvider
    )

    val ingestionDeployment = Ingestion.createDeployment(
      namespace = namespaceNameOutput,
      kafkaBootstrapServers = bootstrapServers,
      bucketName = bucketId,
      image = "mattlangsenkamp/ingestion-server:latest",
      imagePullPolicy = "Always",
      provider = k8sProvider
    )

    val readerService = Reader.createService(
      namespace = namespaceNameOutput,
      provider = k8sProvider
    )

    val readerDeployment = Reader.createDeployment(
      namespace = namespaceNameOutput,
      bucketName = bucketId,
      image = "mattlangsenkamp/reader-server:latest",
      imagePullPolicy = "Always",
      provider = k8sProvider
    )

    val writerService = Writer.createService(
      namespace = namespaceNameOutput,
      provider = k8sProvider
    )

    val writerStatefulSet = Writer.createStatefulSet(
      namespace = namespaceNameOutput,
      kafkaBootstrapServers = bootstrapServers,
      bucketName = bucketId,
      image = "mattlangsenkamp/writer-server:latest",
      imagePullPolicy = "Always",
      provider = k8sProvider,
      dependencies = ebsCsiDriver.map(_.addon)
    )

    // 8. Set up ALB Ingress for public access
    val readerServiceNameOpt = readerService.metadata.name
    val readerServiceName = readerServiceNameOpt.getOrElse {
      throw new RuntimeException(
        "Failed to get reader service name from created service resource"
      )
    }

    val albIngress =
      for {
        eks <- eksCluster
        vpc <- vpcOutput
        albIngress <- AlbIngress.make(
          AlbIngressInput(
            eksCluster = eks.cluster,
            nodeGroup = eks.nodeGroup,
            vpcId = vpc.vpc.id,
            clusterName = eks.clusterName,
            clusterOidcIssuer = eks.oidcProviderUrl,
            clusterOidcIssuerArn = eks.oidcProviderArn,
            namespace = namespaceNameOutput,
            readerServiceName = readerServiceName,
            stackName = stackName,
            hostedZoneIdConfig = hostedZoneId,
            baseDomainConfig = baseDomain,
            certificateArnConfig = certificateArn,
            k8sProvider = k8sProvider
          )
        )
      } yield albIngress

    Stack(
      bucket,
      vpcOutput.map(_.vpc),
      vpcOutput.map(_.publicSubnet1),
      vpcOutput.map(_.publicSubnet2),
      vpcOutput.map(_.privateSubnet1),
      vpcOutput.map(_.privateSubnet2),
      vpcOutput.map(_.internetGateway),
      vpcOutput.map(_.natGateway),
      vpcOutput.map(_.publicRouteTable),
      vpcOutput.map(_.privateRouteTable),
      mskOutput.map(_.securityGroup),
      mskOutput.map(_.cluster),
      eksCluster.map(_.cluster),
      awsAuthConfigMap,
      eksCluster.map(_.nodeGroup),
      ebsCsiDriver.map(_.role),
      ebsCsiDriver.map(_.addon),
      albIngress.map(_.oidcProvider),
      namespace,
      ingestionService,
      ingestionDeployment,
      readerService,
      readerDeployment,
      writerService,
      writerStatefulSet,
      albIngress.map(_.policy),
      albIngress.map(_.role),
      albIngress.map(_.serviceAccount),
      albIngress.map(_.helmRelease),
      albIngress.map(_.ingress)
    )
      .exports(
        bucketName = bucketId,
        mskClusterArn = mskOutput.map(_.cluster.arn),
        mskBootstrapServers = bootstrapServers,
        k8sNamespace = namespaceNameOutput,
        eksClusterName = clusterName
      )
  } else {
    // Local stack - S3, K8s namespace, and Kafka in k3d
    // Create explicit Kubernetes provider from EKS outputs
    val awsProvider = AwsProvider.makeLocal(AwsProviderInputs(stackName))

    // 1. Create S3 bucket
    val bucket = S3.makeLocal(S3Input("segments", awsProvider))
    val bucketId = bucket.flatMap(_.bucket.id)

    // 2. Create k3d Kubernetes provider (uses default kubeconfig)
    val k8sProvider = K8Provider.makeLocal(())

    // 3. Create Kubernetes namespace (for k3d cluster)
    val namespace = K8s.createNamespace("zio-lucene", None, None, k8sProvider)
    val namespaceNameOpt = namespace.metadata.name
    val namespaceNameOut = namespaceNameOpt.getOrElse {
      throw new RuntimeException(
        "Failed to get namespace name from created namespace resource"
      )
    }

    // 4. Create Kafka Service
    val kafkaService = K8s.createHeadlessService(
      name = "kafka",
      namespace = namespaceNameOut,
      selector = Map("app" -> "kafka"),
      port = 9092,
      portName = "kafka",
      provider = k8sProvider
    )

    // 5. Create Kafka StatefulSet (KRaft mode - no ZooKeeper needed for Kafka 4.x)
    val kafkaStatefulSet = K8s.createKafkaStatefulSet(
      name = "kafka",
      namespace = namespaceNameOut,
      serviceName = "kafka",
      provider = k8sProvider
    )

    val bootstrapServers = "kafka-0.kafka.zio-lucene.svc.cluster.local:9092"

    // 6. Deploy application services
    val ingestionService = Ingestion.createService(
      namespace = namespaceNameOut,
      provider = k8sProvider
    )

    val ingestionDeployment = Ingestion.createDeployment(
      namespace = namespaceNameOut,
      kafkaBootstrapServers = Output(bootstrapServers),
      bucketName = bucketId,
      provider = k8sProvider
    )

    val readerService = Reader.createService(
      namespace = namespaceNameOut,
      provider = k8sProvider
    )

    val readerDeployment = Reader.createDeployment(
      namespace = namespaceNameOut,
      bucketName = bucketId,
      provider = k8sProvider
    )

    val writerService = Writer.createService(
      namespace = namespaceNameOut,
      provider = k8sProvider
    )

    val writerStatefulSet = Writer.createStatefulSet(
      namespace = namespaceNameOut,
      kafkaBootstrapServers = Output(bootstrapServers),
      bucketName = bucketId,
      storageClassName = "local-path",
      provider = k8sProvider
    )
    Stack(
      bucket,
      namespace,
      kafkaService,
      kafkaStatefulSet,
      ingestionService,
      ingestionDeployment,
      readerService,
      readerDeployment,
      writerService,
      writerStatefulSet
    ).exports(
      bucketName = bucketId,
      k8sNamespace = namespaceNameOut,
      kafkaBootstrapServers = bootstrapServers
    )
  }
}
