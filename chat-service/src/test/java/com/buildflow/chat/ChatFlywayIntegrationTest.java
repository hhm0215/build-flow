package com.buildflow.chat;

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

@EnabledIfEnvironmentVariable(named = "CHAT_FLYWAY_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "SPRING_DATASOURCE_URL",
        matches = "^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/buildflow_chat_flyway_test\\?.*$")
@ActiveProfiles("chat-flyway")
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "management.tracing.enabled=false"
})
class ChatFlywayIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshMysqlRunsV1AndHibernateValidatesWithoutCreatingChatData() {
        assertNotNull(flyway.info().current());
        assertEquals("1", flyway.info().current().getVersion().toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class));

        String messagesDdl = jdbc.queryForObject("SHOW CREATE TABLE chat_messages",
                (resultSet, row) -> resultSet.getString(2));
        assertNotNull(messagesDdl);
        assertTrue(messagesDdl.contains("enum('ASSISTANT','USER')"));
        assertTrue(messagesDdl.contains("KEY `idx_session` (`session_id`)"));
        assertTrue(messagesDdl.contains("COLLATE=utf8mb4_unicode_ci"));

        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
