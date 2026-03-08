package app.writer.services

import app.ingestion.domain.IngestionEvent
import app.writer.domain.internal.SqsConsumerConfig
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.aws.sqs.model.Message
import zio.sqs.{SqsStream, SqsStreamSettings}
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind
import common.activitylogging.*

final case class SqsConsumerLive(
    sqs: Sqs,
    config: SqsConsumerConfig,
    batchIndexer: BatchIndexer,
    tracing: Tracing
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
      .tapError(err => ZIO.logActivity(SqsConsumerLive.SqsStreamError(err.toString)))
      .retry(Schedule.spaced(5.seconds))

  private def parseMessage(msg: Message.ReadOnly): Task[Option[(Message.ReadOnly, IngestionEvent)]] =
    tracing.span("sqs-message-received", SpanKind.CONSUMER):
      msg.body.toOption match
        case None =>
          ZIO.logActivity(SqsConsumerLive.SqsMessageNoBody()).as(None)
        case Some(body) =>
          body.fromJson[IngestionEvent] match
            case Left(err) =>
              ZIO.logActivity(SqsConsumerLive.SqsDeserializationError(err, body.take(200))).as(None)
            case Right(event) =>
              ZIO.logActivity(SqsConsumerLive.SqsEventReceived(
                eventType = event.eventType.getOrElse("unknown"),
                title     = event.title.getOrElse("?"),
                user      = event.user.getOrElse("?")
              )) *> ZIO.succeed(Some((msg, event)))

object SqsConsumerLive:
  case class SqsStreamError(message: String)                             extends ErrorLog derives JsonCodec
  case class SqsMessageNoBody()                                           extends WarnLog derives JsonCodec
  case class SqsDeserializationError(error: String, body: String)        extends WarnLog derives JsonCodec
  case class SqsEventReceived(eventType: String, title: String, user: String) extends InfoLog derives JsonCodec

  val layer: URLayer[Sqs & SqsConsumerConfig & BatchIndexer & Tracing, SqsConsumer] =
    ZLayer.fromFunction(SqsConsumerLive.apply)
