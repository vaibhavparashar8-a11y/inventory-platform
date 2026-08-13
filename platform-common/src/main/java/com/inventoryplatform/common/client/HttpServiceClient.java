package com.inventoryplatform.common.client;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.inventoryplatform.common.error.PlatformExceptions;
import com.inventoryplatform.common.error.RemoteServiceException;
import com.inventoryplatform.common.idempotency.IdempotencyKey;

/**
 * Cloud-mode transport: a real HTTP call to another service.
 *
 * <p>The counterpart to {@code InProcessServiceClient}, and the reason that one goes to such lengths
 * to imitate a network hop — both must be indistinguishable to calling code.
 *
 * <p>Two behaviours are deliberate. A remote {@code ProblemDetail} is rebuilt as a
 * {@link RemoteServiceException} carrying the original type and status, so a business rejection from
 * another service stays a business rejection rather than becoming a generic transport error. And a
 * genuine transport failure — refused connection, timeout — becomes {@code ServiceUnavailable}, the
 * same problem type the in-process binding produces.
 *
 * <p>Timeouts are configured on the injected {@link RestClient.Builder} rather than here, so a
 * service can tune them without a code change. There must never be an unbounded wait
 * (BUILD_PROMPT.md §9).
 */
public final class HttpServiceClient implements ServiceClient {

    private final RestClient restClient;
    private final ServiceRoutes routes;

    public HttpServiceClient(RestClient restClient, ServiceRoutes routes) {
        this.restClient = restClient;
        this.routes = routes;
    }

    @Override
    public <R> R call(ServiceRequest<R> request) {
        String url = routes.urlFor(request.targetService(), request.operation());

        try {
            return restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(
                            headers ->
                                    request
                                            .idempotencyKey()
                                            .map(IdempotencyKey::value)
                                            .ifPresent(key -> headers.add(IdempotencyKey.HEADER, key)))
                    .body(request.payload() == null ? new Object() : request.payload())
                    .exchange(
                            (httpRequest, response) -> {
                                if (response.getStatusCode().isError()) {
                                    throw toException(response, request);
                                }
                                return response.bodyTo(request.responseType());
                            });
        } catch (RemoteServiceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Connection refused, DNS failure, read timeout: the request never got an answer.
            throw new PlatformExceptions.ServiceUnavailable(request.targetService(), e);
        }
    }

    /**
     * Rebuilds the callee's answer where it gave one, so the caller sees the same problem type it
     * would have seen in desktop mode.
     */
    private RuntimeException toException(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            ServiceRequest<?> request) {

        ProblemDetail problem = readProblem(response);

        if (problem == null || problem.getType() == null) {
            // No problem document: nothing meaningful to relay, so report it as a transport failure.
            return new PlatformExceptions.ServiceUnavailable(
                    request.targetService(),
                    new IllegalStateException(
                            "Unexpected response from " + request.targetService()));
        }

        return new RemoteServiceException(problem, request.targetService());
    }

    /**
     * Reads a problem document, tolerating anything that is not one.
     *
     * <p>An error response is not obliged to carry JSON: a reverse proxy returning its own HTML 502
     * page is normal in cloud, and so is an empty body. Letting the converter's exception escape
     * would replace a useful {@code ServiceUnavailable} with a raw Spring error, so a body that
     * cannot be read is simply treated as no problem document at all.
     */
    private ProblemDetail readProblem(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ProblemDetail.class);
        } catch (RestClientException e) {
            return null;
        }
    }
}
