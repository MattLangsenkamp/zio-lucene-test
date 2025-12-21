# Future Enhancements

Likely next steps for the infrastructure:

## 1. ConfigMaps and Secrets

**Current**: Environment variables are inline in deployments

**Enhancement**: Separate configuration from deployment manifests

```scala
// ConfigMap for non-sensitive config
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
spec = k8s.core.v1.inputs.PodSpecArgs(
  containers = List(
    k8s.core.v1.inputs.ContainerArgs(
      envFrom = List(
        k8s.core.v1.inputs.EnvFromSourceArgs(
          configMapRef = k8s.core.v1.inputs.ConfigMapEnvSourceArgs(
            name = "app-config"
          )
        )
      )
    )
  )
)
```

## 2. Ingress Controller

**Current**: Services are ClusterIP only, accessed via port-forward

**Enhancement**: Expose reader service externally

```scala
// Install nginx-ingress
val nginxIngress = k8s.helm.v3.Release(
  "nginx-ingress",
  k8s.helm.v3.ReleaseArgs(
    chart = "ingress-nginx",
    repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
      repo = "https://kubernetes.github.io/ingress-nginx"
    )
  )
)

// Create Ingress for reader service
val readerIngress = k8s.networking.v1.Ingress(
  "reader-ingress",
  k8s.networking.v1.IngressArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "reader",
      namespace = namespace
    ),
    spec = k8s.networking.v1.inputs.IngressSpecArgs(
      rules = List(
        k8s.networking.v1.inputs.IngressRuleArgs(
          host = "search.example.com",
          http = k8s.networking.v1.inputs.HTTPIngressRuleValueArgs(
            paths = List(
              k8s.networking.v1.inputs.HTTPIngressPathArgs(
                path = "/",
                pathType = "Prefix",
                backend = k8s.networking.v1.inputs.IngressBackendArgs(
                  service = k8s.networking.v1.inputs.IngressServiceBackendArgs(
                    name = "reader",
                    port = k8s.networking.v1.inputs.ServiceBackendPortArgs(
                      number = 8081
                    )
                  )
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

## 3. Resource Limits and Requests

**Current**: No resource constraints

**Enhancement**: Set appropriate limits for production QoS

```scala
containers = List(
  k8s.core.v1.inputs.ContainerArgs(
    resources = k8s.core.v1.inputs.ResourceRequirementsArgs(
      requests = Map(
        "cpu" -> "100m",
        "memory" -> "256Mi"
      ),
      limits = Map(
        "cpu" -> "1000m",
        "memory" -> "1Gi"
      )
    )
  )
)
```

## 4. IRSA (IAM Roles for Service Accounts)

**Current**: No S3 authentication configured

**Enhancement**: Use IRSA for secure S3 access from EKS

```scala
// Create IAM role for service account
val writerRole = aws.iam.Role(
  "writer-role",
  aws.iam.RoleArgs(
    assumeRolePolicy = /* OIDC trust policy */
  )
)

// Attach S3 policy
val writerPolicy = aws.iam.RolePolicy(
  "writer-s3-policy",
  aws.iam.RolePolicyArgs(
    role = writerRole.id,
    policy = /* S3 access policy */
  )
)

// Create K8s ServiceAccount with annotation
val writerServiceAccount = k8s.core.v1.ServiceAccount(
  "writer-sa",
  k8s.core.v1.ServiceAccountArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "writer",
      namespace = namespace,
      annotations = Map(
        "eks.amazonaws.com/role-arn" -> writerRole.arn
      )
    )
  )
)

// Reference in StatefulSet
spec = k8s.core.v1.inputs.PodSpecArgs(
  serviceAccountName = "writer",
  // ...
)
```

## 5. Image Pull Secrets

**Current**: Using public images or defaults

**Enhancement**: Configure private registry access

```scala
val dockerSecret = k8s.core.v1.Secret(
  "docker-registry",
  k8s.core.v1.SecretArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "docker-registry",
      namespace = namespace
    ),
    `type` = "kubernetes.io/dockerconfigjson",
    data = Map(
      ".dockerconfigjson" -> base64EncodedConfig
    )
  )
)

