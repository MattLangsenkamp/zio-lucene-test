//> using jvm "21"
//> using scala "3.7.4"
//> using dep "org.virtuslab::besom-core:0.5.0"
//> using dep "org.virtuslab::besom-aws:7.7.0-core.0.5"
//> using dep "org.virtuslab::besom-kubernetes:4.19.0-core.0.5"
//> using dep "org.virtuslab::scala-yaml:0.3.1"

import besom.*
import besom.api.aws
import besom.api.kubernetes as k8s
import platform.{AwsProvider, AwsProviderInputs, K8Provider, K8sInputs}
import platform.{ExternalSecrets, ExternalSecretsInput}
import platform.{IamRoles, ServiceIrsaInput}
import platform.{ArgoCD, ArgoCDInput}
import resources.{Queues, QueueInput, QueueOutput, Buckets, BucketInput, BucketOutput}
import platform.IrsaRoleOutput
import utils.*

@main def main = Pulumi.run {
  // ── Service manifest — parsed once, drives queues/buckets/IRSA/ArgoCD ────
  val manifest = ServiceManifest.load()
  val argoCDServices = manifest.services.map(s => (s.name, s.k8sPath))

  val stackName = sys.env.getOrElse(
    "PULUMI_STACK",
    throw new RuntimeException("PULUMI_STACK environment variable is not set.")
  )
  val isLocal = stackName == "local"

  val config         = Config("zio-lucene-infra")
  val hostedZoneId   = config.get[String]("hostedZoneId")
  val baseDomain     = config.get[String]("domain")
  val certificateArn = config.get[String]("certificateArn")
  val grafanaConfig = GrafanaCloudConfig(
    instanceId   = config.require[String]("grafanaCloudInstanceId"),
    apiKey       = config.require[String]("grafanaCloudApiKey"),
    otlpEndpoint = config.require[String]("grafanaCloudOtlpEndpoint")
  )
  val useKafka = sys.env.get("KAFKA_MODE").exists(_.toLowerCase == "true")

  if (!isLocal) {
    // ── Cloud (EKS) ──────────────────────────────────────────────────────────

    val awsProvider = AwsProvider.make(AwsProviderInputs(stackName))

    // Create all unique SQS queues and S3 buckets declared in the manifest.
    // De-duplication is done by uniqueSqsQueues/uniqueS3Buckets (same queue/bucket
    // may appear in multiple services but is only created once).
    val queues: Map[String, Output[QueueOutput]] =
      manifest.uniqueSqsQueues.map { r =>
        r.logicalName -> Queues.make(QueueInput(r.logicalName, r.logicalName, stackName, awsProvider))
      }.toMap

    val buckets: Map[String, Output[BucketOutput]] =
      manifest.uniqueS3Buckets.map { r =>
        r.logicalName -> Buckets.make(BucketInput(r.logicalName, r.logicalName, stackName, awsProvider))
      }.toMap

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

    // Create one IRSA role per service, reading permissions from the manifest.
    // sqsQueueArns/sqsWriteQueueArns/bucketArns are resolved by looking up each
    // service's manifest resources in the queues/buckets maps created above.
    val serviceIrsas: List[(String, Output[IrsaRoleOutput])] = manifest.services.map { svc =>
      val sqsReadArns  = svc.sqsReadQueues.flatMap(r => queues.get(r.logicalName).map(_.flatMap(_.queue.arn)))
      val sqsWriteArns = svc.sqsWriteQueues.flatMap(r => queues.get(r.logicalName).map(_.flatMap(_.queue.arn)))
      val s3Arns       = svc.s3Resources.flatMap(r => buckets.get(r.logicalName).map(_.flatMap(_.bucket.arn)))
      svc.name -> IamRoles.makeServiceIrsa(svc.name, ServiceIrsaInput(
        env               = stackName,
        oidcProvider      = oidcProvider,
        namespace         = namespaceName,
        awsProvider       = awsProvider,
        sqsQueueArns      = sqsReadArns,
        sqsWriteQueueArns = sqsWriteArns,
        bucketArns        = s3Arns,
        allowS3Write      = svc.allowS3Write
      ))
    }

    // gitRepoUrl is required for cloud stacks — set zio-lucene-infra:gitRepoUrl in stack config
    val repoUrl = config.require[String]("gitRepoUrl")

    val argoCD = ArgoCD.make(ArgoCDInput(
      k8sProvider = k8sProvider,
      repoUrl     = repoUrl,
      env         = stackName,
      services    = argoCDServices,
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

    // Collect dynamic per-service resources into typed sequences for Stack registration
    val queueResources: Seq[Output[?]] =
      queues.values.toSeq.flatMap(q => Seq(q.map(_.queue), q.map(_.ssmParam)))

    val bucketResources: Seq[Output[?]] =
      buckets.values.toSeq.flatMap(b => Seq(b.map(_.bucket), b.map(_.ssmParam)))

    val irsaResources: Seq[Output[?]] =
      serviceIrsas.flatMap { case (_, i) => Seq(i.map(_.role), i.map(_.rolePolicy), i.map(_.ssmParam)) }

    // NOTE: Resources that depend on the k8s provider (which itself depends on the EKS cluster)
    // will NOT appear in `pulumi preview` when the EKS cluster is being newly created. This is
    // expected Pulumi behavior — Pulumi cannot resolve provider outputs until the upstream resource
    // actually exists. During `pulumi up` they ARE created in the correct order after the cluster.
    //
    // Affected resources not shown in preview on first `pulumi preview`:
    //   - eksCluster.nodeGroup (depends on awsAuthConfigMap → k8s provider → EKS cluster)
    //   - eksCluster.awsAuthConfigMap (depends on k8s provider → EKS cluster)
    //   - All k8s resources: externalSecrets.{namespace,helmRelease,secretStore},
    //     namespace, otelCollector.*,
    //     argoCD.*, albController.{serviceAccount,helmRelease}, albIngress.ingress
    val fixedResources: Seq[Output[?]] = Seq(
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
      argoCD.map(_.namespace),
      argoCD.map(_.helmRelease),
      argoCD.map(_.applicationSet),
      albController.map(_.policy),
      albController.map(_.role),
      albController.map(_.serviceAccount),
      albController.map(_.helmRelease),
      albIngress.map(_.ingress)
    )

    Stack(
      (fixedResources ++ queueResources ++ bucketResources ++ irsaResources)*
    ).exports(
      bucketName     = buckets("segments").flatMap(_.bucketName),
      k8sNamespace   = namespaceName,
      eksClusterName = clusterName,
      esoRoleArn     = esoIrsa.map(_.roleArn)
    )

  } else {
    // ── Local (k3d + LocalStack) ──────────────────────────────────────────────

    val awsProvider = AwsProvider.makeLocal(AwsProviderInputs(stackName))
    val k8sProvider = K8Provider.makeLocal(())

    val buckets: Map[String, Output[BucketOutput]] =
      manifest.uniqueS3Buckets.map { r =>
        r.logicalName -> Buckets.makeLocal(BucketInput(r.logicalName, r.logicalName, stackName, awsProvider))
      }.toMap

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

    val argoCD = ArgoCD.makeLocal(ArgoCDInput(
      k8sProvider = k8sProvider,
      repoUrl     = Output("file:///repo"),
      env         = stackName,
      services    = argoCDServices,
      autoSync    = true
    ))

    val bucketResources: Seq[Output[?]] =
      buckets.values.toSeq.flatMap(b => Seq(b.map(_.bucket), b.map(_.ssmParam)))

    val fixedResources: Seq[Output[?]] = Seq(
      externalSecrets.map(_.namespace),
      externalSecrets.map(_.helmRelease),
      externalSecrets.map(_.secretStore),
      namespace,
      otelCollector.map(_.namespace),
      otelCollector.map(_.grafanaSecret),
      otelCollector.map(_.helmRelease),
      argoCD.map(_.namespace),
      argoCD.map(_.helmRelease),
      argoCD.map(_.applicationSet)
    )

    if (useKafka) {
      val kafkaOutput = Kafka.makeLocal(KafkaLocalInput(
        namespace = namespaceName,
        provider  = k8sProvider
      ))

      Stack(
        (fixedResources ++ bucketResources ++ Seq(kafkaOutput.map(_.service), kafkaOutput.map(_.statefulSet)))*
      ).exports(
        bucketName    = buckets("segments").flatMap(_.bucketName),
        k8sNamespace  = namespaceName,
        messagingMode = "kafka"
      )
    } else {
      val queues: Map[String, Output[QueueOutput]] =
        manifest.uniqueSqsQueues.map { r =>
          r.logicalName -> Queues.makeLocal(QueueInput(r.logicalName, r.logicalName, stackName, awsProvider))
        }.toMap

      val queueResources: Seq[Output[?]] =
        queues.values.toSeq.flatMap(q => Seq(q.map(_.queue), q.map(_.ssmParam)))

      // ── Local-only: create k8s Secrets directly (ESO cannot reach LocalStack) ──
      // In cloud mode, ExternalSecrets pull these from SSM.  In local mode, ESO
      // cannot resolve AWS_ENDPOINT_URL overrides and hits real AWS, so we bypass it
      // entirely and create the k8s Secrets ourselves.
      val ingestUrl  = queues("document-ingestion").flatMap(_.queueUrl)
      val commitUrl  = queues("document-commit").flatMap(_.queueUrl)
      val bucketName = buckets("segments").flatMap(_.bucketName)

      val ingestionSecret = k8sProvider.flatMap { prov =>
        k8s.core.v1.Secret(
          "ingestion-secrets",
          k8s.core.v1.SecretArgs(
            metadata   = k8s.meta.v1.inputs.ObjectMetaArgs(name = "ingestion-secrets", namespace = "zio-lucene"),
            stringData = ingestUrl.map(u => Map("SQS_QUEUE_URL" -> u))
          ),
          opts(provider = prov, dependsOn = namespace)
        )
      }

      val writerSecret = k8sProvider.flatMap { prov =>
        k8s.core.v1.Secret(
          "writer-secrets",
          k8s.core.v1.SecretArgs(
            metadata   = k8s.meta.v1.inputs.ObjectMetaArgs(name = "writer-secrets", namespace = "zio-lucene"),
            stringData = for (sq <- ingestUrl; cq <- commitUrl; bk <- bucketName)
                         yield Map("SQS_QUEUE_URL" -> sq, "COMMIT_QUEUE_URL" -> cq, "STORAGE_BUCKET" -> bk)
          ),
          opts(provider = prov, dependsOn = namespace)
        )
      }

      val readerSecret = k8sProvider.flatMap { prov =>
        k8s.core.v1.Secret(
          "reader-secrets",
          k8s.core.v1.SecretArgs(
            metadata   = k8s.meta.v1.inputs.ObjectMetaArgs(name = "reader-secrets", namespace = "zio-lucene"),
            stringData = bucketName.map(bk => Map("S3_BUCKET_NAME" -> bk))
          ),
          opts(provider = prov, dependsOn = namespace)
        )
      }

      Stack(
        (fixedResources ++ bucketResources ++ queueResources ++ Seq(ingestionSecret, writerSecret, readerSecret))*
      ).exports(
        bucketName    = buckets("segments").flatMap(_.bucketName),
        k8sNamespace  = namespaceName,
        messagingMode = "sqs"
      )
    }
  }
}
