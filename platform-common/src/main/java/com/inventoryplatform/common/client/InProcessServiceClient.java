package com.inventoryplatform.common.client;

import java.util.Optional;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.inventoryplatform.common.error.PlatformException;
import com.inventoryplatform.common.error.PlatformExceptions;

import tools.jackson.databind.ObjectMapper;

/**
 * Desktop-mode transport: dispatches to another service inside the same JVM.
 *
 * <p>Deliberately not a direct method call. Three properties of a real network hop are preserved,
 * each because losing it would make desktop and cloud behave differently:
 *
 * <ul>
 *   <li><strong>Payloads are serialised.</strong> Caller and callee never share an object, so a
 *       callee mutating its argument cannot reach back into the caller's state — impossible over
 *       HTTP, and it must stay impossible here.
 *   <li><strong>The callee runs in a new transaction.</strong> Joining the caller's would mean a
 *       failure downstream rolls back the caller's work, which cloud mode would never do. This is
 *       the property the reservation protocol depends on: the caller commits its own record in its
 *       own transaction, independently of the ledger write.
 *   <li><strong>Failures surface as {@code ServiceUnavailable}</strong>, the same problem type the
 *       HTTP binding produces.
 * </ul>
 *
 * <p>Business exceptions thrown by the callee pass through unchanged — they are the callee's answer,
 * not a transport failure.
 */
public final class InProcessServiceClient implements ServiceClient {

    private final ServiceOperationRegistry registry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTransaction;

    /**
     * @param transactionManager may be null in tests or in a service with no database; the callee
     *     then runs without a transaction rather than silently joining the caller's
     */
    public InProcessServiceClient(
            ServiceOperationRegistry registry,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {

        this.registry = registry;
        this.objectMapper = objectMapper;
        this.newTransaction = newTransactionTemplate(transactionManager);
    }

    @Override
    public <R> R call(ServiceRequest<R> request) {
        ServiceOperationRegistry.Registration<?, ?> registration =
                registry
                        .find(request.operation())
                        .orElseThrow(
                                () ->
                                        new PlatformExceptions.ServiceUnavailable(
                                                request.targetService(),
                                                new IllegalStateException(
                                                        "No in-process handler registered for operation '"
                                                                + request.operation()
                                                                + "'")));

        Object payload = roundTrip(request.payload(), registration.payloadType(), request);

        Object response = invoke(registration, payload, request);

        return convert(response, request.responseType(), request);
    }

    /**
     * Serialise then deserialise, so the callee receives a detached copy exactly as it would over
     * the wire.
     */
    private Object roundTrip(Object payload, Class<?> targetType, ServiceRequest<?> request) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(payload), targetType);
        } catch (RuntimeException e) {
            throw new PlatformExceptions.ServiceUnavailable(request.targetService(), e);
        }
    }

    private Object invoke(
            ServiceOperationRegistry.Registration<?, ?> registration,
            Object payload,
            ServiceRequest<?> request) {
        try {
            if (newTransaction == null) {
                return registration.invoke(payload);
            }
            return newTransaction.execute(status -> registration.invoke(payload));
        } catch (PlatformException e) {
            // The callee's considered answer — a business outcome, not a transport failure.
            throw e;
        } catch (RuntimeException e) {
            throw new PlatformExceptions.ServiceUnavailable(request.targetService(), e);
        }
    }

    /**
     * Always round-trips, even when the response is already the requested type: handing back the
     * callee's own instance would share mutable state across the boundary, which HTTP could never
     * do.
     */
    private <R> R convert(Object response, Class<R> responseType, ServiceRequest<?> request) {
        if (response == null) {
            return null;
        }
        return responseType.cast(roundTrip(response, responseType, request));
    }

    private static TransactionTemplate newTransactionTemplate(PlatformTransactionManager manager) {
        return Optional.ofNullable(manager)
                .map(
                        m -> {
                            TransactionTemplate template = new TransactionTemplate(m);
                            template.setPropagationBehavior(
                                    TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                            return template;
                        })
                .orElse(null);
    }
}
