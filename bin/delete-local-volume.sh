#!/bin/bash
# delete-local-volume.sh <localstack_volume>
#
# Permanently deletes the LocalStack Docker volume, destroying all persisted
# local AWS service data (S3 buckets, SQS queues, secrets, etc.).
# Prompts for confirmation before proceeding.

LOCALSTACK_VOLUME="${1:-zio-lucene-localstack-data}"

echo "⚠️  WARNING: This will delete all persisted LocalStack data!"
read -p "Are you sure? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  echo "Deleting LocalStack volume..."
  docker volume rm "${LOCALSTACK_VOLUME}" 2>/dev/null || echo "Volume does not exist or is in use"
  echo "✅ Volume deleted"
else
  echo "Cancelled"
fi
