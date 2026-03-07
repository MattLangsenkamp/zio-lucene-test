package app.writer.services

import app.writer.domain.internal.CommitEvent
import zio.*

trait CommitPublisher:
  def publish(event: CommitEvent): ZIO[Any, Throwable, Unit]

object CommitPublisher:
  def publish(event: CommitEvent): ZIO[CommitPublisher, Throwable, Unit] =
    ZIO.serviceWithZIO(_.publish(event))
