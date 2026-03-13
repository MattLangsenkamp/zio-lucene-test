package app.writer.domain.internal

import zio.*
import zio.config.magnolia.*

final case class S3Config(storageBucket: String, storageEnv: String, storageEndpointUrl: String) derives Config

object S3Config:
  val layer: TaskLayer[S3Config] =
    ZLayer.fromZIO(ZIO.config[S3Config])
