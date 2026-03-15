package resources

import besom.*
import besom.api.aws.s3
import besom.api.aws.ssm
import besom.api.aws.Provider as AwsProvider

case class BucketInput(
  name: String,        // S3 bucket name prefix
  logicalName: String, // kebab-case identifier used in SSM path and Pulumi resource names
  env: String,         // stack name: local | dev | prod
  awsProvider: Option[Output[AwsProvider]] = None
)

case class BucketOutput(
  bucket: s3.Bucket,
  bucketName: Output[String],
  ssmParam: ssm.Parameter
)

object Buckets:

  def make(params: BucketInput)(using Context): Output[BucketOutput] =
    createBucket(params)

  def makeLocal(params: BucketInput)(using Context): Output[BucketOutput] =
    createBucket(params)

  private def createBucket(params: BucketInput)(using Context): Output[BucketOutput] =
    val providerOutput: Output[Option[AwsProvider]] =
      params.awsProvider.fold(Output(None))(_.map(Some(_)))

    providerOutput.flatMap { maybeProvider =>
      val bucket = s3.Bucket(
        NonEmptyString(params.logicalName).getOrElse {
          throw new IllegalArgumentException(s"Bucket logical name cannot be empty: '${params.logicalName}'")
        },
        s3.BucketArgs(
          forceDestroy = true,
          tags = Map("Name" -> params.name, "env" -> params.env)
        ),
        maybeProvider.fold(opts())(p => opts(provider = p))
      )

      bucket.flatMap { b =>
        // Write bucket name to SSM ParameterStore so ESO can surface it to pods.
        // bucket.id is the actual bucket name in AWS/LocalStack.
        val param = ssm.Parameter(
          s"${params.logicalName}-name-ssm",
          ssm.ParameterArgs(
            name = s"/zio-lucene/${params.env}/s3/${params.logicalName}",
            `type` = "String",
            value = b.id,
            overwrite = true,
            description = s"S3 bucket name for ${params.name} (${params.env})"
          ),
          maybeProvider.fold(opts(dependsOn = b))(p => opts(provider = p, dependsOn = b))
        )
        param.map { p =>
          BucketOutput(bucket = b, bucketName = b.id, ssmParam = p)
        }
      }
    }
