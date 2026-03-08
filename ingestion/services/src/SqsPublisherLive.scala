package app.ingestion.services

import app.ingestion.domain.IngestionEvent
import app.ingestion.domain.internal.SqsPublisherConfig
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.SendMessageRequest
import zio.telemetry.opentelemetry.metrics.{Counter, Meter}
import common.activitylogging.*

final case class SqsPublisherLive(
    sqs: Sqs,
    config: SqsPublisherConfig,
    publishFailureCounter: Counter[Long]
) extends SqsPublisher:

  override def publish(event: IngestionEvent): Task[Unit] =
    sqs
      .sendMessage(
        SendMessageRequest(
          queueUrl = config.sqsQueueUrl,
          messageBody = event.toJson
        )
      )
      .mapError(_.toThrowable)
      .retry(Schedule.recurs(3) zip Schedule.exponential(100.millis))
      .tapError(e =>
        ZIO.logActivity(SqsPublisherLive.SqsPublishFailed(e.getMessage)) *>
          publishFailureCounter.add(1).ignore
      )
      .unit
      .orElse(ZIO.unit)

object SqsPublisherLive:
  case class SqsPublishFailed(message: String) extends ErrorLog derives JsonCodec

  val layer: RLayer[Sqs & SqsPublisherConfig & Meter, SqsPublisher] =
    ZLayer.fromZIO:
      for
        sqs     <- ZIO.service[Sqs]
        config  <- ZIO.service[SqsPublisherConfig]
        meter   <- ZIO.service[Meter]
        counter <- meter.counter("sqs.publish.failure")
      yield SqsPublisherLive(sqs, config, counter)
