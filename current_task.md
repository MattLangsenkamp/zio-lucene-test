# DevOps Framework Spec — Phase 1 (Prototyping / No CI-CD)

## Purpose

This document is a precise implementation spec intended to be fed directly to Claude Code.
It describes the target state of the monorepo's DevOps framework, what needs to be built,
what needs to be migrated, and the order of operations. Sections marked
`[ASSUMPTION: ...]` must be verified against the actual repo before proceeding.

---

## Goals for This Phase

- Service helm charts live **with the service**, not inside Pulumi
- A `service-manifest.yaml` at the repo root is the single registry of all services
- Pulumi (Besom/Scala) provisions infrastructure only — it does **not** deploy helm releases
- ArgoCD is installed into the cluster by Pulumi and wired to `service-manifest.yaml`,
  but sync is **manual** — no automated GitOps promotion yet
- External Secrets Operator bridges AWS resources (SQS, S3, Secrets Manager) into pods
  automatically via SSM Parameter Store — no manual secret copy-pasting
- LocalStack is the local target; a single command spins up a full env locally or in AWS
- Makefile is the unified CLI interface for all operations

---

## Assumptions to Verify

> Claude Code: confirm each of these before writing any code or moving any files.

- `[ASSUMPTION: DIR]` Root monorepo structure is approximately:
  ```
  /
  ├── infra/          ← Pulumi/Besom project lives here
  ├── services/       ← individual microservices live here
  │   ├── service-a/
  │   └── service-b/
  ├── Makefile
  └── ...
  ```
  Confirm actual paths before proceeding. All generated paths in this spec derive from this.

- `[ASSUMPTION: BESOM]` Besom project is a single Pulumi project under `infra/` with
  stacks for `local`, `dev`, and `prod` (or similar). Confirm stack names.

- `[ASSUMPTION: EMBEDDED-CHARTS]` Existing k8s manifests or helm releases are currently
  defined inside Besom source files (e.g. as `k8s.helm.v3.Release` resources).
  Confirm which services have these and list them before migration begins.

- `[ASSUMPTION: LOCALSTACK]` LocalStack is running via docker-compose and is accessible
  at `http://localhost:4566`. Confirm the compose file location and the endpoint.

- `[ASSUMPTION: SERVICES]` There are fewer than 15 services. Confirm the full list so
  `service-manifest.yaml` can be seeded accurately.

- `[ASSUMPTION: CLUSTER]` A local k8s cluster exists (e.g. k3d, kind, or minikube) for
  local dev. Confirm which one is in use and how it is currently provisioned.

---

## Target Directory Structure

```
/
├── service-manifest.yaml               ← root registry of all services
├── Makefile                            ← unified CLI interface
├── scripts/                            ← supporting shell scripts
│   ├── bootstrap.sh                    ← full env spin-up (calls pulumi + argocd sync)
│   ├── destroy.sh                      ← full env tear-down
│   ├── lease-env.sh                    ← (stub only in this phase, for future ephemeral envs)
│   └── verify-prereqs.sh              ← checks pulumi, helm, argocd, aws cli are installed
├── infra/
│   ├── project.scala (or Main.scala)   ← existing Besom entrypoint
│   ├── platform/
│   │   ├── Cluster.scala               ← EKS or local k8s cluster resource
│   │   ├── ArgoCD.scala                ← installs ArgoCD helm release + ApplicationSet
│   │   ├── ExternalSecrets.scala       ← installs ESO + ClusterSecretStore
│   │   └── IamRoles.scala              ← IRSA roles per service
│   ├── resources/
│   │   ├── Queues.scala                ← SQS queues → SSM params
│   │   ├── Buckets.scala               ← S3 buckets → SSM params
│   │   └── AppSecrets.scala            ← Secrets Manager secrets → SSM param paths
│   └── stacks/
│       ├── local.yaml                  ← LocalStack endpoints, no real AWS
│       ├── dev.yaml
│       └── prod.yaml
└── services/
    └── service-a/                      ← [ASSUMPTION: DIR] example service
        ├── src/
        └── k8s/
            ├── Chart.yaml
            ├── values.yaml             ← base values
            ├── values/
            │   ├── values.local.yaml
            │   ├── values.dev.yaml
            │   └── values.prod.yaml
            └── templates/
                ├── deployment.yaml
                ├── service.yaml
                ├── serviceaccount.yaml
                ├── external-secret.yaml   ← ESO CRD — declares which SSM params to pull
                └── configmap.yaml         ← non-secret config (queue names as logical keys)
```

