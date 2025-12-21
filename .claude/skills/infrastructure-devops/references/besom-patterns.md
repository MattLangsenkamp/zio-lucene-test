# Besom/Pulumi Patterns

## Output Handling

Besom uses `Output[T]` for values that won't be known until deployment. Outputs are similar to Futures/Promises but are specific to Pulumi's execution model.

### Transforming Outputs

Use `.map()` to transform Output values:
```scala
val namespace = K8s.createNamespace("zio-lucene")
val namespaceName = namespace.metadata.name.map(_.getOrElse("zio-lucene"))
```

### Passing Outputs to Resources

Outputs can be passed directly to other resource constructors:
```scala
val service = createService(namespaceName)  // namespaceName is Output[String]
```

### Combining Multiple Outputs

Use `Output.all()` or for-comprehensions:
```scala
// Using Output.all
val combined = Output.all(output1, output2).map { case (val1, val2) =>
  s"$val1-$val2"
}

// Using for-comprehension (in ZIO style)
val result = for {
  val1 <- output1
  val2 <- output2
} yield s"$val1-$val2"
```

## Resource Organization

### Utility Modules

Separate infrastructure concerns into focused modules:

**Infrastructure Utilities** (`utils/K8s.scala`, `utils/S3.scala`, `utils/MSK.scala`):
- Generic, reusable patterns
- Can be used across multiple projects
- No application-specific logic

**Service Utilities** (`utils/ingestion/`, `utils/reader/`, `utils/writer/`):
- Application-specific deployments
- Combines infrastructure utilities with app configuration
- One module per service

### Standard Service Module Pattern

Each service module should provide:
```scala
object ServiceName:
  // Creates Kubernetes Service resource
  def createService(
    namespace: Output[String],
    port: Int = <default-port>
  )(using Context): Output[k8s.core.v1.Service] = ???

  // Creates Deployment or StatefulSet
  def createDeployment(
    namespace: Output[String],
    // ... other dependencies
  )(using Context): Output[k8s.apps.v1.Deployment] = ???
```

## Stack Exports

Export values that might be needed by other stacks or for reference:
```scala
Stack.exports(
  bucketName = bucket.id,
  kafkaBootstrapServers = kafkaServers,
  k8sNamespace = namespaceName
)
```

These exports can be:
1. Viewed with `pulumi stack output`
2. Referenced by other Pulumi projects using `StackReference`
3. Used in CI/CD pipelines

## Environment-Specific Logic

Use environment variables to drive conditional infrastructure:
```scala
val stackName = sys.env.getOrElse("STACK_ENV", "local")
val isLocal = stackName == "local"

if (!isLocal) {
  // Create cloud resources (MSK, EKS, etc.)
} else {
  // Create local resources (k3d Kafka, etc.)
}
```

## Resource Naming

Use consistent naming patterns:
```scala
// Resource name in Pulumi state (unique identifier)
k8s.core.v1.Service(
  "service-name-service",  // Pulumi resource name
  k8s.core.v1.ServiceArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "service-name",  // Kubernetes resource name
      namespace = namespace
    ),
    // ...
  )
)
```

**Pattern**: `<resource-type>-<purpose>` for Pulumi name, `<purpose>` for K8s name

## Common Kubernetes Patterns

### ClusterIP Service
```scala
k8s.core.v1.Service(
  "my-service",
  k8s.core.v1.ServiceArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "my-app",
      namespace = namespace,
      labels = Map("app" -> "my-app")
    ),
    spec = k8s.core.v1.inputs.ServiceSpecArgs(
      selector = Map("app" -> "my-app"),
      ports = List(
        k8s.core.v1.inputs.ServicePortArgs(
          name = "http",
          port = 8080,
          targetPort = 8080
        )
      ),
      `type` = "ClusterIP"
    )
  )
)
```

### Headless Service (for StatefulSets)
```scala
spec = k8s.core.v1.inputs.ServiceSpecArgs(
  selector = Map("app" -> "my-app"),
  ports = List(/*...*/),
  clusterIP = "None"  // Makes it headless
)
```

### Deployment
```scala
k8s.apps.v1.Deployment(
  "my-deployment",
  k8s.apps.v1.DeploymentArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "my-app",
      namespace = namespace
    ),
    spec = k8s.apps.v1.inputs.DeploymentSpecArgs(
      replicas = 3,
      selector = k8s.meta.v1.inputs.LabelSelectorArgs(
        matchLabels = Map("app" -> "my-app")
      ),
      template = k8s.core.v1.inputs.PodTemplateSpecArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          labels = Map("app" -> "my-app")
        ),
        spec = k8s.core.v1.inputs.PodSpecArgs(
          containers = List(/*...*/)
        )
      )
    )
  )
)
```

### StatefulSet with Persistent Storage
```scala
k8s.apps.v1.StatefulSet(
  "my-statefulset",
  k8s.apps.v1.StatefulSetArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "my-app",
      namespace = namespace
    ),
    spec = k8s.apps.v1.inputs.StatefulSetSpecArgs(
      serviceName = "my-app",  // Must match headless service name
      replicas = 1,
      selector = k8s.meta.v1.inputs.LabelSelectorArgs(
        matchLabels = Map("app" -> "my-app")
      ),
      template = k8s.core.v1.inputs.PodTemplateSpecArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          labels = Map("app" -> "my-app")
        ),
        spec = k8s.core.v1.inputs.PodSpecArgs(
          containers = List(
            k8s.core.v1.inputs.ContainerArgs(
              name = "my-app",
              image = "my-image:latest",
              volumeMounts = List(
                k8s.core.v1.inputs.VolumeMountArgs(
                  name = "data",
                  mountPath = "/data"
                )
              )
            )
          )
        )
      ),
      volumeClaimTemplates = List(
        k8s.core.v1.inputs.PersistentVolumeClaimArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "data"  // Must match volumeMount name
          ),
          spec = k8s.core.v1.inputs.PersistentVolumeClaimSpecArgs(
            accessModes = List("ReadWriteOnce"),
            resources = k8s.core.v1.inputs.VolumeResourceRequirementsArgs(
              requests = Map("storage" -> "1Gi")
            )
          )
        )
      )
    )
  )
)
```

## Multi-Project Pattern (Future)

When splitting into multiple Pulumi projects:

**Infrastructure Project** exports:
```scala
Stack.exports(
  bucketName = bucket.id,
  kafkaBootstrapServers = bootstrapServers,
  k8sNamespace = namespaceName
)
```

**Application Project** imports:
```scala
val infraStack = StackReference(s"organization/infra/$stackName")
val bucketName = infraStack.getOutput("bucketName")
val kafkaBootstrapServers = infraStack.requireOutput("kafkaBootstrapServers")
val namespace = infraStack.requireOutput("k8sNamespace")
```
