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
    config: SqsConsumerConfig,
    batchIndexer: BatchIndexer
) extends SqsConsumer:

  override def consume: Task[Unit] =
    SqsStream(
      queueUrl = config.queueUrl,
      settings = SqsStreamSettings.default.withMaxNumberOfMessages(10)
    )
      .mapZIO(parseMessage)
      .collectSome
      .via(batchIndexer.pipeline)
      .run(SqsStream.deleteMessageBatchSink(config.queueUrl))
      .provide(ZLayer.succeed(sqs))
      .tapError(err => ZIO.logError(s"SQS stream error, reconnecting: ${err.toString}"))
      .retry(Schedule.spaced(5.seconds))

  private def parseMessage(msg: Message.ReadOnly): Task[Option[(Message.ReadOnly, IngestionEvent)]] =
    msg.body.toOption match
      case None =>
        ZIO.logWarning("Received SQS message with no body").as(None)
      case Some(body) =>
        body.fromJson[IngestionEvent] match
          case Left(err) =>
            ZIO.logWarning(s"Failed to deserialize SQS message: $err (body: ${body.take(200)})").as(None)
          case Right(event) =>
            ZIO.logInfo(s"Received event: ${event.eventType.getOrElse("unknown")} - ${event.title.getOrElse("?")} by ${event.user.getOrElse("?")}") *>
              ZIO.succeed(Some((msg, event)))

object SqsConsumerLive:
  val layer: URLayer[Sqs & SqsConsumerConfig & BatchIndexer, SqsConsumer] =
    ZLayer.fromFunction(SqsConsumerLive.apply)
