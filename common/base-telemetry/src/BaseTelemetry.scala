package common.basetelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import zio.*
import zio.logging.backend.SLF4J
import zio.telemetry.opentelemetry.OpenTelemetry as ZOpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.context.ContextStorage

import java.time.Duration as JDuration

object BaseTelemetry:

  /** SLF4J logging bootstrap layer.
    * Replace the default ZIO logger with SLF4J so logs flow through
    * Logback -> OpenTelemetryAppender -> OTel LoggerProvider.
    * This ensures traceId/spanId are automatically attached to log records.
    */
  val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  /** Default OTLP endpoint for the collector running as a DaemonSet. */
  private val DefaultOtlpEndpoint = "http://opentelemetry-stack-daemon-collector.opentelemetry-operator-system.svc.cluster.local:4317"

  private def createResource(serviceName: String): Resource =
    Resource.getDefault.toBuilder
      .put(ServiceAttributes.SERVICE_NAME, serviceName)
      .build()

  private def traceProvider(serviceName: String): RIO[Scope, SdkTracerProvider] =
    for
      endpoint <- System.env("OTEL_EXPORTER_OTLP_ENDPOINT").map(_.getOrElse(DefaultOtlpEndpoint))
      spanExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .build()
        )
      )
      spanProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          BatchSpanProcessor.builder(spanExporter).build()
        )
      )
      provider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider.builder()
            .setResource(createResource(serviceName))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )
    yield provider

  private def metricProvider(serviceName: String): RIO[Scope, SdkMeterProvider] =
    for
      endpoint <- System.env("OTEL_EXPORTER_OTLP_ENDPOINT").map(_.getOrElse(DefaultOtlpEndpoint))
      metricExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint)
            .build()
        )
      )
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader.builder(metricExporter)
            .setInterval(JDuration.ofSeconds(30))
            .build()
        )
      )
      provider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider.builder()
            .setResource(createResource(serviceName))
            .registerMetricReader(metricReader)
            .build()
        )
      )
    yield provider

  private def logProvider(serviceName: String): RIO[Scope, SdkLoggerProvider] =
    for
      endpoint <- System.env("OTEL_EXPORTER_OTLP_ENDPOINT").map(_.getOrElse(DefaultOtlpEndpoint))
      logExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(endpoint)
            .build()
        )
      )
      logProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          BatchLogRecordProcessor.builder(logExporter).build()
        )
      )
      provider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkLoggerProvider.builder()
            .setResource(createResource(serviceName))
            .addLogRecordProcessor(logProcessor)
            .build()
        )
      )
    yield provider

  private def sdkLayer(serviceName: String): TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for
        tracer <- traceProvider(serviceName)
        meter <- metricProvider(serviceName)
        logger <- logProvider(serviceName)
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk.builder()
              .setTracerProvider(tracer)
              .setMeterProvider(meter)
              .setLoggerProvider(logger)
              .build()
          )
        )
        _ <- ZIO.attempt(OpenTelemetryAppender.install(sdk))
      yield sdk
    }

  /** Creates a complete telemetry layer with all ZIO OpenTelemetry services.
    *
    * Provides OpenTelemetry SDK, Tracing, Meter, and ContextStorage.
    * Logs are routed via SLF4J/Logback -> OpenTelemetryAppender which
    * automatically attaches traceId/spanId to OTel log records.
    *
    * Services using this must set `override val bootstrap = BaseTelemetry.bootstrap`
    * in their ZIOAppDefault to redirect ZIO logs through SLF4J.
    */
  def live(serviceName: String): TaskLayer[OpenTelemetry & Tracing & Meter & ContextStorage] =
    ZLayer.make[OpenTelemetry & Tracing & Meter & ContextStorage](
      sdkLayer(serviceName),
      ZOpenTelemetry.contextZIO,
      ZOpenTelemetry.tracing(serviceName),
      ZOpenTelemetry.metrics(serviceName)
    )
