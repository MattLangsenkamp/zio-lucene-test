package utils

import besom.*
import besom.api.aws.s3

case class S3Input(
  name: String,
  enableVersioning: Boolean = false
)

case class S3Output(
  bucket: s3.Bucket
)

object S3 extends Resource[S3Input, S3Output, S3Input, S3Output]:

  override def make(inputParams: S3Input)(using Context): Output[S3Output] =
    for {
      bucket <- createBucket(inputParams)
    } yield S3Output(
      bucket = bucket
    )

  override def makeLocal(inputParams: S3Input)(using Context): Output[S3Output] =
    make(inputParams)

  private def createBucket(params: S3Input)(using Context): Output[s3.Bucket] =
    s3.Bucket(
      NonEmptyString(params.name).getOrElse {
        throw new IllegalArgumentException(s"S3 bucket name cannot be empty. Provided: '${params.name}'")
      },
      if params.enableVersioning then
        s3.BucketArgs(
          versioning = s3.inputs.BucketVersioningArgs(
            enabled = true
          )
        )
      else
        s3.BucketArgs()
    )

  // Convenience function for backward compatibility
  def createBucket(name: String)(using Context): Output[s3.Bucket] =
    make(S3Input(name)).map(_.bucket)
