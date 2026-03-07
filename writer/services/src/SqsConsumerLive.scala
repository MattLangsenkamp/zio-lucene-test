package app.writer.services

import app.ingestion.domain.IngestionEvent
import app.writer.domain.internal.SqsConsumerConfig
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio.sqs.{SqsStream, SqsStreamSettings}
import zio.stream.ZStream

final case class SqsConsumerLive(
    sqs: Sqs,
    config: SqsConsumerConfig,
    batchIndexer: BatchIndexer
) extends SqsConsumer:

  override def consume: Task[Unit] =
    Queue.bounded[IngestionEvent](1000).flatMap { queue =>
      val sqsFiber =
        SqsStream(
          queueUrl = config.queueUrl,
          settings = SqsStreamSettings.default.withMaxNumberOfMessages(10)
        )
          .tap(msg => parseAndEnqueue(queue)(msg))
          .run(SqsStream.deleteMessageBatchSink(config.queueUrl))
          .provide(ZLayer.succeed(sqs))
          .onExit(_ => queue.shutdown)
          .tapError(err => ZIO.logError(s"SQS stream error, reconnecting: ${err.toString}"))
          .retry(Schedule.spaced(5.seconds))
          .fork

      val indexFiber = batchIndexer.run(ZStream.fromQueue(queue)).fork

      sqsFiber.zip(indexFiber).flatMap { case (s, i) => s.join <&> i.join }
    }

  private def parseAndEnqueue(queue: Queue[IngestionEvent])(msg: Message.ReadOnly): Task[Unit] =
    msg.body.toOption match
      case None =>
        ZIO.logWarning("Received SQS message with no body")
      case Some(body) =>
        body.fromJson[IngestionEvent] match
          case Left(err) =>
            ZIO.logWarning(s"Failed to deserialize SQS message: $err (body: ${body.take(200)})")
          case Right(event) =>
            ZIO.logInfo(s"Received event: ${event.eventType.getOrElse("unknown")} - ${event.title.getOrElse("?")} by ${event.user.getOrElse("?")}") *>
              queue.offer(event).unit

object SqsConsumerLive:
  val layer: URLayer[Sqs & SqsConsumerConfig & BatchIndexer, SqsConsumer] =
    ZLayer.fromFunction(SqsConsumerLive.apply)
