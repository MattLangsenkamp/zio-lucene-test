#!/bin/bash
# health.sh <service> <namespace>
#
# Checks the /health HTTP endpoint for a named service by opening a
# kubectl port-forward tunnel, polling until ready, then curling the endpoint.
# Reports the HTTP status code and response body.
#
# Port assignments:
#   ingestion -> 8083  (pod found via label selector)
#   reader    -> 8081  (pod found via label selector)
#   writer    -> 8082  (pod: writer-0)
#
# Args:
#   $1  service   : ingestion | reader | writer  (required)
#   $2  namespace : Kubernetes namespace, defaults to "zio-lucene"

set -e

SERVICE="${1:?Usage: health.sh <service> <namespace>}"
NAMESPACE="${2:-zio-lucene}"

case "$SERVICE" in
  ingestion)
    PORT=8083
    POD=$(kubectl get pods -n "$NAMESPACE" -l app=ingestion -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    ;;
  reader)
    PORT=8081
    POD=$(kubectl get pods -n "$NAMESPACE" -l app=reader -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    ;;
  writer)
    PORT=8082
    POD="writer-0"
    ;;
  *)
    echo "❌ Unknown service: ${SERVICE}"
    echo "Available services: ingestion, reader, writer"
    exit 1
    ;;
esac

if [ -z "$POD" ]; then
  echo "❌ No pod found for service: ${SERVICE}"
  exit 1
fi

echo "🏥 Checking health for ${POD} on port ${PORT}..."
kubectl port-forward -n "$NAMESPACE" "$POD" "${PORT}:8080" > /dev/null 2>&1 &
PF_PID=$!

echo "Waiting for port-forward to be ready..."
for i in 1 2 3 4 5; do
  sleep 1
  if curl -s --max-time 1 "http://localhost:${PORT}/health" > /dev/null 2>&1; then
    break
  fi
done

echo ""
RESPONSE=$(curl -s -w "\n%{http_code}" --max-time 5 "http://localhost:${PORT}/health" 2>/dev/null)
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ Status: ${HTTP_CODE}"
  echo "Response: ${BODY}"
elif [ "$HTTP_CODE" = "000" ] || [ -z "$HTTP_CODE" ]; then
  echo "❌ Failed to connect to ${POD}:8080"
  echo "   Port-forward may have failed or service not listening on port 8080"
else
  echo "❌ Status: ${HTTP_CODE}"
  echo "Response: ${BODY}"
fi

echo ""
kill $PF_PID 2>/dev/null