// Reference in pod spec
spec = k8s.core.v1.inputs.PodSpecArgs(
  imagePullSecrets = List(
    k8s.core.v1.inputs.LocalObjectReferenceArgs(
      name = "docker-registry"
    )
  )
)
```

## 6. Horizontal Pod Autoscaling

**Current**: Fixed replica counts

**Enhancement**: Auto-scale based on CPU/memory or custom metrics

```scala
val readerHPA = k8s.autoscaling.v2.HorizontalPodAutoscaler(
  "reader-hpa",
  k8s.autoscaling.v2.HorizontalPodAutoscalerArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "reader",
      namespace = namespace
    ),
    spec = k8s.autoscaling.v2.inputs.HorizontalPodAutoscalerSpecArgs(
      scaleTargetRef = k8s.autoscaling.v2.inputs.CrossVersionObjectReferenceArgs(
        apiVersion = "apps/v1",
        kind = "Deployment",
        name = "reader"
      ),
      minReplicas = 2,
      maxReplicas = 10,
      metrics = List(
        k8s.autoscaling.v2.inputs.MetricSpecArgs(
          `type` = "Resource",
          resource = k8s.autoscaling.v2.inputs.ResourceMetricSourceArgs(
            name = "cpu",
            target = k8s.autoscaling.v2.inputs.MetricTargetArgs(
              `type` = "Utilization",
              averageUtilization = 70
            )
          )
        )
      )
    )
  )
)
```

## 7. Monitoring and Observability

**Enhancement**: Prometheus + Grafana stack

```scala
// Install kube-prometheus-stack via Helm
val prometheusStack = k8s.helm.v3.Release(
  "kube-prometheus-stack",
  k8s.helm.v3.ReleaseArgs(
    chart = "kube-prometheus-stack",
    repositoryOpts = k8s.helm.v3.inputs.RepositoryOptsArgs(
      repo = "https://prometheus-community.github.io/helm-charts"
    ),
    namespace = "monitoring"
  )
)

// Create ServiceMonitor for custom metrics
val writerServiceMonitor = k8s.apiextensions.CustomResource(
  "writer-servicemonitor",
  k8s.apiextensions.CustomResourceArgs(
    apiVersion = "monitoring.coreos.com/v1",
    kind = "ServiceMonitor",
    metadata = Map(
      "name" -> "writer",
      "namespace" -> namespace
    ),
    spec = Map(
      "selector" -> Map(
        "matchLabels" -> Map("app" -> "writer")
      ),
      "endpoints" -> List(
        Map(
          "port" -> "http",
          "path" -> "/metrics"
        )
      )
    )
  )
)
```

## 8. Multi-Project Split

**Current**: Single Pulumi project

**Enhancement**: Split into `infra/` and `app-deployment/` projects

**Benefits**:
- Deploy infrastructure changes without touching apps
- Deploy app updates without re-provisioning infrastructure
- Different teams can own different projects
- Faster deployment cycles
- Different access controls per project

See `besom-patterns.md` for StackReference usage.

## 9. Health Checks

**Current**: No liveness/readiness probes

**Enhancement**: Add health checks for better resilience

```scala
containers = List(
  k8s.core.v1.inputs.ContainerArgs(
    livenessProbe = k8s.core.v1.inputs.ProbeArgs(
      httpGet = k8s.core.v1.inputs.HTTPGetActionArgs(
        path = "/health",
        port = 8080
      ),
      initialDelaySeconds = 30,
      periodSeconds = 10
    ),
    readinessProbe = k8s.core.v1.inputs.ProbeArgs(
      httpGet = k8s.core.v1.inputs.HTTPGetActionArgs(
        path = "/ready",
        port = 8080
      ),
      initialDelaySeconds = 5,
      periodSeconds = 5
    )
  )
)
```

## 10. Network Policies

**Current**: No network segmentation

**Enhancement**: Restrict pod-to-pod communication

```scala
val networkPolicy = k8s.networking.v1.NetworkPolicy(
  "writer-netpol",
  k8s.networking.v1.NetworkPolicyArgs(
    metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
      name = "writer",
      namespace = namespace
    ),
    spec = k8s.networking.v1.inputs.NetworkPolicySpecArgs(
      podSelector = k8s.meta.v1.inputs.LabelSelectorArgs(
        matchLabels = Map("app" -> "writer")
      ),
      policyTypes = List("Ingress", "Egress"),
      ingress = List(
        k8s.networking.v1.inputs.NetworkPolicyIngressRuleArgs(
          from = List(
            k8s.networking.v1.inputs.NetworkPolicyPeerArgs(
              podSelector = k8s.meta.v1.inputs.LabelSelectorArgs(
                matchLabels = Map("app" -> "ingestion")
              )
            )
          )
        )
      ),
      egress = List(
        k8s.networking.v1.inputs.NetworkPolicyEgressRuleArgs(
          to = List(
            k8s.networking.v1.inputs.NetworkPolicyPeerArgs(
              podSelector = k8s.meta.v1.inputs.LabelSelectorArgs(
                matchLabels = Map("app" -> "kafka")
              )
            )
          )
        )
      )
    )
  )
)
```
