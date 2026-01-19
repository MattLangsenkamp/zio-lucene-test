# Implementation Plan: OpenTelemetry Collector DaemonSet

## Overview

Deploy OpenTelemetry Collector as a DaemonSet to collect metrics, logs, and traces from Kubernetes clusters (both EKS and k3d).

## Goals

1. Collect cluster-level metrics (nodes, pods, deployments)
2. Collect node-level metrics (kubelet stats, container metrics)
3. Receive application telemetry (traces, metrics, logs via OTLP)
4. Export to appropriate backends (configurable per environment)

## Architecture

```
┌─────────────────────────────────────────┐
│ Kubernetes Cluster                      │
│                                         │
│  ┌──────────┐  ┌──────────┐            │
│  │ Node 1   │  │ Node 2   │            │
│  │          │  │          │            │
│  │ ┌──────┐ │  │ ┌──────┐ │            │
│  │ │ OTel │←┼──┼─┤ OTel │ │ ← DaemonSet│
│  │ │Coll. │ │  │ │Coll. │ │   (1 per node)
│  │ └──┬───┘ │  │ └──┬───┘ │            │
│  │    │     │  │    │     │            │
│  │    │ ┌───┼──┼────┘     │            │
│  │    │ │   │  │          │            │
│  │ ┌──▼─▼┐  │  │ ┌──────┐ │            │
│  │ │Pods │  │  │ │Pods  │ │            │
│  │ └─────┘  │  │ └──────┘ │            │
│  └──────────┘  └──────────┘            │
│       │             │                   │
│       └─────┬───────┘                   │
│             │                           │
└─────────────┼───────────────────────────┘
              │
              ▼
     ┌─────────────────┐
     │ Export Backend  │
     │ (Prometheus/    │
     │  Jaeger/OTLP/   │
     │  Console)       │
     └─────────────────┘
```

## Resource Structure

Following the established Resource trait pattern:

### File: `infra/src/utils/OtelCollector.scala`

```scala
package utils

import besom.*
import besom.api.kubernetes as k8s

case class OtelCollectorInput(
  namespace: Output[String],
  k8sProvider: Output[k8s.Provider],
  exporterConfig: OtelExporterConfig
)

case class OtelExporterConfig(
  prometheusEnabled: Boolean = false,
  prometheusEndpoint: Option[String] = None,
  otlpEnabled: Boolean = false,
  otlpEndpoint: Option[String] = None,
  loggingEnabled: Boolean = true,  // Console output for debugging
  loggingVerbosity: String = "detailed"  // basic, normal, detailed
)

case class OtelCollectorOutput(
  serviceAccount: k8s.core.v1.ServiceAccount,
  clusterRole: k8s.rbac.v1.ClusterRole,
  clusterRoleBinding: k8s.rbac.v1.ClusterRoleBinding,
  configMap: k8s.core.v1.ConfigMap,
  daemonSet: k8s.apps.v1.DaemonSet,
  service: k8s.core.v1.Service  // For receiving OTLP from apps
)

object OtelCollector extends Resource[OtelCollectorInput, OtelCollectorOutput, OtelCollectorInput, OtelCollectorOutput]:

  override def make(inputParams: OtelCollectorInput)(using Context): Output[OtelCollectorOutput] =
    // EKS implementation with full exporters
    for {
      sa <- createServiceAccount(inputParams)
      cr <- createClusterRole(inputParams)
      crb <- createClusterRoleBinding(inputParams, sa)
      cm <- createConfigMap(inputParams)
      svc <- createService(inputParams)
      ds <- createDaemonSet(inputParams, sa, cm, svc)
    } yield OtelCollectorOutput(
      serviceAccount = sa,
      clusterRole = cr,
      clusterRoleBinding = crb,
      configMap = cm,
      daemonSet = ds,
      service = svc
    )

  override def makeLocal(inputParams: OtelCollectorInput)(using Context): Output[OtelCollectorOutput] =
    // k3d implementation - same as make but with different default config
    // Override exporterConfig to use console/file for local testing
    val localInput = inputParams.copy(
      exporterConfig = OtelExporterConfig(
        loggingEnabled = true,
        loggingVerbosity = "detailed"
      )
    )
    make(localInput)

  // Helper functions (detailed below)
  private def createServiceAccount(...)
  private def createClusterRole(...)
  private def createClusterRoleBinding(...)
  private def createConfigMap(...)
  private def createService(...)
  private def createDaemonSet(...)
```

