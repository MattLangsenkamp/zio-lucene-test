.PHONY: local-dev dev prod check-local-deps start-local-env

# Check all local dependencies are installed
check-local-deps:
	@echo "Checking local development dependencies..."
	@command -v pulumi >/dev/null 2>&1 || { echo "❌ pulumi is not installed. Install: https://www.pulumi.com/docs/install/"; exit 1; }
	@command -v k3d >/dev/null 2>&1 || { echo "❌ k3d is not installed. Install: curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash"; exit 1; }
	@command -v docker >/dev/null 2>&1 || { echo "❌ docker is not installed. Install: https://docs.docker.com/engine/install/"; exit 1; }
	@command -v kubectl >/dev/null 2>&1 || { echo "❌ kubectl is not installed. Install: snap install kubectl --classic"; exit 1; }
	@command -v aws >/dev/null 2>&1 || { echo "❌ aws cli is not installed. Install: snap install aws-cli --classic"; exit 1; }
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
	@if ! docker ps | grep -q localstack; then \
		echo "Starting LocalStack..."; \
		docker run -d \
			--name localstack \
			-p 4566:4566 \
			-e SERVICES=s3,kafka,iam,secretsmanager \
			localstack/localstack; \
		echo "Waiting for LocalStack to be ready..."; \
		sleep 5; \
	else \
		echo "✅ LocalStack already running"; \
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
	@echo ""
	@echo "Initializing Pulumi local stack..."
	@cd infra && (pulumi stack select local 2>/dev/null || pulumi stack init local)
	@echo ""
	@echo "✅ Local environment ready!"
	@echo "   LocalStack: http://localhost:4566"
	@echo "   Kubernetes: k3d-zio-lucene"
	@echo "   Pulumi stack: local"
	@echo ""

# Deploy to local environment
local-dev: start-local-env
	@echo "Deploying to local environment..."
	cd infra && pulumi stack select local
	cd infra && pulumi up

dev:
	@echo "Deploying to dev environment..."
	cd infra && pulumi stack select dev
	cd infra && pulumi up

prod:
	@echo "Deploying to prod environment..."
	cd infra && pulumi stack select prod
	cd infra && pulumi up --yes

# Helper targets
local-preview: start-local-env
	cd infra && pulumi stack select local
	cd infra && pulumi preview

local-destroy:
	@echo "Destroying local stack..."
	cd infra && pulumi stack select local
	cd infra && pulumi destroy

stop-local-env:
	@echo "Stopping local environment..."
	@docker stop localstack 2>/dev/null || true
	@docker rm localstack 2>/dev/null || true
	@k3d cluster delete zio-lucene 2>/dev/null || true
	@echo "✅ Local environment stopped"

# Clean everything (destroy stack + stop env)
local-clean: local-destroy stop-local-env
	@echo "✅ Local environment cleaned up"