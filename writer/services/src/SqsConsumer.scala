package app.writer.services

import zio.*

trait SqsConsumer:
  def consume: Task[Unit]

object SqsConsumer:
  def consume: ZIO[SqsConsumer, Throwable, Unit] =
    ZIO.serviceWithZIO(_.consume)
