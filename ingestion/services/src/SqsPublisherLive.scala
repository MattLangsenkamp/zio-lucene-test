package app.ingestion.services

import app.ingestion.domain.IngestionEvent
import app.ingestion.domain.internal.SqsPublisherConfig
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.SendMessageRequest
import zio.telemetry.opentelemetry.metrics.{Counter, Meter}
import zio.telemetry.opentelemetry.tracing.Tracing
import common.activitylogging.*
import common.sqstracing.SqsTracing

final case class SqsPublisherLive(
    sqs: Sqs,
    config: SqsPublisherConfig,
    publishFailureCounter: Counter[Long],
    tracing: Tracing
) extends SqsPublisher:

  override def publish(event: IngestionEvent): Task[Unit] =
    SqsTracing.withProducerSpan(tracing, SendMessageRequest(queueUrl = config.sqsQueueUrl, messageBody = event.toJson)):
      req => sqs.sendMessage(req).mapError(_.toThrowable)
    .retry(Schedule.recurs(3) zip Schedule.exponential(100.millis))
    .tapError(e =>
      ZIO.logActivity(SqsPublisherLive.SqsPublishFailed(e.getMessage)) *>
        publishFailureCounter.add(1).ignore
    )
    .unit
    .orElse(ZIO.unit)

object SqsPublisherLive:
  case class SqsPublishFailed(message: String) extends ErrorLog derives JsonCodec

  val layer: RLayer[Sqs & SqsPublisherConfig & Meter & Tracing, SqsPublisher] =
    ZLayer.fromZIO:
      for
        sqs     <- ZIO.service[Sqs]
        config  <- ZIO.service[SqsPublisherConfig]
        meter   <- ZIO.service[Meter]
        tracing <- ZIO.service[Tracing]
        counter <- meter.counter("sqs.publish.failure")
      yield SqsPublisherLive(sqs, config, counter, tracing)
