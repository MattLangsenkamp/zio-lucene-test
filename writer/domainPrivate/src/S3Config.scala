package app.writer.domain.internal

import zio.*

final case class S3Config(bucket: String, env: String)

object S3Config:

  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  val layer: TaskLayer[S3Config] =
    ZLayer.fromZIO:
      for
        bucket <- envRequired("S3_BUCKET")
        env    <- envRequired("S3_ENV")
      yield S3Config(bucket, env)
