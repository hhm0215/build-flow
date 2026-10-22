package com.buildflow.notification;

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

@EnabledIfEnvironmentVariable(named = "NOTIFICATION_FLYWAY_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_notification_flyway_test\\?.*$")
@ActiveProfiles("notification-flyway")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "spring.kafka.listener.auto-startup=false",
        "app.outbox.poll-delay-ms=3600000",
        "app.ocr.recovery-initial-delay-ms=3600000",
        "management.tracing.enabled=false"
})
class NotificationFlywayIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshMysqlRunsV1AndHibernateValidatesWithoutCreatingBusinessData() {
        assertNotNull(flyway.info().current());
        assertEquals("1", flyway.info().current().getVersion().toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class));
        for (String table : new String[]{"notifications", "processed_notification_events", "outbox_events",
                "defect_warranties", "warranty_audit_events", "warranty_file_retentions"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }

        String outboxDdl = ddl("outbox_events");
        assertTrue(outboxDdl.contains("enum('CLAIMED','PENDING','SENT','SUPPRESSED')"));
        assertTrue(outboxDdl.contains("KEY `idx_outbox_due`"));
        assertTrue(outboxDdl.contains("KEY `idx_outbox_record`"));
        String warrantyDdl = ddl("defect_warranties");
        assertTrue(warrantyDdl.contains("DEFAULT 'MANUAL'"));
        assertTrue(warrantyDdl.contains("`expiry_confirmed_at` datetime(6) DEFAULT NULL"));
        assertTrue(ddl("warranty_audit_events").contains("KEY `idx_warranty_audit_warranty`"));
        assertTrue(ddl("warranty_file_retentions").contains("KEY `idx_warranty_retention_due`"));
        assertTrue(warrantyDdl.contains("COLLATE=utf8mb4_unicode_ci"));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    private String ddl(String table) {
        String value = jdbc.queryForObject("SHOW CREATE TABLE " + table, (resultSet, row) -> resultSet.getString(2));
        assertNotNull(value);
        return value;
    }
}
