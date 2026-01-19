# External Secrets Operator

This document explains how External Secrets Operator integrates AWS Secrets Manager with Kubernetes.

## Architecture Overview

```
┌─────────────────────┐
│ AWS Secrets Manager │  ← Source of truth: zio-lucene/datadog-api-key = "dummy"
└──────────┬──────────┘
           │
           │ ③ AWS API call (authenticated via IRSA)
           │
           ↓
┌─────────────────────────────────────────┐
│ External Secrets Operator (pod in k8s) │  ← Reads CRDs and syncs secrets
└──────────┬──────────────────────────────┘
           │
           │ ④ Creates/updates Kubernetes Secret
           │
           ↓
┌─────────────────────────────────┐
│ Kubernetes Secret               │
│ name: datadog-api-key           │
│ namespace: zio-lucene           │
│ data:                           │
│   api-key: "ZHVtbXk="  (base64) │
└─────────────────────────────────┘
           ↑
           │ ⑤ Application pods mount/use
           │
┌──────────┴──────────┐
│ Your App Pods       │
└─────────────────────┘

① Pulumi creates CRDs ────────────────┐
② IRSA role grants permissions ───────┤
                                       ↓
                        ┌──────────────────────────┐
                        │ ClusterSecretStore CRD   │ ← Created by SecretSync
                        │ ExternalSecret CRD       │ ← Created by SecretSync
                        └──────────────────────────┘
```

## The Players

### 1. AWS Secrets Manager
- **What**: AWS service that stores encrypted secrets
- **Knows**: Secret key-value pairs (e.g., `zio-lucene/datadog-api-key` = "dummy")
- **Knows nothing about**: Kubernetes, pods, namespaces

### 2. External Secrets Operator
- **What**: Kubernetes controller (pod running in `external-secrets` namespace)
- **Does**: Watches for ExternalSecret CRDs and syncs them to Kubernetes Secrets
- **Installation**: Helm chart installed by Pulumi (ExternalSecretsOperator.scala)

### 3. IRSA (IAM Roles for Service Accounts)
- **What**: AWS mechanism to grant IAM permissions to Kubernetes service accounts
- **How**: Maps `system:serviceaccount:external-secrets:external-secrets` → IAM role ARN
- **Grants**: `secretsmanager:GetSecretValue`, `secretsmanager:DescribeSecret`, `secretsmanager:ListSecrets`

### 4. SecretSync (Our Pulumi Code)
- **What**: Pulumi resource that creates configuration CRDs
- **Does NOT**: Fetch or sync secrets itself
- **Does**: Creates ClusterSecretStore, SecretStore, ExternalSecret, and LocalStack credentials

## What SecretSync Does

SecretSync creates **Custom Resources** that configure the External Secrets Operator:

### 1. ClusterSecretStore (EKS) / SecretStore (local)
**Purpose**: Tells the operator HOW to connect to the secret backend

**EKS Example**:
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: aws-secretsmanager
spec:
  provider:
    aws:
      service: SecretsManager
      region: us-east-1
      auth:
        jwt:
          serviceAccountRef:
            name: external-secrets  # Uses IRSA
```

**Local Example**:
```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
  name: localstack-secretsmanager
  namespace: zio-lucene
spec:
  provider:
    aws:
      service: SecretsManager
      region: us-east-1
      endpoint: http://host.k3d.internal:4566  # LocalStack
      auth:
        secretRef:
          accessKeyID:
            name: localstack-credentials
            key: access-key
```

**Key Insight**: This is like giving the operator the "connection string" to AWS Secrets Manager.

### 2. LocalStack Credentials Secret (local only)
**Purpose**: Provide static AWS credentials for LocalStack

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: localstack-credentials
  namespace: zio-lucene
type: Opaque
stringData:
  access-key: test
  secret-key: test
```

**Why**: LocalStack doesn't support IRSA, so we use static credentials.

### 3. ExternalSecret
**Purpose**: Tells the operator WHICH secrets to sync

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: datadog-api-key
  namespace: zio-lucene
spec:
  refreshInterval: 1h  # Re-sync every hour
  secretStoreRef:
    name: aws-secretsmanager  # or localstack-secretsmanager
    kind: ClusterSecretStore  # or SecretStore
  target:
    name: datadog-api-key  # Name of Kubernetes Secret to create
    creationPolicy: Owner  # ESO owns the Secret
  data:
    - secretKey: api-key  # Key in Kubernetes Secret
      remoteRef:
        key: zio-lucene/datadog-api-key  # Key in AWS Secrets Manager
