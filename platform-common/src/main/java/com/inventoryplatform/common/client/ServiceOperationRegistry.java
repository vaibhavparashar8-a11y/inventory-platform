package com.inventoryplatform.common.client;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each service can be asked to do in-process, keyed by logical operation name.
 *
 * <p>Services register their handlers at startup; the composite launcher shares one registry across
 * every child context, which is what lets eight services talk inside one JVM without HTTP.
 *
 * <p>The payload type is registered alongside the handler because generic erasure means the
 * transport cannot otherwise know what to deserialise an incoming payload into.
 *
 * <p>In cloud mode the registry is simply empty and every call goes over HTTP.
 */
public final class ServiceOperationRegistry {

    private final Map<String, Registration<?, ?>> operations = new ConcurrentHashMap<>();

    /**
     * @throws IllegalStateException if the operation is already registered — a duplicate means two
     *     services claim the same name, and letting the last one silently win would route calls to
     *     an arbitrary handler
     */
    public <P, R> void register(
            String operation, Class<P> payloadType, ServiceOperation<P, R> handler) {

        Registration<?, ?> existing =
                operations.putIfAbsent(operation, new Registration<>(payloadType, handler));
        if (existing != null) {
            throw new IllegalStateException(
                    "Operation '" + operation + "' is already registered by another service");
        }
    }

    public Optional<Registration<?, ?>> find(String operation) {
        return Optional.ofNullable(operations.get(operation));
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * A handler plus the type its payload deserialises into.
     *
     * @param <P> payload type
     * @param <R> response type
     */
    public record Registration<P, R>(Class<P> payloadType, ServiceOperation<P, R> handler) {

        /**
         * Invokes the handler with a payload known to be of the registered type.
         *
         * <p>The cast is unchecked but safe: the transport deserialises into {@link #payloadType()}
         * immediately before calling this, and {@code register} is the only way to pair a type with a
         * handler.
         */
        @SuppressWarnings("unchecked")
        public Object invoke(Object payload) {
            return ((ServiceOperation<Object, ?>) handler).handle(payload);
        }
    }

    /**
     * A single in-process operation.
     *
     * @param <P> payload type
     * @param <R> response type
     */
    @FunctionalInterface
    public interface ServiceOperation<P, R> {

        R handle(P payload);
    }
}
