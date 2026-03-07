package app.writer.services

import zio.*

trait IndexSegmentStore:
  def uploadSegmentFile(filename: String): ZIO[Any, Throwable, Unit]
  def uploadCommitPoint(segmentsFileName: String): ZIO[Any, Throwable, Unit]
  def exists(filename: String): ZIO[Any, Throwable, Boolean]

object IndexSegmentStore:
  def uploadSegmentFile(filename: String): ZIO[IndexSegmentStore, Throwable, Unit] =
    ZIO.serviceWithZIO(_.uploadSegmentFile(filename))
  def uploadCommitPoint(segmentsFileName: String): ZIO[IndexSegmentStore, Throwable, Unit] =
    ZIO.serviceWithZIO(_.uploadCommitPoint(segmentsFileName))
  def exists(filename: String): ZIO[IndexSegmentStore, Throwable, Boolean] =
    ZIO.serviceWithZIO(_.exists(filename))
