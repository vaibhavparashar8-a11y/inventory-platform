package com.inventoryplatform.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.inventoryplatform.common.error.PlatformExceptions;
import com.inventoryplatform.common.error.RemoteServiceException;
import com.inventoryplatform.common.idempotency.IdempotencyKey;

/**
 * The HTTP binding must be indistinguishable from the in-process one to calling code — that is the
 * whole basis of "one codebase, two deployment shapes" (BUILD_PROMPT.md §3).
 */
class HttpServiceClientTest {

    private static final String BASE_URL = "http://stock-service:8080";

    private MockRestServiceServer server;
    private HttpServiceClient client;

    record ReserveRequest(String variantId, int quantity) {}

    record ReserveResponse(String reservationId) {}

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client =
                new HttpServiceClient(
                        builder.build(), new ServiceRoutes(Map.of("stock-service", BASE_URL)));
    }

    @Test
    void postsThePayloadToTheResolvedOperationUrl() {
        server
                .expect(requestTo(BASE_URL + "/internal/stock.reserve"))
                .andExpect(jsonPath("$.variantId").value("v1"))
                .andExpect(jsonPath("$.quantity").value(5))
                .andRespond(
                        withSuccess("{\"reservationId\":\"r1\"}", MediaType.APPLICATION_JSON));

        ReserveResponse response =
                client.call(
                        ServiceRequest.query(
                                "stock-service",
                                "stock.reserve",
                                new ReserveRequest("v1", 5),
                                ReserveResponse.class));

        assertThat(response.reservationId()).isEqualTo("r1");
        server.verify();
    }

    @Test
    @DisplayName("the idempotency key travels as a header, or retries would double-decrement")
    void sendsTheIdempotencyKeyHeader() {
        server
                .expect(requestTo(BASE_URL + "/internal/stock.reserve"))
                .andExpect(header(IdempotencyKey.HEADER, "key-123"))
                .andRespond(withSuccess("{\"reservationId\":\"r1\"}", MediaType.APPLICATION_JSON));

        client.call(
                ServiceRequest.command(
                        "stock-service",
                        "stock.reserve",
                        new ReserveRequest("v1", 5),
                        ReserveResponse.class,
                        IdempotencyKey.of("key-123")));

        server.verify();
    }

    @Test
    @DisplayName("a remote business rejection keeps its problem type rather than becoming a 500")
    void relaysRemoteProblemDetails() {
        String problem =
                """
                {
                  "type": "urn:problem:insufficient-stock",
                  "title": "Conflict",
                  "status": 409,
                  "detail": "Only 2 units are available.",
                  "variantId": "v1"
                }
                """;

        server
                .expect(requestTo(BASE_URL + "/internal/stock.reserve"))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .body(problem)
                                .contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThatThrownBy(
                        () ->
                                client.call(
                                        ServiceRequest.query(
                                                "stock-service",
                                                "stock.reserve",
                                                new ReserveRequest("v1", 5),
                                                ReserveResponse.class)))
                .isInstanceOf(RemoteServiceException.class)
                .hasMessageContaining("Only 2 units are available")
                .satisfies(
                        e -> {
                            RemoteServiceException remote = (RemoteServiceException) e;
                            assertThat(remote.originalType())
                                    .hasToString("urn:problem:insufficient-stock");
                            assertThat(remote.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(remote.properties())
                                    .containsEntry("sourceService", "stock-service")
                                    .containsEntry("variantId", "v1");
                        });
    }

    @Test
    @DisplayName("an error with no problem document is a transport failure, not a business answer")
    void unparseableErrorBecomesServiceUnavailable() {
        server
                .expect(requestTo(BASE_URL + "/internal/stock.reserve"))
                .andRespond(withServerError().body("<html>gateway exploded</html>"));

        assertThatThrownBy(
                        () ->
                                client.call(
                                        ServiceRequest.query(
                                                "stock-service",
                                                "stock.reserve",
                                                new ReserveRequest("v1", 5),
                                                ReserveResponse.class)))
                .isInstanceOf(PlatformExceptions.ServiceUnavailable.class)
                .hasMessageContaining("stock-service");
    }

    @Test
    @DisplayName("an unconfigured service fails loudly instead of guessing a URL")
    void unknownServiceIsRejected() {
        assertThatThrownBy(
                        () ->
                                client.call(
                                        ServiceRequest.query(
                                                "sales-service", "sales.create", new ReserveRequest("v1", 1), ReserveResponse.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No base URL is configured");
    }
}
