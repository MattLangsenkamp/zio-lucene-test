#!/bin/bash
# pulumi-infra.sh
#
# Wrapper around pulumi up/preview/destroy that:
#   - injects LocalStack AWS env vars when STACK=local
#
# Usage: ./bin/pulumi-infra.sh <command> <stack>
#   command — up | down | preview
#   stack   — local | dev | prod

set -e

CMD="${1:?Usage: $0 <up|down|preview> <stack>}"
STACK="${2:?Usage: $0 <up|down|preview> <stack>}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")/infra"
LOCALSTACK_ENDPOINT="http://localhost:4566"

run_pulumi() {
  if [ "$STACK" = "local" ]; then
    AWS_ENDPOINT_URL="$LOCALSTACK_ENDPOINT" \
    AWS_ACCESS_KEY_ID=test \
    AWS_SECRET_ACCESS_KEY=test \
    AWS_REGION=us-east-1 \
    pulumi "$@"
  else
    pulumi "$@"
  fi
}

cd "$INFRA_DIR"

case "$CMD" in
  up)
    if [ "$STACK" = "local" ]; then
      run_pulumi up --stack "$STACK" --yes
    else
      run_pulumi up --stack "$STACK"
    fi
    ;;
  down)
    if [ "$STACK" = "local" ]; then
      run_pulumi destroy --stack "$STACK" --yes
    else
      run_pulumi destroy --stack "$STACK"
    fi
    ;;
  preview)
    run_pulumi preview --stack "$STACK"
    ;;
  *)
    echo "ERROR: unknown command '$CMD'. Use up, down, or preview." >&2
    exit 1
    ;;
esac
