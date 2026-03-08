package app.writer.services

import app.writer.domain.internal.{CommitEvent, CommitQueueConfig}
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.SendMessageRequest
import common.activitylogging.*

final case class CommitPublisherLive(
    sqs: Sqs,
    config: CommitQueueConfig
) extends CommitPublisher:

  override def publish(event: CommitEvent): Task[Unit] =
    sqs
      .sendMessage(
        SendMessageRequest(
          queueUrl = config.commitQueueUrl,
          messageBody = event.toJson
        )
      )
      .mapError(_.toThrowable)
      .retry(CommitPublisherLive.retrySchedule)
      .tapError(e => ZIO.logActivity(CommitPublisherLive.CommitPublishFailed(e.getMessage)))
      .unit
      .orElse(ZIO.unit)

object CommitPublisherLive:
  case class CommitPublishFailed(message: String) extends ErrorLog derives JsonCodec

  val retrySchedule: Schedule[Any, Any, Any] =
    Schedule.recurs(3) zip Schedule.exponential(100.millis)

  val layer: URLayer[Sqs & CommitQueueConfig, CommitPublisher] =
    ZLayer.fromFunction(CommitPublisherLive.apply)
