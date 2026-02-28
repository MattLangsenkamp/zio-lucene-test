.PHONY: local-dev local-down dev dev-down prod prod-down dev-preview check-local-deps start-local-env delete-local-volume import-images logs health build-apps dockerhub-push dockerhub-full kubeconfig-local kubeconfig-dev kubeconfig-prod list-eks-clusters list-nodegroups check-aws-resources rollout-dev rollout-local

# Configuration
LOCALSTACK_VOLUME = zio-lucene-localstack-data
K3D_CLUSTER_NAME = zio-lucene
NAMESPACE = zio-lucene
DOCKERHUB_USER = mattlangsenkamp
TAG ?= latest
EKS_CLUSTER_NAME = zio-lucene-cluster
AWS_REGION = us-east-1

# Check all local dependencies are installed
check-local-deps:
	@echo "Checking local development dependencies..."
	@command -v pulumi >/dev/null 2>&1 || { echo "❌ pulumi is not installed. Install: https://www.pulumi.com/docs/install/"; exit 1; }
	@command -v k3d >/dev/null 2>&1 || { echo "❌ k3d is not installed. Install: curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash"; exit 1; }
	@command -v docker >/dev/null 2>&1 || { echo "❌ docker is not installed. Install: https://docs.docker.com/engine/install/"; exit 1; }
	@command -v kubectl >/dev/null 2>&1 || { echo "❌ kubectl is not installed. Install: snap install kubectl --classic"; exit 1; }
	@command -v aws >/dev/null 2>&1 || { echo "❌ aws cli is not installed. Install: snap install aws-cli --classic"; exit 1; }
	@command -v coursier >/dev/null 2>&1 || { echo "❌ coursier is not installed. Visit: https://get-coursier.io/"; exit 1; }
	@command -v ./mill >/dev/null 2>&1 || { echo "❌ mill is not installed. Install: https://mill-build.com/mill/Intro_to_Mill.html#_installation"; exit 1; }
	@echo "✅ All dependencies installed"
	@echo ""
	@echo "Checking Docker daemon..."
	@docker info >/dev/null 2>&1 || { echo "❌ Docker daemon is not running."; exit 1; }
	@echo "✅ Docker daemon is running"
	@echo ""
	@echo "Checking LocalStack..."
	@docker ps | grep -q localstack || { echo "⚠️  LocalStack is not running."; }
	@echo ""
	@echo "Checking k3d cluster..."
	@k3d cluster list | grep -q zio-lucene || { echo "⚠️  k3d cluster 'zio-lucene' does not exist. Run \`make start-local-env\`"; }
	@echo ""

# Start local environment (LocalStack + k3d)
start-local-env: check-local-deps
	@echo "Starting local development environment..."
	@if ! docker volume ls | grep -q $(LOCALSTACK_VOLUME); then \
		echo "Creating LocalStack volume..."; \
		docker volume create $(LOCALSTACK_VOLUME); \
	fi
	@if docker ps -a | grep -q localstack; then \
		if docker ps | grep -q localstack; then \
			echo "✅ LocalStack already running"; \
		else \
			echo "Removing stopped LocalStack container..."; \
			docker rm localstack 2>/dev/null || true; \
			echo "Starting LocalStack..."; \
			docker run -d \
				--name localstack \
				-p 4566:4566 \
				-v $(LOCALSTACK_VOLUME):/var/lib/localstack \
				-e SERVICES=s3,kafka,iam,secretsmanager,ec2,sqs \
				-e PERSISTENCE=1 \
				localstack/localstack; \
			echo "Waiting for LocalStack to be ready..."; \
			sleep 5; \
		fi; \
	else \
		echo "Starting LocalStack..."; \
		docker run -d \
			--name localstack \
			-p 4566:4566 \
			-v $(LOCALSTACK_VOLUME):/var/lib/localstack \
			-e SERVICES=s3,kafka,iam,secretsmanager,ec2,sqs \
			-e PERSISTENCE=1 \
			localstack/localstack; \
		echo "Waiting for LocalStack to be ready..."; \
		sleep 5; \
	fi
	@if ! k3d cluster list | grep -q zio-lucene; then \
		echo "Creating k3d cluster..."; \
		k3d cluster create zio-lucene \
			--api-port 6550 \
			--port "8080:80@loadbalancer" \
			--port "8443:443@loadbalancer"; \
	else \
		echo "✅ k3d cluster 'zio-lucene' already exists"; \
	fi
	@echo "Connecting LocalStack to k3d network..."
	@docker network connect k3d-$(K3D_CLUSTER_NAME) localstack 2>/dev/null || echo "✅ LocalStack already connected to k3d network"
	@echo ""
	@echo "Initializing Pulumi local stack..."
	@cd infra && (pulumi stack select local 2>/dev/null || pulumi stack init local)
	@echo ""
	@echo "✅ Local environment ready!"
	@echo "   LocalStack: http://localhost:4566"
	@echo "   Kubernetes: k3d-zio-lucene"
	@echo "   Pulumi stack: local"
	@echo ""

