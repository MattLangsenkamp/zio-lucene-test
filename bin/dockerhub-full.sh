#!/bin/bash
# dockerhub-full.sh <dockerhub_user> <tag>
#
# Builds all application Docker images and then pushes them to Docker Hub.
# Equivalent to running:
#   1. build-apps.sh                       (mill builds all three images)
#   2. dockerhub-push.sh <user> <tag>      (tag + push all three to Docker Hub)
#
# Args:
#   $1  dockerhub_user : Docker Hub username  (required)
#   $2  tag            : image tag, defaults to "latest"

set -e

DOCKERHUB_USER="${1:?Usage: dockerhub-full.sh <dockerhub_user> <tag>}"
TAG="${2:-latest}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/build-apps.sh"
"$SCRIPT_DIR/dockerhub-push.sh" "$DOCKERHUB_USER" "$TAG"
