package app.writer.services

import app.writer.domain.internal.{CommitEvent, CommitQueueConfig}
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.SendMessageRequest
import zio.telemetry.opentelemetry.tracing.Tracing
import common.activitylogging.*
import common.sqstracing.SqsTracing

final case class CommitPublisherLive(
    sqs: Sqs,
    config: CommitQueueConfig,
    tracing: Tracing
) extends CommitPublisher:

  override def publish(event: CommitEvent): Task[Unit] =
    SqsTracing.withProducerSpan(tracing, SendMessageRequest(queueUrl = config.commitQueueUrl, messageBody = event.toJson)):
      req => sqs.sendMessage(req).mapError(_.toThrowable)
    .retry(CommitPublisherLive.retrySchedule)
    .tapError(e => ZIO.logActivity(CommitPublisherLive.CommitPublishFailed(e.getMessage)))
    .unit
    .orElse(ZIO.unit)

object CommitPublisherLive:
  case class CommitPublishFailed(message: String) extends ErrorLog derives JsonCodec

  val retrySchedule: Schedule[Any, Any, Any] =
    Schedule.recurs(3) zip Schedule.exponential(100.millis)

  val layer: URLayer[Sqs & CommitQueueConfig & Tracing, CommitPublisher] =
    ZLayer.fromFunction(CommitPublisherLive.apply)
