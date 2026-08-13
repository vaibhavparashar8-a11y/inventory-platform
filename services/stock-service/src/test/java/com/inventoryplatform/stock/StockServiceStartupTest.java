package com.inventoryplatform.stock;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import com.inventoryplatform.common.client.ServiceOperationRegistry;
import com.inventoryplatform.stock.api.HealthOperations;

/**
 * Proves the service actually starts: context loads, Flyway migrates, and the schema validates
 * against Hibernate.
 *
 * <p>The Hibernate validation is the load-bearing part. {@code ddl-auto: validate} means a
 * migration that disagrees with an entity fails startup here rather than on a customer's PC after
 * an installer upgrade.
 */
@SpringBootTest(
        classes = {StockServiceModule.class, StockServiceStartupTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            // In-memory, per-test-run: never touch the developer's ./data folder.
            "spring.datasource.url=jdbc:h2:mem:stock-startup;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
class StockServiceStartupTest {

    @Autowired private DataSource dataSource;
    @Autowired private HealthOperations healthOperations;
    @Autowired private ServiceOperationRegistry registry;

    static class TestConfig {
        /**
         * The launcher supplies this in desktop mode and it is absent in cloud mode, so the test
         * provides its own rather than depending on either.
         */
        @Bean
        ServiceOperationRegistry serviceOperationRegistry() {
            return new ServiceOperationRegistry();
        }
    }

    @Test
    @DisplayName("the context starts and Flyway has migrated the schema")
    void contextLoadsAndMigrationsRun() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM \"flyway_schema_history\"")) {

            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).as("at least the baseline migration must have applied").isPositive();
        }
    }

    @Test
    @DisplayName("the idempotency uniqueness guarantee is enforced by the database, not by code")
    void idempotencyUniqueIndexExists() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.indexes "
                                        + "WHERE index_name = 'UX_IDEMPOTENCY_KEY'")) {

            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isPositive();
        }
    }

    @Test
    void readinessIsUpOnceMigrationsHaveRun() {
        assertThat(healthOperations.readiness().isUp()).isTrue();
        assertThat(healthOperations.liveness().isUp()).isTrue();
    }

    @Test
    @DisplayName("the service publishes its in-process operations for the launcher to route to")
    void operationsAreRegistered() {
        assertThat(registry.find("stock.health")).isPresent();
        assertThat(registry.find("stock.health.ready")).isPresent();
    }
}
