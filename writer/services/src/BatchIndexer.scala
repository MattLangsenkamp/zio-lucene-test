package app.writer.services

import app.ingestion.domain.IngestionEvent
import zio.*
import zio.aws.sqs.model.Message
import zio.stream.ZPipeline

trait BatchIndexer:
  def pipeline: ZPipeline[Any, Throwable, (Message.ReadOnly, IngestionEvent), Message.ReadOnly]

object BatchIndexer:
  def pipeline: URIO[BatchIndexer, ZPipeline[Any, Throwable, (Message.ReadOnly, IngestionEvent), Message.ReadOnly]] =
    ZIO.serviceWith(_.pipeline)
