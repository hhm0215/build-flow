package com.buildflow.notification.global.outbox;

import com.buildflow.notification.global.event.KafkaEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String recordKey, KafkaEvent<?> event) {
        String eventId = event.getEventId();
        if (eventId == null || !eventId.equals(UUID.fromString(eventId).toString())) {
            throw new IllegalArgumentException("Outbox eventId must be a canonical UUID");
        }
        try {
            repository.save(new OutboxEvent(eventId, topic, recordKey,
                    objectMapper.writeValueAsString(event), Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox event could not be serialized", e);
        }
    }
}
