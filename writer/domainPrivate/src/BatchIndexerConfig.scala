package app.writer.domain.internal

import zio.*
import zio.config.magnolia.*

final case class BatchIndexerConfig(
    batchSize: Int,
    flushThreshold: Int,
    commitThreshold: Int
) derives Config

object BatchIndexerConfig:
  val layer: TaskLayer[BatchIndexerConfig] =
    ZLayer.fromZIO(ZIO.config[BatchIndexerConfig])
