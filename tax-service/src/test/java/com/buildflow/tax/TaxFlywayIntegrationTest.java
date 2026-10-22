package com.buildflow.tax;

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

@EnabledIfEnvironmentVariable(named = "TAX_FLYWAY_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_tax_flyway_test\\?.*$")
@ActiveProfiles("tax-flyway")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "spring.kafka.listener.auto-startup=false",
        "app.outbox.poll-delay-ms=3600000",
        "management.tracing.enabled=false"
})
class TaxFlywayIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshMysqlRunsV1V2AndHibernateValidatesWithoutCreatingBusinessData() {
        assertNotNull(flyway.info().current());
        assertEquals("2", flyway.info().current().getVersion().toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tax_invoices", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tax_payments", Integer.class));
        String paymentDdl = jdbc.queryForObject("SHOW CREATE TABLE tax_payments",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(paymentDdl);
        assertTrue(paymentDdl.contains("`amount` decimal(15,2) NOT NULL"));
        assertTrue(paymentDdl.contains("KEY `idx_tax_payments_invoice`"));

        String taxDdl = jdbc.queryForObject("SHOW CREATE TABLE tax_invoices",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(taxDdl);
        assertTrue(taxDdl.contains("`payment_confirmed` bit(1) NOT NULL"));
        assertTrue(taxDdl.contains("enum('PURCHASE','SALES')"));
        assertTrue(taxDdl.contains("COLLATE=utf8mb4_unicode_ci"));

        String outboxDdl = jdbc.queryForObject("SHOW CREATE TABLE outbox_events",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(outboxDdl);
        assertTrue(outboxDdl.contains("KEY `idx_outbox_pending`"));
        assertEquals("longtext", jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'outbox_events' "
                        + "AND COLUMN_NAME = 'payload_json'",
                String.class));
        assertEquals("NO", jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'outbox_events' "
                        + "AND COLUMN_NAME = 'payload_json'",
                String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
