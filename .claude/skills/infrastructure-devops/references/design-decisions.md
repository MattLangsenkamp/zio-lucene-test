# Design Decisions

This document captures project-specific architectural decisions and patterns that are unique to this infrastructure setup.

## Fail-Fast Error Handling

**Decision**: Never use `.getOrElse("")` or silent defaults in infrastructure code.

**Rationale**: Silent failures create misconfigured resources that fail in production. Better to fail immediately during deployment with a clear error message.

**Pattern**:
```scala
// ❌ WRONG - Silent failure
val name = resource.metadata.name.map(_.getOrElse("default"))

// ✅ CORRECT - Fail fast with context
val name = resource.metadata.name.map(_.getOrElse {
  throw new RuntimeException("Failed to get resource name from created resource")
})

// ✅ CORRECT - For resource creation
NonEmptyString(name).getOrElse {
  throw new IllegalArgumentException(s"Resource name cannot be empty. Provided: '$name'")
}
```

**Exception**: Pulumi config values that fail-fast are acceptable:
```scala
// This is OK - fails immediately if stackEnv is not configured
val config = Config("zio-lucene-infra")
val stackName = config.require[String]("stackEnv")
```

## Port Mapping Standardization

**Decision**: All applications listen on port 8080 internally, but expose different external ports.

**Port Allocation**:
- Ingestion: 8080 (external) → 8080 (internal)
- Reader: 8081 (external) → 8080 (internal)
- Writer: 8082 (external) → 8080 (internal)

**Production**: All services expose port 80 externally (for ALB Ingress).

**Why**:
1. Simplifies application code - no PORT environment variable needed
2. All apps use same internal configuration
3. External ports prevent local port conflicts during development
4. Production standardizes on port 80 for load balancer compatibility

**Implementation**:
```scala
// Service definition
ports = List(
  k8s.core.v1.inputs.ServicePortArgs(
    name = "http",
    port = 80,        // External port (production)
    targetPort = 8080 // Internal container port
  )
)

// Container definition
ports = List(
  k8s.core.v1.inputs.ContainerPortArgs(
    containerPort = 8080,
    name = "http"
  )
)
```

## Writer as StatefulSet

**Decision**: Writer service uses StatefulSet instead of Deployment.

**Rationale**:
1. **Stable Identity**: Predictable pod names (writer-0, writer-1) for debugging
2. **Persistent Storage**: Each pod gets its own PersistentVolumeClaim for local Lucene index building
3. **Ordered Operations**: StatefulSet ensures ordered, graceful startup/shutdown
4. **DNS Stability**: Each pod gets a stable DNS name: `writer-0.writer.zio-lucene.svc.cluster.local`

**Trade-offs**:
- Slower rollouts (sequential updates)
- More complex to scale
- Acceptable because Writer is I/O bound and benefits from stable storage

**Pattern**:
```scala
// Headless service for StatefulSet
def createService(...): Output[k8s.core.v1.Service] =
  k8s.core.v1.Service(
    "writer-service",
    k8s.core.v1.ServiceArgs(
      spec = k8s.core.v1.inputs.ServiceSpecArgs(
        clusterIP = "None",  // Headless service
        selector = Map("app" -> "writer")
      )
    )
  )

// StatefulSet with persistent storage
volumeClaimTemplates = List(
  k8s.core.v1.inputs.PersistentVolumeClaimArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(name = "writer-data"),
    spec = k8s.core.v1.inputs.PersistentVolumeClaimSpecArgs(
      accessModes = List("ReadWriteOnce"),
      resources = k8s.core.v1.inputs.VolumeResourceRequirementsArgs(
        requests = Map("storage" -> "1Gi")
      )
    )
  )
)
```

## k3d Image Management

**Decision**: Docker images must be explicitly imported into k3d cluster.

**Why k3d is Different**:
- k3d runs Kubernetes inside Docker
- It doesn't have access to local Docker images by default
- Images must be imported after building

**Pattern**:
```bash
# 1. Build images
./mill reader.server.docker.build
./mill ingestion.server.docker.build
./mill writer.server.docker.build

# 2. Import into k3d
k3d image import reader-server:latest -c zio-lucene
k3d image import ingestion-server:latest -c zio-lucene
k3d image import writer-server:latest -c zio-lucene
```

**Deployment Configuration**:
```scala
// CRITICAL: Use IfNotPresent to use locally imported images
k8s.core.v1.inputs.ContainerArgs(
  name = "reader",
  image = "reader-server:latest",
  imagePullPolicy = "IfNotPresent"  // Don't try to pull from registry
)
```

**Makefile Automation**:
```makefile
build-apps:
	./mill reader.server.docker.build
	./mill ingestion.server.docker.build
	./mill writer.server.docker.build

import-images:
	k3d image import ingestion-server:latest -c $(K3D_CLUSTER_NAME)
	k3d image import reader-server:latest -c $(K3D_CLUSTER_NAME)
	k3d image import writer-server:latest -c $(K3D_CLUSTER_NAME)

local-dev: start-local-env import-images local-dev-up
```

