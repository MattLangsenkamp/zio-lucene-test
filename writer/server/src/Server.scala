package app.writer.server

import zio.*
import zio.json.*
import zio.http.{Response, Routes, Server as ZServer}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.writer.api.HealthEndpoint
import app.writer.domain.internal.{
  BatchIndexerConfig,
  CommitQueueConfig,
  IndexConfig,
  S3Config,
  SqsConsumerConfig
}
import app.writer.services.{
  BatchIndexer,
  BatchIndexerLive,
  CommitPublisher,
  CommitPublisherLive,
  DocumentIndexer,
  DocumentIndexerLive,
  IndexSegmentStore,
  IndexSegmentStoreLive,
  SegmentSyncService,
  SegmentSyncServiceLive,
  SqsConsumer,
  SqsConsumerLive
}
import zio.aws.core.config.AwsConfig
import zio.aws.netty.NettyHttpClient
import zio.aws.s3.S3
import zio.aws.sqs.Sqs
import common.basetelemetry.{BaseTelemetry, TelemetryEnv}
import common.activitylogging.*
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

object Server extends ZIOAppDefault:

  private val healthServerEndpoint: ZServerEndpoint[Tracing, Any] =
    HealthEndpoint.healthEndpoint.zServerLogic[Tracing]: _ =>
      ZIO.serviceWithZIO[Tracing]: tracing =>
        tracing.span("health-check", SpanKind.SERVER):
          ZIO.logActivity(HealthCheckHit()) *> ZIO.succeed("OK")

  private val app: Routes[Tracing, Response] = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  private val backgroundConsumer: ZIO[SqsConsumer, Nothing, Unit] =
    SqsConsumer.consume
      .tapError(err => ZIO.logActivity(SqsConsumerFailed(err.toString)))
      .catchAll(_ => ZIO.unit)
      .fork
      .unit

  private val backgroundSegmentSync: ZIO[SegmentSyncService, Nothing, Unit] =
    SegmentSyncService.run()
      .tapError(err => ZIO.logActivity(SegmentSyncServiceFailed(err.toString)))
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
        _ <- ZIO.logActivity(ZServerStarting())
        server <- ZServer.default.build
      yield server.get[ZServer]

  private val awsBaseLayer = NettyHttpClient.default >>> AwsConfig.default
  private val sqsLayer: TaskLayer[Sqs] = awsBaseLayer >>> Sqs.live
  private val s3Layer: TaskLayer[S3]   = awsBaseLayer >>> S3.live

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundConsumer
      _      <- backgroundSegmentSync
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("writer-service"),
      serverAfterTelemetry,
      resolvedRoutesLayer,
      // Config layers
      SqsConsumerConfig.layer,
      CommitQueueConfig.layer,
      BatchIndexerConfig.layer,
      IndexConfig.layer,
      S3Config.layer,
      // AWS layers
      sqsLayer,
      s3Layer,
      // Service layers
      IndexSegmentStoreLive.layer,
      CommitPublisherLive.layer,
      DocumentIndexerLive.layer,
      BatchIndexerLive.layer,
      SegmentSyncServiceLive.layer,
      SqsConsumerLive.layer
    )

  case class HealthCheckHit()                          extends InfoLog derives JsonCodec
  case class SqsConsumerFailed(message: String)        extends ErrorLog derives JsonCodec
  case class SegmentSyncServiceFailed(message: String) extends ErrorLog derives JsonCodec
  case class ZServerStarting()                         extends InfoLog derives JsonCodec
