package com.inventoryplatform.common.error;

import java.io.Serial;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * The concrete failures every service shares.
 *
 * <p>Grouped in one file because each is a handful of lines and scattering them across eight
 * near-identical classes helps nobody. Service-specific failures (insufficient stock, expired
 * reservation) live with the service that owns the rule.
 */
public final class PlatformExceptions {

    private PlatformExceptions() {}

    /** A referenced entity does not exist — or exists under a different tenant, which is the same. */
    public static final class NotFound extends PlatformException {
        @Serial private static final long serialVersionUID = 1L;

        public NotFound(String entity, Object id) {
            super(
                    ProblemTypes.NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "%s %s was not found.".formatted(entity, id),
                    Map.of("entity", entity, "id", String.valueOf(id)));
        }
    }

    /** The request conflicts with current state. */
    public static final class Conflict extends PlatformException {
        @Serial private static final long serialVersionUID = 1L;

        public Conflict(String message) {
            super(ProblemTypes.CONFLICT, HttpStatus.CONFLICT, message);
        }

        public Conflict(String message, Map<String, Object> properties) {
            super(ProblemTypes.CONFLICT, HttpStatus.CONFLICT, message, properties);
        }
    }

    /** Business validation failed — as distinct from Bean Validation on the inbound DTO. */
    public static final class ValidationFailed extends PlatformException {
        @Serial private static final long serialVersionUID = 1L;

        public ValidationFailed(String message) {
            super(ProblemTypes.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY, message);
        }

        public ValidationFailed(String message, Map<String, Object> properties) {
            super(ProblemTypes.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY, message, properties);
        }
    }

    /**
     * A downstream service could not be reached.
     *
     * <p>503 rather than 500: the caller's request was fine and retrying may well work, which is
     * both true and the more useful thing to tell the UI.
     */
    public static final class ServiceUnavailable extends PlatformException {
        @Serial private static final long serialVersionUID = 1L;

        public ServiceUnavailable(String service, Throwable cause) {
            super(
                    ProblemTypes.SERVICE_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The %s is not responding. Please try again in a moment.".formatted(service),
                    Map.of("service", service),
                    cause);
        }
    }
}
