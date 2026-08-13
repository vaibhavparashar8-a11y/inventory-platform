package com.inventoryplatform.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.inventoryplatform.common.error.PlatformExceptions;

import tools.jackson.databind.json.JsonMapper;

/**
 * The in-process transport must not quietly differ from HTTP. BUILD_PROMPT.md §3 calls out the
 * new-transaction rule specifically and asks for a test asserting it — without one, desktop mode
 * hides consistency bugs that only appear in cloud.
 */
class InProcessServiceClientTest {

    private ServiceOperationRegistry registry;
    private JsonMapper objectMapper;

    /** Mutable on purpose: the detachment test needs something a callee could scribble on. */
    static final class Payload {
        private List<String> items = new ArrayList<>();

        public List<String> getItems() {
            return items;
        }

        public void setItems(List<String> items) {
            this.items = items;
        }
    }

    record Response(String value) {}

    @BeforeEach
    void setUp() {
        registry = new ServiceOperationRegistry();
        objectMapper = JsonMapper.builder().build();
    }

    @Test
    void dispatchesToTheRegisteredHandler() {
        registry.register("test.echo", Payload.class, payload -> new Response("handled"));

        Response response = client(null).call(ServiceRequest.query("svc", "test.echo", new Payload(), Response.class));

        assertThat(response.value()).isEqualTo("handled");
    }

    @Test
    @DisplayName("the callee receives a detached copy, so it cannot mutate the caller's object")
    void payloadIsSerialisedNotShared() {
        registry.register(
                "test.mutate",
                Payload.class,
                payload -> {
                    payload.getItems().add("written by callee");
                    return new Response("ok");
                });

        Payload caller = new Payload();
        caller.getItems().add("owned by caller");

        client(null).call(ServiceRequest.query("svc", "test.mutate", caller, Response.class));

        assertThat(caller.getItems()).containsExactly("owned by caller");
    }

    @Test
    @DisplayName("the callee runs in a NEW transaction, never the caller's")
    void calleeRunsInItsOwnTransaction() {
        registry.register("test.tx", Payload.class, payload -> new Response("ok"));

        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        client(transactionManager)
                .call(ServiceRequest.query("svc", "test.tx", new Payload(), Response.class));

        assertThat(transactionManager.propagationBehaviours)
                .as("in-process calls must not join the caller's transaction")
                .containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void unknownOperationIsReportedAsServiceUnavailable() {
        assertThatThrownBy(
                        () ->
                                client(null)
                                        .call(
                                                ServiceRequest.query(
                                                        "stock-service", "nope", new Payload(), Response.class)))
                .isInstanceOf(PlatformExceptions.ServiceUnavailable.class)
                .hasMessageContaining("stock-service");
    }

    @Test
    @DisplayName("a business failure from the callee passes through, not masked as a transport error")
    void businessExceptionsPropagate() {
        registry.register(
                "test.reject",
                Payload.class,
                payload -> {
                    throw new PlatformExceptions.Conflict("Not enough stock");
                });

        assertThatThrownBy(
                        () ->
                                client(null)
                                        .call(ServiceRequest.query("svc", "test.reject", new Payload(), Response.class)))
                .isInstanceOf(PlatformExceptions.Conflict.class)
                .hasMessageContaining("Not enough stock");
    }

    @Test
    void duplicateOperationRegistrationIsRejected() {
        registry.register("test.dup", Payload.class, payload -> new Response("first"));

        assertThatThrownBy(
                        () -> registry.register("test.dup", Payload.class, payload -> new Response("second")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    private InProcessServiceClient client(PlatformTransactionManager transactionManager) {
        return new InProcessServiceClient(registry, objectMapper, transactionManager);
    }

    /** Captures the propagation behaviour every transaction is started with. */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<Integer> propagationBehaviours = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagationBehaviours.add(definition.getPropagationBehavior());
            TransactionStatus status = mock(TransactionStatus.class);
            when(status.isNewTransaction()).thenReturn(true);
            return status;
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op: this test asserts on how the transaction was started, not on its outcome
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op, as above
        }
    }
}
