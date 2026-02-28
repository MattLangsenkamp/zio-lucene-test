package app.writer.domain.internal

import zio.*

final case class SqsConsumerConfig(queueUrl: String)

object SqsConsumerConfig:

  private def envRequired(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  val layer: TaskLayer[SqsConsumerConfig] =
    ZLayer.fromZIO:
      envRequired("SQS_QUEUE_URL").map(SqsConsumerConfig.apply)
