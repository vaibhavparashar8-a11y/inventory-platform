package com.inventoryplatform.common.error;

import java.io.Serial;
import java.net.URI;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Base for failures that carry a deliberate API representation.
 *
 * <p>Anything thrown as a {@code PlatformException} has been thought about: it has a stable problem
 * type, a status, and a message safe to show a shopkeeper. Anything else reaching the handler is a
 * bug and becomes a generic 500 with a support id — never a leaked stack trace.
 */
public abstract class PlatformException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient URI type;
    private final HttpStatus status;
    private final transient Map<String, Object> properties;

    protected PlatformException(URI type, HttpStatus status, String message) {
        this(type, status, message, Map.of(), null);
    }

    protected PlatformException(
            URI type, HttpStatus status, String message, Map<String, Object> properties) {
        this(type, status, message, properties, null);
    }

    protected PlatformException(
            URI type,
            HttpStatus status,
            String message,
            Map<String, Object> properties,
            Throwable cause) {
        super(message, cause);
        this.type = type;
        this.status = status;
        this.properties = Map.copyOf(properties);
    }

    public URI type() {
        return type;
    }

    public HttpStatus status() {
        return status;
    }

    /** Extra members added to the problem document, e.g. {@code variantId}, {@code available}. */
    public Map<String, Object> properties() {
        return properties;
    }
}
