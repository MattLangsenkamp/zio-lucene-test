package app.reader.server

import zio.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.reader.api.HealthEndpoint
import common.basetelemetry.{BaseTelemetry, OtelLoggerMiddleware, TelemetryEnv}
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter

object Server extends ZIOAppDefault:

  private val healthServerEndpoint =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      for
        loggers <- ZIO.loggers
        loggerNames = loggers.map(_.getClass.getName).mkString("\n  - ")
        _ <- ZIO.logInfo(s"Health endpoint hit. Active loggers (${loggers.size}):\n  - $loggerNames")
        result <- ZIO.serviceWithZIO[Tracing]: tracing =>
          tracing.span("health-check", SpanKind.SERVER):
            ZIO.logInfo("Health endpoint was hit inside span") *> ZIO.succeed("OK")
      yield result

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundTelemetry: ZIO[Tracing & Meter, Nothing, Unit] =
    (for
      meter   <- ZIO.service[Meter]
      counter <- meter.counter("heartbeat_count")
      _ <- ZIO
        .serviceWithZIO[Tracing]: tracing =>
          tracing.span("heartbeat", SpanKind.SERVER):
            ZIO.logInfo("Reader service heartbeat") *>
              counter.add(1)
        .repeat(Schedule.spaced(30.second))
    yield ()).fork.unit

  private val resolvedRoutesLayer: URLayer[TelemetryEnv, Routes[Any, Response]] =
    ZLayer.fromZIO(
      for
        env        <- ZIO.environment[TelemetryEnv]
        otel       <- ZIO.service[OpenTelemetry]
        ctxStorage <- ZIO.service[ContextStorage]
        tracing    <- ZIO.service[Tracing]
        baseRoutes  = app.provideEnvironment(env)
        routesWithLogging = baseRoutes @@ OtelLoggerMiddleware.middleware(otel, ctxStorage, tracing)
      yield routesWithLogging
    )

  def run =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundTelemetry
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      ZServer.default,
      BaseTelemetry.live("reader-service"),
      resolvedRoutesLayer
    )
