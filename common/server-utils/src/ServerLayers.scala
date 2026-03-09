package common.serverutils

import zio.*
import zio.json.*
import zio.http.{Response, Routes, Server as ZServer}
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import common.basetelemetry.TelemetryEnv
import common.activitylogging.*

/** Shared ZLayer utilities for all ZIO HTTP servers in this project.
  *
  * Eliminates the boilerplate that every server needs:
  *   - starting the ZIO HTTP server after telemetry is initialised
  *   - resolving service-specific Routes into the ambient telemetry environment
  *   - emitting a structured startup log event
  */
object ServerLayers:

  /** Starts the ZIO HTTP server after telemetry services (Tracing, ContextStorage) are present in
    * the environment. Emits a `ZServerStarting` activity log on startup.
    *
    * Requires `TelemetryEnv` so that span context propagation is wired up before the first request
    * is handled.
    */
  val serverAfterTelemetry: ZLayer[TelemetryEnv, Throwable, ZServer] =
    ZLayer.scoped:
      for
        _ <- ZIO.service[Tracing]
        _ <- ZIO.service[ContextStorage]
        _ <- ZIO.logActivity(ZServerStarting())
        server <- ZServer.default.build
      yield server.get[ZServer]

  /** Lifts a `Routes[Tracing, Response]` into a `ZLayer` that resolves the `Tracing` (and other
    * telemetry) environment from the ambient `TelemetryEnv`.
    *
    * Each server passes its own service-specific `app` value here; the layer-wiring logic is
    * shared.
    *
    * @param app
    *   the service-specific routes to resolve
    */
  def resolvedRoutesLayer(app: Routes[Tracing, Response]): URLayer[TelemetryEnv, Routes[Any, Response]] =
    ZLayer.fromZIO:
      for env <- ZIO.environment[TelemetryEnv]
      yield app.provideEnvironment(env)

  /** Emitted once when the ZIO HTTP server begins listening. Defined here so the event is shared
    * across all services rather than duplicated per server.
    */
  case class ZServerStarting() extends InfoLog derives JsonCodec
