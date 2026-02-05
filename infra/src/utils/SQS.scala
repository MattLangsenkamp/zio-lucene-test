package utils

import besom.*
import besom.api.aws.sqs
import besom.api.aws.Provider as AwsProvider

case class SQSInput(
    queueName: String,
    awsProvider: Output[AwsProvider]
)

case class SQSOutput(
    queue: sqs.Queue,
    queueUrl: Output[String]
)

object SQS extends Resource[SQSInput, SQSOutput, SQSInput, SQSOutput]:

  override def make(inputParams: SQSInput)(using Context): Output[SQSOutput] =
    createQueue(inputParams)

  override def makeLocal(inputParams: SQSInput)(using Context): Output[SQSOutput] =
    createQueue(inputParams)

  private def createQueue(params: SQSInput)(using Context): Output[SQSOutput] =
    val prov: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(_.provider)

    val queue = sqs.Queue(
      NonEmptyString(params.queueName).getOrElse {
        throw new IllegalArgumentException(
          s"SQS queue name cannot be empty. Provided: '${params.queueName}'"
        )
      },
      sqs.QueueArgs(
        name = params.queueName,
        visibilityTimeoutSeconds = 30,
        messageRetentionSeconds = 86400, // 1 day
        tags = Map("Name" -> params.queueName)
      ),
      opts = opts(provider = prov)
    )

    queue.map { q =>
      SQSOutput(
        queue = q,
        queueUrl = q.url
      )
    }
