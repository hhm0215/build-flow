package com.buildflow.auth;

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

@EnabledIfEnvironmentVariable(named = "AUTH_FLYWAY_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_auth_flyway_test\\?.*$")
@ActiveProfiles("auth-flyway")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "management.tracing.enabled=false"
})
class AuthFlywayIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshMysqlRunsV1ThenHibernateValidatesWithoutCreatingAnAdministrator() {
        assertNotNull(flyway.info().current());
        assertEquals("1", flyway.info().current().getVersion().toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_accounts", Integer.class));

        String ddl = jdbc.queryForObject("SHOW CREATE TABLE admin_accounts",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(ddl);
        assertTrue(ddl.contains("UNIQUE KEY `login_id` (`login_id`)"));
        assertTrue(ddl.contains("CONSTRAINT `chk_single_admin_id` CHECK"));
        assertTrue(ddl.contains("COLLATE=utf8mb4_unicode_ci"));

        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
