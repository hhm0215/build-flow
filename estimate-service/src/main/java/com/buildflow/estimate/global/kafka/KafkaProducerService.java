package com.buildflow.estimate.global.kafka;

import com.buildflow.estimate.domain.estimate.event.EstimateParsedPayload;
import com.buildflow.estimate.global.event.KafkaEvent;
import com.buildflow.estimate.global.outbox.OutboxEvent;
import com.buildflow.estimate.global.outbox.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_ESTIMATE_PARSED = "estimate.parsed";
    private static final String TOPIC_ESTIMATE_DELETED = "estimate.deleted";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendEstimateParsed(EstimateParsedPayload payload) {
        KafkaEvent<EstimateParsedPayload> event = KafkaEvent.of("ESTIMATE_PARSED", payload);
        enqueue(TOPIC_ESTIMATE_PARSED, payload.getEstimateId(), event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendEstimateDeleted(EstimateParsedPayload payload) {
        KafkaEvent<EstimateParsedPayload> event = KafkaEvent.of("ESTIMATE_DELETED", payload);
        enqueue(TOPIC_ESTIMATE_DELETED, payload.getEstimateId(), event);
    }

    private void enqueue(String topic, Long estimateId, KafkaEvent<EstimateParsedPayload> event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.pending(
                    event.getEventId(), topic, String.valueOf(estimateId), json, Instant.now()));
            log.info("Kafka outbox 적재: {} estimateId={}, eventId={}",
                    topic, estimateId, event.getEventId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("견적 이벤트 직렬화에 실패했습니다", e);
        }
    }
}
