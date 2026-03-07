package app.writer.services

import app.ingestion.domain.IngestionEvent
import app.writer.domain.internal.{IndexConfig, toLuceneDocument}
import zio.*

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.{IndexWriter, IndexWriterConfig}
import org.apache.lucene.store.FSDirectory

import java.nio.file.{Files, Paths}

final case class DocumentIndexerLive(
    writer: IndexWriter
) extends DocumentIndexer:

  override def indexBatch(documents: Chunk[IngestionEvent]): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlockingIO:
      documents.foreach(event => writer.addDocument(event.toLuceneDocument))

  override def flush(): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlockingIO(writer.flush())

  override def commit(): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlockingIO(writer.commit())

object DocumentIndexerLive:

  val layer: RLayer[IndexConfig, DocumentIndexer] =
    ZLayer.scoped:
      for
        config <- ZIO.service[IndexConfig]
        _      <- ZIO.attemptBlockingIO(Files.createDirectories(Paths.get(config.indexPath)))
        dir    <- ZIO.acquireRelease(
                    ZIO.attemptBlockingIO(FSDirectory.open(Paths.get(config.indexPath)))
                  )(d => ZIO.attemptBlockingIO(d.close()).orDie)
        writer <- ZIO.acquireRelease(
                    ZIO.attemptBlockingIO:
                      val iwConfig = new IndexWriterConfig(new StandardAnalyzer())
                      new IndexWriter(dir, iwConfig)
                  )(w => ZIO.attemptBlockingIO(w.close()).orDie)
      yield DocumentIndexerLive(writer)
