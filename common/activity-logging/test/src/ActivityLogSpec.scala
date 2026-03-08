package common.activitylogging

import zio.*
import zio.test.*
import zio.json.*
import zio.json.ast.Json

object ActivityLogSpec extends ZIOSpecDefault:

  // --- Test fixtures ---

  case class SimpleEvent(message: String) extends InfoLog derives JsonCodec

  case class MultiFieldEvent(userId: String, action: String, count: Int) extends WarnLog derives JsonCodec

  // Nested — inner type carries its own codec, serialized as a JSON object
  case class Metadata(key: String, value: String) derives JsonCodec
  case class EventWithMetadata(name: String, metadata: Metadata) extends ErrorLog derives JsonCodec

  case class BatchEvent(items: List[String], count: Int) extends DebugLog derives JsonCodec

  // Deeply nested
  case class Inner(score: Int, tag: String) derives JsonCodec
  case class DeepEvent(label: String, inner: Inner) extends TraceLog derives JsonCodec

  // --- Helpers ---

  def parse(s: String): Json =
    s.fromJson[Json].getOrElse(Json.Null)

  def field(json: Json, key: String): Option[Json] = json match
    case Json.Obj(fields) => fields.find(_._1 == key).map(_._2)
    case _                => None

  def str(json: Json, key: String): Option[String] = field(json, key).collect:
    case Json.Str(v) => v

  def int(json: Json, key: String): Option[Int] = field(json, key).collect:
    case Json.Num(n) => n.intValue()

  def arr(json: Json, key: String): Option[Chunk[Json]] = field(json, key).collect:
    case Json.Arr(elems) => elems

  // --- Tests ---

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ActivityLogSpec")(
    suite("JSON serialization")(
      test("simple event includes field and _ActivityLog class tag") {
        val json = parse(toActivityJson(SimpleEvent("hello")))
        assertTrue(
          str(json, "message").contains("hello"),
          str(json, "_ActivityLog").contains("SimpleEvent")
        )
      },
      test("multi-field event serializes all primitive fields") {
        val json = parse(toActivityJson(MultiFieldEvent("u123", "click", 5)))
        assertTrue(
          str(json, "userId").contains("u123"),
          str(json, "action").contains("click"),
          int(json, "count").contains(5),
          str(json, "_ActivityLog").contains("MultiFieldEvent")
        )
      },
      test("nested case class serializes as a JSON object") {
        val event = EventWithMetadata("deploy", Metadata("env", "prod"))
        val json  = parse(toActivityJson(event))
        val meta  = field(json, "metadata")
        assertTrue(
          str(json, "name").contains("deploy"),
          str(json, "_ActivityLog").contains("EventWithMetadata"),
          meta.flatMap(str(_, "key")).contains("env"),
          meta.flatMap(str(_, "value")).contains("prod")
        )
      },
      test("list field serializes as a JSON array") {
        val event = BatchEvent(List("a", "b", "c"), 3)
        val json  = parse(toActivityJson(event))
        assertTrue(
          arr(json, "items").contains(Chunk(Json.Str("a"), Json.Str("b"), Json.Str("c"))),
          int(json, "count").contains(3),
          str(json, "_ActivityLog").contains("BatchEvent")
        )
      },
      test("deeply nested case class serializes inner object correctly") {
        val event = DeepEvent("root", Inner(99, "fast"))
        val json  = parse(toActivityJson(event))
        val inner = field(json, "inner")
        assertTrue(
          str(json, "label").contains("root"),
          str(json, "_ActivityLog").contains("DeepEvent"),
          inner.flatMap(int(_, "score")).contains(99),
          inner.flatMap(str(_, "tag")).contains("fast")
        )
      }
    ),
    suite("log levels")(
      test("InfoLog reports Info level") {
        assertTrue(SimpleEvent("x").logLevel == LogLevel.Info)
      },
      test("WarnLog reports Warning level") {
        assertTrue(MultiFieldEvent("u", "a", 1).logLevel == LogLevel.Warning)
      },
      test("ErrorLog reports Error level") {
        assertTrue(EventWithMetadata("e", Metadata("k", "v")).logLevel == LogLevel.Error)
      },
      test("DebugLog reports Debug level") {
        assertTrue(BatchEvent(Nil, 0).logLevel == LogLevel.Debug)
      },
      test("TraceLog reports Trace level") {
        assertTrue(DeepEvent("x", Inner(0, "")).logLevel == LogLevel.Trace)
      }
    )
  )
