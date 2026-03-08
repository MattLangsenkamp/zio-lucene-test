package app.writer.domain.internal

import zio.*
import zio.config.magnolia.*

final case class IndexConfig(luceneIndexPath: String) derives Config

object IndexConfig:
  val layer: TaskLayer[IndexConfig] =
    ZLayer.fromZIO(ZIO.config[IndexConfig])
