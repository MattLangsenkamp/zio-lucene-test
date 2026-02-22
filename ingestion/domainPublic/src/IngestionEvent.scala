package app.ingestion.domain

import zio.json.*

enum IngestionSource derives JsonCodec:
  case Wikipedia, Wikidata

case class ExtraField(key: String, value: String) derives JsonCodec

case class IngestionEvent(
    source: IngestionSource,
    timestamp: Option[String],
    title: Option[String],
    user: Option[String],
    isBot: Option[Boolean],
    eventType: Option[String],
    pageUrl: Option[String],
    wiki: Option[String],
    extras: Seq[ExtraField]
) derives JsonCodec