# Build all application Docker images
build-apps:
	@echo "Building all application Docker images..."
	./mill reader.server.docker.build
	./mill ingestion.server.docker.build
	./mill writer.server.docker.build
	@echo "✅ All images built"

# Import Docker images into k3d cluster
import-images:
	@echo "Importing Docker images into k3d cluster..."
	@k3d image import ingestion-server:latest -c $(K3D_CLUSTER_NAME) 2>/dev/null || echo "⚠️  ingestion-server:latest not found"
	@k3d image import reader-server:latest -c $(K3D_CLUSTER_NAME) 2>/dev/null || echo "⚠️  reader-server:latest not found"
	@k3d image import writer-server:latest -c $(K3D_CLUSTER_NAME) 2>/dev/null || echo "⚠️  writer-server:latest not found"
	@echo "✅ Images imported"

# Push Docker images to Docker Hub (usage: make dockerhub-push TAG=v1.0.0)
dockerhub-push:
	@echo "Logging into Docker Hub..."
	docker login
	@echo ""
	@echo "Tagging and pushing images to Docker Hub as $(DOCKERHUB_USER)/*:$(TAG)..."
	@echo "Tagging ingestion-server..."
	docker tag ingestion-server:latest $(DOCKERHUB_USER)/ingestion-server:$(TAG)
	@echo "Pushing ingestion-server..."
	docker push $(DOCKERHUB_USER)/ingestion-server:$(TAG)
	@echo ""
	@echo "Tagging reader-server..."
	docker tag reader-server:latest $(DOCKERHUB_USER)/reader-server:$(TAG)
	@echo "Pushing reader-server..."
	docker push $(DOCKERHUB_USER)/reader-server:$(TAG)
	@echo ""
	@echo "Tagging writer-server..."
	docker tag writer-server:latest $(DOCKERHUB_USER)/writer-server:$(TAG)
	@echo "Pushing writer-server..."
	docker push $(DOCKERHUB_USER)/writer-server:$(TAG)
	@echo ""
	@echo "✅ All images pushed to Docker Hub"
	@echo "   - $(DOCKERHUB_USER)/ingestion-server:$(TAG)"
	@echo "   - $(DOCKERHUB_USER)/reader-server:$(TAG)"
	@echo "   - $(DOCKERHUB_USER)/writer-server:$(TAG)"

# Build and push Docker images to Docker Hub in one command
dockerhub-full: build-apps dockerhub-push

local-dev-up: kubeconfig-local
	@echo "Deploying to local environment..."
	cd infra && pulumi stack select local
	@LOCALSTACK_IP=$$(docker inspect localstack | python3 -c "import sys,json; d=json.load(sys.stdin)[0]; print(d['NetworkSettings']['Networks']['k3d-$(K3D_CLUSTER_NAME)']['IPAddress'])" 2>/dev/null); \
	if [ -n "$$LOCALSTACK_IP" ]; then \
		echo "LocalStack IP in k3d network: $$LOCALSTACK_IP"; \
	else \
		echo "⚠️  Could not detect LocalStack IP in k3d network — run 'make start-local-env' first"; \
	fi; \
	cd infra && LOCALSTACK_K3D_IP=$$LOCALSTACK_IP pulumi up --yes --refresh

# Deploy to local environment
local-dev: start-local-env import-images local-dev-up

dev:
	@echo "Deploying to dev environment..."
	cd infra && pulumi stack select dev
	cd infra && pulumi up

dev-down:
	@echo "Destroying dev environment..."
	cd infra && pulumi stack select dev
	cd infra && pulumi destroy

prod:
	@echo "Deploying to prod environment..."
	cd infra && pulumi stack select prod
	cd infra && pulumi up --yes

prod-down:
	@echo "Destroying prod environment..."
	cd infra && pulumi stack select prod
	cd infra && pulumi destroy

