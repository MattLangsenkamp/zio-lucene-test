package app.ingestion.services

import zio.*

trait WikipediaStreamService:
  def consumeStream: Task[Unit]

object WikipediaStreamService:
  def consumeStream: RIO[WikipediaStreamService, Unit] =
    ZIO.serviceWithZIO[WikipediaStreamService](_.consumeStream)