## Implementation Steps

### Step 1: Create OtelCollector.scala

**File**: `infra/src/utils/OtelCollector.scala`

**Components to implement**:

#### 1.1 ServiceAccount
```scala
private def createServiceAccount(
  params: OtelCollectorInput
)(using Context): Output[k8s.core.v1.ServiceAccount] =
  params.k8sProvider.flatMap { prov =>
    params.namespace.flatMap { ns =>
      k8s.core.v1.ServiceAccount(
        "otel-collector-sa",
        k8s.core.v1.ServiceAccountArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "otel-collector",
            namespace = ns
          )
        ),
        opts(provider = prov)
      )
    }
  }
```

#### 1.2 ClusterRole
```scala
private def createClusterRole(
  params: OtelCollectorInput
)(using Context): Output[k8s.rbac.v1.ClusterRole] =
  params.k8sProvider.flatMap { prov =>
    k8s.rbac.v1.ClusterRole(
      "otel-collector-clusterrole",
      k8s.rbac.v1.ClusterRoleArgs(
        metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
          name = "otel-collector"
        ),
        rules = List(
          // Read cluster resources
          k8s.rbac.v1.inputs.PolicyRuleArgs(
            apiGroups = List(""),
            resources = List("nodes", "nodes/stats", "nodes/metrics", "services", "endpoints", "pods", "namespaces", "events"),
            verbs = List("get", "list", "watch")
          ),
          // Read apps resources
          k8s.rbac.v1.inputs.PolicyRuleArgs(
            apiGroups = List("apps"),
            resources = List("replicasets", "deployments", "daemonsets", "statefulsets"),
            verbs = List("get", "list", "watch")
          ),
          // Read batch resources
          k8s.rbac.v1.inputs.PolicyRuleArgs(
            apiGroups = List("batch"),
            resources = List("jobs", "cronjobs"),
            verbs = List("get", "list", "watch")
          ),
          // Read autoscaling
          k8s.rbac.v1.inputs.PolicyRuleArgs(
            apiGroups = List("autoscaling"),
            resources = List("horizontalpodautoscalers"),
            verbs = List("get", "list", "watch")
          )
        )
      ),
      opts(provider = prov)
    )
  }
```

#### 1.3 ClusterRoleBinding
```scala
private def createClusterRoleBinding(
  params: OtelCollectorInput,
  serviceAccount: k8s.core.v1.ServiceAccount
)(using Context): Output[k8s.rbac.v1.ClusterRoleBinding] =
  params.k8sProvider.flatMap { prov =>
    params.namespace.flatMap { ns =>
      k8s.rbac.v1.ClusterRoleBinding(
        "otel-collector-clusterrolebinding",
        k8s.rbac.v1.ClusterRoleBindingArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "otel-collector"
          ),
          roleRef = k8s.rbac.v1.inputs.RoleRefArgs(
            apiGroup = "rbac.authorization.k8s.io",
            kind = "ClusterRole",
            name = "otel-collector"
          ),
          subjects = List(
            k8s.rbac.v1.inputs.SubjectArgs(
              kind = "ServiceAccount",
              name = "otel-collector",
              namespace = ns
            )
          )
        ),
        opts(provider = prov, dependsOn = serviceAccount)
      )
    }
  }
```

