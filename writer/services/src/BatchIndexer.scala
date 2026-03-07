package app.writer.services

import app.ingestion.domain.IngestionEvent
import zio.*
import zio.stream.ZStream

trait BatchIndexer:
  def run(documents: ZStream[Any, Throwable, IngestionEvent]): ZIO[Any, Throwable, Unit]

object BatchIndexer:
  def run(documents: ZStream[Any, Throwable, IngestionEvent]): ZIO[BatchIndexer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.run(documents))
