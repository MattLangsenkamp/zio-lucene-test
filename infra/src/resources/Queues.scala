package resources

import besom.*
import besom.api.aws.sqs
import besom.api.aws.ssm
import besom.api.aws.Provider as AwsProvider

case class QueueInput(
  name: String,        // SQS queue name
  logicalName: String, // kebab-case identifier used in SSM path and Pulumi resource names
  env: String,         // stack name: local | dev | prod
  awsProvider: Output[AwsProvider]
)

case class QueueOutput(
  queue: sqs.Queue,
  queueUrl: Output[String],
  ssmParam: ssm.Parameter
)

object Queues:

  def make(params: QueueInput)(using Context): Output[QueueOutput] =
    createQueue(params)

  def makeLocal(params: QueueInput)(using Context): Output[QueueOutput] =
    createQueue(params)

  private def createQueue(params: QueueInput)(using Context): Output[QueueOutput] =
    params.awsProvider.flatMap { awsProv =>
      val queue = sqs.Queue(
        NonEmptyString(params.logicalName).getOrElse {
          throw new IllegalArgumentException(s"Queue logical name cannot be empty: '${params.logicalName}'")
        },
        sqs.QueueArgs(
          name = params.name,
          visibilityTimeoutSeconds = 30,
          messageRetentionSeconds = 86400,
          tags = Map("Name" -> params.name, "env" -> params.env)
        ),
        opts(provider = awsProv)
      )

      queue.flatMap { q =>
        // Write queue URL to SSM ParameterStore so ESO can surface it to pods
        val param = ssm.Parameter(
          s"${params.logicalName}-url-ssm",
          ssm.ParameterArgs(
            name = s"/zio-lucene/${params.env}/sqs/${params.logicalName}",
            `type` = "String",
            value = q.url,
            overwrite = true,
            description = s"SQS queue URL for ${params.name} (${params.env})"
          ),
          opts(provider = awsProv, dependsOn = q)
        )
        param.map { p =>
          QueueOutput(queue = q, queueUrl = q.url, ssmParam = p)
        }
      }
    }