#### 1.4 ConfigMap (OTel Collector Configuration)
```scala
private def createConfigMap(
  params: OtelCollectorInput
)(using Context): Output[k8s.core.v1.ConfigMap] =
  params.k8sProvider.flatMap { prov =>
    params.namespace.flatMap { ns =>
      val config = generateOtelConfig(params.exporterConfig)

      k8s.core.v1.ConfigMap(
        "otel-collector-config",
        k8s.core.v1.ConfigMapArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "otel-collector-config",
            namespace = ns
          ),
          data = Map(
            "otel-collector-config.yaml" -> config
          )
        ),
        opts(provider = prov)
      )
    }
  }

private def generateOtelConfig(exporterConfig: OtelExporterConfig): String =
  val exporters = buildExportersConfig(exporterConfig)
  val exportersList = buildExportersList(exporterConfig)

  s"""receivers:
  # Receive OTLP traces/metrics/logs from applications
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

  # Collect kubelet metrics from the node
  kubeletstats:
    collection_interval: 20s
    auth_type: "serviceAccount"
    endpoint: "https://$${env:K8S_NODE_NAME}:10250"
    insecure_skip_verify: true
    metric_groups:
      - node
      - pod
      - container

  # Collect cluster-level metrics
  k8s_cluster:
    collection_interval: 30s
    node_conditions_to_report:
      - Ready
      - MemoryPressure
      - DiskPressure
      - PIDPressure
      - NetworkUnavailable
    allocatable_types_to_report:
      - cpu
      - memory
      - storage
      - ephemeral-storage

processors:
  # Batch processor for efficiency
  batch:
    timeout: 10s
    send_batch_size: 1024

  # Memory limiter to prevent OOM
  memory_limiter:
    check_interval: 1s
    limit_mib: 512
    spike_limit_mib: 128

  # Add resource attributes (node, pod, namespace, etc.)
  resourcedetection:
    detectors: [env, system]
    timeout: 5s

  # Resource processor to add K8s metadata
  k8sattributes:
    auth_type: "serviceAccount"
    passthrough: false
    extract:
      metadata:
        - k8s.pod.name
        - k8s.pod.uid
        - k8s.deployment.name
        - k8s.namespace.name
        - k8s.node.name
        - k8s.pod.start_time
    pod_association:
      - sources:
          - from: resource_attribute
            name: k8s.pod.ip
      - sources:
          - from: resource_attribute
            name: k8s.pod.uid
      - sources:
          - from: connection

exporters:
$exporters

service:
  pipelines:
    # Traces pipeline
    traces:
      receivers: [otlp]
      processors: [memory_limiter, k8sattributes, resourcedetection, batch]
      exporters: [$exportersList]

    # Metrics pipeline
    metrics:
      receivers: [otlp, kubeletstats, k8s_cluster]
      processors: [memory_limiter, k8sattributes, resourcedetection, batch]
      exporters: [$exportersList]

    # Logs pipeline
    logs:
      receivers: [otlp]
      processors: [memory_limiter, k8sattributes, resourcedetection, batch]
      exporters: [$exportersList]
"""

private def buildExportersConfig(config: OtelExporterConfig): String =
  val exporters = scala.collection.mutable.ListBuffer[String]()

  if (config.loggingEnabled) {
    exporters += s"""  # Console logging for debugging
  logging:
    verbosity: ${config.loggingVerbosity}
    sampling_initial: 5
    sampling_thereafter: 200"""
  }

  if (config.prometheusEnabled && config.prometheusEndpoint.isDefined) {
    exporters += s"""  # Prometheus remote write
  prometheusremotewrite:
    endpoint: ${config.prometheusEndpoint.get}
    tls:
      insecure: false"""
  }

  if (config.otlpEnabled && config.otlpEndpoint.isDefined) {
    exporters += s"""  # OTLP exporter
  otlp:
    endpoint: ${config.otlpEndpoint.get}
    tls:
      insecure: false"""
  }

  exporters.mkString("\n\n")

private def buildExportersList(config: OtelExporterConfig): String =
  val exporters = scala.collection.mutable.ListBuffer[String]()

  if (config.loggingEnabled) exporters += "logging"
  if (config.prometheusEnabled) exporters += "prometheusremotewrite"
  if (config.otlpEnabled) exporters += "otlp"

  exporters.mkString(", ")
```

#### 1.5 Service (for receiving OTLP)
```scala
private def createService(
  params: OtelCollectorInput
)(using Context): Output[k8s.core.v1.Service] =
  params.k8sProvider.flatMap { prov =>
    params.namespace.flatMap { ns =>
      k8s.core.v1.Service(
        "otel-collector-service",
        k8s.core.v1.ServiceArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "otel-collector",
            namespace = ns,
            labels = Map(
              "app" -> "otel-collector",
              "component" -> "collector"
            )
          ),
          spec = k8s.core.v1.inputs.ServiceSpecArgs(
            selector = Map(
              "app" -> "otel-collector"
            ),
            ports = List(
              // OTLP gRPC
              k8s.core.v1.inputs.ServicePortArgs(
                name = "otlp-grpc",
                port = 4317,
                targetPort = 4317,
                protocol = "TCP"
              ),
              // OTLP HTTP
              k8s.core.v1.inputs.ServicePortArgs(
                name = "otlp-http",
                port = 4318,
                targetPort = 4318,
                protocol = "TCP"
              ),
              // Prometheus metrics (collector's own metrics)
              k8s.core.v1.inputs.ServicePortArgs(
                name = "metrics",
                port = 8888,
                targetPort = 8888,
                protocol = "TCP"
              ),
              // Health check
              k8s.core.v1.inputs.ServicePortArgs(
                name = "health",
                port = 13133,
                targetPort = 13133,
                protocol = "TCP"
              )
            ),
            `type` = "ClusterIP"
          )
        ),
        opts(provider = prov)
      )
    }
  }
```

