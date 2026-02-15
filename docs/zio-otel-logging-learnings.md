# ZIO OpenTelemetry Logging Integration: Learnings

## Problem Statement

We wanted `ZIO.logInfo("message")` calls inside HTTP route handlers to:
1. Be sent to the OpenTelemetry collector
2. Have the correct `traceId` attached for trace correlation

## The Root Cause

Logs **outside** routes worked fine (traceId + collector export), but logs **inside** Tapir routes had neither.

**Why?** `ZOpenTelemetry.logging(serviceName)` adds a logger via `ZIO.withLoggerScoped`, but route handlers run in fibers that don't inherit this scoped logger. We confirmed this by logging active loggers inside a route:

```scala
loggers <- ZIO.loggers
_ <- ZIO.logInfo(s"Active loggers: ${loggers.map(_.getClass.getName)}")
```

The OTel logger wasn't in the list.

## Solution: Middleware That Attaches Logger

We created `OtelLoggerMiddleware` that:
1. Creates an OTel-aware `ZLogger`
2. Uses `ZIO.withLogger(logger)` to attach it to each route handler
3. Wraps handlers in `tracing.extractSpan` for trace context propagation

## Challenge: Synchronous Logger, Async Context

`ZLogger.apply` is **synchronous**:

```scala
def apply(
    trace: Trace,
    fiberId: FiberId,
    logLevel: LogLevel,
    message: () => String,
    cause: Cause[Any],
    context: FiberRefs,  // Snapshot of fiber's state
    spans: List[LogSpan],
    annotations: Map[String, String]
): Unit  // Must be synchronous!
```

To attach the OTel `Context` (containing traceId/spanId), we need to read it from `ContextStorage`. But `ContextStorage.get` returns `UIO[Context]` - an effect we can't run synchronously without issues.

### Failed Approach: `runtime.unsafe.run`

```scala
val otelContext = Unsafe.unsafe { implicit unsafe =>
  runtime.unsafe.run(contextStorage.get).getOrElse(_ => Context.root())
}
```

**Problem**: `unsafe.run` creates a **new fiber** that doesn't inherit the current fiber's `FiberRef` state. So it always returns `Context.root()`.

### Working Approach: Direct FiberRef Access

The `context: FiberRefs` parameter passed to `ZLogger.apply` is a snapshot of the current fiber's state. If we have the actual `FiberRef[Context]` instance, we can read from it synchronously:

```scala
val otelContext = context.get(contextRef).getOrElse(Context.root())
```

## Understanding FiberRef

A `FiberRef[A]` is NOT just a key or just a mutable store - it's both:

- **Definition**: Holds initial value, fork semantics, join semantics
- **Per-fiber storage**: Each fiber has its own mutable value for that FiberRef

```
FiberRef[Context](initial = Context.root())
       ↓
Fiber 1: { ref → Context(traceId=abc) }  ← isolated
Fiber 2: { ref → Context(traceId=def) }  ← isolated
Fiber 3: { ref → Context(traceId=ghi) }  ← isolated
```

The `FiberRef` instance is shared (one object), but each fiber has its own value. This is why sharing `contextRef` across millions of requests is safe.

## The Reflection Problem

`ContextStorage` wraps a `FiberRef[Context]` but doesn't expose it:

```scala
private[opentelemetry] object ContextStorage {
  final class ZIOFiberRef(private[zio] val ref: FiberRef[Context]) extends ContextStorage
}
```

- `ZIOFiberRef` is `private[opentelemetry]`
- `ref` is `private[zio]`

We can't access it from `common.basetelemetry`.

### How zio-telemetry Does It

Their `Logging.scala` is inside the `opentelemetry` package, so they can pattern match:

```scala
ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef =>
    context.get(cs.ref).foreach(builder.setContext)  // Direct access!
  case _: ContextStorage.Native.type  =>
    builder.setContext(Context.current())
}
```

### Our Solution: Reflection (Once at Startup)

```scala
private[basetelemetry] object ContextStorageHelper:
  def extractFiberRef(storage: ContextStorage): Option[FiberRef[Context]] =
    try
      val field = storage.getClass.getDeclaredField("ref")
      field.setAccessible(true)
      Some(field.get(storage).asInstanceOf[FiberRef[Context]])
    catch
      case _: Exception => None
```

**Performance**: Reflection happens **once** at middleware initialization, not per-request. Per-request is just `fiberRefs.get(contextRef)` - a map lookup.

## Alternative: `zio-opentelemetry-zio-logging`

This official module provides `LogFormats` with `spanIdLabel` and `traceIdLabel` for log formatting. However, it:

- Adds traceId as **text labels** in log output
- Does NOT send logs to OTel collector via the logs bridge

If you need logs in the collector with trace correlation, you need the logs bridge approach.

## Recommended Architecture

Instead of having the middleware do reflection, create the logger in one place:

```scala
// OtelLoggerMiddleware - clean API, no reflection knowledge
def middleware(
    tracing: Tracing,
    logger: ZLogger[String, Unit]
): Middleware[Any]

// BaseTelemetry - handles reflection once during layer construction
val otelLoggerLayer: ZLayer[OpenTelemetry & ContextStorage, Nothing, ZLogger[String, Unit]] =
  ZLayer.fromZIO {
    for {
      otel       <- ZIO.service[OpenTelemetry]
      ctxStorage <- ZIO.service[ContextStorage]
      contextRef  = ContextStorageHelper.extractFiberRef(ctxStorage)
                      .getOrElse(throw new RuntimeException("Expected ZIOFiberRef"))
    } yield createOtelLogger(otel, contextRef)
  }
```

