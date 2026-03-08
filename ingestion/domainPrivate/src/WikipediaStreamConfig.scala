package app.ingestion.domain.internal

import zio.*
import zio.config.magnolia.*

final case class WikipediaStreamConfig(
    wikiLang: String,
    wikiStream: String,
    wikiBackoffStartMs: Long,
    wikiBackoffIncrementMs: Long,
    wikiBackoffMaxMs: Long
) derives Config:
  val expectedServerName: String = s"$wikiLang.wikipedia.org"
  val streamUrl: String          = s"https://stream.wikimedia.org/v2/stream/$wikiStream"

object WikipediaStreamConfig:
  val layer: TaskLayer[WikipediaStreamConfig] =
    ZLayer.fromZIO(ZIO.config[WikipediaStreamConfig])
