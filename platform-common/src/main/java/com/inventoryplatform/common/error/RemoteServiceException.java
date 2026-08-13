package com.inventoryplatform.common.error;

import java.io.Serial;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A business rejection relayed from another service.
 *
 * <p>Exists so a downstream "not enough stock" stays "not enough stock" all the way to the browser
 * instead of degrading into a generic 500 at each hop. The original problem type, status and detail
 * are preserved, which matters because the frontend switches on the type URI.
 *
 * <p>Only for answers the callee actually gave. A refused connection or a timeout is a transport
 * failure and becomes {@link PlatformExceptions.ServiceUnavailable} instead — the caller can retry
 * that, and must not retry a business rejection.
 */
public final class RemoteServiceException extends PlatformException {

    @Serial private static final long serialVersionUID = 1L;

    public RemoteServiceException(ProblemDetail problem, String sourceService) {
        super(
                problem.getType(),
                resolveStatus(problem),
                detailOf(problem),
                propertiesOf(problem, sourceService));
    }

    private static HttpStatus resolveStatus(ProblemDetail problem) {
        HttpStatus status = HttpStatus.resolve(problem.getStatus());
        return status != null ? status : HttpStatus.BAD_GATEWAY;
    }

    private static String detailOf(ProblemDetail problem) {
        String detail = problem.getDetail();
        return detail != null ? detail : "A downstream service rejected the request.";
    }

    private static Map<String, Object> propertiesOf(ProblemDetail problem, String sourceService) {
        Map<String, Object> properties = new HashMap<>();
        if (problem.getProperties() != null) {
            problem.getProperties().forEach(properties::putIfAbsent);
        }
        // Which service said no — invaluable when reading a support bundle.
        properties.put("sourceService", sourceService);
        return properties;
    }

    /** The original problem type from the callee, never rewritten in transit. */
    public URI originalType() {
        return type();
    }
}
