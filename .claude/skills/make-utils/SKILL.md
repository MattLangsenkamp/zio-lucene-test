---
name: make-utils
description: >
  Use when working on Makefiles and bin/ scripts in this project, when asked
  to add/modify a make target, or when asked to run any project operation
  (deploy, kubeconfig, rollout, logs, health, build, etc.). Maps natural
  language requests to the correct make target and runs it.
allowed-tools: Bash, Read, Write, Edit, Glob, Grep
argument-hint: "[operation description]"
---

## Running Make Commands

When the user asks you to perform an operation, map their request to the
correct `make` target and run it from the project root. Always run make
from `/home/mattlang/Documents/projects/zio-lucene-test`.

### Natural language → make target mapping

| User says | Make target | Notes |
|-----------|-------------|-------|
| "update kubeconfig for dev" | `make kubeconfig-dev` | |
| "update kubeconfig for prod" | `make kubeconfig-prod` | |
| "update kubeconfig for local" | `make kubeconfig-local` | |
| "deploy to dev" | `make dev` | |
| "deploy to prod" | `make prod` | |
| "deploy local" / "bring up local" | `make local-dev` | full bootstrap |
| "deploy local stack" (already running) | `make local-dev-up` | skip env setup |
| "destroy dev" / "tear down dev" | `make dev-down` | |
| "destroy prod" | `make prod-down` | |
| "destroy local" / "tear down local" | `make local-destroy` | |
| "clean local" / "full local teardown" | `make local-clean` | |
| "preview dev" | `make dev-preview` | |
| "preview local" | `make local-preview` | |
| "build images" / "build apps" | `make build-apps` | |
| "import images" | `make import-images` | |
| "push to dockerhub [TAG=x]" | `make dockerhub-push TAG=x` | |
| "build and push [TAG=x]" | `make dockerhub-full TAG=x` | |
| "rollout local" | `make rollout-local` | |
| "rollout dev SERVICE=reader" | `make rollout-dev SERVICE=reader` | SERVICE required |
| "logs for ingestion" | `make logs SERVICE=ingestion` | |
| "health check for writer" | `make health SERVICE=writer` | |
| "check local deps" | `make check-local-deps` | |
| "start local env" | `make start-local-env` | |
| "stop local env" | `make stop-local-env` | |
| "delete local volume" | `make delete-local-volume` | destructive — confirm first |
| "list eks clusters" | `make list-eks-clusters` | |
| "list node groups" | `make list-nodegroups` | |
| "check aws resources" | `make check-aws-resources` | |

For targets that take a `SERVICE` or `TAG` variable, extract it from the
user's request and pass it as `make <target> SERVICE=<value>` or `TAG=<value>`.

For destructive operations (`delete-local-volume`, `local-clean`, `prod-down`,
`local-destroy`) confirm with the user before running.

# make-utils — Makefile + bin/ Script Conventions

This project follows a strict pattern: the **Makefile is a thin config wrapper**,
and all logic lives in **self-contained bash scripts under `bin/`**.

---

## Core Pattern

### Makefile role
- Holds all configuration variables (cluster names, regions, Docker Hub user, etc.)
- Each target is a one-liner that calls the matching `bin/` script
- Passes config values as **positional args** (env vars only when unavoidable)

```makefile
# Good — thin wrapper passing config as positional args
kubeconfig-dev:
    @./bin/kubeconfig.sh dev $(EKS_CLUSTER_NAME) $(AWS_REGION)

# Bad — logic in the Makefile
kubeconfig-dev:
    @echo "Setting kubeconfig..."
    @aws eks update-kubeconfig --region $(AWS_REGION) --name $(EKS_CLUSTER_NAME)
    @kubectl get nodes
```

### bin/ script role
- Self-contained: works when called directly from the shell (not just via make)
- Reads config from **positional args** with sensible defaults
- Starts with `set -e`
- Has a header comment block (see below)

---

## Script Header Convention

Every script starts with a comment block documenting:
1. Script name + positional arg signature
2. One-line description of what it does
3. Detailed behaviour (what steps it performs)
4. Args section listing each positional param, its default, and whether it's required

```bash
#!/bin/bash
# script-name.sh <required_arg> [optional_arg]
#
# One-line description of what this script does.
# More detail about the steps performed, side effects,
# and anything it does NOT do (e.g. "does not delete the volume").
#
# Args:
#   $1  arg_name : description, defaults to "default-value"
#   $2  arg_name : description  (required)

set -e
```