Benefits:
- Middleware has clean API - just takes a logger
- Reflection contained in layer construction
- Easier to test with mock loggers

## Key Takeaways

1. **Scoped loggers don't propagate to route handlers** - use `ZIO.withLogger` in middleware
2. **`unsafe.run` creates new fibers** - can't use it to read FiberRef values from current fiber
3. **`ZLogger.apply` receives `FiberRefs`** - use this to read fiber-local state synchronously
4. **FiberRef is shared key + per-fiber values** - safe for concurrent access
5. **Reflection is acceptable** - when done once at startup, not per-request
6. **zio-telemetry's logger works** - but needs internal package access we don't have

## References

- [zio-telemetry Logging.scala](https://github.com/zio/zio-telemetry/blob/series/2.x/opentelemetry/src/main/scala/zio/telemetry/opentelemetry/logging/Logging.scala)
- [ContextStorage.scala](https://github.com/zio/zio-telemetry/blob/series/2.x/opentelemetry/src/main/scala/zio/telemetry/opentelemetry/context/ContextStorage.scala)
- [FiberRef documentation](https://zio.dev/reference/state-management/fiberref/)
- [zio-opentelemetry-zio-logging](https://zio.dev/zio-telemetry/opentelemetry-zio-logging/)

---

## Changelog

### 2025-02-14: Deep Dive - The Real Root Cause (NettyRuntime)

After further investigation into the Tapir/ZIO HTTP source code, we identified the **true root cause**: it's not Tapir, it's ZIO HTTP's `NettyRuntime`.

#### NettyRuntime Captures FiberRefs at Startup

```scala
// From zio-http's NettyRuntime.scala
val live: ZLayer[Any, Nothing, NettyRuntime] = {
  ZLayer.fromZIO {
    ZIO.runtime[Any].map(new NettyRuntime(_))  // Captures FiberRefs HERE
  }
}
```

When `NettyRuntime` is created, it calls `ZIO.runtime` which snapshots the current fiber's `FiberRefs`. This snapshot is stored and used later.

#### Request Fibers Fork from Stale FiberRefs

When HTTP requests come in, new fibers are forked from this **stale snapshot**:

```scala
// From NettyRuntime.scala
def runOrFork[E, A](zio: ZIO[R, E, A]) = {
  val fiberRefs = self.fiberRefs.updatedAs(fiberId)(FiberRef.currentEnvironment, environment)
  val fiber = FiberRuntime[E, A](fiberId, fiberRefs.forkAs(fiberId), runtimeFlags)
  // New fiber inherits from NettyRuntime's captured FiberRefs, NOT current app fiber
}
```

The new request fiber's `FiberRefs` are forked from `self.fiberRefs` - the FiberRefs captured when NettyRuntime was created, **not** the current application fiber's FiberRefs.

#### The Timeline Problem

```
1. ZServer.default materializes
   └─→ NettyRuntime created
   └─→ ZIO.runtime captures FiberRefs (no OTel logger yet!)

2. BaseTelemetry.live materializes
   └─→ ZOpenTelemetry.logging runs
   └─→ ZIO.withLoggerScoped adds logger to app fiber's FiberRefs

3. HTTP request comes in
   └─→ NettyRuntime.runOrFork creates new fiber
   └─→ New fiber forked from NettyRuntime's STALE FiberRefs
   └─→ Logger not present!
```

#### Why Layer Reordering Doesn't Help

Even if you reorder layers to ensure `BaseTelemetry.live` runs before `ZServer.default`, it doesn't help because:

1. `NettyRuntime` captures its own runtime snapshot internally
2. The Scope in which logging is added is the application's Scope
3. NettyRuntime's captured FiberRefs are independent of subsequent FiberRef modifications

#### Conclusion: Middleware is the Correct Solution

The middleware approach (`OtelLoggerMiddleware`) is **not a hack** - it's the architecturally correct solution given ZIO HTTP's design.

ZIO HTTP's architecture deliberately does not propagate FiberRef changes from the main application fiber to request-handling fibers. Request fibers are forked from a pre-captured runtime snapshot for performance and isolation reasons.

Therefore, any per-request fiber-local state (like loggers) must be explicitly injected via middleware using `ZIO.withLogger`.

#### Alternative Approaches Considered

| Approach | Feasibility |
|----------|-------------|
| Reorder layers | ❌ Doesn't work - NettyRuntime captures independently |
| Custom ZLayer for Server | ❌ NettyRuntime still captures its own snapshot |
| Tapir interceptor | ✅ Works, but more complex than ZIO HTTP middleware |
| ZIO HTTP middleware with `ZIO.withLogger` | ✅ **Recommended** - simple and effective |
| Modify zio-http source | ❌ Requires upstream changes |

#### New References

- [zio-http NettyRuntime.scala](https://github.com/zio/zio-http/blob/main/zio-http/jvm/src/main/scala/zio/http/netty/NettyRuntime.scala)
- [Tapir ZioHttpInterpreter.scala](https://github.com/softwaremill/tapir/blob/master/server/zio-http-server/src/main/scala/sttp/tapir/server/ziohttp/ZioHttpInterpreter.scala)
