package com.buildflow.notification.domain.notification.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesEventTypeAndReadForTheFrontendContract() throws Exception {
        NotificationResponse response = NotificationResponse.builder()
                .id(4L)
                .eventType("PURCHASE_REGISTERED")
                .message("test")
                .siteId(2L)
                .isRead(false)
                .build();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.path("eventType").asText()).isEqualTo("PURCHASE_REGISTERED");
        assertThat(json.has("read")).isTrue();
        assertThat(json.path("read").asBoolean()).isFalse();
        assertThat(json.has("isRead")).isFalse();
    }
}