dev-preview:
	@echo "Previewing to dev environment..."
	cd infra && pulumi stack select dev
	cd infra && pulumi preview

# Set kubeconfig for local k3d cluster
kubeconfig-local:
	@echo "Setting kubeconfig for local k3d cluster..."
	kubectl config use-context k3d-$(K3D_CLUSTER_NAME)
	@echo "✅ Kubeconfig updated for local cluster"
	@echo ""
	kubectl config current-context
	@echo ""
	kubectl get nodes

# Set kubeconfig for dev EKS cluster
kubeconfig-dev:
	@echo "Setting kubeconfig for dev EKS cluster..."
	aws eks update-kubeconfig --region $(AWS_REGION) --name $(EKS_CLUSTER_NAME)
	@echo "✅ Kubeconfig updated for dev cluster"
	@echo ""
	kubectl config current-context
	@echo ""
	kubectl get nodes

# Set kubeconfig for prod EKS cluster
kubeconfig-prod:
	@echo "Setting kubeconfig for prod EKS cluster..."
	aws eks update-kubeconfig --region $(AWS_REGION) --name $(EKS_CLUSTER_NAME)
	@echo "✅ Kubeconfig updated for prod cluster"
	@echo ""
	kubectl config current-context
	@echo ""
	kubectl get nodes

# List all EKS clusters
list-eks-clusters:
	@echo "Listing all EKS clusters in region $(AWS_REGION)..."
	@aws eks list-clusters --region $(AWS_REGION) --output table

# List node groups for a cluster (usage: make list-nodegroups CLUSTER=zio-lucene-cluster)
list-nodegroups:
	@if [ -z "$(CLUSTER)" ]; then \
		echo "Listing node groups for default cluster: $(EKS_CLUSTER_NAME)..."; \
		aws eks list-nodegroups --region $(AWS_REGION) --cluster-name $(EKS_CLUSTER_NAME) --output table; \
	else \
		echo "Listing node groups for cluster: $(CLUSTER)..."; \
		aws eks list-nodegroups --region $(AWS_REGION) --cluster-name $(CLUSTER) --output table; \
	fi

# Rollout restart all services in local k3d cluster
rollout-local: kubeconfig-local
	kubectl rollout restart deployment/ingestion -n $(NAMESPACE)
	kubectl rollout restart deployment/reader -n $(NAMESPACE)
	kubectl rollout restart statefulset/writer -n $(NAMESPACE)

# Rollout restart a service in dev (usage: make rollout-dev SERVICE=reader)
rollout-dev:
	@if [ -z "$(SERVICE)" ]; then \
		echo "Usage: make rollout-dev SERVICE=<service-name>"; \
		echo "Available services: ingestion, reader, writer"; \
		exit 1; \
	fi
	aws eks update-kubeconfig --region $(AWS_REGION) --name $(EKS_CLUSTER_NAME) > /dev/null 2>&1
	kubectl rollout restart deployment/$(SERVICE) -n $(NAMESPACE)

# Check for dangling AWS resources
check-aws-resources:
	@AWS_REGION=$(AWS_REGION) PROJECT_NAME=zio-lucene ./bin/check-aws-resources.sh

# Helper targets
local-preview: start-local-env kubeconfig-local
	cd infra && pulumi stack select local
	cd infra && pulumi preview

local-destroy:
	@echo "Destroying local stack..."
	@kubectl config use-context k3d-$(K3D_CLUSTER_NAME) 2>/dev/null || true
	cd infra && pulumi stack select local
	cd infra && pulumi destroy

# Alias for local-destroy
local-down: local-destroy

stop-local-env:
	@echo "Stopping local environment..."
	@if docker ps -a | grep -q localstack; then \
		echo "Stopping LocalStack container..."; \
		docker stop localstack 2>/dev/null || true; \
		echo "Removing LocalStack container..."; \
		docker rm localstack 2>/dev/null || true; \
	else \
		echo "LocalStack container not found"; \
	fi
	@if k3d cluster list | grep -q zio-lucene; then \
		echo "Deleting k3d cluster..."; \
		k3d cluster delete zio-lucene 2>/dev/null || true; \
	else \
		echo "k3d cluster not found"; \
	fi
	@echo "✅ Local environment stopped"

# Clean everything (destroy stack + stop env)
local-clean: local-destroy stop-local-env
	@echo "✅ Local environment cleaned up"

