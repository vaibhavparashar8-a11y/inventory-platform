package com.inventoryplatform.stock.api;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Liveness and readiness for stock-service.
 *
 * <p>The two are deliberately different checks, not aliases. Liveness answers "is the process
 * alive" and touches nothing — if it queried the database, a slow query would look like a dead
 * process and trigger a restart that makes things worse. Readiness answers "can this serve traffic
 * yet", which does require the database, because a service whose migrations have not run must not
 * claim it is ready.
 *
 * <p>The launcher waits on readiness before opening the browser, so a shopkeeper never sees a
 * half-started application.
 */
@Component
public class HealthOperations {

    private static final Logger log = LoggerFactory.getLogger(HealthOperations.class);

    private static final int PROBE_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthOperations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public HealthStatus liveness() {
        return HealthStatus.up();
    }

    public HealthStatus readiness() {
        try (var connection = dataSource.getConnection()) {
            if (connection.isValid(PROBE_TIMEOUT_SECONDS)) {
                return HealthStatus.up();
            }
            log.warn("Readiness probe failed: the database connection is not valid");
            return HealthStatus.down();
        } catch (Exception e) {
            // Never silent: a service reporting not-ready without saying why is
            // undiagnosable on a customer's machine.
            log.warn("Readiness probe failed: {}", e.getMessage());
            return HealthStatus.down();
        }
    }

    /** No-payload marker for operations that take no arguments. */
    public record Empty() {}

    /**
     * @param status UP or DOWN
     * @param service which service answered, so a combined health view is readable
     */
    public record HealthStatus(String status, String service) {

        private static final String SERVICE = "stock-service";

        public static HealthStatus up() {
            return new HealthStatus("UP", SERVICE);
        }

        public static HealthStatus down() {
            return new HealthStatus("DOWN", SERVICE);
        }

        public boolean isUp() {
            return "UP".equals(status);
        }
    }
}
