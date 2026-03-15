# SQS Trace Middleware Spec
**Ticket:** ZIO-22
**Branch:** `mattlangsenkamp/zio-22-sqs-trace-middleware`

---

## Overview

A new `common/sqs-tracing` module providing W3C TraceContext propagation over SQS messages.
It wraps both the producer (send) and consumer (receive) sides, propagating `traceparent`/`tracestate`
via SQS MessageAttributes, and building on the existing `zio-opentelemetry:3.1.14` already in the project.

---

## Background: What zio-sqs Provides (and Doesn't)

`zio-sqs 0.12.2` has **no interceptor/middleware/hook pattern**. Its API surface is:

- **Consumer:** `SqsStream(queueUrl, settings)` → `ZStream[Sqs, Throwable, Message.ReadOnly]`
  - `Message.ReadOnly` has `messageAttributes: Optional[Map[String, MessageAttributeValue.ReadOnly]]`
    — note: `Optional` is `zio.prelude.data.Optional`, not `scala.Option`; use `.toOption` to convert
  - `SqsStreamSettings` has `messageAttributeNames: List[String]` — SQS only returns custom attributes
    that are explicitly named here. **If `"traceparent"` is not listed, it will not come back in messages.**

- **Producer (low-level):** `Sqs.sendMessage(SendMessageRequest(...))` — used in our codebase today
  - `SendMessageRequest` has `messageAttributes: Optional[Map[String, MessageAttributeValue]]`
  - `MessageAttributeValue` takes `dataType: String` (required, must be `"String"`) and
    `stringValue: Optional[String]` (use `Optional.Present(v)`, not `Some(v)`)

- **Producer (batch, higher-level):** `Producer[T]` / `ProducerEvent[T]`
  - `ProducerEvent` has `attributes: Map[String, MessageAttributeValue]` passed through to the batch request

**There is no message-level hook point** — the middleware wraps at the call site.

**Note:** `zio-aws-core` exposes `AwsCallAspect`, a `ZIOAspect` that wraps raw AWS API calls at the
transport level. It is **not suitable** for message-attribute-level trace propagation (it operates on
`IO[AwsError, Described[_]]`, below the `Message`/`SendMessageRequest` abstraction), but is useful
for call-level latency metrics or logging if needed separately.

---

## W3C TraceContext Propagation over SQS

**W3C TraceContext** defines two headers:
- `traceparent`: `00-{traceId}-{spanId}-{flags}` (e.g. `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`)
- `tracestate`: optional vendor-specific data (e.g. `rojo=00f067aa0ba902b7,congo=t61rcWkgMzE`)

For SQS, these are carried as **MessageAttributes** with `dataType = "String"`:

```
MessageAttributes: {
  "traceparent": { DataType: "String", StringValue: "00-4bf92f3...36-00f067...b7-01" }
  "tracestate":  { DataType: "String", StringValue: "rojo=00f067..." }
}
```

`zio-opentelemetry` provides:
- `TraceContextPropagator.default` — W3C Trace Context propagator (wraps `W3CTraceContextPropagator.getInstance()`)
- `OutgoingContextCarrier.default(mutable.Map.empty)` — carrier for injection; populate from current span
- `IncomingContextCarrier.default(mutable.Map(...))` — carrier for extraction; seed from message attributes
- `Tracing.injectSpan(propagator, carrier)` — writes current span context into carrier
- `Tracing.extractSpan(propagator, carrier, spanName, spanKind, attributes)(effect)` — restores parent span, wraps effect
- `Tracing.root(spanName, spanKind, attributes)(effect)` — creates a new root span (no parent)

---

## Module Definition

**Location:** `common/sqs-tracing/src/SqsTracing.scala`

**build.mill addition:**

```scala
/** W3C TraceContext propagation for SQS producers and consumers. */
object `sqs-tracing` extends AppModule {
  def moduleDeps = super.moduleDeps ++ Seq(`aws-sqs`, `base-telemetry`)
  def mvnDeps    = zioDeps ++ otelSdkDeps ++ Seq(mvn"dev.zio::zio-sqs:0.12.2")

  object test extends ScalaTests with TestModule.ZioTest {
    def mvnDeps = zioTestDeps
  }
}
```

Services that need SQS tracing add `common.`sqs-tracing`` to their `moduleDeps`.

**Package:** `common.sqstracing`

---

## Public API

The API is symmetric: both sides own their span boundary. Span names default to OTel messaging
semconv format `"{operation} {queueName}"` where `queueName` is derived from the last path segment
of the queue URL (e.g. `"publish ingestion-queue"`, `"process ingestion-queue"`).

### `SqsTracing` object

