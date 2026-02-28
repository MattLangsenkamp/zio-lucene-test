#!/bin/bash
# logs.sh <service> <namespace>
#
# Tails live logs for a named service running in Kubernetes.
# For StatefulSet pods (writer, kafka), connects directly to the pod by name.
# For Deployment pods (ingestion, reader), discovers the first running pod via
# label selector and streams its logs.
#
# Args:
#   $1  service   : ingestion | reader | writer | kafka  (required)
#   $2  namespace : Kubernetes namespace, defaults to "zio-lucene"

set -e

SERVICE="${1:?Usage: logs.sh <service> <namespace>}"
NAMESPACE="${2:-zio-lucene}"

case "$SERVICE" in
  writer)
    echo "📋 Tailing logs for writer-0..."
    kubectl logs -f writer-0 -n "$NAMESPACE"
    ;;
  kafka)
    echo "📋 Tailing logs for kafka-0..."
    kubectl logs -f kafka-0 -n "$NAMESPACE"
    ;;
  ingestion|reader)
    POD=$(kubectl get pods -n "$NAMESPACE" -l "app=${SERVICE}" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    if [ -z "$POD" ]; then
      echo "❌ No pod found for service: ${SERVICE}"
      exit 1
    fi
    echo "📋 Tailing logs for ${POD}..."
    kubectl logs -f "$POD" -n "$NAMESPACE"
    ;;
  *)
    echo "❌ Unknown service: ${SERVICE}"
    echo "Available services: ingestion, reader, writer, kafka"
    exit 1
    ;;
esac
