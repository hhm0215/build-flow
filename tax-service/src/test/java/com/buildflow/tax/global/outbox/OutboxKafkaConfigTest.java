package com.buildflow.tax.global.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxKafkaConfigTest {

    @SuppressWarnings("unchecked")
    @Test
    void stringProducerPreservesConfiguredBrokerAcksIdempotenceAndSecurity() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("broker.internal:9092"));
        properties.getProducer().setAcks("all");
        properties.getProducer().getProperties().put("enable.idempotence", "true");
        properties.getProperties().put("security.protocol", "SASL_SSL");
        ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);

        Map<String, Object> config = new OutboxKafkaConfig()
                .outboxProducerFactory(properties, bundles).getConfigurationProperties();

        assertThat(config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo(List.of("broker.internal:9092"));
        assertThat(config.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo("true");
        assertThat(config.get("security.protocol")).isEqualTo("SASL_SSL");
        assertThat(config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(StringSerializer.class);
        assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(StringSerializer.class);
        assertThat(config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5000);
    }
}
