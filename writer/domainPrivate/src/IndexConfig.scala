package app.writer.domain.internal

import zio.*

final case class IndexConfig(indexPath: String)

object IndexConfig:

  val layer: TaskLayer[IndexConfig] =
    ZLayer.fromZIO:
      System.env("LUCENE_INDEX_PATH").map(_.getOrElse("/tmp/lucene-index")).map(IndexConfig.apply)
