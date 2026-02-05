package common.basetelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import zio.*
import zio.http.{Handler, Middleware, Request, Response, Routes}
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.time.Instant

private[basetelemetry] object ContextStorageHelper:
  /** Extract the FiberRef[Context] from ContextStorage using reflection.
    * ContextStorage.ZIOFiberRef stores context in a FiberRef, but the type isn't easily accessible.
    */
  def extractFiberRef(storage: ContextStorage): Option[FiberRef[Context]] =
    try
      val field = storage.getClass.getDeclaredField("ref")
      field.setAccessible(true)
      Some(field.get(storage).asInstanceOf[FiberRef[Context]])
    catch
      case _: Exception => None

object OtelLoggerMiddleware:

  /** IncomingContextCarrier for extracting trace context from HTTP request headers */
  private class RequestCarrier(val kernel: Request) extends IncomingContextCarrier[Request]:
    override def getAllKeys(carrier: Request): Iterable[String] =
      carrier.headers.toList.map(_.headerName)

    override def getByKey(carrier: Request, key: String): Option[String] =
      carrier.headers.get(key)

  /** Creates a ZLogger that forwards logs to OpenTelemetry's log bridge with proper trace context.
    *
    * @param otel
    *   OpenTelemetry instance for creating the logger
    * @param contextRef
    *   FiberRef containing the OTel Context (extracted from ContextStorage via reflection)
    */
  private def createOtelLogger(
      otel: OpenTelemetry,
      contextRef: FiberRef[Context]
  ): ZLogger[String, Unit] =
    new ZLogger[String, Unit]:
      private val otelLogger = otel.getLogsBridge.loggerBuilder("zio-app").build()

      override def apply(
          trace: Trace,
          fiberId: FiberId,
          logLevel: LogLevel,
          message: () => String,
          cause: Cause[Any],
          context: FiberRefs,
          spans: List[LogSpan],
          annotations: Map[String, String]
      ): Unit =
        val severity = logLevel match
          case LogLevel.Trace   => Severity.TRACE
          case LogLevel.Debug   => Severity.DEBUG
          case LogLevel.Info    => Severity.INFO
          case LogLevel.Warning => Severity.WARN
          case LogLevel.Error   => Severity.ERROR
          case LogLevel.Fatal   => Severity.FATAL
          case _                => Severity.UNDEFINED_SEVERITY_NUMBER

        // Get trace context directly from FiberRefs (synchronous access)
        val otelContext = context.get(contextRef).getOrElse(Context.root())

        val builder = otelLogger
          .logRecordBuilder()
          .setSeverity(severity)
          .setSeverityText(logLevel.label)
          .setBody(message())
          .setTimestamp(Instant.now())
          .setContext(otelContext)

        // Add annotations as attributes
        val attrs = Attributes.builder()
        annotations.foreach { case (k, v) => attrs.put(AttributeKey.stringKey(k), v) }

        // Add trace location
        trace match
          case Trace(location, file, line) =>
            attrs.put(AttributeKey.stringKey("code.filepath"), file)
            attrs.put(AttributeKey.longKey("code.lineno"), line.toLong)
          case _ => ()

        builder.setAllAttributes(attrs.build())
        builder.emit()

  /** Middleware that:
    *   1. Extracts W3C trace context from request headers (traceparent/tracestate)
    *   2. Creates a server span (as child if context found, or root if not)
    *   3. Adds the OTel logger to the request fiber
    *   4. Runs the handler within the span context
    *
    * @param otel
    *   OpenTelemetry instance
    * @param contextStorage
    *   ContextStorage service for accessing OTel Context
    * @param tracing
    *   Tracing service
    */
  def middleware(
      otel: OpenTelemetry,
      contextStorage: ContextStorage,
      tracing: Tracing
  ): Middleware[Any] =
    // Extract the FiberRef[Context] from ContextStorage using reflection
    val contextRef = ContextStorageHelper.extractFiberRef(contextStorage)
      .getOrElse(throw new RuntimeException("Failed to extract FiberRef from ContextStorage - expected ZIOFiberRef implementation"))

    val logger = createOtelLogger(otel, contextRef)

    new Middleware[Any]:
      def apply[Env1 <: Any, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] =
        routes.transform { handler =>
          Handler.fromFunctionZIO { (request: Request) =>
            val spanName = s"${request.method.name} ${request.path.encode}"
            val carrier = new RequestCarrier(request)

            // Add OTel logger and run within an extracted span
            // extractSpan will:
            // - Extract trace context from headers using W3C propagator
            // - Create a child span if context found, or root span if not
            // - Run the effect within that span
            val handlerWithLogging = ZIO.withLogger(logger)(handler.runZIO(request))

            tracing.extractSpan(
              TraceContextPropagator.default,
              carrier,
              spanName,
              SpanKind.SERVER
            )(handlerWithLogging)
          }
        }
