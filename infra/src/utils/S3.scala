package utils

import besom.*
import besom.api.aws.s3
import besom.api.aws.Provider as AwsProvider

case class S3Input(
    name: String,
    awsProvider: Output[AwsProvider]
)

case class S3Output(
    bucket: s3.Bucket
)

object S3 extends Resource[S3Input, S3Output, S3Input, S3Output]:

  override def make(inputParams: S3Input)(using c: Context): Output[S3Output] =
    for {
      bucket <- createBucket(inputParams)
    } yield S3Output(
      bucket = bucket
    )

  override def makeLocal(inputParams: S3Input)(using
      c: Context
  ): Output[S3Output] =
    make(inputParams)

  private def createBucket(
      params: S3Input
  )(using c: Context): Output[s3.Bucket] = {
    val prov: Output[Option[ProviderResource]] =
      params.awsProvider.flatMap(ok => ok.provider)
    s3.Bucket(
      NonEmptyString(params.name).getOrElse {
        throw new IllegalArgumentException(
          s"S3 bucket name cannot be empty. Provided: '${params.name}'"
        )
      },
      s3.BucketArgs(),
      opts = opts(provider = prov)
    )
  }