# Delete LocalStack volume (WARNING: destroys all persisted data)
delete-local-volume:
	@echo "⚠️  WARNING: This will delete all persisted LocalStack data!"
	@read -p "Are you sure? [y/N] " -n 1 -r; \
	echo; \
	if [[ $$REPLY =~ ^[Yy]$$ ]]; then \
		echo "Deleting LocalStack volume..."; \
		docker volume rm $(LOCALSTACK_VOLUME) 2>/dev/null || echo "Volume does not exist or is in use"; \
		echo "✅ Volume deleted"; \
	else \
		echo "Cancelled"; \
	fi

# View logs for a service (usage: make logs SERVICE=ingestion|reader|writer|kafka)
logs:
	@if [ -z "$(SERVICE)" ]; then \
		echo "Usage: make logs SERVICE=<service-name>"; \
		echo "Available services: ingestion, reader, writer, kafka"; \
		exit 1; \
	fi; \
	if [ "$(SERVICE)" = "writer" ]; then \
		echo "📋 Tailing logs for writer-0..."; \
		kubectl logs -f writer-0 -n $(NAMESPACE); \
	elif [ "$(SERVICE)" = "kafka" ]; then \
		echo "📋 Tailing logs for kafka-0..."; \
		kubectl logs -f kafka-0 -n $(NAMESPACE); \
	elif [ "$(SERVICE)" = "ingestion" ] || [ "$(SERVICE)" = "reader" ]; then \
		POD=$$(kubectl get pods -n $(NAMESPACE) -l app=$(SERVICE) -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
		if [ -z "$$POD" ]; then \
			echo "❌ No pod found for service: $(SERVICE)"; \
			exit 1; \
		fi; \
		echo "📋 Tailing logs for $$POD..."; \
		kubectl logs -f $$POD -n $(NAMESPACE); \
	else \
		echo "❌ Unknown service: $(SERVICE)"; \
		echo "Available services: ingestion, reader, writer, kafka"; \
		exit 1; \
	fi

# Check health endpoint (usage: make health SERVICE=ingestion|reader|writer)
health:
	@if [ -z "$(SERVICE)" ]; then \
		echo "Usage: make health SERVICE=<service-name>"; \
		echo "Available services: ingestion, reader, writer"; \
		exit 1; \
	fi; \
	if [ "$(SERVICE)" = "ingestion" ]; then \
		PORT=8083; \
		POD=$$(kubectl get pods -n $(NAMESPACE) -l app=ingestion -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
	elif [ "$(SERVICE)" = "reader" ]; then \
		PORT=8081; \
		POD=$$(kubectl get pods -n $(NAMESPACE) -l app=reader -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
	elif [ "$(SERVICE)" = "writer" ]; then \
		PORT=8082; \
		POD="writer-0"; \
	else \
		echo "❌ Unknown service: $(SERVICE)"; \
		echo "Available services: ingestion, reader, writer"; \
		exit 1; \
	fi; \
	if [ -z "$$POD" ]; then \
		echo "❌ No pod found for service: $(SERVICE)"; \
		exit 1; \
	fi; \
	echo "🏥 Checking health for $$POD on port $$PORT..."; \
	kubectl port-forward -n $(NAMESPACE) $$POD $$PORT:8080 > /dev/null 2>&1 & \
	PF_PID=$$!; \
	echo "Waiting for port-forward to be ready..."; \
	for i in 1 2 3 4 5; do \
		sleep 1; \
		if curl -s --max-time 1 http://localhost:$$PORT/health > /dev/null 2>&1; then \
			break; \
		fi; \
	done; \
	echo ""; \
	RESPONSE=$$(curl -s -w "\n%{http_code}" --max-time 5 http://localhost:$$PORT/health 2>/dev/null); \
	HTTP_CODE=$$(echo "$$RESPONSE" | tail -n1); \
	BODY=$$(echo "$$RESPONSE" | sed '$$d'); \
	if [ "$$HTTP_CODE" = "200" ]; then \
		echo "✅ Status: $$HTTP_CODE"; \
		echo "Response: $$BODY"; \
	elif [ "$$HTTP_CODE" = "000" ] || [ -z "$$HTTP_CODE" ]; then \
		echo "❌ Failed to connect to $$POD:8080"; \
		echo "   Port-forward may have failed or service not listening on port 8080"; \
	else \
		echo "❌ Status: $$HTTP_CODE"; \
		echo "Response: $$BODY"; \
	fi; \
	echo ""; \
	kill $$PF_PID 2>/dev/null