```scala
object SqsTracing:

  // ─── Consumer ──────────────────────────────────────────────────────────────

  /**
   * Extracts W3C trace context from the message's MessageAttributes, creates a CONSUMER
   * span as a child of the upstream producer span, and runs `effect` within it.
   * Falls back to a fresh root span if no valid `traceparent` is present.
   *
   * @param msg      the SQS message being processed
   * @param queueUrl used to derive the span name and populate messaging span attributes
   * @param effect   the processing logic to run within the span
   */
  def withConsumerSpan[R, E, A](
    msg: Message.ReadOnly,
    queueUrl: String
  )(effect: ZIO[R, E, A]): ZIO[Tracing & R, E, A]

  /**
   * Returns a copy of `settings` with "traceparent" and "tracestate" added to
   * messageAttributeNames. Must be applied or the attributes will not be returned by SQS.
   */
  def tracingSettings(settings: SqsStreamSettings): SqsStreamSettings

  // ─── Low-level Producer (SendMessageRequest) ────────────────────────────────

  /**
   * Creates a PRODUCER span, injects the current trace context into the request's
   * MessageAttributes as `traceparent`/`tracestate`, then calls `send` with the
   * enriched request. The span ends when `send` completes.
   *
   * @param request the original SendMessageRequest (queueUrl is read for span attributes)
   * @param send    the actual send effect, receives the trace-enriched request
   */
  def withProducerSpan[R, E, A](
    request: SendMessageRequest
  )(send: SendMessageRequest => ZIO[R, E, A]): ZIO[Tracing & R, E, A]

  // ─── Batch Producer (ProducerEvent) ─────────────────────────────────────────

  /**
   * Creates a PRODUCER span, injects the current trace context into the ProducerEvent's
   * attributes map, then calls `send` with the enriched event.
   *
   * @param event    the original ProducerEvent
   * @param queueUrl used to derive the span name and populate messaging span attributes
   * @param send     the actual send effect, receives the trace-enriched event
   */
  def withProducerEventSpan[T, R, E, A](
    event: ProducerEvent[T],
    queueUrl: String
  )(send: ProducerEvent[T] => ZIO[R, E, A]): ZIO[Tracing & R, E, A]
```

---

## Span Naming and Attributes (OTel Semantic Conventions)

Follows the [OTel messaging span conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/).

**Span name format:** `"{operation} {queueName}"` where `queueName` is the last path segment of the
queue URL (e.g. `"publish ingestion-queue"`, `"process ingestion-queue"`). Derived internally from
`queueUrl` — no caller override needed.

**Span attributes set on every span:**

| Attribute                    | Value                                              | Notes                            |
|------------------------------|----------------------------------------------------|----------------------------------|
| `messaging.system`           | `"aws_sqs"`                                        | constant                         |
| `messaging.destination.name` | last path segment of the queue URL                 | e.g. `"ingestion-queue"`         |
| `messaging.message.id`       | `msg.messageId.toOption.getOrElse("")`             | consumer spans only              |
| `messaging.operation.type`   | `"publish"` (producer) / `"process"` (consumer)   |                                  |

Use `MessagingAttributes` / `MessagingIncubatingAttributes` from
`io.opentelemetry.semconv` (`opentelemetry-semconv:1.29.0-alpha`, already on classpath via `otelSdkDeps`).

---

## Implementation Notes

### Consumer: extracting trace context

```scala
// Pseudocode — illustrates the approach
def withConsumerSpan[R, E, A](msg, queueUrl, spanName)(effect) =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    val attrs   = messagingAttributes(queueUrl, msg.messageId, "process")
    val carrier = buildIncomingCarrier(msg.messageAttributes)  // Map[String, String]

    if carrier has "traceparent" then
      tracing.extractSpan(TraceContextPropagator.default, IncomingContextCarrier.default(carrier),
        spanName, SpanKind.CONSUMER, attrs)(effect)
    else
      tracing.root(spanName, SpanKind.CONSUMER, attrs)(effect)
  }
```

- `buildIncomingCarrier` reads `messageAttributes`, extracts string values for `"traceparent"` and `"tracestate"` into a `mutable.Map[String, String]`
- The check for presence of `"traceparent"` is important — a missing key or empty value must fall through to `root`

### Producer: injecting trace context

```scala
// Pseudocode
def withProducerSpan[R, E, A](request)(send) =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    val spanName = s"publish ${queueNameFromUrl(request.queueUrl)}"
    val attrs    = messagingAttributes(request.queueUrl, "publish")
    tracing.span(spanName, SpanKind.PRODUCER, attrs):
      val carrier = OutgoingContextCarrier.default(mutable.Map.empty)
      tracing.injectSpan(TraceContextPropagator.default, carrier) *>
        send(
          request.copy(
            messageAttributes = Optional.Present(
              request.messageAttributes.toOption.getOrElse(Map.empty) ++
                carrierToMessageAttributes(carrier.kernel)
            )
          )
        )
  }
```

