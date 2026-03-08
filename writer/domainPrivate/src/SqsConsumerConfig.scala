package app.writer.domain.internal

import zio.*
import zio.config.magnolia.*

final case class SqsConsumerConfig(sqsQueueUrl: String) derives Config

object SqsConsumerConfig:
  val layer: TaskLayer[SqsConsumerConfig] =
    ZLayer.fromZIO(ZIO.config[SqsConsumerConfig])
