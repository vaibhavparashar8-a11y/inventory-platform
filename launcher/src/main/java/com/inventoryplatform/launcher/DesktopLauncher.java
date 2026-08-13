package com.inventoryplatform.launcher;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.inventoryplatform.catalog.CatalogServiceModule;
import com.inventoryplatform.gateway.GatewayApplication;
import com.inventoryplatform.stock.StockServiceModule;

/**
 * Desktop mode: every service in one JVM, behind one port.
 *
 * <p>The customer cannot run eight JVMs, so co-location is solved in <em>deployment</em> rather
 * than architecture (BUILD_PROMPT.md §3). Service code is identical in both shapes; only this
 * launcher and the transport binding differ.
 *
 * <h2>Shape</h2>
 *
 * <pre>
 *   parent context          shared infrastructure: operation registry, in-process transport
 *     ├── catalog-service   web type NONE
 *     ├── stock-service     web type NONE
 *     └── gateway           web type SERVLET  ← the only thing that owns a port
 * </pre>
 *
 * <p>Only the gateway is a web context. Two servlet containers cannot share one socket, and "one
 * process, one port" is the entire promise of the installer. Services are reachable through
 * {@code ServiceClient}, which dispatches to the operations they register in the shared registry.
 *
 * <p>Each child sets its own {@code spring.config.name} because all modules sit on one classpath
 * here — a plain {@code application.yml} in each would resolve to whichever jar loaded first.
 *
 * <p><strong>This must keep working.</strong> A smoke test runs it in CI on every PR: if the
 * co-located launcher breaks, the desktop product has no delivery mechanism, and that is not
 * something to discover during packaging in Phase 7.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {}

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Starts the whole platform in one JVM.
     *
     * @return the gateway context — the one bound to a port, and so the one tests drive
     */
    public static ConfigurableApplicationContext launch(String... args) {
        return new SpringApplicationBuilder(LauncherConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=launcher")

                // Services first: the gateway routes to operations they register on startup.
                .child(CatalogServiceModule.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=catalog")

                .sibling(StockServiceModule.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=stock")

                .sibling(GatewayApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.config.name=gateway")

                .run(args);
    }
}
