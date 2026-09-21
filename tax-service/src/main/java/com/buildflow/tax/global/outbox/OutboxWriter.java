package com.buildflow.tax.global.outbox;

import com.buildflow.tax.global.event.KafkaEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String recordKey, String eventType, Object payload) {
        KafkaEvent<Object> event = KafkaEvent.of(eventType, payload);
        try {
            repository.save(new OutboxEvent(event.getEventId(), topic, recordKey,
                    objectMapper.writeValueAsString(event), LocalDateTime.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("세금 이벤트 직렬화 실패", e);
        }
    }
}
