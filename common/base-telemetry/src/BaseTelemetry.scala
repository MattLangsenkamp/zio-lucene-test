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
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry as ZOpenTelemetry
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter

import zio.telemetry.opentelemetry.context.ContextStorage

import java.time.Duration as JDuration

type TelemetryEnv = OpenTelemetry & Tracing & Meter & ContextStorage & Baggage

object BaseTelemetry:

  /** OTLP gRPC endpoint for the DaemonSet collector. */
  private val OtlpEndpoint =
    "http://opentelemetry-stack-daemon-collector.opentelemetry-operator-system.svc.cluster.local:4317"

  private def createResource(serviceName: String): Resource =
    Resource.getDefault.toBuilder
      .put(ServiceAttributes.SERVICE_NAME, serviceName)
      .build()

  private def traceProvider(serviceName: String): RIO[Scope, SdkTracerProvider] =
    for
      spanExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcSpanExporter
            .builder()
            .setEndpoint(OtlpEndpoint)
            .build()
        )
      )
      spanProcessor <- ZIO.fromAutoCloseable(ZIO.succeed(BatchSpanProcessor.builder(spanExporter).build()))
      provider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider
            .builder()
            .setResource(createResource(serviceName))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )
    yield provider

  private def metricProvider(serviceName: String): RIO[Scope, SdkMeterProvider] =
    for
      metricExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcMetricExporter
            .builder()
            .setEndpoint(OtlpEndpoint)
            .build()
        )
      )
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader
            .builder(metricExporter)
            .setInterval(JDuration.ofSeconds(30))
            .build()
        )
      )
      provider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider
            .builder()
            .setResource(createResource(serviceName))
            .registerMetricReader(metricReader)
            .build()
        )
      )
    yield provider

  private def logProvider(serviceName: String): RIO[Scope, SdkLoggerProvider] =
    for
      logExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcLogRecordExporter
            .builder()
            .setEndpoint(OtlpEndpoint)
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
          SdkLoggerProvider
            .builder()
            .setResource(createResource(serviceName))
            .addLogRecordProcessor(logProcessor)
            .build()
        )
      )
    yield provider

  private def sdkLayer(serviceName: String): RLayer[Scope, OpenTelemetry] =
    ZLayer.fromZIO {
      for
        tracer <- traceProvider(serviceName)
        meter <- metricProvider(serviceName)
        logger <- logProvider(serviceName)
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk
              .builder()
              .setTracerProvider(tracer)
              .setMeterProvider(meter)
              .setLoggerProvider(logger)
              .build()
          )
        )
      yield sdk
    }

  /** Creates a complete telemetry layer with all ZIO OpenTelemetry services.
    *
    * Provides OpenTelemetry SDK, Tracing, Meter, ContextStorage, and Baggage. Requires Scope from the caller - this
    * ensures that scoped FiberRef modifications (like the OTel logging bridge) live in the caller's scope and propagate
    * correctly.
    *
    * @param serviceName
    *   The name of the service for telemetry attribution
    */
  def live(serviceName: String): RLayer[Scope, TelemetryEnv] =
    ZLayer.makeSome[Scope, TelemetryEnv](
      sdkLayer(serviceName),
      ZOpenTelemetry.contextZIO,
      ZOpenTelemetry.baggage(),
      ZOpenTelemetry.tracing(serviceName),
      ZOpenTelemetry.metrics(serviceName),
      ZOpenTelemetry.logging(serviceName),
      ZOpenTelemetry.zioMetrics
    )
