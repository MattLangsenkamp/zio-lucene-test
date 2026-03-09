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
import common.basetelemetry.BaseTelemetry
import common.activitylogging.*
import common.serverutils.ServerLayers
import common.awssqs.AwsSqs
import common.awss3.AwsS3
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.tracing.Tracing

object Server extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase.upperCase)

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

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      routes <- ZIO.service[Routes[Any, Response]]
      _      <- backgroundConsumer
      _      <- backgroundSegmentSync
      _      <- ZServer.serve(routes)
    yield ()).provide(
      Scope.default,
      BaseTelemetry.live("writer-service"),
      ServerLayers.serverAfterTelemetry,
      ServerLayers.resolvedRoutesLayer(app),
      // Config layers
      SqsConsumerConfig.layer,
      CommitQueueConfig.layer,
      BatchIndexerConfig.layer,
      IndexConfig.layer,
      S3Config.layer,
      // AWS layers
      AwsSqs.sqsLayer,
      AwsS3.s3Layer,
      // Service layers
      IndexSegmentStoreLive.layer,
      CommitPublisherLive.layer,
      DocumentIndexerLive.layer,
      BatchIndexerLive.layer,
      SegmentSyncServiceLive.layer,
      SqsConsumerLive.layer
    )

  private case class HealthCheckHit()                          extends InfoLog derives JsonCodec
  private case class SqsConsumerFailed(message: String)        extends ErrorLog derives JsonCodec
  private case class SegmentSyncServiceFailed(message: String) extends ErrorLog derives JsonCodec
