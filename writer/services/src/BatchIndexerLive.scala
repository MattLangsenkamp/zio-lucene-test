package app.writer.services

import app.ingestion.domain.IngestionEvent
import app.writer.domain.internal.{BatchIndexerConfig, CommitEvent}
import zio.*
import zio.json.*
import zio.aws.sqs.model.Message
import zio.stream.ZPipeline
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind
import common.activitylogging.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

final case class BatchIndexerLive(
    config: BatchIndexerConfig,
    documentIndexer: DocumentIndexer,
    commitPublisher: CommitPublisher,
    tracing: Tracing
) extends BatchIndexer:

  override def pipeline: ZPipeline[Any, Throwable, (Message.ReadOnly, IngestionEvent), Message.ReadOnly] =
    val batchCounter = new AtomicLong(0L)
    ZPipeline.grouped(config.batchSize) >>>
      ZPipeline.mapZIO { (batch: Chunk[(Message.ReadOnly, IngestionEvent)]) =>
        val idx      = batchCounter.getAndIncrement()
        val batchNum = idx + 1
        val isCommit    = batchNum % config.commitThreshold == 0
        val isFlushOnly = !isCommit && batchNum % config.flushThreshold == 0
        tracing.span("index-batch", SpanKind.INTERNAL):
          for
            _ <- ZIO.logActivity(BatchIndexerLive.BatchProcessed(idx, isCommit, isFlushOnly))
            _ <- documentIndexer.indexBatch(batch.map(_._2))
            _ <- ZIO.when(isFlushOnly)(documentIndexer.flush())
            _ <- ZIO.when(isCommit):
                   documentIndexer.flush() *>
                     documentIndexer.commit() *>
                     ZIO.logActivity(BatchIndexerLive.IndexCommitted()) *>
                     commitPublisher.publish(CommitEvent(Instant.now()))
          yield batch.map(_._1)
      } >>>
      ZPipeline.flattenChunks

object BatchIndexerLive:
  case class BatchProcessed(idx: Long, isCommit: Boolean, isFlushOnly: Boolean) extends InfoLog derives JsonCodec
  case class IndexCommitted()                                                     extends InfoLog derives JsonCodec

  val layer: URLayer[BatchIndexerConfig & DocumentIndexer & CommitPublisher & Tracing, BatchIndexer] =
    ZLayer.fromFunction(BatchIndexerLive.apply)