## Besom Output[T] Handling

**Decision**: Pass `Output[T]` directly to resource constructors. This allows Pulumi to build the correct dependency graph and enables preview mode.

Reference: https://virtuslab.github.io/besom/docs/io/

### Function Signatures

**Pattern**: Functions accept `Output[T]` parameters and pass them directly to resource constructors:

```scala
// ✅ CORRECT - Function accepts Output parameters
def createDeployment(
  namespace: Output[String],
  kafkaBootstrap: Output[String],
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
                    value = kafkaBootstrap  // Pass Output[String] directly
                  )
                )
              )
            )
          )
        )
      )
    )
  )

// ❌ WRONG - Unwrapping loses dependency information
def createDeployment(
  namespace: String,
  kafkaBootstrap: String,
  bucketName: String
): Output[Deployment] = ???
```

**Why**:
1. Pulumi builds the correct dependency graph automatically
2. Preview mode works correctly (no values are resolved)
3. Resources can be created in parallel when possible
4. Dependencies are implicit in the Output chain

### When to Use For-Comprehensions

**Use for-comps ONLY when**:
1. You need actual resource objects for dependsOn/parent relationships
2. You're combining multiple Outputs for the final result

**Never use for-comps to**: Yield Stack (Stack must be constructed directly)

```scala
// ✅ CORRECT - Pass Outputs directly, no unwrapping
val deployment = createDeployment(
  namespace = namespaceOutput,      // Pass Output[String] directly
  kafkaBootstrap = bootstrapOutput, // Pass Output[String] directly
  bucketId = bucketIdOutput         // Pass Output[String] directly
)

// ✅ CORRECT - Use for-comp when you need actual resource object
val namespace = for {
  eks <- eksCluster  // Need actual Cluster object for dependsOn
  ns <- K8s.createNamespace("my-ns", Some(eks.cluster))
} yield ns

// ❌ WRONG - Never yield Stack from for-comp
val stack = for {
  bucket <- S3.createBucket("segments")
  vpc <- Vpc.make(VpcInput())
} yield Stack(bucket, vpc)  // Produces Output[Stack] - won't compile!

// ✅ CORRECT - Construct Stack directly
Stack(
  bucket,    // Output[Bucket]
  vpcOutput, // Output[VpcOutput]
  deployment // Output[Deployment]
).exports(
  bucketId = bucketIdOutput
)
```

### Nested Output Types

**Key Insight**: In Besom's Kubernetes provider, even nested fields are wrapped in `Output`:
```scala
// ingress is Output[k8s.networking.v1.Ingress]
// ingress.status is Output[...]
// ingress.status.loadBalancer is Output[...]
// ingress.status.loadBalancer.ingress is Output[Option[Iterable[IngressLoadBalancerIngress]]]
// ingressItem.hostname is Output[Option[String]]  // ← Not just Option[String]!
```

**Pattern for Extracting Nested Values**:
```scala
// Extract ALB hostname from Ingress
val albHostname: Output[String] = ingress.status.loadBalancer.ingress.flatMap { maybeIngressList =>
  maybeIngressList
    .flatMap(_.headOption)  // Get first element from Iterable
    .map(_.hostname)         // hostname is Output[Option[String]], not Option[String]
    .getOrElse(Output(None)) // If no ingress entry, use None
    .map(_.getOrElse {       // Unwrap Option[String] to String with fail-fast
      throw new RuntimeException(
        "Failed to get ALB hostname from Ingress: hostname field is None"
      )
    })
}
```

### Extracting Optional Fields

**Pattern**: Fail fast when extracting required optional fields from resources:

```scala
val resource = createResource()
val fieldOpt = resource.flatMap(_.metadata.flatMap(_.requiredField))
val fieldOutput = fieldOpt.getOrElse {
  throw new RuntimeException("Failed to get required field from resource")
}

// Now use in for-comprehensions
val dependent = for {
  field <- fieldOutput  // Unwraps to String/Int/etc
  result <- doSomething(field)
} yield result
```

## HTTPS with ACM Certificate

**Decision**: Conditionally configure HTTPS based on certificate availability.

**Configuration Storage**:
```bash
# Store certificate ARN in Pulumi config
pulumi config set certificateArn arn:aws:acm:us-east-1:ACCOUNT:certificate/CERT_ID
pulumi config set domain zio-lucent-test.com
pulumi config set hostedZoneId Z09722571DSCDPR3Y2KK1
```

**Dynamic Ingress Configuration**:
```scala
val annotations = certArnOpt match
  case Some(certArn) =>
    // HTTPS with SSL certificate
    baseAnnotations ++ Map(
      "alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}, {"HTTPS": 443}]""",
      "alb.ingress.kubernetes.io/certificate-arn" -> certArn,
      "alb.ingress.kubernetes.io/ssl-redirect" -> "443"  // Auto-redirect HTTP → HTTPS
    )
  case None =>
    // HTTP only (for testing/local)
    baseAnnotations + ("alb.ingress.kubernetes.io/listen-ports" -> """[{"HTTP": 80}]""")
```

