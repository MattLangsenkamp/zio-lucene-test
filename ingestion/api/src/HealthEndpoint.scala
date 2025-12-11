package app.ingestion.api

import sttp.tapir.*

object HealthEndpoint:
  val healthEndpoint: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("health")
      .out(stringBody)
