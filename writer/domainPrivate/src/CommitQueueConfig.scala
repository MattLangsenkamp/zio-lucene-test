package app.writer.domain.internal

import zio.*

final case class CommitQueueConfig(queueUrl: String)

object CommitQueueConfig:

  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  val layer: TaskLayer[CommitQueueConfig] =
    ZLayer.fromZIO:
      envRequired("COMMIT_QUEUE_URL").map(CommitQueueConfig.apply)
