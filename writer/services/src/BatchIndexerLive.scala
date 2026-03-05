package app.writer.services

import app.ingestion.domain.IngestionEvent
import app.writer.domain.internal.{BatchIndexerConfig, CommitEvent}
import zio.*
import zio.stream.ZStream

import java.time.Instant

final case class BatchIndexerLive(
    config: BatchIndexerConfig,
    documentIndexer: DocumentIndexer,
    commitPublisher: CommitPublisher
) extends BatchIndexer:

  override def run(documents: ZStream[Any, Throwable, IngestionEvent]): ZIO[Any, Throwable, Unit] =
    documents
      .grouped(config.batchSize)
      .zipWithIndex
      .mapZIO { case (batch, idx) =>
        val batchNum    = idx + 1
        val isCommit    = batchNum % config.commitThreshold == 0
        val isFlushOnly = !isCommit && batchNum % config.flushThreshold == 0
        for
          _ <- ZIO.logInfo(s"idx: $idx isCommit: $isCommit isFlushOnly: $isFlushOnly")
          _ <- documentIndexer.indexBatch(batch)
          _ <- ZIO.when(isFlushOnly)(documentIndexer.flush())
          _ <- ZIO.when(isCommit):
                 documentIndexer.flush() *>
                   documentIndexer.commit() *>
                   ZIO.logInfo("Committed index") *>
                   commitPublisher.publish(CommitEvent(Instant.now()))
        yield ()
      }
      .runDrain

object BatchIndexerLive:
  val layer: URLayer[BatchIndexerConfig & DocumentIndexer & CommitPublisher, BatchIndexer] =
    ZLayer.fromFunction(BatchIndexerLive.apply)
