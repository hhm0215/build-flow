package com.buildflow.estimate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "ESTIMATE_FLYWAY_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_estimate_flyway_test\\?.*$")
@ActiveProfiles("estimate-flyway")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "spring.kafka.listener.auto-startup=false",
        "app.outbox.poll-delay-ms=3600000",
        "management.tracing.enabled=false"
})
class EstimateFlywayIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshMysqlRunsV1AndHibernateValidatesWithoutCreatingBusinessData() {
        assertNotNull(flyway.info().current());
        assertEquals("2", flyway.info().current().getVersion().toString());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success = 1",
                Integer.class));
        for (String table : new String[] {"estimates", "estimate_items", "outbox_events"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }

        String estimatesDdl = jdbc.queryForObject("SHOW CREATE TABLE estimates",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(estimatesDdl);
        assertTrue(estimatesDdl.contains("enum('CONFIRMED','DRAFT','SUPERSEDED')"));
        assertTrue(estimatesDdl.contains("`kind` enum('ADDITIONAL','ORIGINAL','REVISION')"));
        assertTrue(estimatesDdl.contains("KEY `idx_estimates_chain_root` (`chain_root_id`)"));
        assertTrue(estimatesDdl.contains("COLLATE=utf8mb4_unicode_ci"));

        String itemsDdl = jdbc.queryForObject("SHOW CREATE TABLE estimate_items",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(itemsDdl);
        assertTrue(itemsDdl.contains("FOREIGN KEY (`estimate_id`) REFERENCES `estimates` (`id`)"));

        String outboxDdl = jdbc.queryForObject("SHOW CREATE TABLE outbox_events",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(outboxDdl);
        assertTrue(outboxDdl.contains("KEY `idx_estimate_outbox_due`"));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
