package app.ingestion.domain.internal

import zio.json.*

final case class StreamsParameterSchema(
    @jsonField("type") schemaType: Option[String] = None,
    items: Option[StreamsItemSchema] = None
) derives JsonDecoder

final case class StreamsItemSchema(
    @jsonField("type") itemType: Option[String] = None,
    @jsonField("enum") enumValues: Option[List[String]] = None
) derives JsonDecoder

final case class StreamsParameter(
    name: Option[String] = None,
    in: Option[String] = None,
    schema: Option[StreamsParameterSchema] = None
) derives JsonDecoder

final case class StreamsGetOperation(
    parameters: Option[List[StreamsParameter]] = None
) derives JsonDecoder

final case class StreamsPathItem(
    get: Option[StreamsGetOperation] = None
) derives JsonDecoder

final case class WikimediaStreamsSpec(
    paths: Option[Map[String, StreamsPathItem]] = None
) derives JsonDecoder

object WikimediaStreamsSpec:
  val specUrl: String = "https://stream.wikimedia.org/?spec"

  def extractAvailableStreams(spec: WikimediaStreamsSpec): Option[List[String]] =
    for
      paths     <- spec.paths
      pathItem  <- paths.get("/v2/stream/{streams}")
      getOp     <- pathItem.get
      params    <- getOp.parameters
      streams   <- params.find(_.name.contains("streams"))
      schema    <- streams.schema
      items     <- schema.items
      enums     <- items.enumValues
    yield enums
