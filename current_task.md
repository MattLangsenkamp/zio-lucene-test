# IRSA for App Services (Writer, Ingestion, Reader)

## Context

All three application services (writer, ingestion, reader) are deployed to EKS but have no AWS credentials. Their pod specs don't set `serviceAccountName`, so pods run as the `default` ServiceAccount which has no IAM role attached. The AWS SDK credential chain exhausts all providers including IMDS, resulting in `SdkClientException: Unable to load credentials`.

The fix is IRSA (IAM Roles for Service Accounts) — the same pattern already used by `EbsCsiDriver`, `ExternalSecretsIrsa`, and `AlbController`. EKS mutates pods using a ServiceAccount annotated with `eks.amazonaws.com/role-arn` to inject `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE`, allowing the SDK to exchange an OIDC token for temporary credentials automatically.

---

## Files to Create

### `infra/src/utils/writer/WriterIrsa.scala`
Create IAM role + policy + K8s ServiceAccount for writer, following `ExternalSecretsIrsa.scala` exactly.

- **Input**: `oidcProvider: Output[OidcProviderOutput]`, `namespace: Output[String]`, `bucketArn: Output[String]`, `sqsQueueArn: Output[String]`
- **IAM role name**: `writer-irsa-role`
- **Trust policy subject**: `system:serviceaccount:zio-lucene:writer`
- **Policy** (inline `RolePolicy`):
  ```json
  s3:GetObject, s3:PutObject, s3:DeleteObject, s3:ListBucket  → bucket ARN + bucket ARN/*
  sqs:ReceiveMessage, sqs:DeleteMessage, sqs:ChangeMessageVisibility, sqs:GetQueueAttributes → queue ARN
  ```
- **K8s ServiceAccount**: name `writer`, namespace from input, annotation `eks.amazonaws.com/role-arn: <role.arn>`
- **Output**: `serviceAccountName: String` (constant `"writer"`)

### `infra/src/utils/ingestion/IngestionIrsa.scala`
Same structure as WriterIrsa.

- **IAM role name**: `ingestion-irsa-role`
- **Trust policy subject**: `system:serviceaccount:zio-lucene:ingestion`
- **Policy**:
  ```json
  s3:PutObject, s3:GetObject, s3:ListBucket  → bucket ARN + bucket ARN/*
  sqs:SendMessage, sqs:GetQueueUrl, sqs:GetQueueAttributes  → queue ARN
  ```
- **K8s ServiceAccount**: name `ingestion`
- **Output**: `serviceAccountName: String`

### `infra/src/utils/reader/ReaderIrsa.scala`
Same structure, S3-only policy (no SQS — reader doesn't touch the queue).

- **IAM role name**: `reader-irsa-role`
- **Trust policy subject**: `system:serviceaccount:zio-lucene:reader`
- **Policy**:
  ```json
  s3:GetObject, s3:ListBucket  → bucket ARN + bucket ARN/*
  ```
- **K8s ServiceAccount**: name `reader`
- **Output**: `serviceAccountName: String`

---

## Files to Modify

### `infra/src/utils/writer/Writer.scala`
Add `serviceAccountName: Output[String]` parameter to `createStatefulSet`. In the `PodSpecArgs`, add:
```scala
serviceAccountName = serviceAccountName
```

### `infra/src/utils/ingestion/Ingestion.scala`
Add `serviceAccountName: Output[String]` parameter to `createDeployment`. In the `PodSpecArgs`, add:
```scala
serviceAccountName = serviceAccountName
```

### `infra/src/utils/reader/Reader.scala`
- Add `serviceAccountName: Output[String]` parameter to `createDeployment`
- Add `serviceAccountName` to `PodSpecArgs`
- Add missing `AWS_REGION = "us-east-1"` to `baseEnvVars`

### `infra/src/Main.scala`
In both the SQS and Kafka branches:

1. After the namespace is created, create the three IRSA resources:
   ```scala
   val sqsQueueArn = sqsOutput.flatMap(_.queue.arn)   // (or kafka equivalent)
   val bucketArn   = bucket.flatMap(_.bucket.arn)

   val writerIrsa    = WriterIrsa.make(...)
   val ingestionIrsa = IngestionIrsa.make(...)
   val readerIrsa    = ReaderIrsa.make(...)
   ```

2. Pass `serviceAccountName` into each service's deployment/statefulset call:
   ```scala
   Writer.createStatefulSet(..., serviceAccountName = writerIrsa.map(_.serviceAccountName), ...)
   Ingestion.createDeployment(..., serviceAccountName = ingestionIrsa.map(_.serviceAccountName), ...)
   Reader.createDeployment(..., serviceAccountName = readerIrsa.map(_.serviceAccountName), ...)
   ```

3. Add the three IRSA resources to each `Stack(...)` export list.

---

## IRSA File Template (to follow for all three)

Reference: `infra/src/utils/ExternalSecretsIrsa.scala` — copy the assume-role-policy structure verbatim, changing only the subject, role name, and policy document.

Key pattern:
```scala
assumeRolePolicy = for
  oidc   <- params.oidcProvider
  issuer <- oidc.issuerUrl
  arn    <- oidc.providerArn
yield s"""{ "Version": "2012-10-17", "Statement": [{ "Effect": "Allow",
  "Principal": { "Federated": "$arn" },
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": { "StringEquals": {
    "${issuer.stripPrefix("https://")}:sub": "system:serviceaccount:<ns>:<sa>",
    "${issuer.stripPrefix("https://")}:aud": "sts.amazonaws.com"
  }}}]}"""
```

K8s ServiceAccount annotation pattern (from `AlbController.scala` line 361-365):
```scala
annotations = role.arn.map(arn => Map("eks.amazonaws.com/role-arn" -> arn))
```

---

## Verification

1. `make dev` — Pulumi applies new IRSA roles, ServiceAccounts, and updated pod specs
2. `make rollout-dev SERVICE=writer` + `make rollout-dev SERVICE=reader` + `make rollout-dev SERVICE=ingestion`
3. Check pods pick up the right SA: `kubectl get pods -n zio-lucene -o yaml | grep serviceAccountName`
4. Confirm credentials injected: `kubectl exec -n zio-lucene <writer-pod> -- env | grep AWS_ROLE_ARN`
5. Check logs for absence of credential errors: `make logs SERVICE=writer`
