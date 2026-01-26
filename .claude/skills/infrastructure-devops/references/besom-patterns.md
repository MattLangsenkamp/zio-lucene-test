# Besom/Pulumi Patterns

## Output Handling Philosophy

Besom uses `Output[T]` for values that won't be known until deployment. Outputs are similar to Futures/Promises but are specific to Pulumi's execution model.

### Core Principle: Pass Output[T] Directly to Resource Constructors

**CRITICAL**: Resource creation functions should accept `Output[T]` parameters and pass them directly to resource constructors. This allows Pulumi to build the correct dependency graph and enables preview mode.

Reference: https://virtuslab.github.io/besom/docs/io/

```scala
// ✅ GOOD - Functions taking Output parameters
def createDeployment(
  namespace: Output[String],
  bootstrapServers: Output[String],
  bucketName: Output[String]
): Output[Deployment] =
  k8s.apps.v1.Deployment(
    "my-deployment",
    k8s.apps.v1.DeploymentArgs(
      metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
        name = "my-app",
        namespace = namespace  // Pass Output[String] directly
      ),
      spec = k8s.apps.v1.inputs.DeploymentSpecArgs(
        template = k8s.core.v1.inputs.PodTemplateSpecArgs(
          spec = k8s.core.v1.inputs.PodSpecArgs(
            containers = List(
              k8s.core.v1.inputs.ContainerArgs(
                env = List(
                  k8s.core.v1.inputs.EnvVarArgs(
                    name = "KAFKA_SERVERS",
                    value = bootstrapServers  // Pass Output[String] directly
                  ),
                  k8s.core.v1.inputs.EnvVarArgs(
                    name = "BUCKET",
                    value = bucketName  // Pass Output[String] directly
                  )
                )
              )
            )
          )
        )
      )
    )
  )

// ❌ BAD - Unwrapping in for-comp before passing
def createDeployment(
  namespace: String,  // Unwrapped - loses dependency information
  bootstrapServers: String,
  bucketName: String
): Output[Deployment] = ???
```

**Why?** This pattern ensures:
1. Pulumi can build the correct dependency graph automatically
2. Preview mode works correctly (no values are actually resolved)
3. Resources can be created in parallel when possible
4. Dependencies are implicit in the Output chain

### When to Use For-Comprehensions

Use for-comprehensions ONLY when you need:
1. **Actual resource objects for dependsOn/parent relationships**
2. **Combining multiple Outputs for the final result**

```scala
// ✅ GOOD - Pass Outputs directly, no unwrapping needed
val deployment = Service.createDeployment(
  namespace = namespaceOutput,        // Pass Output[String] directly
  bootstrapServers = serversOutput,   // Pass Output[String] directly
  bucketId = bucketIdOutput           // Pass Output[String] directly
)

// ✅ GOOD - Use for-comp when you need actual resource object
val namespace = for {
  eks <- eksCluster  // Need actual Cluster object for dependsOn
  ns <- K8s.createNamespace("my-ns", Some(eks.cluster))
} yield ns

// ✅ GOOD - Use for-comp to combine Outputs for final result
val output = for {
  vpc <- vpcResource
  sg <- securityGroup
  cluster <- clusterResource
} yield MyOutput(
  vpc = vpc,      // Need actual resource objects
  sg = sg,        // Not Output[Resource]
  cluster = cluster
)

// ❌ BAD - Never yield Stack from for-comp
val stack = for {
  bucket <- S3.createBucket("segments")
  vpc <- Vpc.make(VpcInput())
} yield Stack(bucket, vpc, ...)  // Produces Output[Stack] - won't compile!

// ✅ GOOD - Construct Stack directly
Stack(
  bucket,    // Output[Bucket]
  vpcOutput, // Output[VpcOutput]
  // ...
)
```

### Fail Fast on Optional Values

When extracting optional values from resources, fail fast with clear error messages:

```scala
// Pattern: Extract optional field from resource
val resource = createResource()
val fieldOpt = resource.flatMap(_.metadata.flatMap(_.someField))
val fieldOutput = fieldOpt.getOrElse {
  throw new RuntimeException("Failed to get required field from resource")
}

// Now use in for-comprehensions
val dependent = for {
  field <- fieldOutput  // Unwraps to concrete type
  result <- doSomething(field)
} yield result
```

### Complete Pattern: Building Resources

