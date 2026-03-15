#!/bin/bash
# ssm-list.sh
#
# Lists all SSM parameters under /zio-lucene/<stack>.
# Routes to LocalStack endpoint when STACK=local.
#
# Usage: ./bin/ssm-list.sh <stack>
#   stack — local | dev | prod

set -e

STACK="${1:?Usage: $0 <stack>}"
PATH_PREFIX="/zio-lucene/${STACK}"

if [ "$STACK" = "local" ]; then
  aws ssm get-parameters-by-path \
    --path "$PATH_PREFIX" \
    --recursive \
    --endpoint-url http://localhost:4566
else
  aws ssm get-parameters-by-path \
    --path "$PATH_PREFIX" \
    --recursive \
    --region "${AWS_REGION:-us-east-1}"
fi
