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
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.context.ContextStorage

import java.time.Duration as JDuration

object BaseTelemetry:

  /** Default OTLP endpoint for the collector running as a DaemonSet.
    * In Kubernetes, this connects to the collector via its service endpoint.
    * Can be overridden via OTEL_EXPORTER_OTLP_ENDPOINT environment variable.
    */
  private val DefaultOtlpEndpoint = "http://opentelemetry-stack-daemon-collector.opentelemetry-operator-system.svc.cluster.local:4317"

  /** Creates a Resource with service metadata for telemetry attribution. */
  private def createResource(serviceName: String): Resource =
    Resource.getDefault.toBuilder
      .put(ServiceAttributes.SERVICE_NAME, serviceName)
      .build()

  /** Creates an SdkTracerProvider configured to export traces via OTLP gRPC.
    *
    * Uses BatchSpanProcessor for efficient batching of spans before export.
    * Connects to the OTel collector DaemonSet running in the cluster.
    */
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

  /** Creates an SdkMeterProvider configured to export metrics via OTLP gRPC.
    *
    * Uses PeriodicMetricReader to export metrics at regular intervals (every 30 seconds).
    * Connects to the OTel collector DaemonSet running in the cluster.
    */
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

  /** Creates an SdkLoggerProvider configured to export logs via OTLP gRPC.
    *
    * Uses BatchLogRecordProcessor for efficient batching of log records before export.
    * Connects to the OTel collector DaemonSet running in the cluster.
    */
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

  /** Creates a fully configured OpenTelemetry SDK layer.
    *
    * Combines trace, metric, and log providers into a single SDK that exports
    * all telemetry to the OTel collector running in the Kubernetes cluster.
    * The collector then forwards data to Grafana Cloud.
    */
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
      yield sdk
    }

  /** Creates a complete telemetry layer with all ZIO OpenTelemetry services.
    *
    * Provides:
    * - OpenTelemetry SDK instance
    * - Tracing service for creating spans
    * - Meter service for recording metrics
    * - ContextStorage for span context propagation
    *
    * @param serviceName The name of the service for telemetry attribution
    * @return A ZLayer providing all telemetry services
    */
  def live(serviceName: String): TaskLayer[OpenTelemetry & Tracing & Meter & ContextStorage] =
    val sdk = sdkLayer(serviceName)
    sdk >+> ZOpenTelemetry.contextZIO >+> ZOpenTelemetry.tracing(serviceName) >+> ZOpenTelemetry.metrics(serviceName)
