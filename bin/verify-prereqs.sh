#!/bin/bash
# verify-prereqs.sh
# Checks that all required CLI tools are installed before any deploy/sync operation.

set -e

REQUIRED_TOOLS=(pulumi helm argocd aws kubectl scala)
MISSING=()

for tool in "${REQUIRED_TOOLS[@]}"; do
  if ! command -v "$tool" &>/dev/null; then
    MISSING+=("$tool")
  fi
done

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "❌ Missing required tools: ${MISSING[*]}"
  echo ""
  echo "Install instructions:"
  for t in "${MISSING[@]}"; do
    case "$t" in
      pulumi)  echo "  pulumi: https://www.pulumi.com/docs/install/" ;;
      helm)    echo "  helm:   https://helm.sh/docs/intro/install/" ;;
      argocd)  echo "  argocd: https://argo-cd.readthedocs.io/en/stable/cli_installation/" ;;
      aws)     echo "  aws:    https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html" ;;
      kubectl) echo "  kubectl: https://kubernetes.io/docs/tasks/tools/" ;;
      scala)   echo "  scala:  https://www.scala-lang.org/download/" ;;
    esac
  done
  exit 1
fi

echo "✅ All prerequisites satisfied: ${REQUIRED_TOOLS[*]}"