```scala
// 1. Create base resources (returns Output[Resource])
val bucket = S3.createBucket("segments")
val vpcOutput = Vpc.make(VpcInput())

// 2. Extract primitive values using flatMap/map (returns Output[String/Int/etc])
val bucketId = bucket.flatMap(_.id)
val vpcId = vpcOutput.flatMap(_.vpc.id)

// 3. Extract optional values with fail-fast
val resourceOpt = resource.flatMap(_.metadata.flatMap(_.name))
val resourceName = resourceOpt.getOrElse {
  throw new RuntimeException("Missing required field")
}

// 4. Build dependent resources - pass Outputs directly!
val dependent = createDependentResource(
  id = bucketId,        // Pass Output[String] directly
  vpc = vpcId,          // Pass Output[String] directly
  name = resourceName   // Pass Output[String] directly
)

// 5. For resources needing actual objects (dependsOn/parent), use for-comp
val namespace = for {
  eks <- eksCluster  // Unwrap to get actual Cluster object
  ns <- K8s.createNamespace("my-ns", Some(eks.cluster))
} yield ns

// 6. Construct Stack directly (NOT in for-comp)
Stack(
  bucket,
  vpcOutput,
  dependent
).exports(
  bucketId = bucketId,
  vpcId = vpcId
)
```

### Transforming Outputs

