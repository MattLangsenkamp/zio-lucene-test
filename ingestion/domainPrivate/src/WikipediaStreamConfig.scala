package app.ingestion.domain.internal

import zio.*

final case class WikipediaStreamConfig(
    language: String,
    stream: String,
    backoffStartMs: Long,
    backoffIncrementMs: Long,
    backoffMaxMs: Long
):
  val expectedServerName: String = s"$language.wikipedia.org"
  val streamUrl: String = s"https://stream.wikimedia.org/v2/stream/$stream"

object WikipediaStreamConfig:

  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  private def envRequiredLong(name: String): Task[Long] =
    envRequired(name).flatMap: value =>
      ZIO
        .attempt(value.toLong)
        .mapError(_ => new IllegalArgumentException(s"Invalid numeric value for $name: '$value'"))

  val fromEnv: Task[WikipediaStreamConfig] =
    for
      language       <- envRequired("WIKI_LANG")
      stream         <- envRequired("WIKI_STREAM")
      backoffStart   <- envRequiredLong("WIKI_BACKOFF_START_MS")
      backoffIncr    <- envRequiredLong("WIKI_BACKOFF_INCREMENT_MS")
      backoffMax     <- envRequiredLong("WIKI_BACKOFF_MAX_MS")
    yield WikipediaStreamConfig(language, stream, backoffStart, backoffIncr, backoffMax)

  val layer: TaskLayer[WikipediaStreamConfig] =
    ZLayer.fromZIO(fromEnv)
