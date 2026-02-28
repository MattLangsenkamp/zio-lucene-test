#!/bin/bash
# build-apps.sh
#
# Builds Docker images for all three application services using Mill.
# Produces locally tagged images:
#   - reader-server:latest
#   - ingestion-server:latest
#   - writer-server:latest
# Must be run from the project root (where ./mill lives).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Building all application Docker images..."
"$PROJECT_DIR/mill" reader.server.docker.build
"$PROJECT_DIR/mill" ingestion.server.docker.build
"$PROJECT_DIR/mill" writer.server.docker.build
echo "✅ All images built"
