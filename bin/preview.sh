#!/bin/bash
# preview.sh <env>
#
# Runs "pulumi preview" for the given environment inside the infra/ directory.
# Shows a diff of what would change without applying anything.
#
#   env=local  -> pulumi stack select local && pulumi preview
#   env=dev    -> pulumi stack select dev && pulumi preview
#   env=prod   -> pulumi stack select prod && pulumi preview
#
# Args:
#   $1  env : local | dev | prod  (required)

set -e

ENV="${1:?Usage: preview.sh <env>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INFRA_DIR="$PROJECT_DIR/infra"

case "$ENV" in
  local|dev|prod)
    echo "Previewing ${ENV} environment..."
    cd "$INFRA_DIR" && pulumi stack select "$ENV"
    cd "$INFRA_DIR" && pulumi preview
    ;;
  *)
    echo "❌ Unknown env: ${ENV}. Must be one of: local, dev, prod"
    exit 1
    ;;
esac
