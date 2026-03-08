package app.writer.services

import app.writer.domain.internal.{CommitEvent, CommitQueueConfig}
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.SendMessageRequest

final case class CommitPublisherLive(
    sqs: Sqs,
    config: CommitQueueConfig
) extends CommitPublisher:

  override def publish(event: CommitEvent): Task[Unit] =
    sqs
      .sendMessage(
        SendMessageRequest(
          queueUrl = config.queueUrl,
          messageBody = event.toJson
        )
      )
      .mapError(_.toThrowable)
      .retry(CommitPublisherLive.retrySchedule)
      .tapError(e => ZIO.logError(s"Failed to publish CommitEvent after retries: ${e.getMessage}"))
      .unit
      .orElse(ZIO.unit)

object CommitPublisherLive:
  val retrySchedule: Schedule[Any, Any, Any] =
    Schedule.recurs(3) zip Schedule.exponential(100.millis)

  val layer: URLayer[Sqs & CommitQueueConfig, CommitPublisher] =
    ZLayer.fromFunction(CommitPublisherLive.apply)
