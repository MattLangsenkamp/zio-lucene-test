# Service Setup Reference

This document covers the conventions for structuring a new service in this project.

---

## Mill Build Conventions

### Dependency Groups

All external dependencies are declared as top-level `val`s of `Seq[Dep]` and mixed together per module. Never declare individual deps inside module definitions.

```scala
val zioDeps = Seq(
  mvn"dev.zio::zio:2.1.17",
  ...
)

val sqsDeps = Seq(
  mvn"dev.zio::zio-sqs:0.12.2",
  ...
)
```

### Service Module Structure

Each service is a top-level `object extends Module` (not `ScalaModule`). This namespaces the submodules without making the top-level object compilable itself.

```scala
object myService extends Module {
  object domainPublic extends ScalaModule { ... }
  object domainPrivate extends ScalaModule { ... }
  object api extends ScalaModule { ... }
  object services extends ScalaModule { ... }
  object server extends ScalaModule with DockerModule { ... }
}
```

### Dependency Direction

The enforced dependency graph (via `moduleDeps`) is:

```
domainPublic
    ↑         ↑
domainPrivate  api
    ↑         ↑
       services
          ↑
        server  (also depends on common.`base-telemetry`)
```

- `domainPublic` depends on nothing in the service
- `domainPrivate` and `api` depend on `domainPublic`
- `services` depends on `domainPublic`, `domainPrivate`, and `api`
- `server` depends on all of the above plus `common.\`base-telemetry\`` and `common.\`server-utils\``, plus any AWS/sttp common modules the service needs

Cross-service dependencies (e.g. `writer.services` consuming `ingestion.domainPublic`) are declared explicitly in `moduleDeps`.

### Server Module

`server` always extends `AppModule with DockerModule` with a nested `docker` config. Always include `common.\`server-utils\`` in `moduleDeps`. Add AWS common modules only for the services that actually need them — do not pull in SQS or S3 if the service doesn't use them.

```scala
object server extends AppModule with DockerModule {
  def moduleDeps = super.moduleDeps ++ Seq(
    domainPublic, domainPrivate, api, services,
    common.`base-telemetry`,
    common.`server-utils`,          // always — provides serverAfterTelemetry, resolvedRoutesLayer
    // common.`aws-sqs`,            // add if the service uses SQS
    // common.`aws-s3`,             // add if the service uses S3
    // common.`sttp-client`,        // add if the service makes outbound HTTP requests via sttp
  )
  def mvnDeps = zioDeps ++ zioHttpDeps ++ otelSdkDeps

  object docker extends DockerConfig {
    def tags = List("my-service-server:latest")
    def baseImage = "azul/zulu-openjdk-alpine:21-jre"
    def exposedPorts = Seq(8080)
  }
}
```

### Centralized Scala Version

Always use `VersionInfo.scalaVersion`, never hardcode.

---

## Directory Structure and Purposes

Each service follows this layout:

```
<service>/
  domainPublic/src/    package: app.<service>.domain
  domainPrivate/src/   package: app.<service>.domain.internal
  api/src/             package: app.<service>.api
  services/src/        package: app.<service>.services
  server/src/          package: app.<service>.server
```

Cross-service shared infrastructure lives in:

```
common/
  <module-name>/src/   package: common.<modulename>
```

### domainPublic

Types that are **safe for other services to consume**. These are the data shapes exchanged via networking boundaries (HTTP, gRPC, SQS, Kafka, etc.). They are referenced by `api` endpoint definitions but do not define the contracts themselves.

A type belongs here if another service might legitimately import and use it.

### domainPrivate

Types that **never leave the service boundary** — not even referenced by `domainPublic`. This includes:
- Config types loaded from environment variables
- Third-party vendor models (SQS message shapes, external API responses)
- Persistence/storage models
- Any internal data structure specific to this service's implementation

**Use access modifiers to enforce this boundary where possible.** The package `app.<service>.domain.internal` signals intent, but `private` or `private[services]` modifiers add compile-time enforcement.

### api

