package com.inventoryplatform.common.tracing;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * Wires OpenTelemetry to Micrometer Tracing by hand.
 *
 * <p><strong>Why this exists.</strong> Spring Boot 4.1 ships no auto-configuration joining the two.
 * {@code spring-boot-opentelemetry} provides the SDK and logging only, and
 * {@code spring-boot-micrometer-tracing} provides {@code NoopTracerAutoConfiguration} — so a project
 * with both on the classpath gets a <em>no-op</em> tracer, every trace id is blank, and nothing
 * complains. That is precisely the silent failure BUILD_PROMPT.md §3 legislates against: a trace id
 * must reach every log line, and the support id in every error response depends on it.
 *
 * <p>Explicit wiring is also the more honest arrangement here — the trace plumbing is load-bearing
 * for supporting a non-technical customer, so it should be visible code rather than an inference
 * from what happens to be on the classpath.
 *
 * <p><strong>No exporter.</strong> Spans are created and ids propagate, but nothing is shipped
 * anywhere: §9 requires that no telemetry leaves the machine without explicit opt-in. The value here
 * is correlation within the install's own logs, not observability-as-a-service. An exporter becomes
 * a configuration choice in the cloud phase.
 */
@AutoConfiguration
public class PlatformTracingAutoConfiguration {

    /**
     * W3C {@code traceparent}, so a trace survives a real HTTP hop in cloud mode and matches what
     * the browser and any future proxy already speak.
     */
    @Bean
    @ConditionalOnMissingBean
    ContextPropagators contextPropagators() {
        return ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    }

    /**
     * Samples everything. This is one shopkeeper's PC, not a high-volume service, and the trace that
     * matters is always the one request that failed.
     */
    @Bean
    @ConditionalOnMissingBean
    SdkTracerProvider sdkTracerProvider() {
        return SdkTracerProvider.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider, ContextPropagators propagators) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(propagators)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    io.opentelemetry.api.trace.Tracer otelTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.inventoryplatform");
    }

    @Bean
    @ConditionalOnMissingBean
    OtelCurrentTraceContext otelCurrentTraceContext() {
        return new OtelCurrentTraceContext();
    }

    /**
     * The bean whose absence caused the whole problem: with a real {@link Tracer} present, Boot's
     * {@code MicrometerTracingAutoConfiguration} contributes the observation handlers and
     * {@code NoopTracerAutoConfiguration} backs off.
     */
    @Bean
    @ConditionalOnMissingBean(Tracer.class)
    OtelTracer micrometerTracer(
            io.opentelemetry.api.trace.Tracer otelTracer, OtelCurrentTraceContext currentTraceContext) {
        return new OtelTracer(otelTracer, currentTraceContext, event -> {});
    }

    @Bean
    @ConditionalOnMissingBean
    OtelPropagator otelPropagator(
            ContextPropagators propagators, io.opentelemetry.api.trace.Tracer otelTracer) {
        return new OtelPropagator(propagators, otelTracer);
    }
}
