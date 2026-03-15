.PHONY: check-prereqs \
        infra-up infra-down infra-preview \
        services-sync-all service-sync services-status \
        env-up env-down \
        helm-template helm-upgrade \
        ssm-list update-irsa-values \
        argocd-ui argocd-password \
        check-local-deps start-local-env stop-local-env delete-local-volume \
        import-images logs health build-apps dockerhub-push dockerhub-full \
        kubeconfig-local kubeconfig-dev kubeconfig-prod \
        list-eks-clusters list-nodegroups check-aws-resources \
        rollout-dev rollout-local local-preview local-destroy local-clean \
        local-dev local-dev-up local-down dev dev-down prod prod-down dev-preview

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
STACK            ?= local
SERVICE          ?=
REPO_ROOT        := $(shell git rev-parse --show-toplevel)
LOCALSTACK_VOLUME = zio-lucene-localstack-data
K3D_CLUSTER_NAME  = zio-lucene
NAMESPACE         = zio-lucene
DOCKERHUB_USER    = mattlangsenkamp
TAG              ?= latest
EKS_CLUSTER_NAME  = zio-lucene-cluster
AWS_REGION        = us-east-1
# ArgoCD server address — override for cloud: make services-sync-all ARGOCD_SERVER=argocd.example.com
ARGOCD_SERVER    ?=
ARGOCD_OPTS       = --server $(ARGOCD_SERVER) --insecure

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------

check-prereqs:
	@./bin/verify-prereqs.sh

# ---------------------------------------------------------------------------
# Infrastructure (Pulumi/Besom)
# ---------------------------------------------------------------------------

# Provision all infrastructure for a given stack
# Usage: make infra-up STACK=local
infra-up: check-prereqs
	@cd $(REPO_ROOT) && ./bin/pulumi-infra.sh up $(STACK)

# Tear down all infrastructure for a given stack
infra-down:
	@cd $(REPO_ROOT) && ./bin/pulumi-infra.sh down $(STACK)

# Preview infra changes without applying
infra-preview:
	@cd $(REPO_ROOT) && ./bin/pulumi-infra.sh preview $(STACK)

# ---------------------------------------------------------------------------
# Service Deployment (ArgoCD)
# ---------------------------------------------------------------------------

# Force an immediate sync of ALL services.
# Local: annotates ArgoCD Application resources directly (no argocd server connection needed).
# Cloud: uses argocd CLI — set ARGOCD_SERVER=<host> and ensure argocd is logged in.
services-sync-all:
	@./bin/services-sync-all.sh $(STACK) $(ARGOCD_SERVER)

# Force an immediate sync of a SINGLE service.
# Usage: make service-sync SERVICE=ingestion
service-sync:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required. Usage: make service-sync SERVICE=ingestion" && exit 1)
	@./bin/service-sync.sh $(STACK) $(SERVICE) $(ARGOCD_SERVER)

# Show status of all ArgoCD-managed services
services-status:
	argocd app list $(ARGOCD_OPTS)

# ---------------------------------------------------------------------------
# Full Environment
# ---------------------------------------------------------------------------

# Spin up a complete environment from scratch (infra + all services)
# Usage: make env-up STACK=local
env-up: infra-up services-sync-all

# Tear down a complete environment
env-down: infra-down

# ---------------------------------------------------------------------------
# Helm (direct — bypasses ArgoCD, useful during chart development)
# ---------------------------------------------------------------------------

# Dry-run render a service's helm chart
# Usage: make helm-template SERVICE=ingestion STACK=local
helm-template:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required" && exit 1)
	helm template $(SERVICE) $(SERVICE)/k8s \
	  -f $(SERVICE)/k8s/values/values.$(STACK).yaml

# Install/upgrade a service helm chart directly (bypasses ArgoCD, dev only)
helm-upgrade:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required" && exit 1)
	helm upgrade --install $(SERVICE) $(SERVICE)/k8s \
	  -f $(SERVICE)/k8s/values/values.$(STACK).yaml \
	  --namespace $(NAMESPACE) --wait

# ---------------------------------------------------------------------------
# Secrets / SSM
# ---------------------------------------------------------------------------

# List all SSM parameters for a given stack
# Usage: make ssm-list STACK=local
ssm-list:
	@./bin/ssm-list.sh $(STACK)

