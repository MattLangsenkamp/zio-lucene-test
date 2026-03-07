package app.writer.services

import app.ingestion.domain.IngestionEvent
import zio.*

trait DocumentIndexer:
  def indexBatch(documents: Chunk[IngestionEvent]): ZIO[Any, Throwable, Unit]
  def flush(): ZIO[Any, Throwable, Unit]
  def commit(): ZIO[Any, Throwable, Unit]

object DocumentIndexer:
  def indexBatch(documents: Chunk[IngestionEvent]): ZIO[DocumentIndexer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.indexBatch(documents))
  def flush(): ZIO[DocumentIndexer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.flush())
  def commit(): ZIO[DocumentIndexer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.commit())
