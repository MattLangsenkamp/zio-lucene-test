package common.activitylogging

import zio.*
import zio.json.*

// Logs are data, not strings. Instead of ZIO.logInfo("Health endpoint hit"),
// callers model log events as typed case classes with a JsonCodec. This makes
// logs structured, searchable, and impossible to misformat at the call site.
//
// Usage:
//   case class HealthCheckHit(path: String) extends InfoLog derives JsonCodec
//   ZIO.logActivity(HealthCheckHit("/health"))
trait ActivityLog:
  def logLevel: LogLevel

// Marker traits for each log level. Each one implements logLevel, so a case
// class only needs to extend the appropriate marker — the level is declared
// once at the definition site rather than repeated at every call site.
trait TraceLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Trace   }
trait DebugLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Debug   }
trait InfoLog  extends ActivityLog { override val logLevel: LogLevel = LogLevel.Info    }
trait WarnLog  extends ActivityLog { override val logLevel: LogLevel = LogLevel.Warning }
trait ErrorLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Error   }
trait FatalLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Fatal   }

// Extension on ZIO.type so the call site mirrors the built-in ZIO.logInfo /
// ZIO.logWarning etc. The JsonCodec constraint ensures the event is serialized
// to JSON rather than relying on toString.
extension (zio: ZIO.type)
  def logActivity[A <: ActivityLog: JsonCodec](a: A): UIO[Unit] =
    val json = a.toJson
    a.logLevel match
      case LogLevel.Trace   => ZIO.logTrace(json)
      case LogLevel.Debug   => ZIO.logDebug(json)
      case LogLevel.Info    => ZIO.logInfo(json)
      case LogLevel.Warning => ZIO.logWarning(json)
      case LogLevel.Error   => ZIO.logError(json)
      case LogLevel.Fatal   => ZIO.logFatal(json)
      case _                => ZIO.logInfo(json)