---

## service-manifest.yaml Spec

This file is the single source of truth for what services exist. ArgoCD's ApplicationSet
reads it. Pulumi reads it to create IRSA roles. The Makefile reads it for targeting.

```yaml
# service-manifest.yaml
apiVersion: v1
services:
  - name: service-a
    k8sPath: services/service-a/k8s
    irsaRoles:
      - sqs:read
      - sqs:write
      - s3:read
    awsResources:
      sqs:
        - logicalName: orders-queue          # used as SSM key suffix
          ssmPath: /myapp/{env}/sqs/orders-queue   # {env} substituted at deploy time
      s3:
        - logicalName: uploads-bucket
          ssmPath: /myapp/{env}/s3/uploads-bucket
      secrets:
        - logicalName: db-credentials
          ssmPath: /myapp/{env}/secrets/db-credentials

  - name: service-b
    k8sPath: services/service-b/k8s
    irsaRoles:
      - sqs:read
    awsResources:
      sqs:
        - logicalName: events-queue
          ssmPath: /myapp/{env}/sqs/events-queue
```

**Rules:**
- `name` must be kebab-case, globally unique
- `k8sPath` is relative to repo root
- `irsaRoles` is a controlled vocabulary — Pulumi maps these to actual IAM policy
  statements. New role types must be added to both this vocab and `IamRoles.scala`.
- `ssmPath` uses `{env}` as a literal placeholder. Pulumi substitutes the stack name
  at resource creation time. ArgoCD substitutes it in helm values at sync time.

---

## Pulumi / Besom Migration Spec

### What to REMOVE from Besom

Remove all of the following from existing Besom source files:

- Any `k8s.helm.v3.Release` resources that deploy application services
- Any hardcoded environment variables or config values being injected into pods
- Any k8s `Deployment`, `Service`, or `ConfigMap` resources for application services
  (platform-level k8s resources like the ArgoCD namespace are fine to keep)

These are being replaced by helm charts living with each service and managed by ArgoCD.

### What to ADD to Besom

#### 1. `platform/ArgoCD.scala`

Install ArgoCD via helm release and apply the ApplicationSet CRD that reads
`service-manifest.yaml`. The ApplicationSet must:

- Use the `git` generator pointing at the monorepo repo URL and revision
- Read `service-manifest.yaml` as the generator file source
- Template one ArgoCD `Application` per service entry
- Set `syncPolicy: {}` (empty — manual sync only, no automated sync)
- Pass `env={{ stack name }}` as a helm value so `values.{env}.yaml` is selected

```scala
// infra/platform/ArgoCD.scala (illustrative — adapt to Besom idiom)
val argocd = helm.v3.Release(
  "argocd",
  chart = "argo-cd",
  repositoryOpts = RepositoryOpts(repo = "https://argoproj.github.io/argo-helm"),
  namespace = "argocd",
  createNamespace = true,
  values = Map(
    "server" -> Map("service" -> Map("type" -> "LoadBalancer"))
  )
)

// ApplicationSet applied after ArgoCD is ready
val appSet = apiextensions.CustomResource(
  "services-appset",
  apiVersion = "argoproj.io/v1alpha1",
  kind = "ApplicationSet",
  metadata = ObjectMeta(namespace = "argocd"),
  // spec: generators.git reads service-manifest.yaml, templates one Application per service
  // syncPolicy is empty on each templated Application (manual sync)
)
```

For **local stack**: ArgoCD should still be installed. The ApplicationSet should point
at the local cluster. Repo access must work — if using a local file path during
prototyping, configure ArgoCD with a `file://` repo or use a local git server (Gitea
via docker-compose is a low-friction option).

#### 2. `platform/ExternalSecrets.scala`

Install External Secrets Operator and create a `ClusterSecretStore`:

