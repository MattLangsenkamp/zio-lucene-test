package app.writer.services

import zio.*

trait SegmentSyncService:
  def run(): ZIO[Any, Throwable, Unit]

object SegmentSyncService:
  def run(): ZIO[SegmentSyncService, Throwable, Unit] =
    ZIO.serviceWithZIO(_.run())
