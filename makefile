.PHONY: local-dev local-down dev dev-down prod prod-down dev-preview check-local-deps start-local-env stop-local-env delete-local-volume import-images logs health build-apps dockerhub-push dockerhub-full kubeconfig-local kubeconfig-dev kubeconfig-prod list-eks-clusters list-nodegroups check-aws-resources rollout-dev rollout-local local-dev-up local-preview local-destroy local-clean

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
LOCALSTACK_VOLUME = zio-lucene-localstack-data
K3D_CLUSTER_NAME  = zio-lucene
NAMESPACE         = zio-lucene
DOCKERHUB_USER    = mattlangsenkamp
TAG              ?= latest
EKS_CLUSTER_NAME  = zio-lucene-cluster
AWS_REGION        = us-east-1

# ---------------------------------------------------------------------------
# Local environment lifecycle
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
# Pulumi deploy / destroy / preview  (one script per operation)
# ---------------------------------------------------------------------------

local-dev-up:
	@./bin/deploy.sh local $(K3D_CLUSTER_NAME)

dev:
	@./bin/deploy.sh dev

prod:
	@./bin/deploy.sh prod

dev-down:
	@./bin/destroy.sh dev

prod-down:
	@./bin/destroy.sh prod

local-destroy:
	@./bin/destroy.sh local $(K3D_CLUSTER_NAME)

# Alias
local-down: local-destroy

dev-preview:
	@./bin/preview.sh dev

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
# Kubernetes operations
# ---------------------------------------------------------------------------

rollout-local:
	@./bin/rollout.sh local $(NAMESPACE) "" $(K3D_CLUSTER_NAME)

rollout-dev:
	@./bin/rollout.sh dev $(NAMESPACE) $(SERVICE) $(EKS_CLUSTER_NAME) $(AWS_REGION)

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
# Composite targets
# ---------------------------------------------------------------------------

local-dev:
	@./bin/local-dev.sh $(LOCALSTACK_VOLUME) $(K3D_CLUSTER_NAME)

local-preview:
	@./bin/local-preview.sh $(LOCALSTACK_VOLUME) $(K3D_CLUSTER_NAME)

local-clean:
	@./bin/local-clean.sh $(K3D_CLUSTER_NAME) $(LOCALSTACK_VOLUME)
