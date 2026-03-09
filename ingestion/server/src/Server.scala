package app.ingestion.server

import zio.*
import zio.json.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.ingestion.api.HealthEndpoint
import app.ingestion.domain.internal.{WikipediaStreamConfig, SqsPublisherConfig}
import app.ingestion.services.{WikipediaStreamService, WikipediaStreamServiceLive, SqsPublisher, SqsPublisherLive}
import common.basetelemetry.BaseTelemetry
import common.activitylogging.*
import common.serverutils.ServerLayers
import common.awssqs.AwsSqs
import common.sttpclient.SttpClientLayer
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.tracing.Tracing
import sttp.client3.SttpBackend
import sttp.capabilities.zio.ZioStreams

object Server extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)

  private val healthServerEndpoint: ZServerEndpoint[Tracing, Any] =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      ZIO.serviceWithZIO[Tracing]: tracing =>
        tracing.span("health-check", SpanKind.SERVER):
          ZIO.logActivity(HealthCheckHit()) *> ZIO.succeed("OK")

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundStreamConsumer: ZIO[WikipediaStreamService, Nothing, Unit] =
    WikipediaStreamService.consumeStream
      .tapError(err => ZIO.logActivity(StreamConsumerFailed(err.getMessage)))
      .catchAll(_ => ZIO.unit)
      .fork
      .unit

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _ <- backgroundStreamConsumer
      _ <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("ingestion-service"),
      ServerLayers.serverAfterTelemetry,
      ServerLayers.resolvedRoutesLayer(app),
      WikipediaStreamConfig.layer,
      SttpClientLayer.sttpBackendLayer,
      SqsPublisherConfig.layer,
      AwsSqs.sqsLayer,
      SqsPublisherLive.layer,
      WikipediaStreamServiceLive.layer
    )

  case class HealthCheckHit() extends InfoLog derives JsonCodec
  case class StreamConsumerFailed(message: String) extends ErrorLog derives JsonCodec
