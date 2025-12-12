//> using jvm "21"
//> using scala "3.7.4"
//> using dep "org.virtuslab::besom-core:0.5.0"
//> using dep "org.virtuslab::besom-aws:7.7.0-core.0.5"

import besom.*
import besom.api.aws.s3

@main def main = Pulumi.run {
  val bucket = s3.Bucket(
    "segments",
    s3.BucketArgs()
  )

  Stack(bucket)
    .exports(
      bucketName = bucket.id
    )
}
