package app.ingestion.domain.internal

import zio.*

final case class SqsPublisherConfig(queueUrl: String)

object SqsPublisherConfig:

  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  val layer: TaskLayer[SqsPublisherConfig] =
    ZLayer.fromZIO:
      envRequired("SQS_QUEUE_URL").map(SqsPublisherConfig.apply)
