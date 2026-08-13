package com.inventoryplatform.gateway;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;

import com.inventoryplatform.common.error.BaseExceptionHandler;
import com.inventoryplatform.gateway.config.OriginValidationFilter;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;

/**
 * The single port the browser talks to.
 *
 * <p>Serves the React bundle and routes {@code /api/v1/**} to the services. It owns no database, no
 * schema and no business rule — in desktop mode it is the only web context, which is what makes
 * "one process, one port, one installer" possible.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(GatewayApplication.class)
                .properties("spring.config.name=gateway")
                .run(args);
    }

    /**
     * Runs before anything else: a request from an unexpected origin should be rejected before it
     * reaches routing, not after.
     */
    @Bean
    FilterRegistrationBean<OriginValidationFilter> originValidationFilter() {
        FilterRegistrationBean<OriginValidationFilter> registration =
                new FilterRegistrationBean<>(new OriginValidationFilter(List.of()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Starts the server span for every request.
     *
     * <p>Registered explicitly rather than relied upon: without a server span there is no trace at
     * all, every {@code traceId} is blank, and §3's "a trace id in every log line" silently does not
     * hold — a failure that is invisible until someone needs a trace to diagnose a customer issue.
     */
    @Bean
    ServerHttpObservationFilter serverHttpObservationFilter(ObservationRegistry observationRegistry) {
        return new ServerHttpObservationFilter(observationRegistry);
    }

    /**
     * Each service declares its own advice (BUILD_PROMPT.md §9) rather than sharing a bean; the
     * shared base supplies the translation and the support id.
     */
    @RestControllerAdvice
    static class GatewayExceptionHandler extends BaseExceptionHandler {

        GatewayExceptionHandler(ObjectProvider<Tracer> tracer) {
            super(tracer.getIfAvailable());
        }
    }
}
