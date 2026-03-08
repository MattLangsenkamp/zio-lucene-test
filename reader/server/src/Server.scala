package app.reader.server

import zio.*
import zio.json.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.reader.api.HealthEndpoint
import common.basetelemetry.{BaseTelemetry, TelemetryEnv}
import common.activitylogging.*
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter

object Server extends ZIOAppDefault:

  private val healthServerEndpoint: ZServerEndpoint[Tracing, Any] =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      for
        loggers <- ZIO.loggers
        _       <- ZIO.logActivity(HealthCheckHit(loggers.map(_.getClass.getName).toList))
        result  <- ZIO.serviceWithZIO[Tracing]: tracing =>
          tracing.span("health-check", SpanKind.SERVER):
            ZIO.logActivity(HealthCheckSpanHit()) *> ZIO.succeed("OK")
      yield result

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundTelemetry: ZIO[Tracing & Meter, Nothing, Unit] =
    (for
      meter   <- ZIO.service[Meter]
      counter <- meter.counter("heartbeat_count")
      _ <- ZIO
        .serviceWithZIO[Tracing]: tracing =>
          tracing.span("heartbeat", SpanKind.SERVER):
            ZIO.logActivity(ReaderHeartbeat()) *>
              counter.add(1)
        .repeat(Schedule.spaced(30.second))
    yield ()).fork.unit

  private val resolvedRoutesLayer: URLayer[TelemetryEnv, Routes[Any, Response]] =
    ZLayer.fromZIO(
      for
        env       <- ZIO.environment[TelemetryEnv]
        baseRoutes = app.provideEnvironment(env)
      yield baseRoutes
    )

  private val serverAfterTelemetry: ZLayer[TelemetryEnv, Throwable, ZServer] =
    ZLayer.scoped {
      for
        _ <- ZIO.service[Tracing]
        _ <- ZIO.service[ContextStorage]
        _ <- ZIO.logActivity(ZServerStarting())
        server <- ZServer.default.build
      yield server.get[ZServer]
    }

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundTelemetry
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("reader-service"),
      serverAfterTelemetry,
      resolvedRoutesLayer
    )

  case class HealthCheckHit(activeLoggers: List[String]) extends InfoLog derives JsonCodec
  case class HealthCheckSpanHit()                        extends InfoLog derives JsonCodec
  case class ReaderHeartbeat()                           extends InfoLog derives JsonCodec
  case class ZServerStarting()                           extends InfoLog derives JsonCodec
