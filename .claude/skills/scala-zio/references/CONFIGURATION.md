# Configuration

All service configuration uses `zio-config-magnolia` automatic derivation. Config is read from environment variables at startup; missing values abort the process with a structured error. No defaults are permitted in code.

## Pattern

```scala
import zio.*
import zio.config.magnolia.*

final case class SqsConsumerConfig(sqsQueueUrl: String) derives Config

object SqsConsumerConfig:
  val layer: TaskLayer[SqsConsumerConfig] =
    ZLayer.fromZIO(ZIO.config[SqsConsumerConfig])
```

## Rules

- All config case classes use `derives Config` from `zio.config.magnolia.*`.
- No default values. Every field is required. If it is missing, the app fails to start.
- The `layer` is always `ZLayer.fromZIO(ZIO.config[T])` — no manual parsing, no `System.env`, no `envRequired` helpers.
- Config companions contain only the `given Config[T]` (if needed) and `val layer`.

## Env Var Naming

The `ConfigProvider` is configured in each server's `bootstrap` to convert field names automatically:

```scala
override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
  Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase.upperCase)
```

This converts camelCase field names to `SCREAMING_SNAKE_CASE` env vars:

| Field name | Env var |
|---|---|
| `sqsQueueUrl` | `SQS_QUEUE_URL` |
| `commitQueueUrl` | `COMMIT_QUEUE_URL` |
| `luceneIndexPath` | `LUCENE_INDEX_PATH` |
| `batchSize` | `BATCH_SIZE` |

**Avoid digits in field names.** ZIO's `snakeCase` splits on digit-letter boundaries, so `s3Bucket` produces `S_3_BUCKET` instead of `S3_BUCKET`. Use a digit-free prefix instead: `storageBucket` → `STORAGE_BUCKET`.

## Where to Define Config Classes

Config case classes live in the `domainPrivate` module of the service that uses them. One file per config class.

## Adding a New Config Field

1. Add the field to the case class — derivation picks it up automatically.
2. Ensure the corresponding env var is set in the Kubernetes ConfigMap or as a direct env var in the infra (`Writer.scala`, `Ingestion.scala`, etc.).
3. No code changes needed in the layer or server.

## Dependency

`zio-config-magnolia` must be in the module's `mvnDeps`:

```scala
object domainPrivate extends AppModule {
  def mvnDeps = zioDeps ++ zioConfigDeps  // zioConfigDeps defined in build.mill
}
```
