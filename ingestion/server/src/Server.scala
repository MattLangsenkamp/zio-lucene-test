package app.ingestion.server

import zio.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.ingestion.api.HealthEndpoint
import app.ingestion.domain.internal.WikipediaStreamConfig
import app.ingestion.services.{WikipediaStreamService, WikipediaStreamServiceLive}
import common.basetelemetry.{BaseTelemetry, TelemetryEnv}
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.capabilities.zio.ZioStreams

object Server extends ZIOAppDefault:

  private val healthServerEndpoint: ZServerEndpoint[Tracing, Any] =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      ZIO.serviceWithZIO[Tracing]: tracing =>
        tracing.span("health-check", SpanKind.SERVER):
          ZIO.logInfo("Health endpoint hit") *> ZIO.succeed("OK")

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundStreamConsumer: ZIO[WikipediaStreamService, Nothing, Unit] =
    WikipediaStreamService.consumeStream
      .tapError(err => ZIO.logError(s"Stream consumer failed: ${err.getMessage}"))
      .catchAll(_ => ZIO.unit)
      .fork
      .unit

  private val resolvedRoutesLayer: URLayer[TelemetryEnv, Routes[Any, Response]] =
    ZLayer.fromZIO:
      for
        env <- ZIO.environment[TelemetryEnv]
      yield app.provideEnvironment(env)

  private val serverAfterTelemetry: ZLayer[TelemetryEnv, Throwable, ZServer] =
    ZLayer.scoped:
      for
        _ <- ZIO.service[Tracing]
        _ <- ZIO.service[ContextStorage]
        _ <- ZIO.logInfo("Building ZServer after TelemetryEnv")
        server <- ZServer.default.build
      yield server.get[ZServer]

  private val sttpBackendLayer: TaskLayer[SttpBackend[Task, ZioStreams]] =
    ZLayer.scoped(HttpClientZioBackend.scoped())

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundStreamConsumer
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("ingestion-service"),
      serverAfterTelemetry,
      resolvedRoutesLayer,
      WikipediaStreamConfig.layer,
      sttpBackendLayer,
      WikipediaStreamServiceLive.layer
    )