---

## Argument Patterns

| Pattern | When to use |
|---------|-------------|
| `"${1:-default}"` | Optional arg with a sensible default |
| `"${1:?Usage: script.sh <arg>}"` | Required arg — exits with usage message if missing |
| `case "$ENV" in local\|dev\|prod)` | Env-switching scripts (deploy, destroy, preview, kubeconfig, rollout) |

Prefer positional args. Only fall back to env vars for args that are genuinely
ambient (e.g. AWS credentials handled by the AWS SDK itself).

---

## Script Categories and Patterns

### Env-switching scripts
Scripts that behave differently per environment (`local`, `dev`, `prod`) use a
`case "$ENV" in` block. The env is always `$1`. Example scripts:
`kubeconfig.sh`, `deploy.sh`, `destroy.sh`, `preview.sh`, `rollout.sh`

```bash
ENV="${1:?Usage: deploy.sh <env> [...]}"
case "$ENV" in
  local) ... ;;
  dev)   ... ;;
  prod)  ... ;;
  *)     echo "❌ Unknown env: ${ENV}. Must be one of: local, dev, prod"; exit 1 ;;
esac
```

### Service-iterating scripts
Scripts that perform the same action across all three services (ingestion, reader,
writer) use a `for SERVICE in ingestion-server reader-server writer-server` loop.
Example: `dockerhub-push.sh`

### Composite scripts
Scripts that orchestrate other scripts call sibling scripts via `$SCRIPT_DIR`:

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/start-local-env.sh" "$LOCALSTACK_VOLUME" "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/import-images.sh"   "$K3D_CLUSTER_NAME"
"$SCRIPT_DIR/deploy.sh"          local "$K3D_CLUSTER_NAME"
```

Always resolve `SCRIPT_DIR` and `PROJECT_DIR` at the top:
```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
```

---

## Adding a New Target Checklist

1. **Create `bin/<name>.sh`** with the header comment and `set -e`
2. **Accept config via positional args** with `${N:-default}` or `${N:?msg}`
3. **Add a Makefile target** that calls `./bin/<name>.sh` passing `$(MAKE_VAR)` args
4. **Make it executable**: `chmod +x bin/<name>.sh`
5. If it orchestrates other scripts, make it a composite script that calls siblings via `$SCRIPT_DIR`

---

## Existing Scripts Reference

| Script | Args | Purpose |
|--------|------|---------|
| `check-local-deps.sh` | `<k3d_cluster>` | Verify all dev tools installed |
| `start-local-env.sh` | `<volume> <k3d_cluster>` | Start LocalStack + k3d |
| `stop-local-env.sh` | `<k3d_cluster>` | Stop LocalStack + k3d |
| `delete-local-volume.sh` | `<volume>` | Delete LocalStack Docker volume |
| `kubeconfig.sh` | `<env> [cluster] [region]` | Switch kubectl context |
| `deploy.sh` | `<env> [k3d_cluster]` | `pulumi up` for given env |
| `destroy.sh` | `<env> [k3d_cluster]` | `pulumi destroy` for given env |
| `preview.sh` | `<env>` | `pulumi preview` for given env |
| `build-apps.sh` | — | Build all Docker images via Mill |
| `import-images.sh` | `<k3d_cluster>` | Load images into k3d |
| `dockerhub-push.sh` | `<user> <tag>` | Tag + push all images to Docker Hub |
| `rollout.sh` | `<env> <ns> [svc] [cluster] [region]` | `kubectl rollout restart` |
| `logs.sh` | `<service> <namespace>` | Tail pod logs |
| `health.sh` | `<service> <namespace>` | Port-forward + check /health |
| `list-eks-clusters.sh` | `<region>` | List EKS clusters |
| `list-nodegroups.sh` | `<region> [cluster]` | List EKS node groups |
| `check-aws-resources.sh` | `<region> <project>` | Audit for dangling AWS resources |
| `local-dev.sh` | `<volume> <k3d_cluster>` | *(composite)* start + import + deploy local |
| `local-preview.sh` | `<volume> <k3d_cluster>` | *(composite)* start + kubeconfig + preview |
| `local-clean.sh` | `<k3d_cluster> <volume>` | *(composite)* destroy + stop |
| `dockerhub-full.sh` | `<user> <tag>` | *(composite)* build + push |
