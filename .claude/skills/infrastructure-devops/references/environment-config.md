# Environment Configuration

## STACK_ENV Pattern

All infrastructure code reads the environment from `STACK_ENV`:
```scala
val stackName = sys.env.getOrElse("STACK_ENV", "local")
val isLocal = stackName == "local"
```

The Makefile sets this for each environment:
- `make local-dev`: Sets `STACK_ENV=local`
- `make dev`: Sets `STACK_ENV=dev`
- `make prod`: Sets `STACK_ENV=prod`

## Environment Variables Strategy

### Current Approach

Environment variables are passed inline to container specs:
```scala
env = List(
  k8s.core.v1.inputs.EnvVarArgs(
    name = "KAFKA_BOOTSTRAP_SERVERS",
    value = kafkaBootstrapServers
  ),
  k8s.core.v1.inputs.EnvVarArgs(
    name = "S3_BUCKET_NAME",
    value = bucketName
  )
)
```

### Future Enhancement

Use ConfigMaps for non-sensitive config, Secrets for sensitive data:
```scala
// Create ConfigMap
val appConfig = k8s.core.v1.ConfigMap(
  "app-config",
  k8s.core.v1.ConfigMapArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "app-config",
      namespace = namespace
    ),
    data = Map(
      "KAFKA_BOOTSTRAP_SERVERS" -> kafkaBootstrapServers,
      "S3_BUCKET_NAME" -> bucketName
    )
  )
)

// Reference in deployment
envFrom = List(
  k8s.core.v1.inputs.EnvFromSourceArgs(
    configMapRef = k8s.core.v1.inputs.ConfigMapEnvSourceArgs(
      name = "app-config"
    )
  )
)
```

## Bootstrap Servers Pattern

- **Local**: `kafka-0.kafka.zio-lucene.svc.cluster.local:9092` (in-cluster DNS)
- **Dev/Prod**: MSK cluster bootstrap brokers endpoint from `mskCluster.bootstrapBrokers`

The pattern uses the StatefulSet pod naming convention:
- `<statefulset-name>-<ordinal>.<service-name>.<namespace>.svc.cluster.local:<port>`
- For Kafka: `kafka-0.kafka.zio-lucene.svc.cluster.local:9092`

## Stack Exports

Current exports (for potential multi-project split later):
```scala
Stack.exports(
  bucketName = bucket.id,
  kafkaBootstrapServers = "...",  // or mskCluster.bootstrapBrokers
  k8sNamespace = namespaceName
)
```

These can be consumed by other Pulumi projects using StackReference:
```scala
val infraStack = StackReference(s"organization/infra/$stackName")
val bucketName = infraStack.getOutput("bucketName")
val kafkaBootstrapServers = infraStack.requireOutput("kafkaBootstrapServers")
```

## Kafka Configuration (Local)

- **Mode**: KRaft (no ZooKeeper)
- **Image**: `apache/kafka:4.1.0`
- **Replicas**: 1
- **Storage**: 1Gi PVC at `/var/lib/kafka/data`
- **Listeners**:
  - `PLAINTEXT://0.0.0.0:9092` (client traffic)
  - `CONTROLLER://0.0.0.0:9093` (internal controller)
- **Advertised listener**: `PLAINTEXT://kafka-0.kafka.zio-lucene.svc.cluster.local:9092`
- **Replication factors**: 1 (single broker, dev only)