#### 1.6 DaemonSet
```scala
private def createDaemonSet(
  params: OtelCollectorInput,
  serviceAccount: k8s.core.v1.ServiceAccount,
  configMap: k8s.core.v1.ConfigMap,
  service: k8s.core.v1.Service
)(using Context): Output[k8s.apps.v1.DaemonSet] =
  params.k8sProvider.flatMap { prov =>
    params.namespace.flatMap { ns =>
      k8s.apps.v1.DaemonSet(
        "otel-collector-daemonset",
        k8s.apps.v1.DaemonSetArgs(
          metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
            name = "otel-collector",
            namespace = ns,
            labels = Map(
              "app" -> "otel-collector",
              "component" -> "collector"
            )
          ),
          spec = k8s.apps.v1.inputs.DaemonSetSpecArgs(
            selector = k8s.meta.v1.inputs.LabelSelectorArgs(
              matchLabels = Map(
                "app" -> "otel-collector"
              )
            ),
            template = k8s.core.v1.inputs.PodTemplateSpecArgs(
              metadata = k8s.meta.v1.inputs.ObjectMetaArgs(
                labels = Map(
                  "app" -> "otel-collector"
                )
              ),
              spec = k8s.core.v1.inputs.PodSpecArgs(
                serviceAccountName = "otel-collector",
                hostNetwork = true,  // Access node metrics
                dnsPolicy = "ClusterFirstWithHostNet",
                containers = List(
                  k8s.core.v1.inputs.ContainerArgs(
                    name = "otel-collector",
                    image = "otel/opentelemetry-collector-k8s:0.95.0",  // Use k8s distribution
                    command = List("/otelcol-k8s"),
                    args = List("--config=/conf/otel-collector-config.yaml"),
                    ports = List(
                      k8s.core.v1.inputs.ContainerPortArgs(
                        name = "otlp-grpc",
                        containerPort = 4317,
                        protocol = "TCP"
                      ),
                      k8s.core.v1.inputs.ContainerPortArgs(
                        name = "otlp-http",
                        containerPort = 4318,
                        protocol = "TCP"
                      ),
                      k8s.core.v1.inputs.ContainerPortArgs(
                        name = "metrics",
                        containerPort = 8888,
                        protocol = "TCP"
                      ),
                      k8s.core.v1.inputs.ContainerPortArgs(
                        name = "health",
                        containerPort = 13133,
                        protocol = "TCP"
                      )
                    ),
                    env = List(
                      // Node name for kubelet stats
                      k8s.core.v1.inputs.EnvVarArgs(
                        name = "K8S_NODE_NAME",
                        valueFrom = k8s.core.v1.inputs.EnvVarSourceArgs(
                          fieldRef = k8s.core.v1.inputs.ObjectFieldSelectorArgs(
                            fieldPath = "spec.nodeName"
                          )
                        )
                      ),
                      // Pod IP
                      k8s.core.v1.inputs.EnvVarArgs(
                        name = "K8S_POD_IP",
                        valueFrom = k8s.core.v1.inputs.EnvVarSourceArgs(
                          fieldRef = k8s.core.v1.inputs.ObjectFieldSelectorArgs(
                            fieldPath = "status.podIP"
                          )
                        )
                      )
                    ),
                    resources = k8s.core.v1.inputs.ResourceRequirementsArgs(
                      limits = Map(
                        "memory" -> "512Mi",
                        "cpu" -> "500m"
                      ),
                      requests = Map(
                        "memory" -> "256Mi",
                        "cpu" -> "200m"
                      )
                    ),
                    volumeMounts = List(
                      k8s.core.v1.inputs.VolumeMountArgs(
                        name = "otel-collector-config",
                        mountPath = "/conf"
                      )
                    ),
                    livenessProbe = k8s.core.v1.inputs.ProbeArgs(
                      httpGet = k8s.core.v1.inputs.HTTPGetActionArgs(
                        path = "/",
                        port = 13133
                      ),
                      initialDelaySeconds = 30,
                      periodSeconds = 10
                    ),
                    readinessProbe = k8s.core.v1.inputs.ProbeArgs(
                      httpGet = k8s.core.v1.inputs.HTTPGetActionArgs(
                        path = "/",
                        port = 13133
                      ),
                      initialDelaySeconds = 10,
                      periodSeconds = 5
                    )
                  )
                ),
                volumes = List(
                  k8s.core.v1.inputs.VolumeArgs(
                    name = "otel-collector-config",
                    configMap = k8s.core.v1.inputs.ConfigMapVolumeSourceArgs(
                      name = "otel-collector-config"
                    )
                  )
                )
              )
            )
          )
        ),
        opts(provider = prov, dependsOn = List(serviceAccount, configMap, service))
      )
    }
  }
```

