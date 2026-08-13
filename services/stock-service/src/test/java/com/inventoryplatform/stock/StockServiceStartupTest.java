package com.inventoryplatform.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;

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
            // Defaults to in-memory H2 so a local run never touches ./data. CI overrides
            // TEST_DB_* to point the very same test at real PostgreSQL — proving not just
            // that migrations apply, but that Hibernate validates the resulting schema on
            // both engines. Docker is absent locally, so CI is the only place this can run.
            "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:stock-startup;DB_CLOSE_DELAY=-1}",
            "spring.datasource.username=${TEST_DB_USER:sa}",
            "spring.datasource.password=${TEST_DB_PASSWORD:}",
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
    void duplicateIdempotencyKeyIsRejectedByTheDatabase() throws Exception {
        // Asserts the behaviour rather than the presence of a named index: index
        // metadata lives in different places in H2 and PostgreSQL, and what actually
        // matters is that the second insert fails. Two concurrent requests with the
        // same key race here, and exactly one must win — that guarantee has to come
        // from the database, not from application code.
        try (var connection = dataSource.getConnection()) {
            insertIdempotencyRecord(connection, "row-1");

            assertThatThrownBy(() -> insertIdempotencyRecord(connection, "row-2"))
                    .as("a duplicate (tenant, endpoint, key) must be rejected by the unique index")
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertIdempotencyRecord(Connection connection, String id) throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        """
                        INSERT INTO idempotency_record
                          (id, tenant_id, idempotency_key, endpoint, request_fingerprint,
                           state, created_at, updated_at, version)
                        VALUES (?, 'default', 'the-same-key', 'POST /movements', 'fp',
                                'IN_FLIGHT', ?, ?, 0)
                        """)) {
            var now = java.sql.Timestamp.from(java.time.Instant.now());
            statement.setString(1, id);
            statement.setTimestamp(2, now);
            statement.setTimestamp(3, now);
            statement.executeUpdate();
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
