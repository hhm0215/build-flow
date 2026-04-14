package com.buildflow.purchase.global.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaEvent<T> {

    private String eventId;
    private String eventType;
    private LocalDateTime timestamp;
    private T payload;

    public static <T> KafkaEvent<T> of(String eventType, T payload) {
        return KafkaEvent.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .timestamp(LocalDateTime.now())
                .payload(payload)
                .build();
    }
}
