package app.ingestion.domain.internal

import zio.json.*

final case class StreamsParameterSchema(
    @jsonField("type") schemaType: Option[String] = None,
    items: Option[StreamsItemSchema] = None
)

object StreamsParameterSchema:
  given JsonDecoder[StreamsParameterSchema] = DeriveJsonDecoder.gen[StreamsParameterSchema]

final case class StreamsItemSchema(
    @jsonField("type") itemType: Option[String] = None,
    @jsonField("enum") enumValues: Option[List[String]] = None
)

object StreamsItemSchema:
  given JsonDecoder[StreamsItemSchema] = DeriveJsonDecoder.gen[StreamsItemSchema]

final case class StreamsParameter(
    name: Option[String] = None,
    in: Option[String] = None,
    schema: Option[StreamsParameterSchema] = None
)

object StreamsParameter:
  given JsonDecoder[StreamsParameter] = DeriveJsonDecoder.gen[StreamsParameter]

final case class StreamsGetOperation(
    parameters: Option[List[StreamsParameter]] = None
)

object StreamsGetOperation:
  given JsonDecoder[StreamsGetOperation] = DeriveJsonDecoder.gen[StreamsGetOperation]

final case class StreamsPathItem(
    get: Option[StreamsGetOperation] = None
)

object StreamsPathItem:
  given JsonDecoder[StreamsPathItem] = DeriveJsonDecoder.gen[StreamsPathItem]

final case class WikimediaStreamsSpec(
    paths: Option[Map[String, StreamsPathItem]] = None
)

object WikimediaStreamsSpec:
  given JsonDecoder[WikimediaStreamsSpec] = DeriveJsonDecoder.gen[WikimediaStreamsSpec]

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
