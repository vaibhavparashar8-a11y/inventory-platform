package com.inventoryplatform.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.inventoryplatform.common.tenant.TenantId;

import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotent replay is on the must-cover list in BUILD_PROMPT.md §9, along with the
 * mismatched-body case.
 */
class IdempotentOperationTest {

    private static final TenantId TENANT = TenantId.DEFAULT;
    private static final String ENDPOINT = "POST /movements";
    private static final IdempotencyKey KEY = IdempotencyKey.of("11111111-2222-3333-4444-555555555555");

    private Clock clock;
    private InMemoryIdempotencyStore store;
    private IdempotentOperation operation;

    record Request(String variantId, int qty) {}

    record Response(String movementId, int balanceAfter) {}

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC);
        store = new InMemoryIdempotencyStore(clock);
        operation = new IdempotentOperation(store, JsonMapper.builder().build());
    }

    @Test
    void firstCallRunsTheWork() {
        AtomicInteger runs = new AtomicInteger();

        Response result = execute(new Request("v1", 5), runs, new Response("m1", 95));

        assertThat(result).isEqualTo(new Response("m1", 95));
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("replay returns the original response and does not run the work again")
    void replayReturnsStoredResponse() {
        AtomicInteger runs = new AtomicInteger();
        Request request = new Request("v1", 5);

        Response first = execute(request, runs, new Response("m1", 95));
        // A different result from the work proves the second call did not execute it.
        Response replayed = execute(request, runs, new Response("SHOULD-NOT-BE-USED", -1));

        assertThat(replayed).isEqualTo(first);
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("same key with a different body is a caller bug: 422, loudly")
    void mismatchedBodyIsRejected() {
        AtomicInteger runs = new AtomicInteger();
        execute(new Request("v1", 5), runs, new Response("m1", 95));

        assertThatThrownBy(() -> execute(new Request("v1", 6), runs, new Response("m2", 94)))
                .isInstanceOf(IdempotentOperation.KeyReused.class)
                .hasMessageContaining("already used for a different request");

        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("a failed attempt releases the claim so an honest retry still works")
    void failureReleasesTheClaim() {
        assertThatThrownBy(
                        () ->
                                operation.execute(
                                        KEY,
                                        TENANT,
                                        ENDPOINT,
                                        new Request("v1", 5),
                                        Response.class,
                                        () -> {
                                            throw new IllegalStateException("database went away");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.find(KEY, TENANT, ENDPOINT)).isEmpty();

        AtomicInteger runs = new AtomicInteger();
        Response retried = execute(new Request("v1", 5), runs, new Response("m1", 95));
        assertThat(retried.movementId()).isEqualTo("m1");
    }

    @Test
    @DisplayName("a concurrent duplicate gets 409 rather than blocking or double-running")
    void concurrentDuplicateIsRejected() throws Exception {
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Response> slow =
                    pool.submit(
                            () ->
                                    operation.execute(
                                            KEY,
                                            TENANT,
                                            ENDPOINT,
                                            new Request("v1", 5),
                                            Response.class,
                                            () -> {
                                                runs.incrementAndGet();
                                                workStarted.countDown();
                                                awaitQuietly(releaseWork);
                                                return new Response("m1", 95);
                                            }));

            assertThat(workStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Second caller arrives while the first is still inside the work.
            assertThatThrownBy(
                            () ->
                                    operation.execute(
                                            KEY,
                                            TENANT,
                                            ENDPOINT,
                                            new Request("v1", 5),
                                            Response.class,
                                            () -> new Response("m2", 90)))
                    .isInstanceOf(IdempotentOperation.InFlight.class);

            releaseWork.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo(new Response("m1", 95));
            assertThat(runs).hasValue(1);
        }
    }

    @Test
    void purgeRemovesRecordsOlderThanTheCutoff() {
        execute(new Request("v1", 5), new AtomicInteger(), new Response("m1", 95));

        assertThat(store.purgeOlderThan(clock.instant().minusSeconds(1))).isZero();
        assertThat(store.purgeOlderThan(clock.instant().plusSeconds(1))).isEqualTo(1);
        assertThat(store.find(KEY, TENANT, ENDPOINT)).isEmpty();
    }

    private Response execute(Request request, AtomicInteger runs, Response result) {
        return operation.execute(
                KEY,
                TENANT,
                ENDPOINT,
                request,
                Response.class,
                () -> {
                    runs.incrementAndGet();
                    return result;
                });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
