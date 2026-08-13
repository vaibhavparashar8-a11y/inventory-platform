package com.inventoryplatform.gateway.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.inventoryplatform.common.client.ServiceClient;
import com.inventoryplatform.common.client.ServiceRequest;

/**
 * Routes health checks through to the services.
 *
 * <p>The gateway holds no business logic and no database — it is a router and a static file server.
 * Anything resembling a rule appearing here means it belongs in a service.
 *
 * <p>Calls go through {@link ServiceClient}, so this same controller works unchanged whether the
 * target is a child context in the same JVM or a container across a network.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /** Services reachable through the gateway. Extended as each service lands. */
    private static final List<String> SERVICES = List.of("catalog", "stock");

    private final ServiceClient serviceClient;

    public HealthController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    @GetMapping("/{service}/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String service) {
        return route(service, ".health");
    }

    @GetMapping("/{service}/health/ready")
    public ResponseEntity<Map<String, Object>> readiness(@PathVariable String service) {
        return route(service, ".health.ready");
    }

    /**
     * Aggregate view used by the launcher: the app is ready when every service is.
     *
     * <p>Reports which services are down rather than a bare false — on a customer's machine, "not
     * ready" without a reason is not actionable.
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> aggregateReadiness() {
        Map<String, Object> services = new LinkedHashMap<>();
        boolean allUp = true;

        for (String service : SERVICES) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> status =
                        serviceClient.call(
                                ServiceRequest.query(
                                        service + "-service", service + ".health.ready", null, Map.class));
                services.put(service, status);
                allUp &= "UP".equals(status.get("status"));
            } catch (RuntimeException e) {
                services.put(service, Map.of("status", "DOWN", "reason", e.getMessage()));
                allUp = false;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("services", services);

        return allUp ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    private ResponseEntity<Map<String, Object>> route(String service, String operationSuffix) {
        if (!SERVICES.contains(service)) {
            return ResponseEntity.notFound().build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> status =
                serviceClient.call(
                        ServiceRequest.query(
                                service + "-service", service + operationSuffix, null, Map.class));

        return ResponseEntity.ok(status);
    }
}
