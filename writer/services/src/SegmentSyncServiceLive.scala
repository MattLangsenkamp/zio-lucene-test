package app.writer.services

import app.writer.domain.internal.{CommitEvent, CommitQueueConfig, IndexConfig}
import zio.*
import zio.json.*
import zio.aws.sqs.Sqs
import zio.sqs.{SqsStream, SqsStreamSettings}

import org.apache.lucene.index.{DirectoryReader, IndexNotFoundException}
import org.apache.lucene.store.FSDirectory

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

final case class SegmentSyncServiceLive(
    sqs: Sqs,
    commitQueueConfig: CommitQueueConfig,
    indexConfig: IndexConfig,
    store: IndexSegmentStore
) extends SegmentSyncService:

  override def run(): ZIO[Any, Throwable, Unit] =
    SqsStream(
      queueUrl = commitQueueConfig.queueUrl,
      settings = SqsStreamSettings.default.withMaxNumberOfMessages(1)
    )
      .mapZIO { msg =>
        for
          _ <- ZIO.logInfo("Received commit event, syncing index to S3")
          _ <- getCurrentIndexFiles().flatMap:
                 case None =>
                   ZIO.logWarning("No committed index found, skipping sync (stale commit event)")
                 case Some((segmentFiles, segmentsFile)) =>
                   ZIO.foreachPar(segmentFiles)(store.uploadSegmentFile) *>
                     store.uploadCommitPoint(segmentsFile) *>
                     ZIO.logInfo(s"Synced index to S3: $segmentsFile (${segmentFiles.size} segment files)")
        yield msg
      }
      .run(SqsStream.deleteMessageBatchSink(commitQueueConfig.queueUrl))
      .provide(ZLayer.succeed(sqs))
      .tapError(err => ZIO.logError(s"SegmentSyncService error, retrying: ${err.toString}"))
      .retry(Schedule.spaced(5.seconds))

  private def getCurrentIndexFiles(): Task[Option[(Set[String], String)]] =
    ZIO.attemptBlockingIO:
      val dir = FSDirectory.open(Paths.get(indexConfig.indexPath))
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
  val layer: URLayer[Sqs & CommitQueueConfig & IndexConfig & IndexSegmentStore, SegmentSyncService] =
    ZLayer.fromFunction(SegmentSyncServiceLive.apply)