```scala
val eso = helm.v3.Release(
  "external-secrets",
  chart = "external-secrets",
  repositoryOpts = RepositoryOpts(repo = "https://charts.external-secrets.io"),
  namespace = "external-secrets",
  createNamespace = true
)

// ClusterSecretStore for local stack: endpoint = http://localhost:4566 (LocalStack SSM)
// ClusterSecretStore for dev/prod: standard AWS SSM, auth via IRSA
val secretStore = apiextensions.CustomResource(
  "aws-ssm-store",
  apiVersion = "external-secrets.io/v1beta1",
  kind = "ClusterSecretStore",
  spec = Map(
    "provider" -> Map(
      "aws" -> Map(
        "service" -> "ParameterStore",
        "region"  -> config.require("aws:region"),
        "endpoint" -> config.get("localstackEndpoint") // only set in local stack
      )
    )
  )
)
```

#### 3. `resources/Queues.scala`

For each SQS queue defined in `service-manifest.yaml`, Pulumi must:

1. Create the SQS queue
2. Write its URL (and ARN if needed) to SSM at the path defined in `service-manifest.yaml`

```scala
// Pattern for every queue:
val ordersQueue = sqs.Queue("orders-queue", ...)

val ordersQueueParam = ssm.Parameter(
  "orders-queue-url-param",
  name  = s"/myapp/$stack/sqs/orders-queue",
  type_ = "String",
  value = ordersQueue.url
)
```

Apply the same pattern to `resources/Buckets.scala` (S3) and `resources/AppSecrets.scala`
(Secrets Manager — write the secret ARN or name to SSM so ESO can fetch it).

#### 4. `platform/IamRoles.scala`

For each service in `service-manifest.yaml`, create an IRSA role that grants:

- `ssm:GetParameter` on the specific SSM paths that service declares
- The resource-level permissions declared in `irsaRoles` (e.g. `sqs:ReceiveMessage`,
  `sqs:DeleteMessage`, `s3:GetObject` etc.)

The IRSA role ARN must itself be written to SSM:
`/myapp/{env}/irsa/{service-name}` so it can be referenced in the service's
helm chart `serviceaccount.yaml`.

---

## Helm Chart Spec (Per Service)

Every service gets a helm chart under `services/{name}/k8s/`. The following templates
are required as a baseline. Services add more as needed.

### `Chart.yaml`

```yaml
apiVersion: v2
name: service-a
version: 0.1.0
appVersion: "latest"
```

### `values.yaml` (base — non-environment-specific)

```yaml
replicaCount: 1
image:
  repository: ""      # set per environment in values.{env}.yaml
  tag: latest
  pullPolicy: IfNotPresent
serviceAccount:
  irsaRoleArn: ""     # injected by ArgoCD from SSM at sync time (see below)
env: local            # overridden per environment
resources: {}
```

### `values/values.local.yaml`

```yaml
env: local
image:
  repository: localhost:5000/service-a   # local registry
replicaCount: 1
resources:
  limits:
    memory: "256Mi"
    cpu: "250m"
```

### `values/values.dev.yaml`

```yaml
env: dev
image:
  repository: <ECR_ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/service-a
replicaCount: 1
```

### `templates/external-secret.yaml`

This is the key template. It declares which SSM parameters the service needs.
ESO reads this and creates a native k8s `Secret`. The pod never touches SSM directly.

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: {{ .Release.Name }}-secrets
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-ssm-store
    kind: ClusterSecretStore
  target:
    name: {{ .Release.Name }}-secrets
    creationPolicy: Owner
  data:
    # Each entry: secretKey = env var name the app will see
    #             remoteRef.key = SSM path (env substituted by helm at render time)

    - secretKey: ORDERS_QUEUE_URL
      remoteRef:
        key: /myapp/{{ .Values.env }}/sqs/orders-queue

    - secretKey: UPLOADS_BUCKET_NAME
      remoteRef:
        key: /myapp/{{ .Values.env }}/s3/uploads-bucket

    - secretKey: DB_PASSWORD
      remoteRef:
        key: /myapp/{{ .Values.env }}/secrets/db-credentials
        property: password          # if the Secrets Manager value is a JSON blob,
                                    # property extracts a single key from it
