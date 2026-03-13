package common.awss3

import zio.*
import zio.aws.s3.S3
import common.awsbase.AwsBase

/** ZLayer for the AWS S3 client.
  *
  * Builds on `AwsBase.awsBaseLayer` (Netty + AwsConfig) and wires in the S3 service. Services
  * that only need S3 depend on this module; the SQS client and its dependencies are never pulled
  * onto their compile or runtime classpath.
  */
object AwsS3:

  /** S3 client layer, ready to inject into any ZIO service that requires `S3`. */
  val s3Layer: TaskLayer[S3] = AwsBase.awsBaseLayer >>> S3.live

  /** S3 client layer with an explicit endpoint override and path-style access enabled.
    * Required for LocalStack and other S3-compatible stores that don't support virtual-hosted-style
    * URLs. Supply the endpoint URL (e.g. `http://host.k3d.internal:4566`).
    */
  def s3LayerWithEndpoint(endpointUrl: String): TaskLayer[S3] =
    AwsBase.awsBaseLayer >>> S3.customized(
      _.endpointOverride(java.net.URI.create(endpointUrl)).forcePathStyle(true)
    )