**Behavior**:
- **With certificate**: Listens on 80 and 443, automatically redirects HTTP → HTTPS
- **Without certificate**: HTTP only on port 80
- **Local environment**: No certificate configured, uses HTTP

## Route53 DNS Management

**Decision**: Route53 record creation is fully automated when config is provided.

**How It Works**:
1. Pulumi waits for the AWS Load Balancer Controller to provision the ALB
2. Extracts the ALB hostname from the Ingress resource's status field
3. Automatically creates a Route53 A record (alias) pointing to the ALB

**Configuration Required**:
```bash
pulumi config set hostedZoneId Z09722571DSCDPR3Y2KK1
pulumi config set domain zio-lucent-test.com
```

**Implementation**:
```scala
// Extract ALB hostname from Ingress status (nested Output handling)
val albHostname: Output[String] = ingress.status.loadBalancer.ingress.flatMap { maybeIngressList =>
  maybeIngressList
    .flatMap(_.headOption)
    .map(_.hostname)  // hostname is Output[Option[String]], not Option[String]
    .getOrElse {
      throw new RuntimeException("ALB ingress list is empty")
    }
    .map(_.getOrElse {
      throw new RuntimeException("ALB hostname field is None")
    })
}

// Create Route53 A record (alias)
aws.route53.Record(
  "alb-dns-record",
  aws.route53.RecordArgs(
    zoneId = zoneId,
    name = domain,
    `type` = "A",
    aliases = List(
      aws.route53.inputs.RecordAliasArgs(
        name = albHostname,
        zoneId = "Z35SXDOTRQ7X7K",  // us-east-1 ALB hosted zone ID
        evaluateTargetHealth = true
      )
    )
  )
)
```

**Domain Pattern**:
- **prod**: `zio-lucent-test.com`
- **dev**: `dev.zio-lucent-test.com`
- **staging**: `staging.zio-lucent-test.com`

**Tear-down and Re-deploy**:
- `pulumi destroy` removes all resources including Route53 record
- `pulumi up` recreates everything including Route53 record automatically
- No manual intervention required if config is set

## ALB Ingress with IRSA

**Decision**: Use IAM Roles for Service Accounts (IRSA) for AWS Load Balancer Controller.

**Components**:
1. **OIDC Provider**: Links EKS cluster to IAM
2. **IAM Policy**: Grants ALB Controller permissions to manage load balancers
3. **IAM Role**: Assumable by Kubernetes ServiceAccount via OIDC
4. **ServiceAccount**: Annotated with IAM role ARN
5. **Helm Release**: AWS Load Balancer Controller configured to use ServiceAccount

**Trust Relationship**:
```scala
assumeRolePolicy = s"""{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "${clusterOidcIssuerArn}"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:$ns:aws-load-balancer-controller",
        "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
      }
    }
  }]
}"""
```

**Why IRSA**:
- No static AWS credentials in pods
- Automatic credential rotation
- Fine-grained permissions per service
- Follows AWS security best practices

## Health Check Port Forwarding

**Decision**: Document the port mapping pattern for health checks in k3d.

**kubectl port-forward Pattern**:
```bash
# Pattern: kubectl port-forward <pod> <local-port>:<container-port>
kubectl port-forward -n zio-lucene reader-pod-abc123 8081:8080

# Then access health endpoint
curl http://localhost:8081/health
```

**Why This Mapping**:
1. **Container port**: Always 8080 (standardized)
2. **Local port**: Matches service's external port (8081 for reader, 8082 for writer, etc.)
3. **Prevents conflicts**: Each service uses different local port

**Makefile Implementation**:
```makefile
health:
	@if [ "$(SERVICE)" = "reader" ]; then \
		PORT=8081; \
	elif [ "$(SERVICE)" = "writer" ]; then \
		PORT=8082; \
	elif [ "$(SERVICE)" = "ingestion" ]; then \
		PORT=8083; \
	fi; \
	kubectl port-forward -n $(NAMESPACE) $$POD $$PORT:8080 &
	# ... curl health check
```

## Pulumi Config for Secrets

**Decision**: Store environment-specific secrets and ARNs in Pulumi config, not code.

**Pattern**:
```bash
# Set config values
pulumi config set hostedZoneId Z09722571DSCDPR3Y2KK1
pulumi config set domain zio-lucent-test.com
pulumi config set certificateArn arn:aws:acm:...

# Use --secret for sensitive values
pulumi config set --secret dbPassword mySecretPassword
```

**Reading in Code**:
```scala
val config = Config("zio-lucene-infra")
val hostedZoneId = config.get[String]("hostedZoneId")  // Returns Output[Option[String]]
val baseDomain = config.get[String]("domain")
val certificateArn = config.get[String]("certificateArn")
```

**Why**:
1. Different values per stack (local/dev/prod)
2. ARNs and IDs aren't known until resources are created
3. Keeps secrets out of version control
4. Easy to update without code changes

**Config File Location**: `Pulumi.local.yaml`, `Pulumi.dev.yaml`, `Pulumi.prod.yaml`