**Declarative contract definitions only** — no ZIO logic, no business logic. Currently this means Tapir endpoint definitions (HTTP shape: method, path, input types, output types, error types). These reference types from `domainPublic`.

The guiding principle is *declarative-first*: if a transport or protocol library offers a declarative, type-safe way to describe a contract (like Tapir does for HTTP), its definitions belong here. If a future transport (e.g. a gRPC IDL wrapper, a declarative Kafka schema DSL) follows the same pattern, put it in `api` as well. Imperative wiring or handler logic does not belong here regardless of the transport.

```scala
object MyEndpoint:
  val myEndpoint: PublicEndpoint[MyRequest, MyError, MyResponse, Any] =
    endpoint.post
      .in("my-path")
      .in(jsonBody[MyRequest])
      .errorOut(jsonBody[MyError])
      .out(jsonBody[MyResponse])
```

### services

**Service traits, Live implementations, and external integrations.** This is where the actual work happens: business logic services, SQS publishers/consumers, HTTP client wrappers, etc.

All services in this directory **must follow the ZIO Service Pattern** (https://zio.dev/reference/service-pattern/). When in doubt, consult those docs. The abbreviated form used in this project:

**1. Trait — the service interface**
```scala
trait MyService:
  def doThing(input: String): Task[Unit]
```

**2. `Live` implementation — `final case class` extending the trait**

The `Live` class is package-private to `app.<service>.services` — visible within the service (so `server` can reference `MyServiceLive.layer`) but not outside it. Callers in other services only ever see the `MyService` trait.

```scala
// no modifier = package-private to app.<service>.services
final case class MyServiceLive(dep: MyDep) extends MyService:
  override def doThing(input: String): Task[Unit] = ???
```

**4. `layer` — lifts the implementation into `ZLayer`**

Simple (no effectful initialization needed):
```scala
object MyServiceLive:
  val layer: URLayer[MyDep, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)
```

Effectful (e.g. allocating a metric counter from `Meter`):
```scala
object MyServiceLive:
  val layer: RLayer[MyDep & Meter, MyService] =
    ZLayer.fromZIO:
      for
        dep     <- ZIO.service[MyDep]
        meter   <- ZIO.service[Meter]
        counter <- meter.counter("my.metric")
      yield MyServiceLive(dep, counter)
```

See the ZIO docs linked above for full detail on the pattern, scoped resources, and layer combinators.

### server

**The `ZIOAppDefault` entry point.** This is the only place where `provide(...)` or `ZLayer.make` is called. It wires all layers together, starts the HTTP server, and forks any background fibers.

### common/<module-name>

**Cross-cutting infrastructure** not owned by any single service. Each module has its own Mill target so services only pull in the deps they actually need.

| Module | Package | Provides |
|---|---|---|
| `base-telemetry` | `common.basetelemetry` | OpenTelemetry SDK setup (traces, metrics, logs via OTLP gRPC). `TelemetryEnv` type alias. |
| `activity-logging` | `common.activitylogging` | `ZIO.logActivity`, `InfoLog`/`ErrorLog`/etc. marker traits. Always on classpath via `AppModule`. |
| `server-utils` | `common.serverutils` | `ServerLayers.serverAfterTelemetry`, `ServerLayers.resolvedRoutesLayer(app)`, `ZServerStarting` event. Every server should depend on this. |
| `aws-base` | `common.awsbase` | `AwsBase.awsBaseLayer` — Netty transport + AWS SDK config. Foundation for `aws-sqs` and `aws-s3`. |
| `aws-sqs` | `common.awssqs` | `AwsSqs.sqsLayer` — SQS client layer. Depends on `aws-base`. |
| `aws-s3` | `common.awss3` | `AwsS3.s3Layer` — S3 client layer. Depends on `aws-base`. |
| `sttp-client` | `common.sttpclient` | `SttpClientLayer.sttpBackendLayer` — generic sttp HTTP client layer for outbound requests. |

**ZIO layer memoization**: `aws-sqs` and `aws-s3` both internally use `AwsBase.awsBaseLayer`. Because it is a stable `val`, ZIO identifies it as the same layer by reference and only instantiates the Netty event loop once, even when both are composed together in the same `provide(...)` call.

---

## Code Patterns

### Service Trait + Live Implementation

The standard pattern for every service:

**Trait (in `services/src/MyService.scala`):**
```scala
package app.myservice.services

import zio.*

trait MyService:
  def doThing(input: String): Task[Unit]
```

**Live implementation (in `services/src/MyServiceLive.scala`):**
```scala
package app.myservice.services

import zio.*

final case class MyServiceLive(dep1: Dep1, dep2: Dep2) extends MyService:
  override def doThing(input: String): Task[Unit] =
    ??? // implementation

object MyServiceLive:
  val layer: URLayer[Dep1 & Dep2, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)
```

When initialization requires ZIO effects (e.g. creating metric counters from `Meter`), use `ZLayer.fromZIO`:

```scala
object MyServiceLive:
  val layer: RLayer[Dep1 & Meter, MyService] =
    ZLayer.fromZIO:
      for
        dep1    <- ZIO.service[Dep1]
        meter   <- ZIO.service[Meter]
        counter <- meter.counter("my.metric.name")
      yield MyServiceLive(dep1, counter)
```

### Config Pattern

Config types live in `domainPrivate`. They load from environment variables and expose a `val layer`:

```scala
package app.myservice.domain.internal

import zio.*

final case class MyConfig(queueUrl: String, timeout: Long)

object MyConfig:
  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  private def envRequiredLong(name: String): Task[Long] =
    envRequired(name).flatMap: value =>
      ZIO
        .attempt(value.toLong)
        .mapError(_ => new IllegalArgumentException(s"Invalid numeric value for $name: '$value'"))

  val layer: TaskLayer[MyConfig] =
    ZLayer.fromZIO:
      for
        queueUrl <- envRequired("MY_QUEUE_URL")
        timeout  <- envRequiredLong("MY_TIMEOUT_MS")
      yield MyConfig(queueUrl, timeout)
```

### Server Entry Point

Use `ServerLayers` from `common.serverutils` — never define `serverAfterTelemetry` or `resolvedRoutesLayer` inline in a server. Pass the service-specific `app` routes to `ServerLayers.resolvedRoutesLayer`. Background jobs are forked here, not in services.

```scala
package app.myservice.server

import zio.*
import zio.json.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import common.basetelemetry.BaseTelemetry
import common.activitylogging.*
import common.serverutils.ServerLayers
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.tracing.Tracing

object Server extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase.upperCase)

  private val healthServerEndpoint: ZServerEndpoint[Tracing, Any] =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      ZIO.serviceWithZIO[Tracing]: tracing =>
        tracing.span("health-check", SpanKind.SERVER):
          ZIO.logActivity(HealthCheckHit()) *> ZIO.succeed("OK")

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  // Background jobs are forked here, not inside service implementations
  private val backgroundJob: ZIO[MyService, Nothing, Unit] =
    MyService.doThing("start")
      .tapError(err => ZIO.logActivity(BackgroundJobFailed(err.getMessage)))
      .catchAll(_ => ZIO.unit)
      .fork
      .unit

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundJob
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("my-service"),
      ServerLayers.serverAfterTelemetry,        // starts ZServer after telemetry is ready
      ServerLayers.resolvedRoutesLayer(app),    // injects TelemetryEnv into routes
      MyConfig.layer,
      MyServiceLive.layer
    )

  case class HealthCheckHit() extends InfoLog derives JsonCodec
  case class BackgroundJobFailed(message: String) extends ErrorLog derives JsonCodec
```

**Do not** define `ZServerStarting` in the server — it is already defined in `common.serverutils.ServerLayers` and emitted by `serverAfterTelemetry`.

### Health Endpoint (api module)

Every service has a health endpoint declared in `api`:

```scala
package app.myservice.api

import sttp.tapir.*

object HealthEndpoint:
  val healthEndpoint: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("health")
      .out(stringBody)
```

The server logic for it (tracing span, log, return "OK") is wired in `server`.
