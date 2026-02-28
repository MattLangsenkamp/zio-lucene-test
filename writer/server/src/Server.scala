package app.writer.server

import zio.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.writer.api.HealthEndpoint
import app.writer.domain.internal.SqsConsumerConfig
import app.writer.services.{SqsConsumer, SqsConsumerLive}
import zio.aws.core.config.AwsConfig
import zio.aws.netty.NettyHttpClient
import zio.aws.sqs.Sqs
import common.basetelemetry.{BaseTelemetry, TelemetryEnv}
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

object Server extends ZIOAppDefault:

  private val healthServerEndpoint: ZServerEndpoint[Tracing, Any] =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      ZIO.serviceWithZIO[Tracing]: tracing =>
        tracing.span("health-check", SpanKind.SERVER):
          ZIO.logInfo("Health endpoint hit") *> ZIO.succeed("OK")

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundConsumer: ZIO[SqsConsumer, Nothing, Unit] =
    SqsConsumer.consume
      .tapError(err => ZIO.logError(s"SQS consumer failed: ${err.toString}"))
      .catchAll(_ => ZIO.unit)
      .fork
      .unit

  private val resolvedRoutesLayer: URLayer[TelemetryEnv, Routes[Any, Response]] =
    ZLayer.fromZIO:
      for env <- ZIO.environment[TelemetryEnv]
      yield app.provideEnvironment(env)

  private val serverAfterTelemetry: ZLayer[TelemetryEnv, Throwable, ZServer] =
    ZLayer.scoped:
      for
        _ <- ZIO.service[Tracing]
        _ <- ZIO.service[ContextStorage]
        _ <- ZIO.logInfo("Building ZServer after TelemetryEnv")
        server <- ZServer.default.build
      yield server.get[ZServer]

  private val sqsLayer: TaskLayer[Sqs] =
    NettyHttpClient.default >>> AwsConfig.default >>> Sqs.live

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundConsumer
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("writer-service"),
      serverAfterTelemetry,
      resolvedRoutesLayer,
      SqsConsumerConfig.layer,
      sqsLayer,
      SqsConsumerLive.layer
    )
