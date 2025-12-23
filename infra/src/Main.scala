//> using jvm "21"
//> using scala "3.7.4"
//> using dep "org.virtuslab::besom-core:0.5.0"
//> using dep "org.virtuslab::besom-aws:7.7.0-core.0.5"
//> using dep "org.virtuslab::besom-kubernetes:4.19.0-core.0.5"

import besom.*
import utils.{S3, K8s, MSK, EKS, AlbIngress}
import utils.ingestion.Ingestion
import utils.reader.Reader
import utils.writer.Writer

@main def main = Pulumi.run {
  val stackName = sys.env.getOrElse("STACK_ENV", "local")
  val isLocal = stackName == "local"

  // Create S3 bucket
  val bucket = S3.createBucket("segments")

  // Create Kubernetes namespace
  val namespace = K8s.createNamespace("zio-lucene")
  val namespaceName = namespace.metadata.name.map(_.getOrElse("zio-lucene"))

  // Only create MSK resources for non-local environments (dev/prod)
  // For local development, use plain Kafka in k3d instead
  if (!isLocal) {
    // Create MSK infrastructure
    val mskInfra = MSK.createMskInfrastructure()
    val bootstrapServers = mskInfra.cluster.bootstrapBrokers

    // Create EKS cluster in the same VPC as MSK
    val eksCluster = EKS.createCluster(
      namePrefix = "zio-lucene",
      vpcId = mskInfra.vpc.id,
      subnetIds = List(mskInfra.subnet1.id, mskInfra.subnet2.id)
    )

    // Deploy application services
    val ingestionService = Ingestion.createService(namespaceName)
    val ingestionDeployment = Ingestion.createDeployment(
      namespaceName,
      bootstrapServers,
      bucket.id
    )

    val readerService = Reader.createService(namespaceName)
    val readerDeployment = Reader.createDeployment(
      namespaceName,
      bucket.id
    )

    val writerService = Writer.createService(namespaceName)
    val writerStatefulSet = Writer.createStatefulSet(
      namespaceName,
      bootstrapServers,
      bucket.id
    )

    // Set up ALB Ingress for public access
    val albIngress = AlbIngress.setup(
      clusterName = eksCluster.clusterName,
      clusterOidcIssuer = eksCluster.oidcProviderUrl,
      clusterOidcIssuerArn = eksCluster.oidcProviderArn,
      namespace = namespaceName,
      readerServiceName = readerService.metadata.name.map(_.getOrElse("reader")),
      stackName = stackName
    )

    Stack(
      bucket,
      mskInfra.vpc,
      mskInfra.subnet1,
      mskInfra.subnet2,
      mskInfra.securityGroup,
      mskInfra.cluster,
      eksCluster.cluster,
      eksCluster.oidcProvider,
      namespace,
      ingestionService,
      ingestionDeployment,
      readerService,
      readerDeployment,
      writerService,
      writerStatefulSet,
      albIngress
    ).exports(
      bucketName = bucket.id,
      mskClusterArn = mskInfra.cluster.arn,
      mskBootstrapServers = bootstrapServers,
      k8sNamespace = namespaceName,
      eksClusterName = eksCluster.clusterName
    )
  } else {
    // Local stack - S3, K8s namespace, and Kafka in k3d

    // Create Kafka Service
    val kafkaService = K8s.createHeadlessService(
      "kafka",
      namespaceName,
      Map("app" -> "kafka"),
      9092,
      "kafka"
    )

    // Create Kafka StatefulSet (KRaft mode - no ZooKeeper needed for Kafka 4.x)
    val kafkaStatefulSet = K8s.createKafkaStatefulSet(
      "kafka",
      namespaceName,
      "kafka"
    )

    val bootstrapServers = Output("kafka-0.kafka.zio-lucene.svc.cluster.local:9092")

    // Deploy application services
    val ingestionService = Ingestion.createService(namespaceName)
    val ingestionDeployment = Ingestion.createDeployment(
      namespaceName,
      bootstrapServers,
      bucket.id
    )

    val readerService = Reader.createService(namespaceName)
    val readerDeployment = Reader.createDeployment(
      namespaceName,
      bucket.id
    )

    val writerService = Writer.createService(namespaceName)
    val writerStatefulSet = Writer.createStatefulSet(
      namespaceName,
      bootstrapServers,
      bucket.id
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
      bucketName = bucket.id,
      k8sNamespace = namespaceName,
      kafkaBootstrapServers = bootstrapServers
    )
  }
}
