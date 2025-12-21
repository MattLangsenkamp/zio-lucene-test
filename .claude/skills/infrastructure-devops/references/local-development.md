# Local Development Workflow

## Initial Setup

```bash
make start-local-env    # Creates k3d cluster + LocalStack
make local-dev          # Deploys infrastructure to local
```

The `start-local-env` target will:
1. Start LocalStack container (if not running)
2. Create k3d cluster named `zio-lucene` (if not exists)
3. Initialize Pulumi stack `local`

## Verification Commands

### Check all resources
```bash
kubectl get all -n zio-lucene
kubectl get pvc -n zio-lucene
```

### Check pod logs
```bash
kubectl logs kafka-0 -n zio-lucene
kubectl logs writer-0 -n zio-lucene
kubectl logs <pod-name> -n zio-lucene
kubectl logs -f <pod-name> -n zio-lucene  # Follow logs
```

### Check pod status
```bash
kubectl get pods -n zio-lucene
kubectl describe pod <pod-name> -n zio-lucene
```

### Check storage
```bash
kubectl get pvc -n zio-lucene
kubectl describe pvc <pvc-name> -n zio-lucene
```

## Testing Services

### Test Kafka
```bash
# List topics
kubectl exec -it kafka-0 -n zio-lucene -- kafka-topics.sh --list --bootstrap-server localhost:9092

# Create a test topic
kubectl exec -it kafka-0 -n zio-lucene -- kafka-topics.sh --create --topic test --bootstrap-server localhost:9092

# Produce messages
kubectl exec -it kafka-0 -n zio-lucene -- kafka-console-producer.sh --topic test --bootstrap-server localhost:9092

# Consume messages
kubectl exec -it kafka-0 -n zio-lucene -- kafka-console-consumer.sh --topic test --from-beginning --bootstrap-server localhost:9092
```

### Test S3 (LocalStack)
```bash
# List buckets
aws --endpoint-url=http://localhost:4566 s3 ls

# List objects in bucket
aws --endpoint-url=http://localhost:4566 s3 ls s3://segments-<hash>/

# Download object
aws --endpoint-url=http://localhost:4566 s3 cp s3://segments-<hash>/path/to/object ./
```

### Port Forwarding for Debugging
```bash
# Forward ingestion service
kubectl port-forward svc/ingestion 8080:8080 -n zio-lucene

# Forward reader service
kubectl port-forward svc/reader 8081:8081 -n zio-lucene

# Forward writer pod (StatefulSet requires pod forwarding)
kubectl port-forward writer-0 8082:8082 -n zio-lucene

# Forward Kafka
kubectl port-forward kafka-0 9092:9092 -n zio-lucene
```

## Troubleshooting

### LocalStack Issues

```bash
# Check if LocalStack is running
docker ps | grep localstack

# View LocalStack logs
docker logs localstack

# Restart LocalStack
docker restart localstack

# Stop and remove LocalStack
docker stop localstack
docker rm localstack
```

### k3d Cluster Issues

```bash
# List clusters
k3d cluster list

# Get cluster info
kubectl cluster-info

# Restart cluster
k3d cluster stop zio-lucene
k3d cluster start zio-lucene

# Delete and recreate cluster
k3d cluster delete zio-lucene
make start-local-env
```

### Pulumi Issues

```bash
# View current stack
cd infra && pulumi stack

# Preview changes
cd infra && STACK_ENV=local pulumi preview

# View stack outputs
cd infra && pulumi stack output

# Refresh stack state
cd infra && pulumi refresh

# View stack history
cd infra && pulumi history
```

### Common Pod Issues

**Pod stuck in Pending:**
```bash
kubectl describe pod <pod-name> -n zio-lucene
# Check Events section for scheduling issues (usually PVC binding)
```

**Pod CrashLoopBackOff:**
```bash
kubectl logs <pod-name> -n zio-lucene --previous
# View logs from previous crashed container
```

**Image pull errors:**
```bash
kubectl describe pod <pod-name> -n zio-lucene
# Check if image exists and is accessible
```

## Cleanup

```bash
# Destroy Pulumi stack (removes K8s resources)
make local-destroy

# Stop k3d cluster and LocalStack
make stop-local-env

# Both destroy + stop
make local-clean
```

## Quick Reference

| Command | Description |
|---------|-------------|
| `make start-local-env` | Start k3d + LocalStack |
| `make local-dev` | Deploy to local environment |
| `make local-preview` | Preview changes |
| `make local-destroy` | Destroy Pulumi stack |
| `make stop-local-env` | Stop k3d + LocalStack |
| `make local-clean` | Destroy stack + stop environment |
| `kubectl get all -n zio-lucene` | List all resources |
| `kubectl logs -f <pod> -n zio-lucene` | Follow pod logs |
| `kubectl describe pod <pod> -n zio-lucene` | Describe pod details |
