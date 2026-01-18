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
  val datadogApiKey = config.require[String]("datadogApiKey")

  // Only create MSK resources for non-local environments (dev/prod)
  // For local development, use plain Kafka in k3d instead
  if (!isLocal) {

    // Create explicit Kubernetes provider from EKS outputs
    val awsProvider = AwsProvider.make(AwsProviderInputs(stackName))

    // 1. Create S3 bucket
    val bucket = S3.make(S3Input("segments", awsProvider))
    val bucketId = bucket.flatMap(_.bucket.id)

    // 1b. Create Secret Store with Datadog API key
    val secretStore = SecretStore.make(
      SecretStoreInput(
        dataDogApiKey = datadogApiKey,
        awsProvider = awsProvider
      )
    )

    // 2. Create VPC with public/private subnets, IGW, NAT
    val vpcOutput = Vpc.make(VpcInput())

    // 3. Create MSK cluster in private subnets
    val kafkaOutput = Kafka.make(
      KafkaInput(
        vpcId = vpcOutput.flatMap(_.vpc.id),
        privateSubnet1Id = vpcOutput.flatMap(_.privateSubnet1.id),
        privateSubnet2Id = vpcOutput.flatMap(_.privateSubnet2.id)
      )
    )

    val bootstrapServers = kafkaOutput.flatMap(_.cluster.bootstrapBrokers)

    // 4. Create EKS cluster with public subnets (for worker nodes)
    val eksCluster =
      EKS.make(
        EksInput(
          namePrefix = "zio-lucene",
          vpcId = vpcOutput.flatMap(_.vpc.id),
          subnetIds = List(
            vpcOutput.flatMap(_.publicSubnet1.id),
            vpcOutput.flatMap(_.publicSubnet2.id)
          )
        )
      )
    val clusterName = eksCluster.map(_.clusterName)

    // Extract K8s provider from EKS output (created inside EKS.make along with aws-auth)
    val k8sProvider = eksCluster.map(_.k8sProvider)

    // 5b. Install EBS CSI driver for persistent volume support
    // todo make depend on node group
    val ebsCsiDriver =
      EbsCsiDriver.make(
        EbsCsiDriverInput(
          eksCluster = eksCluster.map(_.cluster),
          clusterOidcIssuer = eksCluster.flatMap(_.oidcProviderUrl),
          clusterOidcIssuerArn = eksCluster.flatMap(_.oidcProviderArn),
          k8sProvider = k8sProvider
        )
      )

    // 5c. Install External Secrets Operator
    val externalSecretsOperator =
      ExternalSecretsOperator.make(
        ExternalSecretsOperatorInput(
          k8sProvider = k8sProvider,
          cluster = Some(eksCluster.map(_.cluster)),
          nodeGroup = Some(eksCluster.map(_.nodeGroup))
        )
      )

    // 5d. Create IAM Roles for Service Account (IRSA) role for External Secrets Operator
    val externalSecretsIrsa = for {
      eso <- externalSecretsOperator
      esNamespace <- eso.namespace.metadata.name
    } yield ExternalSecretsIrsa.make(
      ExternalSecretsIrsaInput(
        externalSecretsNamespace =
          Output(esNamespace.getOrElse("external-secrets")),
        clusterOidcIssuer = eksCluster.flatMap(_.oidcProviderUrl),
        clusterOidcIssuerArn = eksCluster.flatMap(_.oidcProviderArn),
        awsProvider = awsProvider
      )
    )
    val externalSecretsIrsaFlattened = externalSecretsIrsa.flatten

    // 6. Create Kubernetes namespace (for EKS cluster)
    val namespace = K8s.createNamespace(
      "zio-lucene",
      eksCluster.map(_.cluster),
      eksCluster.map(_.nodeGroup),
      k8sProvider
    )
    val namespaceNameOpt = namespace.flatMap(_.metadata.flatMap(_.name))
    val namespaceNameOutput = namespaceNameOpt.getOrElse {
      throw new RuntimeException(
        "Failed to get namespace name from created namespace resource"
      )
    }

    // 6b. Create SecretStore and ExternalSecret resources
    val secretSync = externalSecretsOperator.flatMap { eso =>
      SecretSync.make(
        SecretSyncInput(
          namespace = namespaceNameOutput,
          k8sProvider = k8sProvider,
          irsaServiceAccountName = Some(Output("external-secrets")),
          helmChart = Some(Output(eso.helmRelease))
        )
      )
    }

    // 6c. Deploy OpenTelemetry Collector via Helm
    val grafanaConfig = GrafanaCloudConfig(
      instanceId = config.require[String]("grafanaCloudInstanceId"),
      apiKey = config.require[String]("grafanaCloudApiKey"),
      otlpEndpoint = config.require[String]("grafanaCloudOtlpEndpoint")
    )

    val otelCollector = eksCluster.flatMap { eks =>
      OtelCollector.make(
        OtelCollectorInput(
          k8sProvider = k8sProvider,
          grafanaCloudConfig = grafanaConfig,
          cluster = Some(eks.cluster),
          nodeGroup = Some(eks.nodeGroup)
        )
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
      secretStore.map(_.secret),
      secretStore.map(_.secretVersion),
      vpcOutput.map(_.vpc),
      vpcOutput.map(_.publicSubnet1),
      vpcOutput.map(_.publicSubnet2),
      vpcOutput.map(_.privateSubnet1),
      vpcOutput.map(_.privateSubnet2),
      vpcOutput.map(_.internetGateway),
      vpcOutput.map(_.natGateway),
      vpcOutput.map(_.publicRouteTable),
      vpcOutput.map(_.privateRouteTable),
      kafkaOutput.map(_.securityGroup),
      kafkaOutput.map(_.cluster),
      eksCluster.map(_.cluster),
      eksCluster.map(_.awsAuthConfigMap),
      eksCluster.map(_.nodeGroup),
      ebsCsiDriver.map(_.role),
      ebsCsiDriver.map(_.addon),
      externalSecretsOperator.map(_.namespace),
      externalSecretsOperator.map(_.helmRelease),
      externalSecretsIrsaFlattened.map(_.role),
      externalSecretsIrsaFlattened.map(_.rolePolicy),
      secretSync.map(_.secretStore),
      secretSync.map(_.externalSecret),
      otelCollector.map(_.namespace),
      otelCollector.map(_.grafanaSecret),
      otelCollector.map(_.helmRelease),
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
        kafkaClusterArn = kafkaOutput.map(_.cluster.arn),
        kafkaBootstrapServers = bootstrapServers,
        k8sNamespace = namespaceNameOutput,
        eksClusterName = clusterName,
        externalSecretsRoleArn = externalSecretsIrsaFlattened.map(_.roleArn)
      )
  } else {
    // Local stack - S3, K8s namespace, and Kafka in k3d
    // Create explicit Kubernetes provider from EKS outputs
    val awsProvider = AwsProvider.makeLocal(AwsProviderInputs(stackName))

    // 1. Create S3 bucket
    val bucket = S3.makeLocal(S3Input("segments", awsProvider))
    val bucketId = bucket.flatMap(_.bucket.id)

    // 1b. Create Secret Store with Datadog API key
    val secretStore = SecretStore.makeLocal(
      SecretStoreInput(
        dataDogApiKey = datadogApiKey,
        awsProvider = awsProvider
      )
    )

    // 2. Create k3d Kubernetes provider (uses default kubeconfig)
    val k8sProvider = K8Provider.makeLocal(())

    // 2b. Install local-path provisioner for persistent volume support
    val localCsiDriver = EbsCsiDriver.makeLocal(())

    // 2c. Install External Secrets Operator
    val externalSecretsOperator = ExternalSecretsOperator.makeLocal(
      ExternalSecretsOperatorInput(
        k8sProvider = k8sProvider
      )
    )

    // 3. Create Kubernetes namespace (for k3d cluster)
    val namespace = K8s.createNamespace("zio-lucene", None, None, k8sProvider)
    val namespaceNameOpt = namespace.metadata.name
    val namespaceNameOut = namespaceNameOpt.getOrElse {
      throw new RuntimeException(
        "Failed to get namespace name from created namespace resource"
      )
    }

    // 3b. Create SecretStore and ExternalSecret resources for LocalStack
    val secretSync = externalSecretsOperator.flatMap { eso =>
      SecretSync.makeLocal(
        SecretSyncInput(
          namespace = namespaceNameOut,
          k8sProvider = k8sProvider
        )
      )
    }

    // 3c. Deploy OpenTelemetry Collector via Helm
    val grafanaConfig = GrafanaCloudConfig(
      instanceId = config.require[String]("grafanaCloudInstanceId"),
      apiKey = config.require[String]("grafanaCloudApiKey"),
      otlpEndpoint = config.require[String]("grafanaCloudOtlpEndpoint")
    )

    val otelCollector = OtelCollector.makeLocal(
      OtelCollectorInput(
        k8sProvider = k8sProvider,
        grafanaCloudConfig = grafanaConfig
      )
    )

    // 4. Create Kafka Service and StatefulSet
    val kafkaOutput = Kafka.makeLocal(
      KafkaLocalInput(
        namespace = namespaceNameOut,
        provider = k8sProvider
      )
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
      secretStore.map(_.secret),
      secretStore.map(_.secretVersion),
      localCsiDriver,
      externalSecretsOperator.map(_.namespace),
      externalSecretsOperator.map(_.helmRelease),
      secretSync.map(_.secretStore),
      secretSync.map(_.externalSecret),
      otelCollector.map(_.namespace),
      otelCollector.map(_.grafanaSecret),
      otelCollector.map(_.helmRelease),
      namespace,
      kafkaOutput.map(_.service),
      kafkaOutput.map(_.statefulSet),
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
