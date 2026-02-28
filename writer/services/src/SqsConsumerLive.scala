package app.writer.services

import app.ingestion.domain.IngestionEvent
import app.writer.domain.internal.SqsConsumerConfig
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio.sqs.{SqsStream, SqsStreamSettings}

final case class SqsConsumerLive(
    sqs: Sqs,
    config: SqsConsumerConfig
) extends SqsConsumer:

  override def consume: Task[Unit] =
    SqsStream(
      queueUrl = config.queueUrl,
      settings = SqsStreamSettings.default.withMaxNumberOfMessages(10)
    )
      .tap(processMessage)
      .run(SqsStream.deleteMessageBatchSink(config.queueUrl))
      .provide(ZLayer.succeed(sqs))
      .tapError(err => ZIO.logError(s"SQS stream error, reconnecting: ${err.toString}"))
      .retry(Schedule.spaced(5.seconds))

  private def processMessage(msg: Message.ReadOnly): Task[Unit] =
    msg.body.toOption match
      case None =>
        ZIO.logWarning("Received SQS message with no body")
      case Some(body) =>
        body.fromJson[IngestionEvent] match
          case Left(err) =>
            ZIO.logWarning(s"Failed to deserialize SQS message: $err (body: ${body.take(200)})")
          case Right(event) =>
            ZIO.logInfo(
              s"[${event.source}] " +
                s"title=${event.title.getOrElse("?")} " +
                s"user=${event.user.getOrElse("?")} " +
                s"bot=${event.isBot.getOrElse(false)} " +
                s"wiki=${event.wiki.getOrElse("?")} " +
                s"ts=${event.timestamp.getOrElse("?")}"
            )

object SqsConsumerLive:
  val layer: URLayer[Sqs & SqsConsumerConfig, SqsConsumer] =
    ZLayer.fromFunction(SqsConsumerLive.apply)
