package app.writer.domain.internal

import app.ingestion.domain.IngestionEvent
import org.apache.lucene.document.{Document, Field, StringField, TextField}

extension (event: IngestionEvent)
  def toLuceneDocument: Document =
    val doc = new Document()

    def addText(name: String, valueOpt: Option[String]): Unit =
      valueOpt.filter(_.nonEmpty).foreach(v => doc.add(new TextField(name, v, Field.Store.YES)))

    def addString(name: String, valueOpt: Option[String]): Unit =
      valueOpt.filter(_.nonEmpty).foreach(v => doc.add(new StringField(name, v, Field.Store.YES)))

    doc.add(new StringField("source", event.source.toString, Field.Store.YES))
    addText("title", event.title)
    addString("user", event.user)
    addString("timestamp", event.timestamp)
    addString("eventType", event.eventType)
    addString("pageUrl", event.pageUrl)
    addString("wiki", event.wiki)
    event.isBot.foreach(b => doc.add(new StringField("isBot", b.toString, Field.Store.YES)))

    event.extras
      .filter(e => e.key.nonEmpty && e.value.nonEmpty)
      .foreach(e => doc.add(new TextField(e.key, e.value, Field.Store.YES)))

    doc
