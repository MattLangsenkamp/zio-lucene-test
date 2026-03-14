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
ARGOCD_SERVER="${3:-localhost:8080}"
ARGOCD_OPTS="--server ${ARGOCD_SERVER} --plaintext --insecure"

if [ "$STACK" = "local" ]; then
  kubectl annotate application "$SERVICE" -n argocd argocd.argoproj.io/refresh=hard --overwrite
else
  argocd app sync "$SERVICE" $ARGOCD_OPTS
fi
