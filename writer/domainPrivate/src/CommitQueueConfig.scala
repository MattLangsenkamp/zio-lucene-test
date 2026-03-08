package app.writer.domain.internal

import zio.*
import zio.config.magnolia.*

final case class CommitQueueConfig(commitQueueUrl: String) derives Config

object CommitQueueConfig:
  val layer: TaskLayer[CommitQueueConfig] =
    ZLayer.fromZIO(ZIO.config[CommitQueueConfig])
