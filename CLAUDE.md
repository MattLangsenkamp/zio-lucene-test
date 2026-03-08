# Project Conventions

## Activity Logging

Always use `ZIO.logActivity(...)` instead of `ZIO.logInfo(...)` / `ZIO.logWarning(...)` / etc. for logging observable application events. Logs are data — model them as typed case classes, not strings.

### How it works

`ZIO.logActivity` is an extension method defined in `common/activity-logging`. It requires the event type to extend `ActivityLog` (via a level marker trait) and have a `JsonCodec`. The event is serialized to JSON and routed to the correct ZIO log level automatically.

```scala
// Define the event
case class HealthCheckHit(path: String) extends InfoLog derives JsonCodec

// Log it
ZIO.logActivity(HealthCheckHit("/health"))
```

### Log level marker traits

Pick the trait that matches the severity of the event:

| Trait      | Level   |
|------------|---------|
| `TraceLog` | Trace   |
| `DebugLog` | Debug   |
| `InfoLog`  | Info    |
| `WarnLog`  | Warning |
| `ErrorLog` | Error   |
| `FatalLog` | Fatal   |

### Where to define activity case classes

Define activity case classes in the **companion object of the service or handler that emits them**. This keeps the event definition close to where it is produced, making it easy to see at a glance what a service logs.

```scala
// services/src/HealthService.scala
object HealthService:
  case class HealthCheckHit(path: String) extends InfoLog derives JsonCodec

final case class HealthServiceLive(...) extends HealthService:
  def check(path: String): UIO[Unit] =
    ZIO.logActivity(HealthService.HealthCheckHit(path))
```

Some activity types are shared across multiple services (e.g. a cross-cutting audit event). In those cases it is acceptable to define them in a reusable location such as a `domainPublic` module, but this should be the exception — prefer colocation by default.

### What not to do

```scala
// Bad — loose string, no structure
ZIO.logInfo("Health check hit /health")

// Bad — toString output, not JSON
ZIO.logInfo(myEvent.toString)
```
