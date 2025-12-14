//> using jvm "21"
//> using scala "3.7.4"
//> using dep "org.virtuslab::besom-core:0.5.0"
//> using dep "org.virtuslab::besom-aws:7.7.0-core.0.5"
//> using dep "org.virtuslab::besom-kubernetes:4.19.0-core.0.5"

import besom.*
import utils.{S3, K8s, MSK}

@main def main = Pulumi.run {
  val stackName = config.require[String]("pulumi:stack") //.getOrElse("local")
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

    Stack(bucket, mskInfra.vpc, mskInfra.subnet1, mskInfra.subnet2, mskInfra.securityGroup, mskInfra.cluster, namespace)
      .exports(
        bucketName = bucket.id,
        mskClusterArn = mskInfra.cluster.arn,
        mskBootstrapBrokers = mskInfra.cluster.bootstrapBrokers,
        k8sNamespace = namespaceName
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

    Stack(bucket, namespace, kafkaService, kafkaStatefulSet)
      .exports(
        bucketName = bucket.id,
        k8sNamespace = namespaceName,
        kafkaBootstrapServers = "kafka-0.kafka.zio-lucene.svc.cluster.local:9092"
      )
  }
}
