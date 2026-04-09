package com.buildflow.estimate.global.kafka;

import com.buildflow.estimate.domain.estimate.event.EstimateParsedPayload;
import com.buildflow.estimate.global.event.KafkaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_ESTIMATE_PARSED = "estimate.parsed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendEstimateParsed(EstimateParsedPayload payload) {
        KafkaEvent<EstimateParsedPayload> event = KafkaEvent.of("ESTIMATE_PARSED", payload);
        kafkaTemplate.send(TOPIC_ESTIMATE_PARSED, String.valueOf(payload.getEstimateId()), event);
        log.info("Kafka 발행: {} estimateId={}", TOPIC_ESTIMATE_PARSED, payload.getEstimateId());
    }
}
