package app.ingestion.domain.internal

import zio.*
import zio.config.magnolia.*

final case class SqsPublisherConfig(sqsQueueUrl: String) derives Config

object SqsPublisherConfig:
  val layer: TaskLayer[SqsPublisherConfig] =
    ZLayer.fromZIO(ZIO.config[SqsPublisherConfig])
