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

  /** S3 client layer backed by a config. Reads the endpoint URL from `C` via `getUrl`:
    * - if non-empty, enables path-style access and overrides the endpoint (for LocalStack etc.)
    * - if empty, falls back to the standard AWS endpoint
    */
  def s3LayerFromConfig[C: Tag](getUrl: C => String): ZLayer[C, Throwable, S3] =
    ZLayer.service[C].flatMap { env =>
      val url = getUrl(env.get[C])
      if url.nonEmpty then
        AwsBase.awsBaseLayer >>> S3.customized(
          _.endpointOverride(java.net.URI.create(url)).forcePathStyle(true)
        )
      else s3Layer
    }