- `tracing.span` is used (not `root`) so the PRODUCER span becomes a child of whatever span is
  currently active in the fiber — preserving the full trace chain if the publish is called from
  within an HTTP request, batch job, or any other parent span. It naturally becomes a root only
  when there is no active span.
- `carrierToMessageAttributes` converts `Map[String, String]` → `Map[String, MessageAttributeValue]`
  with `dataType = "String"` and `stringValue = Optional.Present(value)` (zio-prelude `Optional`, not `scala.Option`)
- The outgoing carrier starts empty; `injectSpan` populates `"traceparent"` (and `"tracestate"` if non-empty)
- `tracestate` empty string: filter out before converting to message attributes

### Required settings change for consumers

Consumers **must** call `SqsTracing.tracingSettings(settings)` to receive trace attributes:

```scala
SqsStream(
  queueUrl = config.sqsQueueUrl,
  settings = SqsTracing.tracingSettings(
    SqsStreamSettings.default.withMaxNumberOfMessages(10)
  )
)
```

---

## Integration with Existing Services

### Ingestion `SqsPublisherLive`

```scala
override def publish(event: IngestionEvent): ZIO[Tracing, Throwable, Unit] =
  SqsTracing.withProducerSpan(
    SendMessageRequest(queueUrl = config.sqsQueueUrl, messageBody = event.toJson)
  )(req => sqs.sendMessage(req).mapError(_.toThrowable))
    .retry(Schedule.recurs(3) zip Schedule.exponential(100.millis))
    .tapError(e => ZIO.logActivity(SqsPublisherLive.SqsPublishFailed(e.getMessage)) *> publishFailureCounter.add(1).ignore)
    .unit
    .orElse(ZIO.unit)
```

### Writer `SqsConsumerLive`

```scala
// Replace the existing tracing.span("sqs-message-received", ...) with withConsumerSpan
private def parseMessage(msg: Message.ReadOnly): Task[Option[(Message.ReadOnly, IngestionEvent)]] =
  SqsTracing.withConsumerSpan(msg, config.sqsQueueUrl):
    // ... existing parse logic unchanged

// Also update SqsStream settings to receive trace attributes:
SqsStream(
  queueUrl = config.sqsQueueUrl,
  settings = SqsTracing.tracingSettings(SqsStreamSettings.default.withMaxNumberOfMessages(10))
)
```

### Writer `CommitPublisherLive`

```scala
override def publish(event: CommitEvent): ZIO[Tracing, Throwable, Unit] =
  SqsTracing.withProducerSpan(
    SendMessageRequest(queueUrl = config.commitQueueUrl, messageBody = event.toJson)
  )(req => sqs.sendMessage(req).mapError(_.toThrowable))
    .retry(retrySchedule)
    .tapError(e => ZIO.logActivity(CommitPublisherLive.CommitPublishFailed(e.getMessage)))
    .unit
    .orElse(ZIO.unit)
```

---

## Constraints and Edge Cases

| Concern | Handling |
|---------|----------|
| SQS max 10 message attributes per message | Our middleware adds at most 2 (`traceparent`, `tracestate`). Leave 8 for application use. Document this limit. |
| FIFO queues with content-based deduplication | Adding new message attributes changes the content hash. **Callers using content-based dedup must be aware.** |
| `tracestate` empty string | If the carrier produces an empty `tracestate`, omit it from the message attributes entirely. |
| Trace injection/extraction failure | Must never fail the main operation. All tracing calls are `URIO` or wrapped in `.orDie` / `.ignore` as appropriate. |
| `messageAttributeNames` not set | If `tracingSettings` is not called, message attributes are not returned by SQS and context is silently lost. Consider logging a warning at startup. |
| `traceparent` malformed | `W3CTraceContextPropagator` silently ignores invalid values and produces an invalid `SpanContext`; `extractSpan` in that case creates a root span — acceptable behavior. |
| No active span on producer side | `injectSpan` with no active span writes an invalid/empty `traceparent`. The consumer will detect an invalid context and fall back to `root`. |

---

## Resolved Decisions

| Question | Decision |
|----------|----------|
| Span naming convention | OTel semconv format: `"{operation} {queueName}"`, derived internally from the queue URL. No caller override. |
| FIFO dedup variant | No — not needed now. Callers with content-based dedup must use explicit deduplication IDs if needed. |
| `tracestate` passthrough | Handled automatically by `W3CTraceContextPropagator` — no special logic needed. |
| Baggage propagation | Deferred. |
| Producer span ownership | **Option B** — `withProducerSpan` owns the PRODUCER span, symmetric with `withConsumerSpan`. |

---

## Out of Scope

- Kafka / SNS propagation (separate tickets if needed)
- Auto-instrumentation of the underlying AWS SDK HTTP calls
- Sampling configuration (inherits the global sampler already configured in `BaseTelemetry`)
