package com.inventoryplatform.catalog;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.inventoryplatform.catalog.api.HealthOperations;
import com.inventoryplatform.common.client.ServiceOperationRegistry;

/**
 * catalog-service as a deployable unit.
 *
 * <p>The same class serves both deployment shapes (BUILD_PROMPT.md §3): run directly it is a
 * standalone Boot application for cloud mode, and the composite launcher starts it as a non-web
 * child context for desktop mode. Nothing in the service knows which it is.
 *
 * <p>Desktop mode runs it non-web because only the gateway owns the port — two servlet containers
 * cannot share one socket, and "one process, one port" is the whole promise of the installer.
 * Callers reach it through {@code ServiceClient}, which dispatches to the operations registered
 * below.
 */
@SpringBootApplication
public class CatalogServiceModule {

    /**
     * Standalone entry point for cloud mode.
     *
     * <p>The config name is set explicitly because every module ships its own YAML and, in the
     * co-located build, they all sit on one classpath — a plain { application.yml} in each
     * would resolve to whichever jar happened to load first.
     */
    public static void main(String[] args) {
        new org.springframework.boot.builder.SpringApplicationBuilder(CatalogServiceModule.class)
                .properties("spring.config.name=catalog")
                .run(args);
    }

    /** The service's name in routing and in problem documents. */
    public static final String SERVICE_NAME = "catalog-service";

    /**
     * Publishes this service's in-process operations.
     *
     * <p>Registration happens once at startup and fails loudly on a duplicate name, so two services
     * claiming the same operation is a startup error rather than a call silently reaching the wrong
     * handler.
     */
    @Bean
    CatalogOperationRegistrar catalogOperationRegistrar(
            ServiceOperationRegistry registry, HealthOperations healthOperations) {
        return new CatalogOperationRegistrar(registry, healthOperations);
    }

    /** Registers operations on construction; separate class so the wiring is testable. */
    public static class CatalogOperationRegistrar {

        public CatalogOperationRegistrar(
                ServiceOperationRegistry registry, HealthOperations healthOperations) {
            registry.register(
                    "catalog.health", HealthOperations.Empty.class, request -> healthOperations.liveness());
            registry.register(
                    "catalog.health.ready",
                    HealthOperations.Empty.class,
                    request -> healthOperations.readiness());
        }
    }
}
