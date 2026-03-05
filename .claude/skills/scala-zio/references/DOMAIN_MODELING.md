# Domain Modeling Reference

This document covers conventions for modeling data in this project.

---

## Core Principles

- Follow standard Scala 3 and functional programming conventions
- All data is **immutable** — `val` everywhere, `var` is heavily discouraged
- Case classes and enums are the primary modeling tools
- **Avoid raw primitives** (`String`, `Int`, `Float`, `Boolean`, etc.) for domain values — wrap them in newtypes or opaque types instead

---

## Case Classes

Use `case class` for product types (data with multiple fields). They are immutable by default and provide structural equality, `copy`, and pattern matching for free.

```scala
case class Article(
  id: ArticleId,
  title: Title,
  content: Content,
  author: UserId
)
```

Convenience methods are fine as long as they are **pure** (no side effects, no mutation):

```scala
case class FullName(first: FirstName, last: LastName):
  def display: String = s"${first.value} ${last.value}"
```

Never define methods that mutate state or return `Unit` (a method returning `Unit` almost certainly has a side effect).

---

## Enums

Use Scala 3 `enum` for sum types (a value that is one of several cases):

```scala
enum IngestionSource:
  case Wikipedia, Wikidata

enum Status:
  case Active
  case Inactive(reason: String)
```

Prefer `enum` over `sealed trait` + `case class` for simple sum types. Use `sealed trait` only when you need more control (e.g., mixed-in interfaces on variants).

---

## Newtypes with Neotype

Avoid using primitives directly for domain values. A raw `String` for a user ID and a raw `String` for a queue URL are indistinguishable to the compiler — wrapping them catches mistakes at compile time and makes code self-documenting.

Use the [neotype](https://github.com/kitlangton/neotype) library for this. Neotype creates zero-cost newtypes with optional compile-time or runtime validation.

### Simple newtype (no validation)

```scala
import neotype.*

object UserId extends Newtype[String]
type UserId = UserId.Type

object QueueUrl extends Newtype[String]
type QueueUrl = QueueUrl.Type
```

### Newtype with validation

Validation runs at construction time. Invalid values are rejected at compile time when the input is a literal, or produce an error at runtime otherwise.

```scala
import neotype.*

object Title extends Newtype[String]:
  override inline def validate(value: String): Boolean | String =
    if value.nonEmpty then true
    else "Title must not be empty"

type Title = Title.Type
```

### Constructing and unwrapping

```scala
val id: UserId = UserId("abc-123")   // compile-time check if literal
val raw: String = id.unwrap          // unwrap with .unwrap (requires: import neotype.unwrap)
```

### Mill dependency

Choose the variant that matches the needs of the module:

| Needs | `mvnDeps` value |
|---|---|
| Neotype only | `neotypeDeps.core` |
| Neotype + ZIO JSON codecs | `neotypeDeps.withJson` |
| Neotype + ZIO Quill codecs | `neotypeDeps.withQuill` |
| Neotype + both | `neotypeDeps.withBoth` |

```scala
object domainPublic extends ScalaModule {
  def scalaVersion = VersionInfo.scalaVersion
  def mvnDeps = zioJsonDeps ++ neotypeDeps.withJson
}
```

---

## Opaque Types

Use Scala 3 `opaque type` when:
- You want a zero-cost type alias with no runtime wrapper
- The type needs **no validation** and **no library integration** (no JSON codec, no DB codec)
- You want to hide the underlying representation entirely within a module

```scala
object Ids:
  opaque type CorrelationId = String
  object CorrelationId:
    def apply(s: String): CorrelationId = s
    extension (id: CorrelationId) def value: String = id
```

If you need JSON codecs, DB codecs, or validation, prefer **neotype** over opaque types — deriving those integrations manually for opaque types is tedious.

---

## Choosing Between Newtype and Opaque Type

| Situation | Use |
|---|---|
| Domain ID or value with no validation | `Newtype` (neotype) — free codecs via extensions |
| Domain value with validation rules | `Newtype` with `validate` override |
| Internal-only alias, never serialized | `opaque type` |
| You need full control over representation and no codec | `opaque type` |

When in doubt, default to neotype — the compile-time validation and automatic codec derivation are worth it.

---

## Codecs and `derives`

### Always use `derives` syntax

Use Scala 3 `derives` to generate codec instances. Never write `implicit val` or `given` codec definitions by hand unless the derived version genuinely cannot work.

```scala
// correct
case class Article(id: ArticleId, title: Title) derives JsonCodec

// avoid — manual codec is unnecessary boilerplate when derives works
object Article:
  given JsonCodec[Article] = DeriveJsonCodec.gen[Article]
```

Enums derive the same way:

```scala
enum IngestionSource derives JsonCodec:
  case Wikipedia, Wikidata
```

### Codecs live next to the type definition

Codecs for a type belong in the **same file as the type**, in the same package. Do not collect codecs in a separate `Codecs.scala` or `JsonFormats.scala` file. This keeps the type and all its representations in one place and avoids implicit-hunting across files.

```scala
// domainPublic/src/Article.scala
package app.myservice.domain

import zio.json.*
import neotype.*
import neotype.ziojson.*

object ArticleId extends Newtype[String]
type ArticleId = ArticleId.Type

case class Article(id: ArticleId, title: String) derives JsonCodec
```

### ZIO JSON + neotype

Add `neotypeDeps.withJson` to the module. Import `neotype.ziojson.*` to bring automatic newtype codec derivation into scope — no manual codec needed for any `Newtype` field.

```scala
import neotype.*
import neotype.ziojson.*
import zio.json.*

object UserId extends Newtype[String]
type UserId = UserId.Type

case class User(id: UserId, name: String) derives JsonCodec
```

---

## Model-to-Model Translations

Conversions between internal domain models and external representations belong in the **domain package**, not in service implementations. This keeps services focused on orchestration and pushes representation knowledge down to where the models live.

Examples of where translations belong:
- **gRPC ↔ internal model** — in the domain package of the service that owns the internal type
- **Third-party library types ↔ internal model** (e.g. Lucene `Document`, Kafka `ProducerRecord`) — in the domain package of the consuming service
- **JSON ↔ internal model** — via `derives JsonCodec` on the type itself (see Codecs section above)

Use Scala 3 **extension methods** to add the conversion directly to the type:

```scala
// writer/domainPrivate/src/LuceneConversions.scala
package app.writer.domain.internal

import app.ingestion.domain.IngestionEvent
import org.apache.lucene.document.{Document, Field, StringField, TextField}

extension (event: IngestionEvent)
  def toLuceneDocument: Document = ...
```

The service then imports the extension and calls it naturally:

```scala
import app.writer.domain.internal.toLuceneDocument

writer.addDocument(event.toLuceneDocument)
```

This ensures that if the external library changes, only the domain package needs updating — services are unaffected.

---

## What to Avoid

| Pattern | Why | Alternative |
|---|---|---|
| `var x = ...` | Mutable state breaks reasoning | `val` + `copy` |
| `def process(name: String, id: String)` | Primitive confusion | `def process(name: Name, id: UserId)` |
| Mutable collections (`ArrayBuffer`, etc.) | Hidden mutation | `List`, `Vector`, `Seq` |
| Methods returning `Unit` on case classes | Implies side effect | Pure methods only |
| `null` | Partial values | `Option[A]` |
| Codecs in a separate `Codecs.scala` file | Hard to find, implicit leakage | Define codec with `derives` next to the type |
| `implicit val` / `given` codec written by hand | Brittle boilerplate | `derives JsonCodec` |
