package common.awssqs

import zio.*
import zio.aws.sqs.Sqs
import common.awsbase.AwsBase

/** ZLayer for the AWS SQS client.
  *
  * Builds on `AwsBase.awsBaseLayer` (Netty + AwsConfig) and wires in the SQS service. Services
  * that only need SQS depend on this module; the S3 client and its dependencies are never pulled
  * onto their compile or runtime classpath.
  */
object AwsSqs:

  /** SQS client layer, ready to inject into any ZIO service that requires `Sqs`. */
  val sqsLayer: TaskLayer[Sqs] = AwsBase.awsBaseLayer >>> Sqs.live
