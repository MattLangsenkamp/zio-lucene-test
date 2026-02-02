package app.reader.server

import zio.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.reader.api.HealthEndpoint
import common.basetelemetry.BaseTelemetry
import io.opentelemetry.api.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage

object Server extends ZIOAppDefault:

  private val healthServerEndpoint =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      ZIO.logInfo("Health endpoint was hit1") *>
        ZIO.serviceWithZIO[Tracing]: tracing =>
          tracing.span("health-check", SpanKind.SERVER)(
            ZIO.logInfo("Health endpoint was hit2") *> ZIO.succeed("OK")
          )

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundTelemetry: ZIO[Tracing & Meter, Nothing, Unit] =
    (for
      meter <- ZIO.service[Meter]
      counter <- meter.counter("heartbeat_count")
      _ <- ZIO
        .serviceWithZIO[Tracing]: tracing =>
          tracing.span("heartbeat", SpanKind.SERVER)(
            ZIO.logInfo("Reader service heartbeat") *>
              counter.add(1)
          )
        .repeat(Schedule.spaced(30.second))
    yield ()).fork.unit

  private val telemetryLayer: TaskLayer[OpenTelemetry & Tracing & Meter & ContextStorage & Baggage] =
    BaseTelemetry.live("reader-service")

  def run =
    for
      env <- telemetryLayer.build
      _ <- backgroundTelemetry.provideEnvironment(env)
      _ <- ZServer
        .serve(app.provideEnvironment(env))
        .provide(ZServer.default)
    yield ()
