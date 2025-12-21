package utils

import besom.*
import besom.api.aws.s3

object S3:
  def createBucket(name: String)(using Context): Output[s3.Bucket] =
    s3.Bucket(
      NonEmptyString(name).getOrElse("default"),
      s3.BucketArgs()
    )

  def createBucketWithVersioning(name: String)(using Context): Output[s3.Bucket] =
    s3.Bucket(
      NonEmptyString(name).getOrElse("default"),
      s3.BucketArgs(
        versioning = s3.inputs.BucketVersioningArgs(
          enabled = true
        )
      )
    )