### Step 2: Update Main.scala

**File**: `infra/src/Main.scala`

#### 2.1 Import OtelCollector
```scala
import utils.OtelCollector
```

#### 2.2 Add to EKS/dev/prod stack (after namespace creation)
```scala
// 6b. Deploy OpenTelemetry Collector DaemonSet
val otelCollector = OtelCollector.make(
  OtelCollectorInput(
    namespace = namespaceNameOutput,
    k8sProvider = k8sProvider,
    exporterConfig = OtelExporterConfig(
      loggingEnabled = true,
      loggingVerbosity = "normal",
      // TODO: Add Prometheus or OTLP endpoint for production
      prometheusEnabled = false,
      otlpEnabled = false
    )
  )
)
```

#### 2.3 Add to local stack
```scala
// 5. Deploy OpenTelemetry Collector DaemonSet
val otelCollector = OtelCollector.makeLocal(
  OtelCollectorInput(
    namespace = namespaceNameOut,
    k8sProvider = k8sProvider,
    exporterConfig = OtelExporterConfig(
      loggingEnabled = true,
      loggingVerbosity = "detailed"
    )
  )
)
```

#### 2.4 Add to Stack exports
```scala
// EKS stack
Stack(
  // ... existing resources
  otelCollector.map(_.serviceAccount),
  otelCollector.map(_.clusterRole),
  otelCollector.map(_.clusterRoleBinding),
  otelCollector.map(_.configMap),
  otelCollector.map(_.daemonSet),
  otelCollector.map(_.service),
  // ... rest of resources
)

// Local stack
Stack(
  // ... existing resources
  otelCollector.map(_.serviceAccount),
  otelCollector.map(_.clusterRole),
  otelCollector.map(_.clusterRoleBinding),
  otelCollector.map(_.configMap),
  otelCollector.map(_.daemonSet),
  otelCollector.map(_.service),
  // ... rest of resources
)
```

### Step 3: Update Application Services to Send Telemetry

Applications should send traces/metrics/logs to the OTel Collector service:

**Endpoint**: `http://otel-collector.zio-lucene.svc.cluster.local:4317` (gRPC)
**Or**: `http://otel-collector.zio-lucene.svc.cluster.local:4318` (HTTP)

This would be configured via environment variables in your app deployments.

### Step 4: Remove Datadog Resources

**Files to update**:
- Remove `datadogApiKey` from SecretStore/SecretSync
- Remove ExternalSecret for datadog-api-key
- Update Pulumi config to remove `datadogApiKey`

**Updated SecretSync** (if Datadog was the only secret):
- Can remove entirely, or
- Keep structure for future secrets, or
- Convert to generic secret sync utility

## Configuration Options

### Local (k3d)
```scala
OtelExporterConfig(
  loggingEnabled = true,
  loggingVerbosity = "detailed"
)
```
**Result**: Logs to console for easy debugging

### Dev (with Prometheus)
```scala
OtelExporterConfig(
  loggingEnabled = true,
  loggingVerbosity = "normal",
  prometheusEnabled = true,
  prometheusEndpoint = Some("https://prometheus.example.com/api/v1/write")
)
```

### Prod (with OTLP backend like Grafana Cloud)
```scala
OtelExporterConfig(
  loggingEnabled = false,  // Reduce noise in prod
  otlpEnabled = true,
  otlpEndpoint = Some("https://otlp.grafana.net:443")
)
```

## Testing Plan

### Step 1: Compile
```bash
cd infra/src
scala-cli compile .
```

### Step 2: Deploy to local
```bash
cd infra
pulumi up --stack local
```

### Step 3: Verify DaemonSet
```bash
kubectl get daemonset otel-collector -n zio-lucene
kubectl get pods -n zio-lucene -l app=otel-collector
```