# Patch IRSA ARNs into values.{STACK}.yaml for all services after pulumi up
# Usage: make update-irsa-values STACK=dev
update-irsa-values:
	@test -n "$(STACK)" || (echo "ERROR: STACK is required. Usage: make update-irsa-values STACK=dev" && exit 1)
	@./bin/update-irsa-values.sh $(STACK)

# ---------------------------------------------------------------------------
# ArgoCD utilities
# ---------------------------------------------------------------------------

# Port-forward ArgoCD UI to localhost:8080
argocd-ui:
	kubectl port-forward svc/argocd-server -n argocd 8080:443

# Print ArgoCD initial admin password
argocd-password:
	kubectl get secret argocd-initial-admin-secret -n argocd \
	  -o jsonpath="{.data.password}" | base64 -d && echo

# ---------------------------------------------------------------------------
# Local environment lifecycle (preserved from original Makefile)
# ---------------------------------------------------------------------------

check-local-deps:
	@./bin/check-local-deps.sh $(K3D_CLUSTER_NAME)

start-local-env:
	@./bin/start-local-env.sh $(LOCALSTACK_VOLUME) $(K3D_CLUSTER_NAME)

stop-local-env:
	@./bin/stop-local-env.sh $(K3D_CLUSTER_NAME)

delete-local-volume:
	@./bin/delete-local-volume.sh $(LOCALSTACK_VOLUME)

# ---------------------------------------------------------------------------
# Kubeconfig
# ---------------------------------------------------------------------------

kubeconfig-local:
	@./bin/kubeconfig.sh local $(K3D_CLUSTER_NAME)

kubeconfig-dev:
	@./bin/kubeconfig.sh dev $(EKS_CLUSTER_NAME) $(AWS_REGION)

kubeconfig-prod:
	@./bin/kubeconfig.sh prod $(EKS_CLUSTER_NAME) $(AWS_REGION)

# ---------------------------------------------------------------------------
# Docker / images
# ---------------------------------------------------------------------------

build-apps:
	@./bin/build-apps.sh

import-images:
	@./bin/import-images.sh $(K3D_CLUSTER_NAME)

dockerhub-push:
	@./bin/dockerhub-push.sh $(DOCKERHUB_USER) $(TAG)

dockerhub-full:
	@./bin/dockerhub-full.sh $(DOCKERHUB_USER) $(TAG)

# ---------------------------------------------------------------------------
# Kubernetes operations (preserved from original Makefile)
# ---------------------------------------------------------------------------

rollout-local:
	@./bin/rollout.sh local $(NAMESPACE) "" $(K3D_CLUSTER_NAME)

rollout-dev:
	@./bin/rollout.sh dev $(NAMESPACE) "$(SERVICE)" $(EKS_CLUSTER_NAME) $(AWS_REGION)

logs:
	@./bin/logs.sh $(SERVICE) $(NAMESPACE)

health:
	@./bin/health.sh $(SERVICE) $(NAMESPACE)

# ---------------------------------------------------------------------------
# AWS utilities
# ---------------------------------------------------------------------------

list-eks-clusters:
	@./bin/list-eks-clusters.sh $(AWS_REGION)

list-nodegroups:
	@./bin/list-nodegroups.sh $(AWS_REGION) $(or $(CLUSTER),$(EKS_CLUSTER_NAME))

check-aws-resources:
	@./bin/check-aws-resources.sh $(AWS_REGION) zio-lucene

# ---------------------------------------------------------------------------
# Composite / legacy aliases (muscle memory preserved)
# ---------------------------------------------------------------------------

# 'make local-dev' is the original all-in-one local spin-up
local-dev:
	@./bin/local-dev.sh $(LOCALSTACK_VOLUME) $(K3D_CLUSTER_NAME)

local-preview:
	@./bin/local-preview.sh $(LOCALSTACK_VOLUME) $(K3D_CLUSTER_NAME)

local-clean:
	@./bin/local-clean.sh $(K3D_CLUSTER_NAME) $(LOCALSTACK_VOLUME)

# Legacy aliases for infra operations
local-dev-up: infra-up              ## alias → make infra-up STACK=local
local-destroy: start-local-env infra-down stop-local-env  ## start LocalStack → pulumi destroy → stop
local-down: local-destroy           ## alias

dev:
	$(MAKE) infra-up STACK=dev
dev-down:
	$(MAKE) infra-down STACK=dev
dev-preview:
	$(MAKE) infra-preview STACK=dev
prod:
	$(MAKE) infra-up STACK=prod
prod-down:
	$(MAKE) infra-down STACK=prod
