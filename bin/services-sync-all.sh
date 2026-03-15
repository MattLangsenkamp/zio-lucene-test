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

if [ "$STACK" = "local" ]; then
  kubectl get applications -n argocd -o name | \
    xargs -I{} kubectl annotate {} -n argocd argocd.argoproj.io/refresh=hard --overwrite
else
  if [ -n "${2:-}" ]; then
    ARGOCD_SERVER="$2"
  else
    ARGOCD_SERVER=$(kubectl get svc argocd-server -n argocd \
      -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
    if [ -z "$ARGOCD_SERVER" ]; then
      echo "ERROR: could not detect ArgoCD server address. Pass it explicitly: $0 $STACK <argocd_server>" >&2
      exit 1
    fi
    echo "Detected ArgoCD server: ${ARGOCD_SERVER}"
  fi
  ARGOCD_OPTS="--server ${ARGOCD_SERVER} --insecure"
  echo "Running: argocd app list -o name $ARGOCD_OPTS"
  APPS=$(argocd app list -o name $ARGOCD_OPTS)
  echo "Apps: $APPS"
  echo "Running: argocd app sync $ARGOCD_OPTS $APPS"
  echo "$APPS" | xargs argocd app sync $ARGOCD_OPTS
fi
