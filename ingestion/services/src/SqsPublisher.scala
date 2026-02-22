package app.ingestion.services

import app.ingestion.domain.IngestionEvent
import zio.*

trait SqsPublisher:
  def publish(event: IngestionEvent): Task[Unit]

object SqsPublisher:
  def publish(event: IngestionEvent): ZIO[SqsPublisher, Throwable, Unit] =
    ZIO.serviceWithZIO(_.publish(event))
