package com.inventoryplatform.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import io.micrometer.tracing.Tracer;

/**
 * Shared translation of exceptions into RFC 9457 problem documents.
 *
 * <p>Each service declares its own {@code @RestControllerAdvice} extending this (BUILD_PROMPT.md
 * §9) so it can add service-specific handlers without services sharing a bean.
 *
 * <p>Two rules hold throughout. Every response carries a {@code supportId} equal to the current
 * trace id, so a shopkeeper reading an error aloud on the phone gives support the exact thread to
 * pull. And an unrecognised exception never reveals its internals: it is logged in full and
 * reported as a bare 500, because a stack trace on screen is frightening, useless to the user, and
 * a disclosure risk on a machine we do not control.
 */
public abstract class BaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BaseExceptionHandler.class);

    private final Tracer tracer;

    protected BaseExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(PlatformException.class)
    public ProblemDetail handlePlatform(PlatformException ex) {
        // Expected failures are logged at WARN without a stack trace: they are business
        // outcomes, not defects, and stack traces here would drown the real ones.
        log.warn("{} -> {}", ex.type(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(ex.type());
        problem.setTitle(titleFor(ex.status()));
        ex.properties().forEach(problem::setProperty);
        addSupportId(problem);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Some of the values supplied are not valid.");
        problem.setType(ProblemTypes.VALIDATION_FAILED);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", fieldErrors);
        addSupportId(problem);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Some of the values supplied are not valid.");
        problem.setType(ProblemTypes.VALIDATION_FAILED);
        problem.setTitle("Validation failed");
        addSupportId(problem);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Full detail to the log, nothing to the user beyond the support id.
        log.error("Unhandled exception", ex);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Something went wrong on our side. Please try again, and quote the support id "
                                + "below if it keeps happening.");
        problem.setType(ProblemTypes.INTERNAL_ERROR);
        problem.setTitle("Unexpected error");
        addSupportId(problem);
        return problem;
    }

    /** Exposed so subclasses can attach the same support id to their own handlers. */
    protected void addSupportId(ProblemDetail problem) {
        if (tracer != null && tracer.currentSpan() != null) {
            problem.setProperty("supportId", tracer.currentSpan().context().traceId());
        }
    }

    private String titleFor(HttpStatus status) {
        return status.getReasonPhrase();
    }
}
