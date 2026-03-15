#!/bin/bash
# service-sync.sh
#
# Forces an immediate sync of a SINGLE ArgoCD-managed service.
#
# Local: annotates the Application resource directly.
# Cloud: uses argocd CLI — set ARGOCD_SERVER and ensure argocd is logged in.
#
# Usage: ./bin/service-sync.sh <stack> <service> [argocd_server]
#   stack         — local | dev | prod
#   service       — ingestion | reader | writer
#   argocd_server — ArgoCD server address (default: localhost:8080)

set -e

STACK="${1:?Usage: $0 <stack> <service> [argocd_server]}"
SERVICE="${2:?Usage: $0 <stack> <service> [argocd_server]}"

if [ "$STACK" = "local" ]; then
  kubectl annotate application "$SERVICE" -n argocd argocd.argoproj.io/refresh=hard --overwrite
else
  if [ -n "${3:-}" ]; then
    ARGOCD_SERVER="$3"
  else
    ARGOCD_SERVER=$(kubectl get svc argocd-server -n argocd \
      -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
    if [ -z "$ARGOCD_SERVER" ]; then
      echo "ERROR: could not detect ArgoCD server address. Pass it explicitly: $0 $STACK $SERVICE <argocd_server>" >&2
      exit 1
    fi
    echo "Detected ArgoCD server: ${ARGOCD_SERVER}"
  fi
  ARGOCD_OPTS="--server ${ARGOCD_SERVER} --plaintext --insecure"
  argocd app sync "$SERVICE" $ARGOCD_OPTS
fi
