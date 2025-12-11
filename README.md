# Live Search Engine Project

## Project Overview

Build a live search engine using Scala 3, ZIO, and Apache Lucene that indexes Wikipedia's real-time edit stream. The system will be deployed to AWS EKS with Kafka (MSK) as the message backbone, using a single-writer, multiple-reader architecture.

## Technology Stack

- **Language**: Scala 3
- **Build System**: Bazel (MODULE.bazel, modern approach)
- **Application Framework**: ZIO ecosystem (zio-streams, zio-kafka, zio-http, Tapir)
- **Search Engine**: Apache Lucene
- **Message Queue**: Apache Kafka (AWS MSK)
- **Infrastructure as Code**: Besom (Pulumi for Scala)
- **Container Runtime**: Docker
- **Container Registry**: Docker Hub
- **Orchestration**: Kubernetes (AWS EKS)
- **Observability**: OpenTelemetry + Datadog
- **CI/CD**: GitHub Actions
- **Data Source**: Wikipedia EventStreams API (SSE)

## Architecture

### Components

1. **Ingestion Service** (Deployment)
    - HTTP API endpoint for manual document submission
    - Wikipedia SSE stream consumer
    - Emits documents to Kafka topic `document-ingestion`

2. **Writer Service** (StatefulSet, replicas=1)
    - Consumes from Kafka topic `document-ingestion`
    - Maintains canonical Lucene index on persistent volume
    - Uploads new segments to S3 after each commit
    - Publishes notifications to Kafka topic `index-updates` with S3 paths

3. **Reader Service** (Deployment, horizontally scalable)
    - Consumes from Kafka topic `index-updates`
    - Downloads new segments from S3 incrementally
    - Refreshes Lucene searchers for zero-downtime updates
    - Exposes search API and stats endpoints
    - Uses ephemeral storage (64Gi) for local index cache

### Data Flow

```
Wikipedia SSE ──┐
                ├──> Ingestion Service ──> Kafka (document-ingestion) ──> Writer Service ──> Lucene Index (PV)
HTTP API ───────┘                                                              │
                                                                               ├──> S3 (segments)
                                                                               │
                                                                               └──> Kafka (index-updates) ──> Reader Services ──> Search API
```

### Observability

- **Application Telemetry**: OpenTelemetry sidecars → Datadog backend
    - Logs, traces, and metrics from all services
- **Infrastructure Logs**: Datadog Agent DaemonSet → Datadog backend
    - K8s events, pod logs, node metrics

### API Endpoints

**Search Endpoint**: `POST /search`
```json
{
  "query": "search terms",
  "limit": 10,
  "offset": 0
}
```

**Stats Endpoint**: `GET /stats`
```json
{
  "total_documents": 150000,
  "by_source": {
    "wikipedia": 150000
  },
  "index_version": "generation_42",
  "num_segments": 12,
  "index_size_bytes": 524288000,
  "last_updated": "2025-12-09T10:30:00Z"
}
```