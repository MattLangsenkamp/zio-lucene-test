package app.writer.services

import app.writer.domain.internal.{CommitEvent, CommitQueueConfig, IndexConfig}
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.sqs.{SqsStream, SqsStreamSettings}
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind
import common.activitylogging.*

import org.apache.lucene.index.{DirectoryReader, IndexNotFoundException}
import org.apache.lucene.store.FSDirectory

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

final case class SegmentSyncServiceLive(
    sqs: Sqs,
    commitQueueConfig: CommitQueueConfig,
    indexConfig: IndexConfig,
    store: IndexSegmentStore,
    tracing: Tracing
) extends SegmentSyncService:

  override def run(): ZIO[Any, Throwable, Unit] =
    SqsStream(
      queueUrl = commitQueueConfig.commitQueueUrl,
      settings = SqsStreamSettings.default.withMaxNumberOfMessages(1)
    )
      .mapZIO { msg =>
        tracing.span("sync-index-segment", SpanKind.CONSUMER):
          for
            _ <- ZIO.logActivity(SegmentSyncServiceLive.CommitEventReceived())
            _ <- getCurrentIndexFiles().flatMap:
                   case None =>
                     ZIO.logActivity(SegmentSyncServiceLive.StaleCommitEvent())
                   case Some((segmentFiles, segmentsFile)) =>
                     ZIO.foreachPar(segmentFiles)(store.uploadSegmentFile) *>
                       store.uploadCommitPoint(segmentsFile) *>
                       ZIO.logActivity(SegmentSyncServiceLive.IndexSyncedToS3(segmentsFile, segmentFiles.size))
          yield msg
      }
      .run(SqsStream.deleteMessageBatchSink(commitQueueConfig.commitQueueUrl))
      .provide(ZLayer.succeed(sqs))
      .tapError(err => ZIO.logActivity(SegmentSyncServiceLive.SegmentSyncError(err.toString)))
      .retry(Schedule.spaced(5.seconds))

  private def getCurrentIndexFiles(): Task[Option[(Set[String], String)]] =
    ZIO.attemptBlockingIO:
      val dir = FSDirectory.open(Paths.get(indexConfig.luceneIndexPath))
      try
        val commits = DirectoryReader.listCommits(dir).asScala
        val latest  = commits.last
        val segmentsFileName = latest.getSegmentsFileName
        val allFiles = latest.getFileNames.asScala.toSet
        val segmentFiles = allFiles.filter(f => f != segmentsFileName && f != "write.lock")
        Some((segmentFiles, segmentsFileName))
      catch
        case _: IndexNotFoundException => None
      finally
        dir.close()

object SegmentSyncServiceLive:
  case class CommitEventReceived()                                        extends InfoLog derives JsonCodec
  case class StaleCommitEvent()                                           extends WarnLog derives JsonCodec
  case class IndexSyncedToS3(segmentsFile: String, segmentFileCount: Int) extends InfoLog derives JsonCodec
  case class SegmentSyncError(message: String)                            extends ErrorLog derives JsonCodec

  val layer: URLayer[Sqs & CommitQueueConfig & IndexConfig & IndexSegmentStore & Tracing, SegmentSyncService] =
    ZLayer.fromFunction(SegmentSyncServiceLive.apply)
