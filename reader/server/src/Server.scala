package app.reader.server

import zio.*
import zio.http.Server as ZServer
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.reader.api.HealthEndpoint
import common.basetelemetry.BaseTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import io.opentelemetry.api.trace.SpanKind

object Server extends ZIOAppDefault:

  private val healthServerEndpoint =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing] { _ =>
      ZIO.serviceWithZIO[Tracing] { tracing =>
        tracing.span("health-check", SpanKind.SERVER)(
          ZIO.logInfo("Health endpoint was hit") *> ZIO.succeed("OK")
        )
      }
    }

  private val app = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundTelemetry: ZIO[Meter, Nothing, Unit] =
    (for
      meter <- ZIO.service[Meter]
      counter <- meter.counter("heartbeat_count")
      _ <- (
        ZIO.logInfo("Reader service heartbeat") *>
          counter.add(1)
      ).repeat(Schedule.spaced(1.second))
    yield ()).fork.unit

  def run =
    (backgroundTelemetry *> ZServer.serve(app))
      .provide(
        ZServer.default,
        BaseTelemetry.live("reader-service")
      )
