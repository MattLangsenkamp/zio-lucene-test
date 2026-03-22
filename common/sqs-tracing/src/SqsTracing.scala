package common.sqstracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.aws.sqs.model.{Message, MessageAttributeValue, SendMessageRequest}
import zio.prelude.data.Optional
import zio.sqs.SqsStreamSettings
import zio.sqs.producer.ProducerEvent
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

/** W3C TraceContext propagation for SQS producers and consumers.
  *
  * Span names follow OTel messaging semconv format: `"{operation} {queueName}"`.
  * See: https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/
  */
object SqsTracing:

  private val TraceparentKey = "traceparent"
  private val TracestateKey  = "tracestate"

  // OTel messaging semantic convention attribute keys
  private val MessagingSystem          = AttributeKey.stringKey("messaging.system")
  private val MessagingDestinationName = AttributeKey.stringKey("messaging.destination.name")
  private val MessagingMessageId       = AttributeKey.stringKey("messaging.message.id")
  private val MessagingOperationType   = AttributeKey.stringKey("messaging.operation.type")

  private def queueName(queueUrl: String): String =
    queueUrl.split('/').lastOption.getOrElse(queueUrl)

  private def buildAttributes(queueUrl: String, operation: String, messageId: Option[String] = None): Attributes =
    val b = Attributes.builder()
      .put(MessagingSystem, "aws_sqs")
      .put(MessagingDestinationName, queueName(queueUrl))
      .put(MessagingOperationType, operation)
    messageId.foreach(id => b.put(MessagingMessageId, id))
    b.build()

  private def extractString(
    attrs: Optional[Map[String, MessageAttributeValue.ReadOnly]],
    key: String
  ): Option[String] =
    attrs.toOption
      .flatMap(_.get(key))
      .flatMap(_.stringValue.toOption)

  private def buildIncomingCarrier(
    attrs: Optional[Map[String, MessageAttributeValue.ReadOnly]]
  ): mutable.Map[String, String] =
    val m = mutable.Map.empty[String, String]
    extractString(attrs, TraceparentKey).foreach(m.put(TraceparentKey, _))
    extractString(attrs, TracestateKey).foreach(m.put(TracestateKey, _))
    m

  private def carrierToMessageAttributes(
    kernel: mutable.Map[String, String]
  ): Map[String, MessageAttributeValue] =
    kernel.collect { case (k, v) if v.nonEmpty =>
      k -> MessageAttributeValue(stringValue = Optional.Present(v), dataType = "String")
    }.toMap

  // ─── Consumer ─────────────────────────────────────────────────────────────────

  /** Extracts W3C trace context from the message's MessageAttributes, creates a CONSUMER span as a
    * child of the upstream producer span, and runs `effect` within it. Falls back to a fresh root
    * span if no valid `traceparent` is present.
    *
    * @param tracing
    *   the Tracing service instance
    * @param msg
    *   the SQS message being processed
    * @param queueUrl
    *   used to derive the span name and populate messaging span attributes
    * @param effect
    *   the processing logic to run within the span
    */
  def withConsumerSpan[R, E, A](
    tracing: Tracing,
    msg: Message.ReadOnly,
    queueUrl: String
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    val spanName = s"process ${queueName(queueUrl)}"
    val msgId    = msg.messageId.toOption
    val attrs    = buildAttributes(queueUrl, "process", msgId)
    val carrier  = buildIncomingCarrier(msg.messageAttributes)
    tracing.extractSpan(
      TraceContextPropagator.default,
      IncomingContextCarrier.default(carrier),
      spanName,
      SpanKind.CONSUMER,
      attrs
    )(effect)

  /** Returns a copy of `settings` with "traceparent" and "tracestate" added to
    * messageAttributeNames. Must be applied or the attributes will not be returned by SQS.
    */
  def tracingSettings(settings: SqsStreamSettings): SqsStreamSettings =
    settings.withMessageAttributeNames(List(TraceparentKey, TracestateKey))

  // ─── Low-level Producer (SendMessageRequest) ──────────────────────────────────

  /** Creates a PRODUCER span as a child of the current active span (or a root span if none),
    * injects the trace context into the request's MessageAttributes as `traceparent`/`tracestate`,
    * then calls `send` with the enriched request.
    *batchIndexer.pipeline
    * @param tracing
    *   the Tracing service instance
    * @param request
    *   the original SendMessageRequest (queueUrl is used for span attributes)
    * @param send
    *   the actual send effect; receives the trace-enriched request
    */
  def withProducerSpan[R, E, A](
    tracing: Tracing,
    request: SendMessageRequest
  )(send: SendMessageRequest => ZIO[R, E, A]): ZIO[R, E, A] =
    val spanName = s"publish ${queueName(request.queueUrl)}"
    val attrs    = buildAttributes(request.queueUrl, "publish")
    tracing.span(spanName, SpanKind.PRODUCER, attrs):
      val carrier = OutgoingContextCarrier.default(mutable.Map.empty[String, String])
      tracing.injectSpan(TraceContextPropagator.default, carrier) *>
        send(
          request.copy(
            messageAttributes = Optional.Present(
              request.messageAttributes.toOption.getOrElse(Map.empty) ++
                carrierToMessageAttributes(carrier.kernel)
            )
          )
        )

  // ─── Batch Producer (ProducerEvent) ───────────────────────────────────────────

  /** Creates a PRODUCER span as a child of the current active span (or a root span if none),
    * injects the trace context into the ProducerEvent's attributes map, then calls `send` with the
    * enriched event.
    *
    * @param tracing
    *   the Tracing service instance
    * @param event
    *   the original ProducerEvent
    * @param queueUrl
    *   used to derive the span name and populate messaging span attributes
    * @param send
    *   the actual send effect; receives the trace-enriched event
    */
  def withProducerEventSpan[T, R, E, A](
    tracing: Tracing,
    event: ProducerEvent[T],
    queueUrl: String
  )(send: ProducerEvent[T] => ZIO[R, E, A]): ZIO[R, E, A] =
    val spanName = s"publish ${queueName(queueUrl)}"
    val attrs    = buildAttributes(queueUrl, "publish")
    tracing.span(spanName, SpanKind.PRODUCER, attrs):
      val carrier = OutgoingContextCarrier.default(mutable.Map.empty[String, String])
      tracing.injectSpan(TraceContextPropagator.default, carrier) *>
        send(event.copy(attributes = event.attributes ++ carrierToMessageAttributes(carrier.kernel)))