```

**Key Insight**: This is the "sync instruction" - what to fetch and where to put it.

## Complete Flow

### Step 1: Pulumi Deployment
```bash
pulumi up --stack dev
```

**What happens**:
1. Installs External Secrets Operator (Helm chart)
2. Creates IRSA role with Secrets Manager permissions
3. Creates ClusterSecretStore CRD (connection config)
4. Creates ExternalSecret CRD (sync instruction)

### Step 2: Operator Detects New CRDs
The External Secrets Operator controller watches for new ExternalSecret resources:

```
Operator logs:
"New ExternalSecret detected: zio-lucene/datadog-api-key"
"Using ClusterSecretStore: aws-secretsmanager"
```

### Step 3: Operator Authenticates via IRSA
**IRSA Flow**:
1. Operator pod has service account `external-secrets` mounted
2. Service account has annotation: `eks.amazonaws.com/role-arn: arn:aws:iam::...:role/external-secrets-irsa`
3. AWS SDK detects service account token and exchanges it for AWS credentials
4. Operator can now call AWS APIs as that IAM role

### Step 4: Operator Fetches Secret
```bash
# Operator makes this call internally
aws secretsmanager get-secret-value \
  --secret-id zio-lucene/datadog-api-key \
  --region us-east-1

# Returns:
{
  "SecretString": "dummy"
}
```

### Step 5: Operator Creates Kubernetes Secret
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: datadog-api-key
  namespace: zio-lucene
  ownerReferences:
    - apiVersion: external-secrets.io/v1beta1
      kind: ExternalSecret
      name: datadog-api-key
      controller: true
type: Opaque
data:
  api-key: ZHVtbXk=  # base64("dummy")
```

### Step 6: Application Pods Use Secret
```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: app
      env:
        - name: DD_API_KEY
          valueFrom:
            secretKeyRef:
              name: datadog-api-key
              key: api-key
```

## EKS vs Local Differences

| Aspect | EKS (dev/prod) | Local (k3d) |
|--------|---------------|-------------|
| **Secret Backend** | AWS Secrets Manager | LocalStack |
| **Authentication** | IRSA (service account → IAM role) | Static credentials (`test`/`test`) |
| **Store Type** | ClusterSecretStore (cluster-wide) | SecretStore (namespace-scoped) |
| **Endpoint** | Default AWS endpoint | `http://host.k3d.internal:4566` |
| **Credentials** | Automatic via IRSA | Manual Secret (`localstack-credentials`) |

## IRSA Deep Dive

### What is IRSA?

IRSA (IAM Roles for Service Accounts) allows Kubernetes service accounts to assume AWS IAM roles without using static credentials.

### How it Works

1. **EKS Cluster has OIDC Provider**
   ```
   Issuer: https://oidc.eks.us-east-1.amazonaws.com/id/ABC123...
   ```

2. **IAM Role Trust Policy**
   ```json
   {
     "Effect": "Allow",
     "Principal": {
       "Federated": "arn:aws:iam::123456789012:oidc-provider/oidc.eks..."
     },
     "Action": "sts:AssumeRoleWithWebIdentity",
     "Condition": {
       "StringEquals": {
         "oidc.eks.../sub": "system:serviceaccount:external-secrets:external-secrets",
         "oidc.eks.../aud": "sts.amazonaws.com"
       }
     }
   }
   ```

3. **Service Account Annotation**
   ```yaml
   apiVersion: v1
   kind: ServiceAccount
   metadata:
     name: external-secrets
     namespace: external-secrets
     annotations:
       eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/external-secrets-irsa
   ```

4. **Pod gets AWS credentials automatically**
   - Service account token mounted at `/var/run/secrets/eks.amazonaws.com/serviceaccount/token`
   - AWS SDK detects token and calls `sts:AssumeRoleWithWebIdentity`
   - Receives temporary AWS credentials (access key, secret key, session token)

### IRSA in Our Stack

**Created by**: `ExternalSecretsIrsa.scala`

**Resources**:
- IAM Role with trust policy for OIDC provider
- IAM Policy granting Secrets Manager permissions
- Role-Policy attachment

**Applied to**: `external-secrets` service account in `external-secrets` namespace

## Implementation Files

### Pulumi Code
- **`infra/src/utils/SecretSync.scala`** - Creates CRDs (ClusterSecretStore, SecretStore, ExternalSecret)
- **`infra/src/utils/ExternalSecretsOperator.scala`** - Installs Helm chart
- **`infra/src/utils/ExternalSecretsIrsa.scala`** - Creates IAM role for IRSA
- **`infra/src/Main.scala`** - Wires everything together

### Key Patterns

**Creating CustomResources in Besom**:
```scala
k8s.apiextensions.CustomResource[SpecType](
  "resource-name",
  k8s.apiextensions.CustomResourceArgs[SpecType](
    apiVersion = "external-secrets.io/v1beta1",
    kind = "ClusterSecretStore",
    metadata = ObjectMetaArgs(...),
    spec = mySpec  // Must be unwrapped from Output
  ),
  ComponentResourceOptions(
    providers = List(prov),  // Note: plural, not singular!
    dependsOn = dependencies
  )
)
```

**Key Difference**: CustomResource uses `ComponentResourceOptions(providers = ...)` not `opts(provider = ...)`

