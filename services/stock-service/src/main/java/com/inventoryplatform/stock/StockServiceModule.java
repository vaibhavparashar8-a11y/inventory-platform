package com.inventoryplatform.stock;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.inventoryplatform.common.client.ServiceOperationRegistry;
import com.inventoryplatform.stock.api.HealthOperations;

/**
 * stock-service as a deployable unit.
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
public class StockServiceModule {

    /**
     * Standalone entry point for cloud mode.
     *
     * <p>The config name is set explicitly because every module ships its own YAML and, in the
     * co-located build, they all sit on one classpath — a plain { application.yml} in each
     * would resolve to whichever jar happened to load first.
     */
    public static void main(String[] args) {
        new org.springframework.boot.builder.SpringApplicationBuilder(StockServiceModule.class)
                .properties("spring.config.name=stock")
                .run(args);
    }

    /** The service's name in routing and in problem documents. */
    public static final String SERVICE_NAME = "stock-service";

    /**
     * Publishes this service's in-process operations.
     *
     * <p>Registration happens once at startup and fails loudly on a duplicate name, so two services
     * claiming the same operation is a startup error rather than a call silently reaching the wrong
     * handler.
     */
    @Bean
    StockOperationRegistrar stockOperationRegistrar(
            ServiceOperationRegistry registry, HealthOperations healthOperations) {
        return new StockOperationRegistrar(registry, healthOperations);
    }

    /** Registers operations on construction; separate class so the wiring is testable. */
    public static class StockOperationRegistrar {

        public StockOperationRegistrar(
                ServiceOperationRegistry registry, HealthOperations healthOperations) {
            registry.register(
                    "stock.health", HealthOperations.Empty.class, request -> healthOperations.liveness());
            registry.register(
                    "stock.health.ready",
                    HealthOperations.Empty.class,
                    request -> healthOperations.readiness());
        }
    }
}