**Expected**: 1 pod per node in k3d (probably 1 total for local)

### Step 4: Check logs
```bash
kubectl logs -n zio-lucene -l app=otel-collector --tail=100
```

**Look for**:
- "Everything is ready. Begin running and processing data."
- Metrics being collected from kubeletstats
- No errors about RBAC permissions

### Step 5: Test OTLP endpoint
```bash
kubectl port-forward -n zio-lucene svc/otel-collector 4317:4317
```

Send test trace:
```bash
# Using grpcurl or similar tool
grpcurl -plaintext -d @ localhost:4317 opentelemetry.proto.collector.trace.v1.TraceService/Export <<EOF
{
  "resource_spans": [{
    "scope_spans": [{
      "spans": [{
        "name": "test-span",
        "kind": 1,
        "start_time_unix_nano": "1234567890000000000",
        "end_time_unix_nano": "1234567891000000000"
      }]
    }]
  }]
}
EOF
```

**Expected**: Trace appears in collector logs

### Step 6: Deploy to dev
```bash
pulumi up --stack dev
```

Repeat verification steps.

## Success Criteria

- ✅ OtelCollector.scala compiles
- ✅ DaemonSet deploys successfully to local
- ✅ DaemonSet deploys successfully to dev
- ✅ 1 collector pod per node
- ✅ Collector pods have RBAC access to K8s API
- ✅ Kubelet metrics are being collected
- ✅ Cluster metrics are being collected
- ✅ OTLP endpoint is reachable
- ✅ Exporters are working (logs show data being exported)
- ✅ No CrashLoopBackOff or errors in logs

## Future Enhancements

1. **Add Filelog Receiver** for container logs
2. **Add Prometheus Receiver** to scrape metrics from apps
3. **Configure IRSA** (if sending to AWS backends like X-Ray)
4. **Add Persistent Volume** for buffering (optional)
5. **Configure Sampling** for traces in high-volume environments
6. **Add ServiceMonitor** for Prometheus operator to scrape collector's own metrics
7. **Configure TLS** for OTLP endpoint
8. **Add NetworkPolicy** to restrict access

## Migration from Datadog

### Remove Datadog Components
1. Delete `datadogApiKey` from Pulumi config:
   ```bash
   pulumi config rm zio-lucene-infra:datadogApiKey --stack local
   pulumi config rm zio-lucene-infra:datadogApiKey --stack dev
   pulumi config rm zio-lucene-infra:datadogApiKey --stack prod
   ```

2. Update SecretStore/SecretSync:
   - Option A: Remove entirely if Datadog was the only secret
   - Option B: Keep for other secrets, remove Datadog-specific ExternalSecret

3. Remove AWS Secrets Manager secret:
   ```bash
   aws secretsmanager delete-secret \
     --secret-id zio-lucene/datadog-api-key \
     --force-delete-without-recovery
   ```

4. Clean up Stack in Main.scala:
   - Remove `secretStore` references if no longer needed
   - Remove `secretSync` references

## Key Files Summary

**New files**:
- `infra/src/utils/OtelCollector.scala` - Main implementation

**Modified files**:
- `infra/src/Main.scala` - Wire up OtelCollector
- Remove/update Datadog secret references

**Documentation to add**:
- Add to `infrastructure-devops` skill
- Document OTLP endpoints for applications
- Document configuration options

## Next Steps After Implementation

1. **Instrument Applications**: Add OpenTelemetry SDKs to ingestion, reader, writer services
2. **Configure Exporters**: Set up Prometheus, Grafana, or other backends
3. **Set up Dashboards**: Create Grafana dashboards for cluster and app metrics
4. **Configure Alerts**: Set up alerting based on metrics
5. **Document**: Add comprehensive docs to skill references

## Questions to Resolve

1. **Where do you want to export telemetry?**
   - Prometheus + Grafana?
   - Grafana Cloud (OTLP)?
   - Self-hosted Jaeger for traces?
   - AWS X-Ray?
   - Just console for now?

2. **Do you need log collection?**
   - Should we add filelog receiver for container logs?
   - Or rely on CloudWatch/kubectl logs?

3. **Application instrumentation**:
   - Are the ZIO apps already using OpenTelemetry?
   - Do they need SDK integration?

4. **Keep or remove Datadog secret?**
   - Delete `zio-lucene/datadog-api-key` from AWS Secrets Manager?
   - Remove SecretStore/SecretSync entirely?
