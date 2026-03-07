package app.writer.domain.internal

import zio.*

final case class BatchIndexerConfig(
    batchSize: Int,
    flushThreshold: Int,
    commitThreshold: Int
)

object BatchIndexerConfig:

  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  private def envWithDefault(name: String, default: String): Task[String] =
    System.env(name).map(_.getOrElse(default))

  val layer: TaskLayer[BatchIndexerConfig] =
    ZLayer.fromZIO:
      for
        batchSize <- envWithDefault("BATCH_SIZE", "5").map(_.toInt)
        flushThreshold <- envWithDefault("FLUSH_THRESHOLD", "5").map(_.toInt)
        commitThreshold <- envWithDefault("COMMIT_THRESHOLD", "25").map(_.toInt)
      yield BatchIndexerConfig(batchSize, flushThreshold, commitThreshold)
