package com.inventoryplatform.launcher;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.inventoryplatform.common.client.InProcessServiceClient;
import com.inventoryplatform.common.client.ServiceClient;
import com.inventoryplatform.common.client.ServiceOperationRegistry;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parent context: infrastructure shared by every co-located service.
 *
 * <p>Child contexts inherit these beans, which is what lets the gateway hold a {@link ServiceClient}
 * that can reach handlers registered by contexts it knows nothing about.
 *
 * <p>Deliberately minimal. Anything that belongs to a service belongs in that service — a bean
 * added here to "share" it would couple every service to every other, which is precisely what the
 * architecture spends its complexity budget avoiding.
 */
@Configuration
@EnableAutoConfiguration
public class LauncherConfiguration {

    /**
     * One registry across all children. Registration fails loudly on a duplicate operation name, so
     * two services claiming the same name is a startup failure rather than calls silently reaching
     * the wrong handler.
     */
    @Bean
    ServiceOperationRegistry serviceOperationRegistry() {
        return new ServiceOperationRegistry();
    }

    @Bean
    ObjectMapper launcherObjectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * Desktop transport.
     *
     * <p>No transaction manager is passed: the parent context owns no database, and each service
     * manages its own transactions inside its own context. The in-process client therefore does not
     * wrap calls in a parent-level transaction — which is correct, since joining one would be
     * exactly the behaviour ADR 0004 exists to prevent.
     */
    @Bean
    ServiceClient serviceClient(ServiceOperationRegistry registry, ObjectMapper objectMapper) {
        return new InProcessServiceClient(registry, objectMapper, null);
    }
}
