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
# Functions
# ---------------------------------------------------------------------------

# Patch IRSA ARNs from SSM into values.{stack}.yaml for all services.
# Usage: $(call update_irsa_values,dev)
define update_irsa_values
for svc in ingestion reader writer; do \
  ARN=$$(aws ssm get-parameter --name /zio-lucene/$(1)/irsa/$$svc \
    --query 'Parameter.Value' --output text 2>/dev/null); \
  if [ -n "$$ARN" ]; then \
    echo "Patching $$svc IRSA ARN: $$ARN"; \
    sed -i "s|irsaRoleArn:.*|irsaRoleArn: $$ARN|" $$svc/k8s/values/values.$(1).yaml; \
  else \
    echo "WARNING: no SSM param found for /zio-lucene/$(1)/irsa/$$svc — skipping"; \
  fi; \
done
endef

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
	cd infra && pulumi up --stack $(STACK) --yes
	@if [ "$(STACK)" != "local" ]; then $(call update_irsa_values,$(STACK)); fi

# Tear down all infrastructure for a given stack
infra-down:
	cd infra && pulumi destroy --stack $(STACK) --yes

# Preview infra changes without applying
infra-preview:
	cd infra && pulumi preview --stack $(STACK)

# ---------------------------------------------------------------------------
# Service Deployment (ArgoCD)
# ---------------------------------------------------------------------------

# Deploy ALL services via ArgoCD manual sync
services-sync-all:
	argocd app sync --all --wait

# Deploy a SINGLE service via ArgoCD
# Usage: make service-sync SERVICE=ingestion
service-sync:
	@test -n "$(SERVICE)" || (echo "ERROR: SERVICE is required. Usage: make service-sync SERVICE=ingestion" && exit 1)
	argocd app sync $(SERVICE) --wait

# Show status of all ArgoCD-managed services
services-status:
	argocd app list

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
	@if [ "$(STACK)" = "local" ]; then \
	  aws ssm get-parameters-by-path \
	    --path /zio-lucene/$(STACK) \
	    --recursive \
	    --endpoint-url http://localhost:4566; \
	else \
	  aws ssm get-parameters-by-path \
	    --path /zio-lucene/$(STACK) \
	    --recursive \
	    --region $(AWS_REGION); \
	fi

# Patch IRSA ARNs into values.{STACK}.yaml for all services after pulumi up
# Usage: make update-irsa-values STACK=dev
update-irsa-values:
	@test -n "$(STACK)" || (echo "ERROR: STACK is required. Usage: make update-irsa-values STACK=dev" && exit 1)
	@$(call update_irsa_values,$(STACK))

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
local-destroy: infra-down           ## alias → make infra-down STACK=local
local-down: local-destroy           ## alias

dev: infra-up                       ## alias → make infra-up STACK=dev (set STACK=dev first)
dev-down: infra-down                ## alias
dev-preview:
	$(MAKE) infra-preview STACK=dev  ## alias → make infra-preview STACK=dev
prod: infra-up                      ## alias → make infra-up STACK=prod (set STACK=prod first)
prod-down: infra-down               ## alias