```scala
// Simple transformation with .map()
val upperName = nameOutput.map(_.toUpperCase)

// Chaining with .flatMap() (when field is Output[T])
val bucketId = bucket.flatMap(_.id)        // bucket.id is Output[String]
val vpcId = vpcOutput.flatMap(_.vpc.id)    // vpc.id is Output[String]

// Accessing resource objects with .map()
val vpc = vpcOutput.map(_.vpc)             // vpc is Resource
val subnet1 = vpcOutput.map(_.publicSubnet1)  // subnet is Resource

// Combining multiple Outputs with .zip()
val combined = output1.zip(output2).map { case (val1, val2) =>
  s"$val1-$val2"
}

// Combining many Outputs for a result
val securityGroup = createSecurityGroup(params)
val cluster = createCluster(params, securityGroup.flatMap(_.id))

securityGroup.zip(cluster).map { case (sg, cl) =>
  MyOutput(securityGroup = sg, cluster = cl)
}
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

Each service module should provide functions that accept Output parameters:

```scala
object ServiceName:
  // Creates Kubernetes Service resource
  def createService(
    namespace: Output[String],  // ✅ Output[String] for dependency tracking
    port: Int = <default-port>
  )(using Context): Output[k8s.core.v1.Service] =
    k8s.core.v1.Service(
      "service-name-service",
      k8s.core.v1.ServiceArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = "service-name",
          namespace = namespace,  // Pass Output[String] directly
          labels = Map("app" -> "service-name")
        ),
        spec = k8s.core.v1.inputs.ServiceSpecArgs(
          selector = Map("app" -> "service-name"),
          ports = List(
            k8s.core.v1.inputs.ServicePortArgs(
              port = port,
              targetPort = 8080
            )
          )
        )
      )
    )

  // Creates Deployment or StatefulSet
  def createDeployment(
    namespace: Output[String],        // ✅ Output[String]
    bootstrapServers: Output[String], // ✅ Output[String]
    bucketName: Output[String],       // ✅ Output[String]
    replicas: Int = 1,
    image: String = "default:latest",
    imagePullPolicy: String = "IfNotPresent"
  )(using Context): Output[k8s.apps.v1.Deployment] =
    k8s.apps.v1.Deployment(
      "service-name-deployment",
      k8s.apps.v1.DeploymentArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = "service-name",
          namespace = namespace  // Pass Output[String] directly
        ),
        spec = k8s.apps.v1.inputs.DeploymentSpecArgs(
          template = k8s.core.v1.inputs.PodTemplateSpecArgs(
            spec = k8s.core.v1.inputs.PodSpecArgs(
              containers = List(
                k8s.core.v1.inputs.ContainerArgs(
                  image = image,
                  env = List(
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "KAFKA_BOOTSTRAP_SERVERS",
                      value = bootstrapServers  // Pass Output[String] directly
                    ),
                    k8s.core.v1.inputs.EnvVarArgs(
                      name = "BUCKET_NAME",
                      value = bucketName  // Pass Output[String] directly
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
```

**Pattern**: Functions return `Output[Resource]` and accept `Output[T]` parameters for values that come from other resources. The caller passes Outputs directly without unwrapping.

### Resource Trait Pattern

For infrastructure that needs environment-specific behavior, use the Resource trait:

```scala
// 1. Define input case class with Output[T] fields
case class MyResourceInput(
  vpcId: Output[String],
  subnetIds: List[Output[String]]
)

// 2. Define output case class with actual resource objects
case class MyResourceOutput(
  resource: aws.someservice.Resource,
  securityGroup: aws.ec2.SecurityGroup
)

// 3. Implement Resource trait
object MyResource extends Resource[MyResourceInput, MyResourceOutput]:
  // For cloud environments (dev/prod)
  // Keep make() method SIMPLE - delegate to private functions
  def make(input: MyResourceInput)(using Context): Output[MyResourceOutput] =
    val securityGroup = createSecurityGroup(input)
    val resource = createResource(input, securityGroup.flatMap(_.id))

    securityGroup.zip(resource).map { case (sg, res) =>
      MyResourceOutput(
        resource = res,
        securityGroup = sg
      )
    }

  // Private functions accept Output[T] and pass them directly
  private def createSecurityGroup(input: MyResourceInput)(using Context): Output[aws.ec2.SecurityGroup] =
    aws.ec2.SecurityGroup(
      "my-sg",
      aws.ec2.SecurityGroupArgs(
        vpcId = input.vpcId,  // Pass Output[String] directly
        // ...
      )
    )

  private def createResource(
    input: MyResourceInput,
    securityGroupId: Output[String]
  )(using Context): Output[aws.someservice.Resource] =
    aws.someservice.Resource(
      "my-resource",
      aws.someservice.ResourceArgs(
        securityGroups = List(securityGroupId),  // Pass Output[String] directly
        // ...
      )
    )

  // For local environment
  // PREFER: Implement in terms of make() when possible
  def makeLocal(input: MyResourceInput)(using Context): Output[MyResourceOutput] =
    make(input)  // Reuse cloud implementation if no differences needed
```

**Usage in Main.scala:**
```scala
if (!isLocal) {
  val myResource = MyResource.make(MyResourceInput(vpcId, subnetIds))
} else {
  val myResource = MyResource.makeLocal(MyResourceInput(vpcId, subnetIds))
}
```

**Why implement makeLocal in terms of make?**
- Reduces duplication
- Ensures consistency between environments
- Only add complexity when there's a real environmental difference

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

Use the `PULUMI_STACK` environment variable (automatically set by Pulumi CLI) to drive conditional infrastructure:
```scala
val stackName = sys.env.get("PULUMI_STACK").getOrElse {
  throw new RuntimeException("PULUMI_STACK environment variable is not set. This should be set automatically by Pulumi CLI.")
}
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

## Output[T] Double-Wrapping Pitfall

**Problem**: When a case class field is typed as `Output[T]`, and that case class is returned from a function that returns `Output[CaseClass]`, you end up with `Output[Output[T]]` when accessing the field.

```scala
// ❌ BAD - Fields wrapped in Output cause double-wrapping
case class VpcOutput(
  vpc: Output[ec2.Vpc],           // Field is Output[Vpc]
  publicSubnet1: Output[ec2.Subnet]
)

def make(): Output[VpcOutput] = ...

// When used:
val vpcOutput: Output[VpcOutput] = Vpc.make()
val vpc: Output[Output[ec2.Vpc]] = vpcOutput.map(_.vpc)  // Double-wrapped!

// ✅ GOOD - Fields are plain resource types
case class VpcOutput(
  vpc: ec2.Vpc,           // Field is Vpc directly
  publicSubnet1: ec2.Subnet
)

def make(): Output[VpcOutput] = ...

// When used:
val vpcOutput: Output[VpcOutput] = Vpc.make()
val vpc: Output[ec2.Vpc] = vpcOutput.map(_.vpc)  // Single Output wrapper
```

**Rule**: Output case class fields should contain the actual resource types, not `Output[T]`. The `Output` wrapper comes from the function return type.

## For-Comprehension Side Effects

**Problem**: Resources created as side effects in for-comprehensions may not be tracked if their Outputs aren't used.

```scala
// ❌ BAD - natGateway and route created but Outputs discarded
private def createPrivateRouting(...): Unit =
  for {
    eip <- aws.ec2.Eip(...)
    natGateway <- aws.ec2.NatGateway(...)  // Output discarded!
    route <- aws.ec2.Route(...)            // Output discarded!
  } yield ()  // Returns Unit, resources may not be tracked

// ✅ GOOD - Return the resources so they're tracked
private def createPrivateRouting(...): Output[(ec2.NatGateway, ec2.Route)] =
  for {
    eip <- aws.ec2.Eip(...)
    natGateway <- aws.ec2.NatGateway(...)
    route <- aws.ec2.Route(...)
  } yield (natGateway, route)  // Resources are part of Output chain
```

**Rule**: Always return resources from for-comprehensions. If you need them in an outer Output, include them in the yield or ensure they're used in the final Stack.

## Kubernetes Admission Webhook Timing

**Problem**: Helm charts that install admission webhooks (like AWS Load Balancer Controller) register the webhook configuration immediately, but the pods that handle webhook requests take time to start. If other resources try to create Services/Ingresses before pods are ready, the API server's webhook call fails.

```
Error: Internal error occurred: failed calling webhook "mservice.elbv2.k8s.aws":
Post "https://aws-load-balancer-webhook-service.kube-system.svc:443/mutate-v1-service":
dial tcp ... connect: connection refused
```

**Solution**: Split webhook-installing resources into separate phases and make Service-creating resources depend on them:

```scala
// Phase 1: Install ALB Controller early (registers webhook)
val albController = AlbController.make(
  AlbControllerInput(
    eksCluster = eks.cluster,
    nodeGroup = eks.nodeGroup,
    // ...
  )
)

// Phase 2: Resources that create Services depend on controller
val otelCollector = for {
  alb <- albController
  collector <- OtelCollector.make(
    OtelCollectorInput(
      // ...
      albControllerHelmRelease = Some(alb.helmRelease)  // Ensures webhook is ready
    )
  )
} yield collector

// Phase 3: Ingress created last (after all Services exist)
val ingress = for {
  alb <- albController
  ing <- AlbIngress.make(
    AlbIngressInput(
      albController = alb,
      // ...
    )
  )
} yield ing
```

**Key insight**: The Helm release with `waitForJobs = true` ensures pods are ready before the Output resolves, so dependent resources won't start until the webhook can handle requests.

## OpenTelemetry Kube Stack Helm Chart

### Helm Values Structure

The `opentelemetry-kube-stack` chart has a specific structure. Use `collectors.daemon` for the operator-managed DaemonSet collector, NOT `opentelemetry-collector` (which is a subchart):

```scala
// ❌ WRONG - opentelemetry-collector is a subchart with different structure
"opentelemetry-collector" -> JsObject(
  "config" -> JsObject(...)
)

// ✅ CORRECT - collectors.daemon is the operator-managed collector
"collectors" -> JsObject(
  "daemon" -> JsObject(
    "env" -> JsArray(...),      // Environment variables
    "config" -> JsObject(...)   // Collector config (merged with defaults)
  )
)
```

### Grafana Cloud Integration Pattern

To export telemetry to Grafana Cloud:

1. **Create a Secret with credentials**:
```scala
k8s.core.v1.Secret(
  "grafana-cloud-auth",
  k8s.core.v1.SecretArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "grafana-cloud-auth",
      namespace = "opentelemetry-operator-system"
    ),
    stringData = Map(
      "GRAFANA_CLOUD_INSTANCE_ID" -> instanceId,
      "GRAFANA_CLOUD_API_KEY" -> apiKey,
      "GRAFANA_CLOUD_OTLP_ENDPOINT" -> endpoint
    )
  )
)
```

2. **Configure collector with env vars and exporters**:
```scala
"collectors" -> JsObject(
  "daemon" -> JsObject(
    // Inject credentials as env vars
    "env" -> JsArray(Vector(
      JsObject(
        "name" -> JsString("GRAFANA_CLOUD_OTLP_ENDPOINT"),
        "valueFrom" -> JsObject(
          "secretKeyRef" -> JsObject(
            "name" -> JsString("grafana-cloud-auth"),
            "key" -> JsString("GRAFANA_CLOUD_OTLP_ENDPOINT")
          )
        )
      ),
      // ... similar for INSTANCE_ID and API_KEY
    )),
    // Config is merged with chart defaults
    "config" -> JsObject(
      "extensions" -> JsObject(
        "basicauth/grafana" -> JsObject(
          "client_auth" -> JsObject(
            "username" -> JsString("${env:GRAFANA_CLOUD_INSTANCE_ID}"),
            "password" -> JsString("${env:GRAFANA_CLOUD_API_KEY}")
          )
        )
      ),
      "exporters" -> JsObject(
        "otlphttp/grafana" -> JsObject(
          "endpoint" -> JsString("${env:GRAFANA_CLOUD_OTLP_ENDPOINT}"),
          "auth" -> JsObject(
            "authenticator" -> JsString("basicauth/grafana")
          )
        )
      ),
      "service" -> JsObject(
        "extensions" -> JsArray(Vector(JsString("basicauth/grafana"))),
        "pipelines" -> JsObject(
          "traces" -> JsObject(
            "exporters" -> JsArray(Vector(
              JsString("otlphttp/grafana"),
              JsString("debug")
            ))
          ),
          "metrics" -> JsObject(
            "exporters" -> JsArray(Vector(
              JsString("otlphttp/grafana"),
              JsString("debug")
            ))
          ),
          "logs" -> JsObject(
            "exporters" -> JsArray(Vector(
              JsString("otlphttp/grafana"),
              JsString("debug")
            ))
          )
        )
      )
    )
  )
)
```

**Key points**:
- Use `basicauth/grafana` extension for Grafana Cloud authentication
- Use `otlphttp/grafana` exporter (HTTP-based OTLP)
- Reference env vars with `${env:VAR_NAME}` syntax in config
- Add extension to `service.extensions` list
- Add exporter to each pipeline's `exporters` list

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
