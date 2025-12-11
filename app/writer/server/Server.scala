package app.writer.server

import zio.ZIO
import zio.ZIOAppDefault
import zio.http.Server as ZServer
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import app.writer.api.HealthEndpoint

object Server extends ZIOAppDefault:

  private val healthServerEndpoint =
    HealthEndpoint.healthEndpoint.zServerLogic[Any](_ => ZIO.succeed("OK"))

  private val app = ZioHttpInterpreter().toHttp(healthServerEndpoint)

  def run =
    ZServer.serve(app).provide(ZServer.default)
