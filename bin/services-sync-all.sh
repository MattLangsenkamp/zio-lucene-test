#!/bin/bash
# services-sync-all.sh
#
# Forces an immediate sync of ALL ArgoCD-managed services.
#
# Local: annotates Application resources directly (no argocd server connection needed).
# Cloud: uses argocd CLI — set ARGOCD_SERVER and ensure argocd is logged in.
#
# Usage: ./bin/services-sync-all.sh <stack> [argocd_server]
#   stack         — local | dev | prod
#   argocd_server — ArgoCD server address (default: localhost:8080)

set -e

STACK="${1:?Usage: $0 <stack> [argocd_server]}"
ARGOCD_SERVER="${2:-localhost:8080}"
ARGOCD_OPTS="--server ${ARGOCD_SERVER} --plaintext --insecure"

if [ "$STACK" = "local" ]; then
  kubectl get applications -n argocd -o name | \
    xargs -I{} kubectl annotate {} -n argocd argocd.argoproj.io/refresh=hard --overwrite
else
  argocd app list -o name $ARGOCD_OPTS | xargs argocd app sync $ARGOCD_OPTS
fi
