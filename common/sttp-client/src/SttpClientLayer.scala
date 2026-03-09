package common.sttpclient

import zio.*
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.capabilities.zio.ZioStreams

/** Generic sttp ZIO HTTP client layer.
  *
  * Provides an async `SttpBackend` backed by Java's built-in `HttpClient`, suitable for any
  * service that needs to make outbound HTTP requests via sttp. This layer is not tied to any
  * specific upstream service or domain.
  *
  * Lifecycle is managed via ZIO `Scope`: the client is cleanly shut down when the application
  * exits.
  */
object SttpClientLayer:

  /** Scoped sttp backend layer. Acquire on startup, release on shutdown. */
  val sttpBackendLayer: TaskLayer[SttpBackend[Task, ZioStreams]] =
    ZLayer.scoped(HttpClientZioBackend.scoped())
