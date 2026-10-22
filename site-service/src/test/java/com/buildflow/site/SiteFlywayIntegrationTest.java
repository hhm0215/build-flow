package com.buildflow.site;

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

@EnabledIfEnvironmentVariable(named = "SITE_FLYWAY_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_site_flyway_test\\?.*$")
@ActiveProfiles("site-flyway")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
class SiteFlywayIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshMysqlRunsV1V2AndHibernateValidatesWithoutCreatingBusinessData() {
        assertNotNull(flyway.info().current());
        assertEquals("2", flyway.info().current().getVersion().toString());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success = 1",
                Integer.class));
        for (String table : new String[]{"clients", "sites", "site_profits",
                "processed_profit_events", "purchase_profit_projections"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }

        String sitesDdl = jdbc.queryForObject("SHOW CREATE TABLE sites",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(sitesDdl);
        assertTrue(sitesDdl.contains("enum('BIDDING','COMPLETED','IN_PROGRESS','SETTLING','WARRANTY')"));
        assertTrue(sitesDdl.contains("FOREIGN KEY (`client_id`) REFERENCES `clients` (`id`)"));
        assertTrue(sitesDdl.contains("COLLATE=utf8mb4_unicode_ci"));
        assertEquals("datetime", column("sites", "archived_at"));
        assertEquals("datetime", column("clients", "archived_at"));
        assertEquals("decimal", column("site_profits", "margin_rate"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT NUMERIC_SCALE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'site_profits' AND COLUMN_NAME = 'margin_rate'", Integer.class));
        assertTrue(jdbc.queryForObject("SHOW CREATE TABLE site_profits",
                (resultSet, row) -> resultSet.getString(2)).contains("UNIQUE KEY"));
        assertTrue(jdbc.queryForObject("SHOW CREATE TABLE purchase_profit_projections",
                (resultSet, row) -> resultSet.getString(2)).contains("KEY `idx_purchase_profit_projection_site`"));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    private String column(String table, String column) {
        return jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
    }
}
