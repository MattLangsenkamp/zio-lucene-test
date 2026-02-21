package app.ingestion.domain

import zio.json.*

@jsonMemberNames(SnakeCase)
final case class EventMeta(
    uri: Option[String] = None,
    id: Option[String] = None,
    domain: Option[String] = None,
    stream: Option[String] = None,
    dt: Option[String] = None,
    requestId: Option[String] = None,
    topic: Option[String] = None,
    partition: Option[Int] = None,
    offset: Option[Long] = None
) derives JsonDecoder

final case class LengthChange(
    old: Option[Int] = None,
    @jsonField("new") newLength: Option[Int] = None
) derives JsonDecoder

final case class RevisionChange(
    old: Option[Long] = None,
    @jsonField("new") newRevision: Option[Long] = None
) derives JsonDecoder

@jsonMemberNames(SnakeCase)
final case class WikipediaEvent(
    @jsonField("$schema") schema: Option[String] = None,
    meta: EventMeta,
    id: Option[Long] = None,
    @jsonField("type") eventType: Option[String] = None,
    namespace: Option[Int] = None,
    title: Option[String] = None,
    titleUrl: Option[String] = None,
    comment: Option[String] = None,
    timestamp: Option[Long] = None,
    user: Option[String] = None,
    bot: Option[Boolean] = None,
    minor: Option[Boolean] = None,
    patrolled: Option[Boolean] = None,
    length: Option[LengthChange] = None,
    revision: Option[RevisionChange] = None,
    serverUrl: Option[String] = None,
    serverName: Option[String] = None,
    serverScriptPath: Option[String] = None,
    wiki: Option[String] = None,
    parsedcomment: Option[String] = None,
    notifyUrl: Option[String] = None,
    logType: Option[String] = None,
    logAction: Option[String] = None,
    logId: Option[Long] = None
) derives JsonDecoder

object WikipediaEvent:
  def isCanary(event: WikipediaEvent): Boolean =
    event.meta.domain.contains("canary")

  def matchesServer(event: WikipediaEvent, expectedServer: String): Boolean =
    event.serverName.contains(expectedServer)
