# Project Architecture

This project uses Besom (Pulumi for Scala) to manage infrastructure across three environments:
- **local**: k3d cluster + LocalStack for local development
- **dev**: AWS EKS + MSK for development environment
- **prod**: AWS EKS + MSK for production environment

## Application Services

Three main services with specific deployment patterns:

### 1. Ingestion Service (port 8080)
- **Deployment type**: Standard Kubernetes Deployment
- **Purpose**: Consumes Wikipedia EventStreams (SSE), produces to Kafka
- **Dependencies**: Kafka bootstrap servers
- **Scaling**: Stateless, can scale horizontally
- **Location**: `infra/src/utils/ingestion/Ingestion.scala`

### 2. Reader Service (port 8081)
- **Deployment type**: Standard Kubernetes Deployment
- **Purpose**: Serves search queries against Lucene indices from S3
- **Dependencies**: S3 bucket name
- **Scaling**: Stateless, can scale horizontally
- **Location**: `infra/src/utils/reader/Reader.scala`

### 3. Writer Service (port 8082)
- **Deployment type**: StatefulSet (headless service)
- **Purpose**: Consumes from Kafka, builds Lucene indices, uploads segments to S3
- **Dependencies**: Kafka bootstrap servers, S3 bucket name
- **Uses StatefulSet because it needs**:
  - Stable network identity for coordination
  - Persistent storage for local Lucene index building
  - Volume mount at `/data` with 1Gi storage (default)
- **Location**: `infra/src/utils/writer/Writer.scala`

## Infrastructure Components

### Kubernetes Resources
- **Namespace**: `zio-lucene` (single namespace for all services)
- **Kafka StatefulSet** (local only): `kafka-0` in KRaft mode, Apache Kafka 4.1.0
- **Services**: ClusterIP for ingestion/reader, headless for writer/kafka

### AWS Resources (dev/prod only)
- **S3 bucket**: `segments` for storing Lucene index segments
- **MSK cluster**: Managed Kafka service
- **EKS cluster**: Managed Kubernetes
- **VPC, subnets, security groups, IAM roles**

### Local Resources
- **LocalStack**: S3, Kafka, IAM, Secrets Manager on port 4566
- **k3d cluster**: `zio-lucene` cluster with API on 6550, load balancer on 8080/8443

## Project Structure

```
infra/src/
├── Main.scala              # Main Pulumi program
└── utils/
    ├── K8s.scala          # Generic K8s utilities (namespace, headless service, kafka statefulset)
    ├── S3.scala           # S3 bucket creation
    ├── MSK.scala          # MSK cluster + VPC infrastructure
    ├── ingestion/
    │   └── Ingestion.scala  # Ingestion service + deployment
    ├── reader/
    │   └── Reader.scala     # Reader service + deployment
    └── writer/
        └── Writer.scala     # Writer headless service + statefulset
```

## Adding a New Service

1. Create `utils/<service>/<Service>.scala`
2. Define `createService` method (returns `Output[k8s.core.v1.Service]`)
3. Define `createDeployment` or `createStatefulSet` (returns appropriate type)
4. Import in `Main.scala` and wire up with namespace, bucket, and kafka servers
5. Add to Stack exports if needed

## Production Considerations

Current defaults suitable for local/dev, need adjustments for prod:

- **Replica counts**: Currently 1 for all services (scale reader/ingestion in prod)
- **Storage sizes**: 1Gi default (increase for prod writer and kafka)
- **Image pull policy**: Not specified, defaults to IfNotPresent (use Always for `latest` tags)
- **Resource limits**: Not set (required for production QoS)
- **Ingress**: Currently no external access (add ingress for reader)
- **Health checks**: Liveness/readiness probes not configured
- **Security**: No network policies, pod security policies, or RBAC configured yet