```

### `templates/deployment.yaml`

```yaml
env:
  - name: ORDERS_QUEUE_URL
    valueFrom:
      secretKeyRef:
        name: {{ .Release.Name }}-secrets
        key: ORDERS_QUEUE_URL
  - name: UPLOADS_BUCKET_NAME
    valueFrom:
      secretKeyRef:
        name: {{ .Release.Name }}-secrets
        key: UPLOADS_BUCKET_NAME
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: {{ .Release.Name }}-secrets
        key: DB_PASSWORD
```

Application code reads standard env vars. It has no knowledge of SSM, ARNs, or AWS.

### `templates/serviceaccount.yaml`

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ .Release.Name }}
  annotations:
    eks.amazonaws.com/role-arn: {{ .Values.serviceAccount.irsaRoleArn }}
```

The IRSA role ARN comes from `values.{env}.yaml` which ArgoCD populates from the
SSM path `/myapp/{env}/irsa/{service-name}` at sync time.

---

## Makefile Migration Spec

The Makefile becomes the single CLI interface. All existing targets must be preserved
and remapped. New targets are added below.

### Target Structure

```makefile
# ── Configuration ──────────────────────────────────────────────────────────────
STACK         ?= local
SERVICE       ?=
REPO_ROOT     := $(shell git rev-parse --show-toplevel)

# ── Prerequisites ──────────────────────────────────────────────────────────────
.PHONY: check-prereqs
check-prereqs:
	@scripts/verify-prereqs.sh    # checks: pulumi, helm, argocd, aws cli, scala, sbt

# ── Infrastructure ─────────────────────────────────────────────────────────────

# Provision all infrastructure for a given stack (default: local)
# Usage: make infra-up STACK=local
.PHONY: infra-up
infra-up: check-prereqs
	cd infra && pulumi up --stack $(STACK) --yes

# Tear down all infrastructure for a given stack
.PHONY: infra-down
infra-down:
	cd infra && pulumi destroy --stack $(STACK) --yes

# Preview infra changes without applying
.PHONY: infra-preview
infra-preview:
	cd infra && pulumi preview --stack $(STACK)

# ── Service Deployment (ArgoCD) ────────────────────────────────────────────────

# Deploy ALL services (ArgoCD manual sync for every Application)
.PHONY: services-sync-all
services-sync-all:
	argocd app sync --all --wait

# Deploy a SINGLE service
# Usage: make service-sync SERVICE=service-a
.PHONY: service-sync
service-sync:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required. Usage: make service-sync SERVICE=service-a" && exit 1)
	argocd app sync $(SERVICE) --wait

# Show status of all service deployments
.PHONY: services-status
services-status:
	argocd app list

# ── Full Environment ────────────────────────────────────────────────────────────

# Spin up a complete environment from scratch (infra + all services)
# Usage: make env-up STACK=local
.PHONY: env-up
env-up: infra-up services-sync-all

# Tear down a complete environment
.PHONY: env-down
env-down: infra-down

# ── Helm (direct, bypasses ArgoCD — useful during chart development) ────────────

# Dry-run render a service's helm chart
# Usage: make helm-template SERVICE=service-a STACK=local
.PHONY: helm-template
helm-template:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required" && exit 1)
	helm template $(SERVICE) services/$(SERVICE)/k8s \
	  -f services/$(SERVICE)/k8s/values/values.$(STACK).yaml

# Install/upgrade a service helm chart directly (bypasses ArgoCD, dev only)
.PHONY: helm-upgrade
helm-upgrade:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required" && exit 1)
	helm upgrade --install $(SERVICE) services/$(SERVICE)/k8s \
	  -f services/$(SERVICE)/k8s/values/values.$(STACK).yaml \
	  --namespace default --wait

# ── Secrets / SSM ──────────────────────────────────────────────────────────────

# List all SSM parameters for a given stack (useful for debugging ESO wiring)
# Usage: make ssm-list STACK=local
.PHONY: ssm-list
ssm-list:
	aws ssm get-parameters-by-path \
	  --path /myapp/$(STACK) \
	  --recursive \
	  --endpoint-url http://localhost:4566   # remove endpoint for real AWS stacks

# ── Utilities ──────────────────────────────────────────────────────────────────

# Port-forward ArgoCD UI to localhost:8080
.PHONY: argocd-ui
argocd-ui:
	kubectl port-forward svc/argocd-server -n argocd 8080:443

# Print ArgoCD initial admin password
.PHONY: argocd-password
argocd-password:
	kubectl get secret argocd-initial-admin-secret -n argocd \
	  -o jsonpath="{.data.password}" | base64 -d && echo
```

