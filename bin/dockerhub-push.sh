#!/bin/bash
# dockerhub-push.sh <dockerhub_user> <tag>
#
# Tags and pushes all three application images to Docker Hub.
# Prompts for Docker Hub login, then for each service (ingestion, reader, writer):
#   1. docker tag <service>:latest <dockerhub_user>/<service>:<tag>
#   2. docker push <dockerhub_user>/<service>:<tag>
#
# Args:
#   $1  dockerhub_user : Docker Hub username  (required)
#   $2  tag            : image tag to push, defaults to "latest"

set -e

DOCKERHUB_USER="${1:?Usage: dockerhub-push.sh <dockerhub_user> <tag>}"
TAG="${2:-latest}"

echo "Logging into Docker Hub..."
docker login
echo ""
echo "Tagging and pushing images to Docker Hub as ${DOCKERHUB_USER}/*:${TAG}..."

for SERVICE in ingestion-server reader-server writer-server; do
  echo "Tagging ${SERVICE}..."
  docker tag "${SERVICE}:latest" "${DOCKERHUB_USER}/${SERVICE}:${TAG}"
  echo "Pushing ${SERVICE}..."
  docker push "${DOCKERHUB_USER}/${SERVICE}:${TAG}"
  echo ""
done

echo "✅ All images pushed to Docker Hub"
echo "   - ${DOCKERHUB_USER}/ingestion-server:${TAG}"
echo "   - ${DOCKERHUB_USER}/reader-server:${TAG}"
echo "   - ${DOCKERHUB_USER}/writer-server:${TAG}"
