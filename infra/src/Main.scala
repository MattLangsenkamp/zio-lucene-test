//> using jvm "21"
//> using scala "3.7.4"
//> using dep "org.virtuslab::besom-core:0.5.0"
//> using dep "org.virtuslab::besom-aws:7.7.0-core.0.5"
//> using dep "org.virtuslab::besom-kubernetes:4.19.0-core.0.5"

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s
import platform.{AwsProvider, AwsProviderInputs, K8Provider, K8sInputs}
import platform.{ExternalSecrets, ExternalSecretsInput}
import platform.{IamRoles, ServiceIrsaInput}
import platform.{ArgoCD, ArgoCDInput}
import resources.{Queues, QueueInput, Buckets, BucketInput, AppSecrets, AppSecretsInput}
import utils.*

@main def main = Pulumi.run {
  val stackName = sys.env.getOrElse(
    "PULUMI_STACK",
    throw new RuntimeException("PULUMI_STACK environment variable is not set.")
  )
  val isLocal = stackName == "local"

  val config         = Config("zio-lucene-infra")
  val hostedZoneId   = config.get[String]("hostedZoneId")
  val baseDomain     = config.get[String]("domain")
  val certificateArn = config.get[String]("certificateArn")
  val datadogApiKey  = config.require[String]("datadogApiKey")
  val grafanaConfig = GrafanaCloudConfig(
    instanceId   = config.require[String]("grafanaCloudInstanceId"),
    apiKey       = config.require[String]("grafanaCloudApiKey"),
    otlpEndpoint = config.require[String]("grafanaCloudOtlpEndpoint")
  )
  val useKafka = sys.env.get("KAFKA_MODE").exists(_.toLowerCase == "true")

  if (!isLocal) {
    // ── Cloud (EKS) ──────────────────────────────────────────────────────────

    val awsProvider = AwsProvider.make(AwsProviderInputs(stackName))
    val bucket      = Buckets.make(BucketInput("segments", "segments", stackName, awsProvider))

    val vpcOutput = Vpc.make(VpcInput())

    val eksCluster = EKS.make(EksInput(
      namePrefix = "zio-lucene",
      vpcId      = vpcOutput.flatMap(_.vpc.id),
      subnetIds  = List(
        vpcOutput.flatMap(_.publicSubnet1.id),
        vpcOutput.flatMap(_.publicSubnet2.id)
      )
    ))
    val clusterName = eksCluster.map(_.clusterName)
    val k8sProvider = eksCluster.map(_.k8sProvider)

    val oidcProvider = OidcProvider.make(OidcProviderInput(
      clusterOidcIssuer = eksCluster.flatMap(_.oidcProviderUrl),
      cluster           = eksCluster.map(_.cluster)
    ))

    val albController = AlbController.make(AlbControllerInput(
      eksCluster   = eksCluster.map(_.cluster),
      nodeGroup    = eksCluster.map(_.nodeGroup),
      vpcId        = vpcOutput.flatMap(_.vpc.id),
      clusterName  = eksCluster.flatMap(_.clusterName),
      oidcProvider = oidcProvider,
      stackName    = stackName,
      k8sProvider  = k8sProvider
    ))

    val ebsCsiDriver = EbsCsiDriver.make(EbsCsiDriverInput(
      eksCluster   = eksCluster.map(_.cluster),
      oidcProvider = oidcProvider,
      k8sProvider  = k8sProvider
    ))

    val externalSecrets = ExternalSecrets.make(ExternalSecretsInput(
      k8sProvider = k8sProvider,
      env         = stackName,
      cluster     = Some(eksCluster.map(_.cluster)),
      nodeGroup   = Some(eksCluster.map(_.nodeGroup))
    ))

    val esoIrsa = IamRoles.makeExternalSecretsIrsa(
      env          = stackName,
      esoNamespace = externalSecrets.flatMap(_.namespace.metadata.name).map(_.getOrElse("external-secrets")),
      oidcProvider = oidcProvider,
      awsProvider  = awsProvider
    )

    val namespace = K8s.createNamespace(
      "zio-lucene",
      eksCluster.map(_.cluster),
      eksCluster.map(_.nodeGroup),
      k8sProvider
    )
    val namespaceName = namespace.flatMap(_.metadata.flatMap(_.name)).map(_.getOrElse {
      throw new RuntimeException("Failed to get namespace name")
    })

    val otelCollector = OtelCollector.make(OtelCollectorInput(
      k8sProvider              = k8sProvider,
      grafanaCloudConfig       = grafanaConfig,
      cluster                  = Some(eksCluster.map(_.cluster)),
      nodeGroup                = Some(eksCluster.map(_.nodeGroup)),
      albControllerHelmRelease = Some(albController.map(_.helmRelease))
    ))

    val appSecrets = AppSecrets.make(AppSecretsInput(
      datadogApiKey  = datadogApiKey,
      env            = stackName,
      namespace      = namespaceName,
      awsProvider    = awsProvider,
      k8sProvider    = k8sProvider,
      esoHelmRelease = Some(externalSecrets.map(_.helmRelease))
    ))

    val bucketArn = bucket.flatMap(_.bucket.arn)

    // Phase 1: SQS is always used in cloud. Kafka mode is local-only (useKafka flag has no effect here).
    val sqsOutput   = Queues.make(QueueInput("document-ingestion", "document-ingestion", stackName, awsProvider))
    val commitQueue = Queues.make(QueueInput("document-commit", "document-commit", stackName, awsProvider))
    val sqsArn      = sqsOutput.flatMap(_.queue.arn)
    val commitArn   = commitQueue.flatMap(_.queue.arn)

    val ingestionIrsa = IamRoles.makeIngestionIrsa(ServiceIrsaInput(
      env               = stackName,
      oidcProvider      = oidcProvider,
      namespace         = namespaceName,
      awsProvider       = awsProvider,
      sqsWriteQueueArns = List(sqsArn),
      bucketArns        = List(bucketArn)
    ))

    val readerIrsa = IamRoles.makeReaderIrsa(ServiceIrsaInput(
      env          = stackName,
      oidcProvider = oidcProvider,
      namespace    = namespaceName,
      awsProvider  = awsProvider,
      bucketArns   = List(bucketArn)
    ))

    val writerIrsa = IamRoles.makeWriterIrsa(ServiceIrsaInput(
      env               = stackName,
      oidcProvider      = oidcProvider,
      namespace         = namespaceName,
      awsProvider       = awsProvider,
      sqsQueueArns      = List(sqsArn),
      sqsWriteQueueArns = List(commitArn),
      bucketArns        = List(bucketArn),
      allowS3Write      = true
    ))

    // gitRepoUrl is required for cloud stacks — set zio-lucene-infra:gitRepoUrl in stack config
    val repoUrl = config.require[String]("gitRepoUrl")

    val argoCD = ArgoCD.make(ArgoCDInput(
      k8sProvider = k8sProvider,
      repoUrl     = repoUrl,
      env         = stackName,
      cluster     = Some(eksCluster.map(_.cluster)),
      nodeGroup   = Some(eksCluster.map(_.nodeGroup))
    ))

    val albIngress = AlbIngress.make(AlbIngressInput(
      eksCluster           = eksCluster.map(_.cluster),
      namespace            = namespaceName,
      readerServiceName    = Output("reader"),
      stackName            = stackName,
      hostedZoneIdConfig   = hostedZoneId,
      baseDomainConfig     = baseDomain,
      certificateArnConfig = certificateArn,
      k8sProvider          = k8sProvider,
      albController        = albController
    ))

    // NOTE: Resources that depend on the k8s provider (which itself depends on the EKS cluster)
    // will NOT appear in `pulumi preview` when the EKS cluster is being newly created. This is
    // expected Pulumi behavior — Pulumi cannot resolve provider outputs until the upstream resource
    // actually exists. During `pulumi up` they ARE created in the correct order after the cluster.
    //
    // Affected resources not shown in preview on first `pulumi preview`:
    //   - eksCluster.nodeGroup (depends on awsAuthConfigMap → k8s provider → EKS cluster)
    //   - eksCluster.awsAuthConfigMap (depends on k8s provider → EKS cluster)
    //   - All k8s resources: externalSecrets.{namespace,helmRelease,secretStore},
    //     namespace, otelCollector.*, appSecrets.datadogExternalSecret,
    //     argoCD.*, albController.{serviceAccount,helmRelease}, albIngress.ingress
    Stack(
      bucket.map(_.bucket),
      bucket.map(_.ssmParam),
      sqsOutput.map(_.queue),
      sqsOutput.map(_.ssmParam),
      commitQueue.map(_.queue),
      commitQueue.map(_.ssmParam),
      vpcOutput.map(_.vpc),
      vpcOutput.map(_.publicSubnet1),
      vpcOutput.map(_.publicSubnet2),
      vpcOutput.map(_.privateSubnet1),
      vpcOutput.map(_.privateSubnet2),
      vpcOutput.map(_.internetGateway),
      vpcOutput.map(_.natGateway),
      vpcOutput.map(_.publicRouteTable),
      vpcOutput.map(_.privateRouteTable),
      eksCluster.map(_.cluster),
      eksCluster.map(_.awsAuthConfigMap),
      eksCluster.map(_.nodeGroup),
      ebsCsiDriver.map(_.role),
      ebsCsiDriver.map(_.addon),
      externalSecrets.map(_.namespace),
      externalSecrets.map(_.helmRelease),
      externalSecrets.map(_.secretStore),
      esoIrsa.map(_.role),
      esoIrsa.map(_.rolePolicy),
      esoIrsa.map(_.ssmParam),
      oidcProvider.map(_.provider),
      namespace,
      otelCollector.map(_.namespace),
      otelCollector.map(_.grafanaSecret),
      otelCollector.map(_.helmRelease),
      ingestionIrsa.map(_.role),
      ingestionIrsa.map(_.rolePolicy),
      ingestionIrsa.map(_.ssmParam),
      readerIrsa.map(_.role),
      readerIrsa.map(_.rolePolicy),
      readerIrsa.map(_.ssmParam),
      writerIrsa.map(_.role),
      writerIrsa.map(_.rolePolicy),
      writerIrsa.map(_.ssmParam),
      appSecrets.map(_.datadogSsmParam),
      appSecrets.map(_.datadogExternalSecret),
      argoCD.map(_.namespace),
      argoCD.map(_.helmRelease),
      argoCD.map(_.applicationSet),
      albController.map(_.policy),
      albController.map(_.role),
      albController.map(_.serviceAccount),
      albController.map(_.helmRelease),
      albIngress.map(_.ingress)
    ).exports(
      bucketName   = bucket.flatMap(_.bucketName),
      k8sNamespace = namespaceName,
      eksClusterName = clusterName,
      esoRoleArn   = esoIrsa.map(_.roleArn)
    )

  } else {
    // ── Local (k3d + LocalStack) ──────────────────────────────────────────────

    val awsProvider = AwsProvider.makeLocal(AwsProviderInputs(stackName))
    val k8sProvider = K8Provider.makeLocal(())

    val bucket = Buckets.makeLocal(BucketInput("segments", "segments", stackName, awsProvider))

    val externalSecrets = ExternalSecrets.makeLocal(ExternalSecretsInput(
      k8sProvider = k8sProvider,
      env         = stackName
    ))

    val namespace = K8s.createNamespace("zio-lucene", None, None, k8sProvider)
    val namespaceName = namespace.metadata.name.map(_.getOrElse {
      throw new RuntimeException("Failed to get namespace name")
    })

    val otelCollector = OtelCollector.makeLocal(OtelCollectorInput(
      k8sProvider        = k8sProvider,
      grafanaCloudConfig = grafanaConfig
    ))

    val appSecrets = AppSecrets.makeLocal(AppSecretsInput(
      datadogApiKey  = datadogApiKey,
      env            = stackName,
      namespace      = namespaceName,
      awsProvider    = awsProvider,
      k8sProvider    = k8sProvider,
      esoHelmRelease = Some(externalSecrets.map(_.helmRelease))
    ))

    val argoCD = ArgoCD.makeLocal(ArgoCDInput(
      k8sProvider = k8sProvider,
      repoUrl     = Output("file:///repo"),
      env         = stackName
    ))

    if (useKafka) {
      val kafkaOutput = Kafka.makeLocal(KafkaLocalInput(
        namespace = namespaceName,
        provider  = k8sProvider
      ))

      Stack(
        bucket.map(_.bucket),
        bucket.map(_.ssmParam),
        externalSecrets.map(_.namespace),
        externalSecrets.map(_.helmRelease),
        externalSecrets.map(_.secretStore),
        namespace,
        otelCollector.map(_.namespace),
        otelCollector.map(_.grafanaSecret),
        otelCollector.map(_.helmRelease),
        appSecrets.map(_.datadogSsmParam),
        appSecrets.map(_.datadogExternalSecret),
        argoCD.map(_.namespace),
        argoCD.map(_.helmRelease),
        argoCD.map(_.applicationSet),
        kafkaOutput.map(_.service),
        kafkaOutput.map(_.statefulSet)
      ).exports(
        bucketName    = bucket.flatMap(_.bucketName),
        k8sNamespace  = namespaceName,
        messagingMode = "kafka"
      )
    } else {
      val sqsOutput   = Queues.makeLocal(QueueInput("document-ingestion", "document-ingestion", stackName, awsProvider))
      val commitQueue = Queues.makeLocal(QueueInput("document-commit", "document-commit", stackName, awsProvider))

      Stack(
        bucket.map(_.bucket),
        bucket.map(_.ssmParam),
        sqsOutput.map(_.queue),
        sqsOutput.map(_.ssmParam),
        commitQueue.map(_.queue),
        commitQueue.map(_.ssmParam),
        externalSecrets.map(_.namespace),
        externalSecrets.map(_.helmRelease),
        externalSecrets.map(_.secretStore),
        namespace,
        otelCollector.map(_.namespace),
        otelCollector.map(_.grafanaSecret),
        otelCollector.map(_.helmRelease),
        appSecrets.map(_.datadogSsmParam),
        appSecrets.map(_.datadogExternalSecret),
        argoCD.map(_.namespace),
        argoCD.map(_.helmRelease),
        argoCD.map(_.applicationSet)
      ).exports(
        bucketName    = bucket.flatMap(_.bucketName),
        k8sNamespace  = namespaceName,
        messagingMode = "sqs"
      )
    }
  }
}
