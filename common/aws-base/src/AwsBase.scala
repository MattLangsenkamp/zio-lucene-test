package common.awsbase

import zio.*
import zio.aws.core.config.AwsConfig
import zio.aws.netty.NettyHttpClient

/** Foundational AWS connectivity layer shared by all AWS-backed services.
  *
  * Combines the Netty HTTP client with the default AWS SDK configuration (region, credentials
  * chain, etc.). Both `aws-sqs` and `aws-s3` depend on this module so the transport client is not
  * duplicated on the classpath.
  *
  * At runtime ZIO will memoize this layer by reference identity, ensuring only a single Netty
  * event loop is created even when multiple AWS service layers (e.g. SQS + S3) are composed
  * together in the same application.
  */
object AwsBase:

  /** Base AWS layer: Netty transport → AWS SDK configuration. */
  val awsBaseLayer: TaskLayer[AwsConfig] =
    NettyHttpClient.default >>> AwsConfig.default
