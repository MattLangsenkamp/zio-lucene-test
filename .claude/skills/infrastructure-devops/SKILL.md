---
name: infrastructure-devops
description: Infrastructure provisioning and deployment automation using Besom (Pulumi for Scala), Kubernetes (EKS), AWS services (MSK, S3, IAM), Docker containers, and GitHub Actions CI/CD. Use when working on: (1) AWS infrastructure provisioning with Besom, (2) Kubernetes resource definitions and deployments, (3) EKS cluster configuration, (4) MSK (Kafka) setup, (5) S3 bucket management, (6) IAM roles and IRSA configuration, (7) Docker containerization, (8) CI/CD pipeline configuration, or (9) deployment automation.
---

# Infrastructure/DevOps Agent

## Overview

This project uses **Besom** (Pulumi for Scala) to manage infrastructure across three environments:
- **local**: k3d cluster + LocalStack
- **dev**: AWS EKS + MSK
- **prod**: AWS EKS + MSK

## Quick Reference

### Application Services
- **Ingestion** (port 8080): Deployment - Consumes Wikipedia EventStreams, produces to Kafka
- **Reader** (port 8081): Deployment - Serves search queries from S3 Lucene indices
- **Writer** (port 8082): StatefulSet - Consumes Kafka, builds Lucene indices, uploads to S3

### Project Structure
```
infra/src/
├── Main.scala              # Main Pulumi program
└── utils/
    ├── K8s.scala          # Generic K8s utilities
    ├── S3.scala           # S3 bucket creation
    ├── SecretStore.scala  # AWS Secrets Manager secret creation
    ├── SecretSync.scala   # External Secrets CRD creation (ClusterSecretStore, ExternalSecret)
    ├── ExternalSecretsOperator.scala  # Helm chart installation
    ├── ExternalSecretsIrsa.scala      # IRSA role for operator
    ├── MSK.scala          # MSK cluster + VPC
    ├── ingestion/Ingestion.scala
    ├── reader/Reader.scala
    └── writer/Writer.scala
```

### Common Commands

**Local development:**
```bash
make start-local-env    # Start k3d + LocalStack
make local-dev          # Deploy to local
make local-destroy      # Destroy stack
make stop-local-env     # Stop k3d + LocalStack
```

**Kubernetes debugging:**
```bash
kubectl get all -n zio-lucene
kubectl logs -f <pod> -n zio-lucene
kubectl describe pod <pod> -n zio-lucene
kubectl port-forward <pod/svc> <port>:<port> -n zio-lucene
```

**Kafka testing:**
```bash
kubectl exec -it kafka-0 -n zio-lucene -- kafka-topics.sh --list --bootstrap-server localhost:9092
```

## Reference Documentation

For detailed information, see the reference files:

- **[besom-patterns.md](references/besom-patterns.md)**: Output handling, for-comprehensions, Resource trait pattern, function signatures, Stack construction
- **[design-decisions.md](references/design-decisions.md)**: Fail-fast error handling, Output[T] patterns, port mapping, StatefulSet usage, k3d image management, HTTPS/DNS configuration
- **[project-architecture.md](references/project-architecture.md)**: Service details, deployment patterns, infrastructure components, adding new services
- **[environment-config.md](references/environment-config.md)**: Stack environment configuration, Pulumi config patterns, bootstrap servers, Kafka configuration
- **[local-development.md](references/local-development.md)**: Setup, testing, debugging, troubleshooting commands
- **[eks-best-practices.md](references/eks-best-practices.md)**: EKS configuration patterns
- **[msk-configuration.md](references/msk-configuration.md)**: MSK setup and configuration
- **[iam-roles.md](references/iam-roles.md)**: IAM role patterns and IRSA
- **[external-secrets.md](references/external-secrets.md)**: External Secrets Operator architecture, IRSA authentication, SecretSync implementation, CustomResource patterns, troubleshooting
- **[future-enhancements.md](references/future-enhancements.md)**: ConfigMaps, Ingress, IRSA, HPA, monitoring, and other roadmap items

## Key Patterns

### Stack Environment Configuration
```scala
val stackName = sys.env.get("PULUMI_STACK").getOrElse {
  throw new RuntimeException("PULUMI_STACK environment variable is not set. This should be set automatically by Pulumi CLI.")
}
val isLocal = stackName == "local"
```

The `PULUMI_STACK` environment variable is automatically set by Pulumi CLI to match the current stack name (local, dev, prod). The code fails fast if this variable is not set.

### Bootstrap Servers
- **Local**: `kafka-0.kafka.zio-lucene.svc.cluster.local:9092`
- **Dev/Prod**: From MSK `bootstrapBrokers` output

### External Secrets Flow
**How secrets sync from AWS to Kubernetes:**
1. **SecretStore.scala** creates secret in AWS Secrets Manager (e.g., `zio-lucene/datadog-api-key`)
2. **ExternalSecretsOperator.scala** installs controller via Helm
3. **ExternalSecretsIrsa.scala** creates IAM role (IRSA) for authentication
4. **SecretSync.scala** creates CRDs that tell operator what to sync:
   - `ClusterSecretStore` (EKS) / `SecretStore` (local) - Connection config
   - `ExternalSecret` - Sync instruction (which secret, where to put it)
5. **External Secrets Operator** (running pod) reads CRDs, fetches from AWS, creates K8s Secret

**Result**: Kubernetes Secret `datadog-api-key` in `zio-lucene` namespace, auto-synced every 1 hour

See [external-secrets.md](references/external-secrets.md) for complete architecture and troubleshooting.

### Creating Kubernetes CustomResources in Besom

**Key Pattern**: CustomResource requires `ComponentResourceOptions` (not regular `opts`):

```scala
k8s.apiextensions.CustomResource[SpecType](
  "resource-name",
  k8s.apiextensions.CustomResourceArgs[SpecType](
    apiVersion = "external-secrets.io/v1beta1",
    kind = "ClusterSecretStore",
    metadata = ObjectMetaArgs(...),
    spec = mySpec  // Already unwrapped from Output
  ),
  ComponentResourceOptions(
    providers = List(prov),  // Note: plural 'providers', not singular!
    dependsOn = dependencies
  )
)
```

**Critical Difference**:
- Regular resources: `opts(provider = prov)` (singular)
- CustomResource: `ComponentResourceOptions(providers = List(prov))` (plural)

See [external-secrets.md](references/external-secrets.md) for complete example.

### Adding a Service
1. Create `utils/<service>/<Service>.scala`
2. Define `createService()` and `createDeployment()`/`createStatefulSet()`
3. Import in `Main.scala` and wire up dependencies
4. Add to Stack exports if needed
