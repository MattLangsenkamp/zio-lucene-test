package app.reader.api

import sttp.tapir.*

object HealthEndpoint:
  val healthEndpoint: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("search" / "health")
      .out(stringBody)
