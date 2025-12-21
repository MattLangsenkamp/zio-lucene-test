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

- **[project-architecture.md](references/project-architecture.md)**: Service details, deployment patterns, infrastructure components, adding new services
- **[environment-config.md](references/environment-config.md)**: STACK_ENV pattern, environment variables, bootstrap servers, Kafka configuration
- **[besom-patterns.md](references/besom-patterns.md)**: Output handling, resource organization, common K8s patterns, multi-project setup
- **[local-development.md](references/local-development.md)**: Setup, testing, debugging, troubleshooting commands
- **[future-enhancements.md](references/future-enhancements.md)**: ConfigMaps, Ingress, IRSA, HPA, monitoring, and other roadmap items
- **[eks-best-practices.md](references/eks-best-practices.md)**: EKS configuration patterns
- **[msk-configuration.md](references/msk-configuration.md)**: MSK setup and configuration
- **[iam-roles.md](references/iam-roles.md)**: IAM role patterns and IRSA

## Key Patterns

### STACK_ENV Configuration
```scala
val stackName = sys.env.getOrElse("STACK_ENV", "local")
val isLocal = stackName == "local"
```

### Bootstrap Servers
- **Local**: `kafka-0.kafka.zio-lucene.svc.cluster.local:9092`
- **Dev/Prod**: From MSK `bootstrapBrokers` output

### Adding a Service
1. Create `utils/<service>/<Service>.scala`
2. Define `createService()` and `createDeployment()`/`createStatefulSet()`
3. Import in `Main.scala` and wire up dependencies
4. Add to Stack exports if needed
