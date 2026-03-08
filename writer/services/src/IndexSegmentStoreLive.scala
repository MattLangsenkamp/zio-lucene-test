package app.writer.services

import app.writer.domain.internal.{IndexConfig, S3Config}
import zio.*
import zio.stream.ZStream
import zio.aws.s3.S3
import zio.aws.s3.model.{HeadObjectRequest, PutObjectRequest}
import zio.aws.s3.model.primitives.{BucketName, ContentLength, ObjectKey}
import zio.aws.core.AwsError

import software.amazon.awssdk.services.s3.model.NoSuchKeyException

import java.nio.file.{Files, Paths}

final case class IndexSegmentStoreLive(
    s3: S3,
    s3Config: S3Config,
    indexConfig: IndexConfig
) extends IndexSegmentStore:

  private def s3Key(filename: String): ObjectKey =
    ObjectKey(s"${s3Config.env}/index/$filename")

  override def uploadSegmentFile(filename: String): Task[Unit] =
    exists(filename).flatMap:
      case true  => ZIO.logDebug(s"Segment file $filename already in S3, skipping")
      case false => doUpload(filename)

  override def uploadCommitPoint(segmentsFileName: String): Task[Unit] =
    doUpload(segmentsFileName)

  override def exists(filename: String): Task[Boolean] =
    s3.headObject(
      HeadObjectRequest(
        bucket = BucketName(s3Config.bucket),
        key    = s3Key(filename)
      )
    )
      .as(true)
      .catchAll { err =>
        err.toThrowable match
          case _: NoSuchKeyException => ZIO.succeed(false)
          case t                     => ZIO.fail(t)
      }

  private def doUpload(filename: String): Task[Unit] =
    val path = Paths.get(indexConfig.indexPath, filename)
    ZIO.attemptBlockingIO(Files.size(path)).flatMap { fileSize =>
      s3.putObject(
        PutObjectRequest(
          bucket        = BucketName(s3Config.bucket),
          key           = s3Key(filename),
          contentLength = Some(ContentLength(fileSize))
        ),
        ZStream.fromPath(path).mapError(AwsError.fromThrowable)
      )
        .mapError(_.toThrowable)
        .unit
        .tapBoth(
          err => ZIO.logError(s"Failed to upload $filename to S3: ${err.getMessage}"),
          _   => ZIO.logDebug(s"Uploaded $filename to S3")
        )
    }

object IndexSegmentStoreLive:
  val layer: URLayer[S3 & S3Config & IndexConfig, IndexSegmentStore] =
    ZLayer.fromFunction(IndexSegmentStoreLive.apply)