**Migration rule:** Every existing Makefile target that currently deploys a service
via Pulumi must be remapped to `argocd app sync <service-name>`. Do not delete old
target names — alias them to the new targets so existing muscle memory is preserved.

---

## Migration Steps (Ordered)

Claude Code should execute these in order. Do not proceed to the next step until the
current step compiles/applies cleanly.

### Step 1 — Audit
- List every `helm.v3.Release`, `k8s.apps.v1.Deployment`, and `k8s.core.v1.ConfigMap`
  currently defined in Besom source files
- List every service directory under `services/` (or equivalent)
- Confirm `[ASSUMPTION]` blocks at the top of this doc against actual repo state
- Document findings before touching any files

### Step 2 — Scaffold service-manifest.yaml
- Create `service-manifest.yaml` at repo root
- Populate with one entry per service found in Step 1
- Leave `awsResources` empty for services that don't yet have confirmed resource needs
- Do not wire anything yet — this is data only

### Step 3 — Scaffold helm charts
- For each service in `service-manifest.yaml`:
    - Create `services/{name}/k8s/` if it doesn't exist
    - Generate `Chart.yaml`, `values.yaml`, `values/values.local.yaml`,
      `values/values.dev.yaml`, `values/values.prod.yaml`
    - Create template stubs: `deployment.yaml`, `service.yaml`,
      `serviceaccount.yaml`, `external-secret.yaml`, `configmap.yaml`
    - If the service had an existing helm release in Besom, port its values
      into the new `values.yaml` and environment-specific overrides
- Validate each chart: `helm template <name> services/<name>/k8s` must succeed

### Step 4 — Add platform modules to Besom
- Create `infra/platform/ExternalSecrets.scala`
- Create `infra/platform/ArgoCD.scala`
- Create `infra/platform/IamRoles.scala`
- Create `infra/resources/Queues.scala`, `Buckets.scala`, `AppSecrets.scala`
- Wire all new modules into the main Pulumi program
- `pulumi preview` must show no errors before proceeding

### Step 5 — Remove service deployments from Besom
- Delete all `helm.v3.Release`, `Deployment`, `ConfigMap` resources
  that belong to application services (identified in Step 1)
- Run `pulumi preview` again — verify removed resources show as deletes,
  not errors. Verify remaining platform resources are unaffected.

### Step 6 — Apply and verify
- `make infra-up STACK=local`
- Verify ArgoCD is running: `make argocd-ui`
- Verify ESO is running: `kubectl get pods -n external-secrets`
- Verify ClusterSecretStore is ready:
  `kubectl get clustersecretstore aws-ssm-store -o yaml`
- Verify SSM params were written: `make ssm-list STACK=local`

### Step 7 — Sync services via ArgoCD
- `make services-sync-all`
- Verify ExternalSecrets are syncing: `kubectl get externalsecret -A`
- Verify pods are running and env vars are populated:
  `kubectl exec -it <pod> -- env | grep QUEUE`
- Fix any issues per-service before declaring done

### Step 8 — Migrate Makefile
- Port all existing targets per the spec above
- Alias any renamed targets to their old names
- Test: `make env-down && make env-up STACK=local` must go from zero to
  fully running without manual intervention

---

## Out of Scope for This Phase

The following are explicitly deferred. Do not implement them now.

- GitHub Actions workflows (any of them)
- ArgoCD automated sync policies
- Promotion pipelines (dev → staging → prod)
- Ephemeral environment lease system
- Multiple shared environment stacks (dev, staging, sandbox, prod clusters)
- ArgoCD ApplicationSet for multi-env promotion
- Image build pipelines

---

## Definition of Done

This phase is complete when:

1. `make env-up STACK=local` runs from a clean state and produces a fully working
   local environment with all services deployed, no manual steps required
2. `make service-sync SERVICE=<name>` deploys a single service without touching others
3. Any service pod can log its env vars and show resolved values for SQS URLs,
   S3 bucket names, and secrets — all sourced from SSM via ESO, none hardcoded
4. `make env-down STACK=local` cleanly tears everything down
5. ArgoCD UI is accessible and shows all services as synced

