package com.buildflow.estimate.global.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class OutboxConfigurationTest {

    @Test
    void failureBackoffIsExponentialAndCapped() {
        assertEquals(Duration.ofSeconds(1), OutboxClaimService.backoff(1));
        assertEquals(Duration.ofSeconds(2), OutboxClaimService.backoff(2));
        assertEquals(Duration.ofSeconds(4), OutboxClaimService.backoff(3));
        assertEquals(Duration.ofMinutes(5), OutboxClaimService.backoff(20));
    }

    @Test
    void stringProducerPreservesBrokerSecurityAndAckSettings() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("broker.internal:9092"));
        properties.getProperties().put("security.protocol", "SASL_SSL");
        properties.getProducer().setAcks("all");
        @SuppressWarnings("unchecked")
        ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);

        Map<String, Object> config = new OutboxKafkaConfig().outboxProducerFactory(properties, bundles)
                .getConfigurationProperties();

        assertEquals(List.of("broker.internal:9092"), config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("SASL_SSL", config.get("security.protocol"));
        assertEquals("all", config.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(StringSerializer.class, config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(StringSerializer.class, config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(5000, config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
    }
}
