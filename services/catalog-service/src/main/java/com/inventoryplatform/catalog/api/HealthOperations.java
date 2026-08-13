package com.inventoryplatform.catalog.api;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Tracer;

/**
 * Liveness and readiness for catalog-service.
 *
 * <p>The two are deliberately different checks, not aliases. Liveness answers "is the process
 * alive" and touches nothing — if it queried the database, a slow query would look like a dead
 * process and trigger a restart that makes things worse. Readiness answers "can this serve traffic
 * yet", which does require the database, because a service whose migrations have not run must not
 * claim it is ready.
 *
 * <p>The launcher waits on readiness before opening the browser, so a shopkeeper never sees a
 * half-started application.
 *
 * <p>The response carries the current trace id. That turns "a trace id flows across every service"
 * from something you squint at in logs into something a test can assert (BUILD_PROMPT.md §3).
 */
@Component
public class HealthOperations {

    private static final Logger log = LoggerFactory.getLogger(HealthOperations.class);

    private static final int PROBE_TIMEOUT_SECONDS = 2;
    private static final String SERVICE = "catalog-service";

    private final DataSource dataSource;
    private final ObjectProvider<Tracer> tracer;

    /**
     * @param tracer resolved lazily: tracing is present in a running service but absent in a
     *     slice test, and health must not depend on observability being configured
     */
    public HealthOperations(DataSource dataSource, ObjectProvider<Tracer> tracer) {
        this.dataSource = dataSource;
        this.tracer = tracer;
    }

    public HealthStatus liveness() {
        return new HealthStatus("UP", SERVICE, currentTraceId());
    }

    public HealthStatus readiness() {
        try (var connection = dataSource.getConnection()) {
            if (connection.isValid(PROBE_TIMEOUT_SECONDS)) {
                return new HealthStatus("UP", SERVICE, currentTraceId());
            }
            log.warn("Readiness probe failed: the database connection is not valid");
            return new HealthStatus("DOWN", SERVICE, currentTraceId());
        } catch (Exception e) {
            // Never silent: a service reporting not-ready without saying why is
            // undiagnosable on a customer's machine.
            log.warn("Readiness probe failed: {}", e.getMessage());
            return new HealthStatus("DOWN", SERVICE, currentTraceId());
        }
    }

    private String currentTraceId() {
        Tracer resolved = tracer.getIfAvailable();
        if (resolved == null || resolved.currentSpan() == null) {
            return null;
        }
        return resolved.currentSpan().context().traceId();
    }

    /** No-payload marker for operations that take no arguments. */
    public record Empty() {}

    /**
     * @param status UP or DOWN
     * @param service which service answered, so a combined health view is readable
     * @param traceId the trace this answer was produced under; null when tracing is not configured
     */
    public record HealthStatus(String status, String service, String traceId) {

        public boolean isUp() {
            return "UP".equals(status);
        }
    }
}
