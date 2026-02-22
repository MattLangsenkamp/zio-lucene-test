package app.ingestion.services

import app.ingestion.domain.WikipediaEvent
import app.ingestion.domain.internal.{WikimediaStreamsSpec, WikipediaStreamConfig}
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
      _                 <- ZIO.logInfo(s"Wikipedia stream config loaded: language=${config.language}, stream=${config.stream}")
      _                 <- validateStreamExists
      deserErrorCounter <- meter.counter("wikipedia.deserialization.error")
      reconnectCounter  <- meter.counter("wikipedia.reconnection.attempt")
      _                 <- ZIO.logInfo(s"Starting stream consumer for ${config.streamUrl} (server: ${config.expectedServerName})")
      _                 <- connectAndConsume(deserErrorCounter, reconnectCounter)
    yield ()

  private def validateStreamExists: Task[Unit] =
    tracing.span("validate-stream-exists", SpanKind.CLIENT):
      for
        _ <- ZIO.logInfo(s"Fetching Wikimedia streams spec from ${WikimediaStreamsSpec.specUrl}")
        response <- basicRequest
          .get(uri"${WikimediaStreamsSpec.specUrl}")
          .header("User-Agent", UserAgent)
          .response(asJson[WikimediaStreamsSpec])
          .send(backend)
        spec <- ZIO.fromEither(response.body)
          .mapError(err => new RuntimeException(s"Failed to parse Wikimedia streams spec: $err"))
        availableStreams <- ZIO.fromOption(WikimediaStreamsSpec.extractAvailableStreams(spec))
          .orElseFail(new RuntimeException("Could not extract available streams from spec"))
        _ <- ZIO.when(!availableStreams.contains(config.stream)):
          ZIO.fail(new RuntimeException(
            s"Stream '${config.stream}' not found. Available: ${availableStreams.mkString(", ")}"
          ))
        _ <- ZIO.logInfo(s"Stream '${config.stream}' validated successfully")
      yield ()

  private def connectAndConsume(
      deserErrorCounter: Counter[Long],
      reconnectCounter: Counter[Long]
  ): Task[Unit] =
    consumeOnce(deserErrorCounter)
      .tapError: err =>
        ZIO.logError(s"Stream connection lost: ${err.getMessage}") *>
          reconnectCounter.add(1)
      .retry(reconnectionSchedule)

  private def consumeOnce(deserErrorCounter: Counter[Long]): Task[Unit] =
    tracing.span("consume-stream", SpanKind.CLIENT):
      for
        _ <- ZIO.logInfo(s"Connecting to ${config.streamUrl}")
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
        ZIO.logWarning(s"Deserialization error: $error (line: ${line.take(200)})") *>
          deserErrorCounter.add(1, Attributes.of(AttributeKey.stringKey("error_type"), errorType))
      case Right(event) if WikipediaEvent.isCanary(event) =>
        ZIO.unit
      case Right(event) if !WikipediaEvent.matchesServer(event, config.expectedServerName) =>
        ZIO.unit
      case Right(event) =>
        ZIO.logInfo(
          s"[${event.eventType.getOrElse("unknown")}] " +
            s"${event.title.getOrElse("?")} by ${event.user.getOrElse("?")} " +
            s"(bot: ${event.bot.getOrElse(false)}, wiki: ${event.wiki.getOrElse("?")})"
        ) *> sqsPublisher.publish(WikipediaEvent.toIngestionEvent(event))

  private def reconnectionSchedule: Schedule[Any, Any, Any] =
    val baseDelay = Duration.fromMillis(config.backoffStartMs)
    val maxDelay = Duration.fromMillis(config.backoffMaxMs)
    val increment = Duration.fromMillis(config.backoffIncrementMs)

    Schedule.recurWhile[Any](_ => true) &&
      Schedule.delayed(
        Schedule.unfold(baseDelay)(d => (d + increment).min(maxDelay))
      ).tapOutput: delay =>
        ZIO.logWarning(s"Reconnecting after ${delay.toMillis}ms")

object WikipediaStreamServiceLive:
  val layer: URLayer[WikipediaStreamConfig & SttpBackend[Task, ZioStreams] & Tracing & Meter & SqsPublisher, WikipediaStreamService] =
    ZLayer.fromFunction(WikipediaStreamServiceLive.apply)
