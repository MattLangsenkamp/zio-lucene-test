package app.ingestion.services

import app.ingestion.domain.internal.{WikimediaStreamsSpec, WikipediaEvent, WikipediaStreamConfig}
import zio.*
import zio.json.*
import zio.stream.*
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.capabilities.zio.ZioStreams
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Counter, Meter}
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import common.activitylogging.*

final case class WikipediaStreamServiceLive(
    config: WikipediaStreamConfig,
    backend: SttpBackend[Task, ZioStreams],
    tracing: Tracing,
    meter: Meter,
    sqsPublisher: SqsPublisher
) extends WikipediaStreamService:

  private val UserAgent = "zio-lucene-test/1.0 (https://github.com/MattLangsenkamp/zio-lucene-test)"

  override def consumeStream: Task[Unit] =
    for
      _                 <- ZIO.logActivity(WikipediaStreamServiceLive.StreamConfigLoaded(config.wikiLang, config.wikiStream))
      _                 <- validateStreamExists
      deserErrorCounter <- meter.counter("wikipedia.deserialization.error")
      reconnectCounter  <- meter.counter("wikipedia.reconnection.attempt")
      _                 <- ZIO.logActivity(WikipediaStreamServiceLive.StreamConsumerStarting(config.streamUrl, config.expectedServerName))
      _                 <- connectAndConsume(deserErrorCounter, reconnectCounter)
    yield ()

  private def validateStreamExists: Task[Unit] =
    tracing.span("validate-stream-exists", SpanKind.CLIENT):
      for
        _ <- ZIO.logActivity(WikipediaStreamServiceLive.FetchingStreamsSpec(WikimediaStreamsSpec.specUrl))
        response <- basicRequest
          .get(uri"${WikimediaStreamsSpec.specUrl}")
          .header("User-Agent", UserAgent)
          .response(asJson[WikimediaStreamsSpec])
          .send(backend)
        spec <- ZIO.fromEither(response.body)
          .mapError(err => new RuntimeException(s"Failed to parse Wikimedia streams spec: $err"))
        availableStreams <- ZIO.fromOption(WikimediaStreamsSpec.extractAvailableStreams(spec))
          .orElseFail(new RuntimeException("Could not extract available streams from spec"))
        _ <- ZIO.when(!availableStreams.contains(config.wikiStream)):
          ZIO.fail(new RuntimeException(
            s"Stream '${config.wikiStream}' not found. Available: ${availableStreams.mkString(", ")}"
          ))
        _ <- ZIO.logActivity(WikipediaStreamServiceLive.StreamValidated(config.wikiStream))
      yield ()

  private def connectAndConsume(
      deserErrorCounter: Counter[Long],
      reconnectCounter: Counter[Long]
  ): Task[Unit] =
    consumeOnce(deserErrorCounter)
      .tapError: err =>
        ZIO.logActivity(WikipediaStreamServiceLive.StreamConnectionLost(err.getMessage)) *>
          reconnectCounter.add(1)
      .retry(reconnectionSchedule)

  private def consumeOnce(deserErrorCounter: Counter[Long]): Task[Unit] =
    tracing.span("consume-stream", SpanKind.CLIENT):
      for
        _ <- ZIO.logActivity(WikipediaStreamServiceLive.ConnectingToStream(config.streamUrl))
        response <- basicRequest
          .get(uri"${config.streamUrl}")
          .header("User-Agent", UserAgent)
          .header("Accept", "application/json")
          .response(asStreamAlwaysUnsafe(ZioStreams))
          .send(backend)
        _ <- response.body
          .via(ZPipeline.utf8Decode)
          .via(ZPipeline.splitLines)
          .filter(_.nonEmpty)
          .mapZIO(line => processLine(line, deserErrorCounter))
          .runDrain
      yield ()

  private def processLine(line: String, deserErrorCounter: Counter[Long]): Task[Unit] =
    line.fromJson[WikipediaEvent] match
      case Left(error) =>
        val errorType = if line.trim.startsWith("{") then "schema_mismatch" else "malformed_json"
        ZIO.logActivity(WikipediaStreamServiceLive.DeserializationError(error, line.take(200))) *>
          deserErrorCounter.add(1, Attributes.of(AttributeKey.stringKey("error_type"), errorType))
      case Right(event) if WikipediaEvent.isCanary(event) =>
        ZIO.unit
      case Right(event) if !WikipediaEvent.matchesServer(event, config.expectedServerName) =>
        ZIO.unit
      case Right(event) =>
        ZIO.logActivity(WikipediaStreamServiceLive.WikipediaEventReceived(
          eventType = event.eventType.getOrElse("unknown"),
          title     = event.title.getOrElse("?"),
          user      = event.user.getOrElse("?"),
          bot       = event.bot.getOrElse(false),
          wiki      = event.wiki.getOrElse("?")
        )) *> sqsPublisher.publish(WikipediaEvent.toIngestionEvent(event))

  private def reconnectionSchedule: Schedule[Any, Any, Any] =
    val baseDelay = Duration.fromMillis(config.wikiBackoffStartMs)
    val maxDelay = Duration.fromMillis(config.wikiBackoffMaxMs)
    val increment = Duration.fromMillis(config.wikiBackoffIncrementMs)

    Schedule.recurWhile[Any](_ => true) &&
      Schedule.delayed(
        Schedule.unfold(baseDelay)(d => (d + increment).min(maxDelay))
      ).tapOutput: delay =>
        ZIO.logActivity(WikipediaStreamServiceLive.ReconnectingStream(delay.toMillis))

object WikipediaStreamServiceLive:
  case class StreamConfigLoaded(language: String, stream: String)                         extends InfoLog derives JsonCodec
  case class StreamConsumerStarting(streamUrl: String, expectedServerName: String)         extends InfoLog derives JsonCodec
  case class FetchingStreamsSpec(url: String)                                              extends InfoLog derives JsonCodec
  case class StreamValidated(stream: String)                                               extends InfoLog derives JsonCodec
  case class StreamConnectionLost(message: String)                                         extends ErrorLog derives JsonCodec
  case class ConnectingToStream(streamUrl: String)                                         extends InfoLog derives JsonCodec
  case class DeserializationError(error: String, line: String)                             extends WarnLog derives JsonCodec
  case class WikipediaEventReceived(eventType: String, title: String, user: String, bot: Boolean, wiki: String) extends InfoLog derives JsonCodec
  case class ReconnectingStream(delayMs: Long)                                             extends WarnLog derives JsonCodec

  val layer: URLayer[WikipediaStreamConfig & SttpBackend[Task, ZioStreams] & Tracing & Meter & SqsPublisher, WikipediaStreamService] =
    ZLayer.fromFunction(WikipediaStreamServiceLive.apply)