## Troubleshooting

### ExternalSecret Not Syncing

**Check ExternalSecret status**:
```bash
kubectl describe externalsecret datadog-api-key -n zio-lucene
```

**Common issues**:
- `SecretStoreRef not found` → ClusterSecretStore/SecretStore not created yet
- `Access denied` → IRSA role missing permissions
- `Secret not found` → Secret doesn't exist in AWS Secrets Manager
- `Invalid credentials` → LocalStack credentials incorrect

### IRSA Not Working (EKS)

**Verify service account annotation**:
```bash
kubectl get sa external-secrets -n external-secrets -o yaml | grep role-arn
```

**Test from pod**:
```bash
kubectl run -it --rm debug \
  --image=amazon/aws-cli \
  --serviceaccount=external-secrets \
  -n external-secrets \
  -- sts get-caller-identity
```

**Should see**:
```json
{
  "UserId": "AROA...:botocore-session-1234567890",
  "Account": "123456789012",
  "Arn": "arn:aws:sts::123456789012:assumed-role/external-secrets-irsa/..."
}
```

### LocalStack Not Reachable (local)

**Test connectivity**:
```bash
kubectl run -it --rm debug \
  --image=curlimages/curl \
  -n zio-lucene \
  -- curl http://host.k3d.internal:4566/_localstack/health
```

**Common issues**:
- LocalStack not running: `docker ps | grep localstack`
- k3d networking issue: Check `host.k3d.internal` resolves inside cluster
- Secret doesn't exist in LocalStack: `awslocal secretsmanager list-secrets`

### Check Operator Logs

```bash
kubectl logs -n external-secrets -l app.kubernetes.io/name=external-secrets --tail=100 -f
```

**Look for**:
- `reconciling ExternalSecret` - Operator processing your ExternalSecret
- `secret synced` - Success!
- `error` - Something went wrong

## Secret Rotation

External Secrets Operator automatically rotates secrets based on `refreshInterval`:

```yaml
spec:
  refreshInterval: 1h  # Check AWS Secrets Manager every hour
```

**How it works**:
1. Every hour, operator fetches secret from AWS Secrets Manager
2. If value changed, updates Kubernetes Secret
3. Pods using the secret will see new value on next read (or restart if needed)

## Security Considerations

### Least Privilege
- IRSA role has minimal permissions: only `GetSecretValue`, `DescribeSecret`, `ListSecrets`
- No `PutSecretValue`, `DeleteSecret`, or other write operations
- Scoped to specific secret paths if desired

### Secret Encryption
- AWS Secrets Manager: Encrypted at rest with KMS
- Kubernetes Secrets: Encrypted at rest if etcd encryption enabled
- In transit: TLS for AWS API calls, internal cluster networking

### Audit
- AWS CloudTrail logs all Secrets Manager API calls
- Kubernetes audit logs track Secret access
- Monitor IRSA role usage via IAM access analyzer

## Best Practices

1. **Use IRSA for EKS** - Never use static AWS credentials in EKS
2. **Scope ClusterSecretStore carefully** - Use namespaced SecretStore if secrets are environment-specific
3. **Set appropriate refreshInterval** - Balance freshness vs API cost (1h is reasonable default)
4. **Use creationPolicy: Owner** - Let ExternalSecret own the Kubernetes Secret lifecycle
5. **Monitor operator logs** - Set up alerts for sync failures
6. **Version secrets in AWS** - Use versioning in Secrets Manager for rollback capability

## Adding New Secrets

To add a new secret to sync:

1. **Create secret in AWS Secrets Manager**:
   ```bash
   aws secretsmanager create-secret \
     --name zio-lucene/my-new-secret \
     --secret-string "my-value"
   ```

2. **Add ExternalSecret to SecretSync.scala**:
   ```scala
   private def createMyNewExternalSecret(
     params: SecretSyncInput,
     secretStore: Output[k8s.apiextensions.CustomResource[?]]
   )(using Context): Output[k8s.apiextensions.CustomResource[ExternalSecretSpec]] =
     // Similar to createExternalSecret but for my-new-secret
   ```

3. **Wire into make() and makeLocal()**:
   ```scala
   for {
     store <- createClusterSecretStore(inputParams)
     datadogSecret <- createExternalSecret(...)
     myNewSecret <- createMyNewExternalSecret(...)  // Add here
   } yield SecretSyncOutput(...)
   ```

4. **Update SecretSyncOutput** to include new secret if needed

5. **Deploy**: `pulumi up`

## References

- [External Secrets Operator Documentation](https://external-secrets.io/)
- [AWS Secrets Manager](https://docs.aws.amazon.com/secretsmanager/)
- [IRSA Documentation](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html)
- [Besom CustomResource API](https://github.com/VirtusLab/besom/blob/main/codegen/resources/overlays/kubernetes/apiextensions/CustomResource.scala)
